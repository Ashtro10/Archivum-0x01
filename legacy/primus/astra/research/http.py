"""Controlled HTTP/HTTPS document retrieval for research (B-041).

Not a Tool. Not a browser. No JavaScript. Security policy is deny-by-default
and re-checked on every redirect and every resolved IP.
"""

from __future__ import annotations

import re
import socket
import ssl
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable
from urllib.parse import quote_plus, urljoin, urlparse

from astra.logging import get_logger
from astra.research.contracts import Finding
from astra.research.htmltext import html_to_text
from astra.research.source import ResearchSource
from astra.security.contracts import Verdict
from astra.security.netpolicy import classify_url, connect_ip, ip_is_blocked

_LOGGER = get_logger(__name__)
SEARCH_URL = "https://html.duckduckgo.com/html/?q={query}"
_URL_IN_TEXT = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
_ALLOWED_TYPES = (
    "text/html",
    "text/plain",
    "application/xhtml+xml",
    "application/xml",
    "text/xml",
)
FetchFn = Callable[[str], "FetchResult"]


@dataclass(frozen=True, slots=True)
class RetrievalLimits:
    connect_timeout_s: float = 5.0
    read_timeout_s: float = 10.0
    max_bytes: int = 524288
    max_sources: int = 3
    max_redirects: int = 3
    max_duration_s: float = 20.0


@dataclass(frozen=True, slots=True)
class FetchResult:
    status: int
    url: str
    body: bytes = b""
    content_type: str = ""
    location: str = ""
    error: str = ""


class HttpResearchSource(ResearchSource):
    """Approved-document retrieval. ``requires_network`` is True."""

    requires_network = True

    def __init__(
        self,
        security: Any,
        *,
        limits: RetrievalLimits | None = None,
        fetch: FetchFn | None = None,
    ) -> None:
        self._security = security
        self._limits = limits or RetrievalLimits()
        self._fetch = fetch
        self.last_outcome = "no_sources"
        self.last_used_network = False
        self.last_errors: list[str] = []

    @property
    def name(self) -> str:
        return "http"

    def is_available(self) -> bool:
        if self._fetch is not None:
            return True
        return callable(getattr(self._security, "check_research_url", None))

    def query(self, question: str, *, limit: int = 10) -> tuple[Finding, ...]:
        self.last_outcome = "no_sources"
        self.last_used_network = False
        self.last_errors = []
        if not question.strip():
            return ()
        all_targets = _targets(question)
        truncated = len(all_targets) > self._limits.max_sources
        targets = all_targets[: self._limits.max_sources]
        if not targets:
            return ()
        deadline = time.monotonic() + self._limits.max_duration_s
        findings: list[Finding] = []
        saw_network = False
        outcomes: list[str] = []
        for url in targets:
            if time.monotonic() > deadline:
                outcomes.append("timeout")
                self.last_errors.append("research duration exceeded")
                break
            if len(findings) >= limit:
                break
            item, outcome, fetched = self._retrieve(url, deadline)
            if fetched:
                saw_network = True
            outcomes.append(outcome)
            if item is not None:
                findings.append(item)
        self.last_used_network = saw_network
        self.last_outcome = _combine_outcomes(outcomes, bool(findings), saw_network)
        if truncated:
            self.last_errors.append("too many sources")
            if self.last_outcome == "success":
                self.last_outcome = "partial"
        return tuple(findings)

    def _retrieve(
        self, url: str, deadline: float
    ) -> tuple[Finding | None, str, bool]:
        current = url.strip()
        hops = 0
        fetched = False
        while hops <= self._limits.max_redirects:
            if time.monotonic() > deadline:
                return None, "timeout", fetched
            allowed, reason = self._authorize(current)
            if not allowed:
                self.last_errors.append(reason)
                return None, "invalid_source" if "scheme" in reason or "malformed" in reason else "network_denied", fetched
            remaining = max(0.1, deadline - time.monotonic())
            timeout = min(self._limits.connect_timeout_s + self._limits.read_timeout_s, remaining)
            try:
                result = self._do_fetch(current, timeout)
            except TimeoutError:
                return None, "timeout", True
            except Exception as exc:
                _LOGGER.warning("research fetch failed: %s", exc)
                self.last_errors.append(str(exc))
                return None, "parse_failed", fetched
            fetched = True
            if result.error:
                self.last_errors.append(result.error)
                if "timeout" in result.error.lower():
                    return None, "timeout", True
                if "too large" in result.error.lower():
                    return None, "content_too_large", True
                return None, "parse_failed", True
            if result.status in {301, 302, 303, 307, 308}:
                location = (result.location or "").strip()
                if not location:
                    return None, "invalid_source", True
                current = urljoin(current, location)
                hops += 1
                if hops > self._limits.max_redirects:
                    self.last_errors.append("excessive redirects")
                    return None, "invalid_source", True
                continue
            if result.status != 200:
                self.last_errors.append(f"HTTP {result.status}")
                return None, "parse_failed", True
            ctype = (result.content_type or "text/html").split(";")[0].strip().lower()
            if ctype and not any(ctype.startswith(item) for item in _ALLOWED_TYPES):
                self.last_errors.append(f"content-type {ctype}")
                return None, "parse_failed", True
            if len(result.body) > self._limits.max_bytes:
                return None, "content_too_large", True
            text = result.body.decode("utf-8", errors="replace")
            title, body = html_to_text(text)
            if not body:
                return None, "parse_failed", True
            stamp = datetime.now(timezone.utc)
            final_url = result.url or current
            claim = (
                "UNTRUSTED_WEB_DOCUMENT\n"
                f"url: {final_url}\n"
                f"title: {title or '(none)'}\n"
                f"retrieved_at: {stamp.isoformat()}\n"
                f"source_status: success\n"
                f"extracted_text_length: {len(body)}\n"
                "---\n"
                f"{body}"
            )
            return (
                Finding(
                    claim=claim[:8000],
                    source=final_url,
                    confidence=0.4,
                    retrieved_at=stamp,
                ),
                "success",
                True,
            )
        return None, "invalid_source", fetched

    def _authorize(self, url: str) -> tuple[bool, str]:
        verdict, reason = classify_url(url)
        if verdict != "allow":
            return False, reason
        check = getattr(self._security, "check_research_url", None)
        if callable(check):
            decision = check(url)
            verdict_s = getattr(getattr(decision, "verdict", None), "value", None) or str(
                getattr(decision, "verdict", "")
            )
            if str(verdict_s).lower() != Verdict.ALLOW.value:
                return False, getattr(decision, "reason", "") or "research URL denied"
        if self._fetch is not None:
            return True, "ok"
        parsed = urlparse(url)
        host = parsed.hostname or ""
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        try:
            infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except OSError as exc:
            return False, f"DNS failed ({exc})"
        ip_check = getattr(self._security, "check_resolved_ip", None)
        for info in infos:
            ip = info[4][0]
            if ip_is_blocked(ip):
                return False, f"resolved to blocked address {ip}"
            if callable(ip_check):
                decision = ip_check(ip)
                verdict_s = getattr(getattr(decision, "verdict", None), "value", None) or str(
                    getattr(decision, "verdict", "")
                )
                if str(verdict_s).lower() != Verdict.ALLOW.value:
                    return False, getattr(decision, "reason", "") or f"IP denied {ip}"
        return True, "ok"

    def _do_fetch(self, url: str, timeout: float) -> FetchResult:
        if self._fetch is not None:
            return self._fetch(url)
        return _pinned_fetch(
            url,
            connect_timeout=min(timeout, self._limits.connect_timeout_s),
            read_timeout=min(timeout, self._limits.read_timeout_s),
            max_bytes=self._limits.max_bytes,
        )


