from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Dict, List

from . import bm25_store, vector_store

KNOWLEDGE_PATH = os.getenv("KNOWLEDGE_PATH", "")


def _find_knowledge_file() -> str:
    """Resolve knowledge file path. Returns actual path so we can read it."""
    if KNOWLEDGE_PATH and os.path.exists(KNOWLEDGE_PATH):
        return KNOWLEDGE_PATH
    alt = os.getenv("KNOWLEDGE_PATH_ALT", "")
    if alt and os.path.exists(alt):
        return alt
    # 向上遍历查找 <repo>/src/main/resources/rag/fitness_knowledge_zh.txt
    # 避免依赖固定目录层级（容器内路径深度与本地不同）。
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "src" / "main" / "resources" / "rag" / "fitness_knowledge_zh.txt"
        if candidate.exists():
            return str(candidate)
    return KNOWLEDGE_PATH


def load_raw_text() -> str:
    path = _find_knowledge_file()
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def clean_text(text: str) -> str:
    """Remove extra whitespace, normalize newlines."""
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    return text.strip()


def chunk_text(text: str, chunk_size: int = 300, overlap: int = 50) -> List[str]:
    """Split text by paragraphs then combine into chunks of ~chunk_size characters."""
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]

    chunks = []
    current = ""
    for para in paragraphs:
        if len(current) + len(para) + 2 <= chunk_size:
            current = (current + "\n\n" + para).strip() if current else para
        else:
            if current:
                chunks.append(current)
            if len(para) > chunk_size:
                sentences = re.split(r"(?<=[。！？])", para)
                sub = ""
                for s in sentences:
                    if len(sub) + len(s) <= chunk_size:
                        sub += s
                    else:
                        if sub:
                            chunks.append(sub)
                        sub = s
                if sub:
                    current = sub
                else:
                    current = ""
            else:
                current = para

    if current:
        chunks.append(current)

    return chunks


def build_metadatas(chunks: List[str]) -> List[Dict]:
    """Generate metadata for each chunk."""
    metadatas = []
    for i, chunk in enumerate(chunks):
        source_title = "Fitness Knowledge"
        # Try to extract a title from first sentence
        lines = chunk.split("\n")
        first_line = lines[0].strip() if lines else ""
        if first_line and len(first_line) < 80:
            source_title = first_line

        metadatas.append({
            "chunk_id": f"chunk_{i:04d}",
            "chunk_index": i,
            "source": source_title,
            "char_count": len(chunk),
        })
    return metadatas


def reindex() -> Dict:
    """Full reindex: load, clean, chunk, embed, store in Chroma + BM25."""
    raw = load_raw_text()
    if not raw:
        return {"status": "error", "message": "Knowledge file not found", "chunk_count": 0}

    cleaned = clean_text(raw)
    chunks = chunk_text(cleaned)
    metadatas = build_metadatas(chunks)

    # Clear and rebuild Chroma
    vector_store.reset_vectorstore()
    vector_store.add_documents(chunks, metadatas)

    # Clear and rebuild BM25
    bm25_store.clear()
    bm25_store.build_index(chunks, metadatas)

    return {
        "status": "ok",
        "chunk_count": len(chunks),
        "message": f"Indexed {len(chunks)} chunks into Chroma + BM25",
    }


def ensure_index() -> bool:
    """Check if index exists, if not build it."""
    count = vector_store.get_chunk_count()
    if count == 0:
        result = reindex()
        return result["status"] == "ok"
    return True
