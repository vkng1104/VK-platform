from __future__ import annotations

import re
from collections.abc import Iterable

from vk_ai.corpus.model import Chunk, Document

_heading_pattern = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
_slug_character_pattern = re.compile(r"[^a-z0-9]+")


def chunk_documents(
    documents: Iterable[Document], *, max_chars: int, overlap_chars: int
) -> list[Chunk]:
    chunks: list[Chunk] = []
    for document in documents:
        chunks.extend(chunk_document(document, max_chars=max_chars, overlap_chars=overlap_chars))
    return chunks


def chunk_document(document: Document, *, max_chars: int, overlap_chars: int) -> list[Chunk]:
    if max_chars <= 0:
        raise ValueError("max_chars must be greater than zero")
    if overlap_chars < 0 or overlap_chars >= max_chars:
        raise ValueError("overlap_chars must be non-negative and smaller than max_chars")

    chunks: list[Chunk] = []
    position = 0
    for heading, paragraphs in _sections(document.content, document.title):
        for text in _pack_paragraphs(paragraphs, max_chars=max_chars, overlap_chars=overlap_chars):
            chunks.append(
                Chunk(
                    chunk_id=(f"{document.source_id}:{_slugify(heading) or 'overview'}:{position}"),
                    source_id=document.source_id,
                    title=document.title,
                    heading=heading,
                    source_url=document.source_url,
                    content_hash=document.content_hash,
                    text=text,
                )
            )
            position += 1
    return chunks


def _sections(markdown: str, fallback_heading: str) -> list[tuple[str, list[str]]]:
    sections: list[tuple[str, list[str]]] = []
    heading = fallback_heading
    paragraph_lines: list[str] = []
    paragraphs: list[str] = []

    def finish_paragraph() -> None:
        if paragraph_lines:
            paragraphs.append(" ".join(line.strip() for line in paragraph_lines).strip())
            paragraph_lines.clear()

    def finish_section() -> None:
        finish_paragraph()
        if paragraphs:
            sections.append((heading, paragraphs.copy()))
            paragraphs.clear()

    for raw_line in markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw_line.strip()
        match = _heading_pattern.match(line)
        if match:
            finish_section()
            heading = match.group(2).strip()
        elif not line:
            finish_paragraph()
        else:
            paragraph_lines.append(line)

    finish_section()
    return sections


def _pack_paragraphs(paragraphs: list[str], *, max_chars: int, overlap_chars: int) -> list[str]:
    packed: list[str] = []
    current: list[str] = []
    current_size = 0

    for paragraph in paragraphs:
        if len(paragraph) > max_chars:
            if current:
                packed.append("\n\n".join(current))
                current = []
                current_size = 0
            packed.extend(_split_oversized(paragraph, max_chars, overlap_chars))
            continue

        added_size = len(paragraph) + (2 if current else 0)
        if current and current_size + added_size > max_chars:
            packed.append("\n\n".join(current))
            current = [paragraph]
            current_size = len(paragraph)
        else:
            current.append(paragraph)
            current_size += added_size

    if current:
        packed.append("\n\n".join(current))
    return [text for text in packed if text]


def _split_oversized(text: str, max_chars: int, overlap_chars: int) -> list[str]:
    pieces: list[str] = []
    start = 0
    while start < len(text):
        end = min(start + max_chars, len(text))
        if end < len(text):
            boundary = text.rfind(" ", start + max_chars // 2, end)
            if boundary > start:
                end = boundary
        piece = text[start:end].strip()
        if piece:
            pieces.append(piece)
        if end >= len(text):
            break
        next_start = max(end - overlap_chars, start + 1)
        while next_start < end and not text[next_start].isspace():
            next_start += 1
        start = next_start
    return pieces


def _slugify(value: str) -> str:
    return _slug_character_pattern.sub("-", value.lower()).strip("-")
