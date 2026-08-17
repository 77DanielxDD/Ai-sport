"""Offline retrieval evaluation using pytrec_eval.

Replaces the previous RAGAS-based evaluation with a standard IR evaluation
pipeline: load a JSONL dataset, run retrieval for each query, build run/qrels,
and compute metrics via pytrec_eval.

Dataset format (one JSON object per line):
{
    "query_id": "q0001",
    "question": "...",
    "relevant_doc_ids": {"chunk_0012": 2, "chunk_0044": 1}
}
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

DEFAULT_DATASET_PATH = Path(__file__).resolve().parent / "eval_dataset.jsonl"

# pytrec_eval 0.5 不支持 mrr_cut.10，用等价的 recip_rank 表达 MRR。
DEFAULT_METRICS = [
    "map",
    "ndcg_cut.10",
    "recall.1",
    "recall.5",
    "recall.10",
    "P.1",
    "P.5",
    "P.10",
    "recip_rank",
]


def load_dataset(path: Optional[str] = None) -> List[Dict[str, Any]]:
    """Load evaluation dataset from JSONL file."""
    filepath = Path(path) if path else DEFAULT_DATASET_PATH
    if not filepath.exists():
        raise FileNotFoundError(f"Evaluation dataset not found: {filepath}")

    samples = []
    with open(filepath, "r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                sample = json.loads(line)
                required = {"query_id", "question", "relevant_doc_ids"}
                missing = required - set(sample.keys())
                if missing:
                    raise ValueError(f"Missing fields: {missing}")
                samples.append(sample)
            except (json.JSONDecodeError, ValueError) as e:
                logger.warning("Skipping invalid line %d: %s", line_no, e)
    return samples


def build_qrels(samples: List[Dict[str, Any]]) -> Dict[str, Dict[str, int]]:
    """Build qrels dict from dataset samples.

    Returns: {query_id: {doc_id: relevance}}
    """
    qrels: Dict[str, Dict[str, int]] = {}
    for sample in samples:
        qid = sample["query_id"]
        qrels[qid] = {
            doc_id: int(rel)
            for doc_id, rel in sample["relevant_doc_ids"].items()
        }
    return qrels


def build_run(
    samples: List[Dict[str, Any]],
    retrieve_fn,
    top_k: int = 10,
) -> Dict[str, Dict[str, float]]:
    """Run retrieval for each query and build run dict.

    Args:
        samples: list of evaluation samples
        retrieve_fn: function(question, top_k) -> List[Dict] where each dict
                     has 'chunk_id' and 'final_score' keys
        top_k: number of results to retrieve per query

    Returns: {query_id: {doc_id: score}}
    """
    run: Dict[str, Dict[str, float]] = {}
    for sample in samples:
        qid = sample["query_id"]
        question = sample["question"]
        results = retrieve_fn(question, top_k=top_k)

        doc_scores: Dict[str, float] = {}
        for r in results:
            chunk_id = r.get("chunk_id") or r.get("metadata", {}).get("chunk_id")
            if chunk_id:
                doc_scores[chunk_id] = r.get("final_score", 0.0)
        run[qid] = doc_scores
    return run


def evaluate(
    qrels: Dict[str, Dict[str, int]],
    run: Dict[str, Dict[str, float]],
    metrics: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """Run pytrec_eval and return per-query and aggregate metrics.

    Returns:
        {
            "aggregate": {"map": 0.45, "ndcg_cut_10": 0.52, ...},
            "per_query": {"q0001": {"map": 0.5, ...}, ...},
            "failed_queries": ["q0003", ...]
        }
    """
    try:
        import pytrec_eval
    except ImportError:
        return {"error": "pytrec_eval not installed. Run: pip install pytrec_eval"}

    if metrics is None:
        metrics = list(DEFAULT_METRICS)

    evaluator = pytrec_eval.RelevanceEvaluator(qrels, metrics)
    per_query_raw = evaluator.evaluate(run)

    # Aggregate
    aggregate: Dict[str, float] = {}
    failed_queries: List[str] = []

    for metric in metrics:
        # pytrec_eval 输出键把 "." 转成 "_"，如 ndcg_cut.10 -> ndcg_cut_10
        pytrec_key = metric.replace(".", "_")
        values = []
        for qid, scores in per_query_raw.items():
            if metric in scores:
                values.append(scores[metric])
            elif pytrec_key in scores:
                values.append(scores[pytrec_key])
        if values:
            aggregate[metric] = round(sum(values) / len(values), 4)
        else:
            aggregate[metric] = 0.0

    # Find queries with no retrieval results
    for qid in qrels:
        if qid not in run or not run[qid]:
            failed_queries.append(qid)

    # Clean per_query output
    per_query: Dict[str, Dict[str, float]] = {}
    for qid, scores in per_query_raw.items():
        per_query[qid] = {
            m: round(scores.get(m, scores.get(m.replace(".", "_"), 0.0)), 4)
            for m in metrics
        }

    return {
        "aggregate": aggregate,
        "per_query": per_query,
        "failed_queries": failed_queries,
        "num_queries": len(qrels),
    }


def run_evaluation(
    dataset_path: Optional[str] = None,
    retrieve_fn=None,
    top_k: int = 10,
    metrics: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """End-to-end evaluation: load dataset -> retrieve -> evaluate.

    Args:
        dataset_path: path to JSONL eval dataset (defaults to eval_dataset.jsonl)
        retrieve_fn: custom retrieval function. If None, uses hybrid_retrieve.
        top_k: number of results per query
        metrics: list of pytrec_eval metric strings

    Returns evaluation report dict.
    """
    samples = load_dataset(dataset_path)
    if not samples:
        return {"error": "No valid samples in dataset"}

    qrels = build_qrels(samples)

    if retrieve_fn is None:
        from . import retriever as rag_retriever

        def retrieve_fn(question: str, top_k: int = 10) -> List[Dict]:
            return rag_retriever.hybrid_retrieve(question, top_k=top_k, use_cache=False)

    run = build_run(samples, retrieve_fn, top_k=top_k)
    result = evaluate(qrels, run, metrics=metrics)

    result["dataset_path"] = str(dataset_path or DEFAULT_DATASET_PATH)
    result["top_k"] = top_k
    return result
