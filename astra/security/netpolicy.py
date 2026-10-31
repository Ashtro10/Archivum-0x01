"""Destination policy for controlled research retrieval.

Syntactic and address-literal checks only. Does not open sockets.
DNS resolution is the caller's job; resolved IPs must be re-checked here.
"""

from __future__ import annotations

import ipaddress
import re
from urllib.parse import urlparse

ALLOWED_SCHEMES = frozenset({"http", "https"})
_BLOCKED_HOSTS = frozenset(
    {
        "localhost",
        "localhost.",
        "ip6-localhost",
        "metadata",
        "metadata.google.internal",
        "metadata.google.internal.",
        "instance-data",
        "kubernetes.default",
        "kubernetes.default.svc",
    }
)
_BLOCKED_NETWORKS = (
    ipaddress.ip_network("0.0.0.0/8"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("169.254.0.0/16"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("198.18.0.0/15"),
    ipaddress.ip_network("224.0.0.0/4"),
    ipaddress.ip_network("255.255.255.255/32"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("::/128"),
    ipaddress.ip_network("fc00::/7"),
    ipaddress.ip_network("fe80::/10"),
    ipaddress.ip_network("ff00::/8"),
)
_NAT64_NETWORK = ipaddress.ip_network("64:ff9b::/96")


def classify_url(url: str) -> tuple[str, str]:
    """Return ``("allow", reason)`` or ``("deny", reason)`` for a URL string."""
    text = (url or "").strip()
    if not text:
        return "deny", "empty URL"
    if re.search(r"[\s<>\"']", text):
        return "deny", "malformed URL"
    parsed = urlparse(text)
    scheme = (parsed.scheme or "").lower()
    if scheme not in ALLOWED_SCHEMES:
        return "deny", f"unsupported scheme {scheme or '(none)'}"
    if parsed.username or parsed.password:
        return "deny", "userinfo in URL is not allowed"
    host = (parsed.hostname or "").lower().strip(".")
    if not host:
        return "deny", "missing host"
    if host.isdigit():
        return "deny", "numeric host is not allowed"
    if host in _BLOCKED_HOSTS or host.endswith(".localhost"):
        return "deny", f"blocked host {host}"
    if host.endswith(".internal") or host.endswith(".local"):
        return "deny", f"blocked host suffix {host}"
    literal = _as_ip(host)
    if literal is not None and ip_is_blocked(str(literal)):
        return "deny", f"blocked address {host}"
    return "allow", f"{scheme} public host {host}"


def normalize_ip_for_policy(
    value: str,
) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    """Map IPv4-mapped and NAT64/DNS64 addresses to policy-checkable forms."""
    address = _as_ip(value)
    if address is None:
        return None
    if isinstance(address, ipaddress.IPv4Address):
        return address
    if address.ipv4_mapped is not None:
        return address.ipv4_mapped
    if address in _NAT64_NETWORK:
        return ipaddress.IPv4Address(int(address) & 0xFFFFFFFF)
    return address


def connect_ip(value: str) -> str | None:
    """Return the address string to use for TCP connect after normalization."""
    normalized = normalize_ip_for_policy(value)
    if normalized is None:
        return None
    if isinstance(normalized, ipaddress.IPv4Address):
        return str(normalized)
    return str(normalized)


def ip_is_blocked(value: str) -> bool:
    address = normalize_ip_for_policy(value)
    if address is None:
        return True
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        address = address.ipv4_mapped
    if address.is_loopback or address.is_private or address.is_link_local:
        return True
    if address.is_multicast or address.is_reserved or address.is_unspecified:
        return True
    for network in _BLOCKED_NETWORKS:
        if address in network:
            return True
    return False


def _as_ip(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    text = (value or "").strip().strip("[]")
    if not text:
        return None
    try:
        return ipaddress.ip_address(text)
    except ValueError:
        return None


__all__ = ["ALLOWED_SCHEMES", "classify_url", "connect_ip", "ip_is_blocked", "normalize_ip_for_policy"]
