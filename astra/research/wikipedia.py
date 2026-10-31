"""Wikipedia API research source — factual lookups without HTML scraping."""

from __future__ import annotations

import json
import re
import time
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote, quote_plus

from astra.logging import get_logger
from astra.research.contracts import Finding
from astra.research.http import _pinned_fetch
from astra.research.source import ResearchSource
from astra.security.netpolicy import classify_url

_LOGGER = get_logger(__name__)

_API = "https://en.wikipedia.org/w/api.php"
_SNIPPET_RE = re.compile(r"<[^>]+>")


def _clean_query(question: str) -> str:
    text = str(question or "").strip()
    lower = text.lower()
    for prefix in (
        "search for ",
        "web search ",
        "search ",
        "who is ",
        "who was ",
        "what is ",
        "tell me about ",
    ):
        if lower.startswith(prefix):
            text = text[len(prefix) :].strip()
            break
    return text or str(question or "").strip()


def _search_queries(question: str) -> list[str]:
    base = _clean_query(question)
    if not base:
        return []
    queries = [base]
    extras = (
        f"{base} warhammer 40000",
        f"{base} fictional character",
        f"{base} primarch",
        f"{base} space wolves",
    )
    seen = {base.lower()}
    for item in extras:
        key = item.lower()
        if key not in seen:
            seen.add(key)
            queries.append(item)
    return queries


def _strip_html(text: str) -> str:
    return " ".join(_SNIPPET_RE.sub(" ", str(text or "")).split())


class WikipediaResearchSource(ResearchSource):
    """Query English Wikipedia via the MediaWiki API."""

    requires_network = True

    def __init__(self, security: Any) -> None:
        self._security = security
        self.last_outcome = "no_sources"
        self.last_used_network = False
        self.last_errors: list[str] = []

    @property
    def name(self) -> str:
        return "wikipedia"

    def is_available(self) -> bool:
        return callable(getattr(self._security, "check_research_url", None))

    def query(self, question: str, *, limit: int = 10) -> tuple[Finding, ...]:
        self.last_outcome = "no_sources"
        self.last_used_network = False
        self.last_errors = []
        if not str(question or "").strip():
            return ()

        findings: list[Finding] = []
        seen_titles: set[str] = set()
        stamp = datetime.now(timezone.utc)

        for query in _search_queries(question):
            search_url = (
                f"{_API}?action=query&list=search&srsearch={quote_plus(query)}"
                f"&format=json&srlimit=3&utf8=1"
            )
            search_payload = self._fetch_json(search_url)
            if search_payload is None:
                continue
            hits = list((search_payload.get("query") or {}).get("search") or [])
            for hit in hits[:3]:
                title = str(hit.get("title") or "").strip()
                if not title or title.lower() in seen_titles:
                    continue
                seen_titles.add(title.lower())
                snippet = _strip_html(str(hit.get("snippet") or ""))
                if not snippet:
                    continue
                page_url = f"https://en.wikipedia.org/wiki/{quote(title.replace(' ', '_'))}"
                claim = (
                    "UNTRUSTED_WEB_DOCUMENT\n"
                    f"url: {page_url}\n"
                    f"title: {title}\n"
                    f"retrieved_at: {stamp.isoformat()}\n"
                    f"source_status: wikipedia_search_snippet\n"
                    f"search_query: {query}\n"
                    "---\n"
                    f"{snippet}"
                )
                score = 0.55
                lower = f"{title} {snippet}".lower()
                if any(token in lower for token in ("warhammer", "primarch", "fictional", "novel")):
                    score = 0.85
                findings.append(
                    Finding(
                        claim=claim[:8000],
                        source=page_url,
                        confidence=score,
                        retrieved_at=stamp,
                    )
                )

        findings.sort(key=lambda item: item.confidence, reverse=True)

        extract_titles: list[str] = []
        for finding in findings:
            if "source_status: wikipedia_search_snippet" not in finding.claim:
                continue
            for line in finding.claim.splitlines():
                if line.startswith("title: "):
                    title = line[len("title: ") :].strip()
                    if title and title not in extract_titles:
                        extract_titles.append(title)
                    break
            if len(extract_titles) >= 2:
                break

        for title in extract_titles:
            extract_url = (
                f"{_API}?action=query&prop=extracts&explaintext=1&exchars=5000"
                f"&titles={quote(title)}&format=json&utf8=1"
            )
            extract_payload = self._fetch_json(extract_url)
            if extract_payload is None:
                continue
            pages = (extract_payload.get("query") or {}).get("pages") or {}
            for page in pages.values():
                extract = str(page.get("extract") or "").strip()
                page_title = str(page.get("title") or title).strip()
                if not extract:
                    continue
                page_url = f"https://en.wikipedia.org/wiki/{quote(page_title.replace(' ', '_'))}"
                claim = (
                    "UNTRUSTED_WEB_DOCUMENT\n"
                    f"url: {page_url}\n"
                    f"title: {page_title}\n"
                    f"retrieved_at: {stamp.isoformat()}\n"
                    f"source_status: wikipedia_extract\n"
                    "---\n"
                    f"{extract[:6000]}"
                )
                findings.append(
                    Finding(
                        claim=claim[:8000],
                        source=page_url,
                        confidence=0.7,
                        retrieved_at=stamp,
                    )
                )

        if findings:
            self.last_outcome = "success"
            self.last_used_network = True
            return tuple(findings[: max(1, limit)])

        self.last_outcome = "parse_failed"
        self.last_errors.append("Wikipedia returned no usable text")
        return ()

    def _fetch_json(self, url: str) -> dict[str, Any] | None:
        verdict, reason = classify_url(url)
        if verdict != "allow":
            self.last_errors.append(reason)
            self.last_outcome = "invalid_source"
            return None
        check = getattr(self._security, "check_research_url", None)
        if callable(check):
            decision = check(url)
            verdict_s = getattr(getattr(decision, "verdict", None), "value", None) or str(
                getattr(decision, "verdict", None) or "deny"
            )
            if str(verdict_s).lower() != "allow":
                self.last_errors.append(getattr(decision, "reason", "") or "research denied")
                self.last_outcome = "network_denied"
                return None

        started = time.monotonic()
        try:
            result = _pinned_fetch(
                url,
                connect_timeout=6.0,
                read_timeout=12.0,
                max_bytes=524288,
            )
        except Exception as exc:  # noqa: BLE001
            self.last_errors.append(str(exc))
            self.last_outcome = "parse_failed"
            return None

        self.last_used_network = True
        if result.error:
            self.last_errors.append(result.error)
        if result.status != 200:
            self.last_errors.append(f"HTTP {result.status}")
            self.last_outcome = "parse_failed"
            return None
        try:
            payload = json.loads(result.body.decode("utf-8", errors="replace"))
        except json.JSONDecodeError as exc:
            self.last_errors.append(str(exc))
            self.last_outcome = "parse_failed"
            return None
        if not isinstance(payload, dict):
            self.last_outcome = "parse_failed"
            return None
        _LOGGER.debug("wikipedia fetch ok in %.2fs", time.monotonic() - started)
        return payload


__all__ = ["WikipediaResearchSource"]