def _targets(question: str) -> list[str]:
    found = [match.group(0).rstrip(").,;") for match in _URL_IN_TEXT.finditer(question)]
    if found:
        return found
    query = quote_plus(question.strip()[:200])
    return [SEARCH_URL.format(query=query)]


def _combine_outcomes(outcomes: list[str], had_findings: bool, used_network: bool) -> str:
    if had_findings and any(item != "success" for item in outcomes):
        return "partial"
    if had_findings:
        return "success"
    if not outcomes:
        return "no_sources"
    if all(item == "network_denied" for item in outcomes):
        return "network_denied"
    if "timeout" in outcomes:
        return "timeout"
    if "content_too_large" in outcomes:
        return "content_too_large"
    if "invalid_source" in outcomes:
        return "invalid_source"
    if used_network:
        return "parse_failed"
    return "no_sources"


def _pinned_fetch(
    url: str,
    *,
    connect_timeout: float,
    read_timeout: float,
    max_bytes: int,
) -> FetchResult:
    import http.client

    parsed = urlparse(url)
    host = parsed.hostname or ""
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    path = parsed.path or "/"
    if parsed.query:
        path = f"{path}?{parsed.query}"
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    infos = sorted(infos, key=lambda item: 0 if item[0] == socket.AF_INET else 1)
    sock = None
    last_error = "no usable address"
    for info in infos:
        ip = info[4][0]
        target = connect_ip(ip) or ip
        if ip_is_blocked(target):
            last_error = f"blocked address {ip}"
            continue
        try:
            sock = socket.create_connection((target, port), timeout=connect_timeout)
            break
        except OSError as exc:
            last_error = str(exc)
            sock = None
    if sock is None:
        return FetchResult(status=0, url=url, error=last_error)
    try:
        sock.settimeout(read_timeout)
        if parsed.scheme == "https":
            context = ssl.create_default_context()
            sock = context.wrap_socket(sock, server_hostname=host)
        conn = http.client.HTTPConnection(host, port, timeout=read_timeout)
        conn.sock = sock
        try:
            conn.request(
                "GET",
                path,
                headers={
                    "Host": host,
                    "User-Agent": "PrimusResearch/0.0.1",
                    "Accept": "text/html, text/plain;q=0.9",
                },
            )
            response = conn.getresponse()
            location = response.getheader("Location") or ""
            content_type = response.getheader("Content-Type") or ""
            chunks: list[bytes] = []
            total = 0
            while True:
                piece = response.read(65536)
                if not piece:
                    break
                total += len(piece)
                if total > max_bytes:
                    return FetchResult(
                        status=response.status,
                        url=url,
                        content_type=content_type,
                        location=location,
                        error="response too large",
                    )
                chunks.append(piece)
            body = b"".join(chunks)
            return FetchResult(
                status=response.status,
                url=url,
                body=body,
                content_type=content_type,
                location=location,
            )
        finally:
            try:
                conn.close()
            except Exception:
                pass
    except TimeoutError:
        return FetchResult(status=0, url=url, error="timeout")
    except OSError as exc:
        return FetchResult(status=0, url=url, error=str(exc))
    finally:
        try:
            sock.close()
        except Exception:
            pass


__all__ = [
    "HttpResearchSource",
    "RetrievalLimits",
    "FetchResult",
    "SEARCH_URL",
]
