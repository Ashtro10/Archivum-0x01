"""HTML to readable text. No JavaScript. No code execution."""

from __future__ import annotations

from html.parser import HTMLParser


class _Extractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._skip = 0
        self._title_parts: list[str] = []
        self._in_title = False
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        name = tag.lower()
        if name in {"script", "style", "noscript", "template"}:
            self._skip += 1
            return
        if name == "title":
            self._in_title = True
        if name in {"p", "div", "br", "li", "h1", "h2", "h3", "tr"}:
            self._parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        name = tag.lower()
        if name in {"script", "style", "noscript", "template"} and self._skip:
            self._skip -= 1
            return
        if name == "title":
            self._in_title = False

    def handle_data(self, data: str) -> None:
        if self._skip:
            return
        text = " ".join(data.split())
        if not text:
            return
        if self._in_title:
            self._title_parts.append(text)
        else:
            self._parts.append(text + " ")

    def result(self) -> tuple[str, str]:
        title = " ".join(self._title_parts).strip()
        body = re_collapse("".join(self._parts))
        return title, body


def re_collapse(text: str) -> str:
    lines = [" ".join(line.split()) for line in text.splitlines()]
    return "\n".join(line for line in lines if line).strip()


def html_to_text(raw: str, *, limit: int = 8000) -> tuple[str, str]:
    """Return ``(title, readable_text)``. Never executes markup."""
    parser = _Extractor()
    try:
        parser.feed(raw or "")
        parser.close()
    except Exception:
        text = re_collapse(raw or "")
        return "", text[:limit]
    title, body = parser.result()
    return title[:200], body[:limit]


__all__ = ["html_to_text"]
