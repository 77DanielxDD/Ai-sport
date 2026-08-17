from __future__ import annotations

import os
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

    # Score fusion: combine results by chunk_id first, fall back to content prefix.
    merged: Dict[str, Dict] = {}
    max_bm25 = max((r.get("bm25_score", 0) for r in bm25_results), default=1.0)

    for rank, r in enumerate(dense_results):
        score = 1.0 - (rank / max(len(dense_results), 1))
        metadata = r.get("metadata", {})
        chunk_id = metadata.get("chunk_id")
        key = chunk_id or r["content"][:100]
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
        metadata = r.get("metadata", {})
        chunk_id = metadata.get("chunk_id")
        key = chunk_id or r["content"][:100]
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


# SiliconFlow 托管 BAAI/bge-reranker-v2-m3 API。key 由环境变量注入，不写进源码。
_RERANK_API_URL = os.getenv("RERANK_API_URL", "https://api.siliconflow.cn/v1/rerank")
_RERANK_API_KEY = os.getenv("APP_RERANK_API_KEY") or os.getenv("RERANK_API_KEY", "")
_RERANK_MODEL = os.getenv("RERANK_MODEL", "BAAI/bge-reranker-v2-m3")


def rerank(
    results: List[Dict],
    query: str,
    top_k: int = 3,
    llm=None,
) -> List[Dict]:
    """Neural rerank via SiliconFlow BAAI/bge-reranker-v2-m3 API.

    API 不可用（无 key / 超时 / 报错）时回退到 keyword heuristic，检索不断链。
    每个结果写入 ``rerank_score``。
    """
    if not results:
        return results

    try:
        scores = _api_rerank(query, results)
        for r, s in zip(results, scores):
            r["rerank_score"] = round(float(s), 4)
        results.sort(key=lambda x: x["rerank_score"], reverse=True)
        return results[:top_k]
    except Exception:
        return _heuristic_rerank(results, query, top_k)


def _api_rerank(query: str, results: List[Dict]) -> List[float]:
    if not _RERANK_API_KEY:
        raise RuntimeError("Rerank API key not configured (APP_RERANK_API_KEY)")

    import httpx

    payload = {
        "model": _RERANK_MODEL,
        "query": query,
        "documents": [r["content"] for r in results],
        "top_n": len(results),
    }
    resp = httpx.post(
        _RERANK_API_URL,
        json=payload,
        headers={"Authorization": f"Bearer {_RERANK_API_KEY}"},
        timeout=30,
    )
    resp.raise_for_status()
    data = resp.json()
    ranked = data.get("results", [])
    scores = [0.0] * len(results)
    for item in ranked:
        scores[item["index"]] = float(item.get("relevance_score", 0.0))
    return scores


def _heuristic_rerank(
    results: List[Dict],
    query: str,
    top_k: int = 3,
) -> List[Dict]:
    """Keyword-match rerank used when the neural model is unavailable."""
    query_terms = set(query.lower().split())

    for r in results:
        content_lower = r["content"].lower()
        keyword_hits = sum(1 for t in query_terms if t in content_lower)
        r["final_score"] = round(
            r["final_score"] * 0.8 + (keyword_hits / max(len(query_terms), 1)) * 0.2, 4
        )

    results.sort(key=lambda x: x["final_score"], reverse=True)
    return results[:top_k]
