import hashlib

from vk_ai.corpus.chunker import chunk_document
from vk_ai.corpus.model import Document


def document(content: str) -> Document:
    return Document(
        source_id="project:vk-platform",
        title="VK Platform",
        source_url="/projects/vk-platform",
        content=content,
        content_hash=hashlib.sha256(content.encode()).hexdigest(),
    )


def test_chunking_is_deterministic_and_preserves_heading_metadata() -> None:
    source = document(
        "# VK Platform\r\n\r\nA systems portfolio.\r\n\r\n"
        "## Architecture\r\n\r\nThe API is written in Go.\r\n\r\n"
        "The AI runtime is written in Python."
    )

    first = chunk_document(source, max_chars=48, overlap_chars=8)
    second = chunk_document(source, max_chars=48, overlap_chars=8)

    assert first == second
    assert [chunk.heading for chunk in first] == ["VK Platform", "Architecture", "Architecture"]
    assert [chunk.chunk_id for chunk in first] == [
        "project:vk-platform:vk-platform:0",
        "project:vk-platform:architecture:1",
        "project:vk-platform:architecture:2",
    ]


def test_oversized_paragraphs_are_bounded_with_overlap() -> None:
    source = document("## Details\n\n" + "word " * 80)

    chunks = chunk_document(source, max_chars=60, overlap_chars=10)

    assert len(chunks) > 1
    assert all(0 < len(chunk.text) <= 60 for chunk in chunks)
    assert chunks[0].text.split()[-1] in chunks[1].text.split()
