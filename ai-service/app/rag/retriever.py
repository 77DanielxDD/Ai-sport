from __future__ import annotations

from typing import Dict, List, Optional

from langchain.chains import LLMChain
from langchain.prompts import PromptTemplate

from ..clients import redis_client as cache
from . import bm25_store, vector_store


def rewrite_query(question: str, llm=None) -> str:
    cached = cache.get_rewrite(question)
    if cached:
        return cached

    if llm is None:
        return question

    try:
        prompt = PromptTemplate(
            input_variables=["question"],
            template=(
                "将用户的口语化健身问题改写成更适合检索的标准问法，聚焦于动作要领、常见错误、纠正方法。\n"
                "用户问题: {question}\n"
                "改写后的问题 (只返回改写后的问题，不要加任何解释):"
            ),
        )
        chain = LLMChain(llm=llm, prompt=prompt)
        rewritten = chain.run(question=question).strip()
        if rewritten:
            cache.set_rewrite(question, rewritten)
            return rewritten
    except Exception:
        pass
    return question


def hybrid_retrieve(
    query: str,
    top_k: int = 5,
    dense_weight: float = 0.7,
    bm25_weight: float = 0.3,
    use_cache: bool = True,
) -> List[Dict]:
    """Dense (Chroma) + Sparse (BM25) hybrid retrieval with score fusion."""
    if use_cache:
        cached = cache.get_retrieval(query)
        if cached:
            return cached

    # Dense retrieval
    dense_results = vector_store.similarity_search(query, k=top_k)

    # BM25 sparse retrieval
    bm25_results = bm25_store.search(query, k=top_k)

    # Score fusion: combine results by content dedup and weighted scoring
    merged: Dict[str, Dict] = {}

    # Normalize scores
    max_dense = max(
        (1.0 for _ in dense_results),  # Chroma returns ordered by relevance
        default=1.0,
    )
    max_bm25 = max((r.get("bm25_score", 0) for r in bm25_results), default=1.0)

    for rank, r in enumerate(dense_results):
        score = 1.0 - (rank / max(len(dense_results), 1))
        key = r["content"][:100]
        metadata = r.get("metadata", {})
        chunk_id = metadata.get("chunk_id")
        merged[key] = {
            "content": r["content"],
            "metadata": metadata,
            "chunk_id": chunk_id,
            "dense_score": round(score, 4),
            "bm25_score": 0.0,
            "final_score": round(score * dense_weight, 4),
            "matched_by": "dense",
        }

    for r in bm25_results:
        score = r.get("bm25_score", 0) / max(max_bm25, 1.0)
        key = r["content"][:100]
        metadata = r.get("metadata", {})
        chunk_id = metadata.get("chunk_id")
        if key in merged:
            merged[key]["bm25_score"] = round(score, 4)
            merged[key]["final_score"] = round(
                merged[key]["dense_score"] * dense_weight + score * bm25_weight, 4
            )
            merged[key]["matched_by"] = "hybrid"
        else:
            merged[key] = {
                "content": r["content"],
                "metadata": metadata,
                "chunk_id": chunk_id,
                "dense_score": 0.0,
                "bm25_score": round(score, 4),
                "final_score": round(score * bm25_weight, 4),
                "matched_by": "bm25",
            }

    sorted_results = sorted(
        merged.values(), key=lambda x: x["final_score"], reverse=True
    )[:top_k]

    if use_cache:
        cache.set_retrieval(query, sorted_results)

    return sorted_results


def rerank(
    results: List[Dict],
    query: str,
    top_k: int = 3,
    llm=None,
) -> List[Dict]:
    """Heuristic rerank: boost chunks with keyword match on query terms."""
    if not results:
        return results

    query_terms = set(query.lower().split())

    for r in results:
        content_lower = r["content"].lower()
        keyword_hits = sum(1 for t in query_terms if t in content_lower)
        r["final_score"] = round(
            r["final_score"] * 0.8 + (keyword_hits / max(len(query_terms), 1)) * 0.2, 4
        )

    results.sort(key=lambda x: x["final_score"], reverse=True)
    return results[:top_k]
