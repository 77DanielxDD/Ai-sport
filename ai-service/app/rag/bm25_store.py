from __future__ import annotations

from typing import Dict, List, Optional

from rank_bm25 import BM25Okapi

_bm25: Optional[BM25Okapi] = None
_bm25_chunks: List[str] = []
_bm25_metadatas: List[Dict] = []


def build_index(chunks: List[str], metadatas: List[Dict]) -> None:
    global _bm25, _bm25_chunks, _bm25_metadatas
    _bm25_chunks = list(chunks)
    _bm25_metadatas = list(metadatas)
    tokenized = [c.split() for c in chunks]
    _bm25 = BM25Okapi(tokenized)


def search(query: str, k: int = 5) -> List[Dict]:
    global _bm25, _bm25_chunks, _bm25_metadatas
    if _bm25 is None:
        return []

    tokenized_query = query.split()
    scores = _bm25.get_scores(tokenized_query)
    indexed = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)
    top = indexed[:k]

    results = []
    for idx, score in top:
        metadata = _bm25_metadatas[idx] if idx < len(_bm25_metadatas) else {}
        results.append({
            "content": _bm25_chunks[idx],
            "metadata": metadata,
            "chunk_id": metadata.get("chunk_id"),
            "bm25_score": round(float(score), 4),
            "source": "bm25",
        })
    return results


def is_built() -> bool:
    return _bm25 is not None


def chunk_count() -> int:
    return len(_bm25_chunks)


def clear() -> None:
    global _bm25, _bm25_chunks, _bm25_metadatas
    _bm25 = None
    _bm25_chunks = []
    _bm25_metadatas = []
