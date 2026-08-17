"""RAG 检索评测模块最小校验层。

覆盖：数据集读取、qrels 组装、run 组装、evaluate 输出结构。
evaluate 依赖 pytrec_eval，本机无法编译时自动跳过。
"""
import json
import sys

import pytest

from app.rag import evaluation

SAMPLES = [
    {"query_id": "q1", "question": "深蹲膝盖内扣", "relevant_doc_ids": {"chunk_0000": 2, "chunk_0001": 1}},
    {"query_id": "q2", "question": "硬拉腰背姿势", "relevant_doc_ids": {"chunk_0002": 2}},
]


def _make_dataset(tmp_path, samples):
    path = tmp_path / "eval.jsonl"
    with open(path, "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    return str(path)


def _fake_retrieve(question, top_k=10):
    return [
        {"chunk_id": "chunk_0000", "final_score": 1.0, "content": "x"},
        {"chunk_id": "chunk_0002", "final_score": 0.5, "content": "y"},
    ]


def test_load_dataset(tmp_path):
    samples = evaluation.load_dataset(_make_dataset(tmp_path, SAMPLES))
    assert len(samples) == 2
    assert samples[0]["query_id"] == "q1"
    assert samples[0]["relevant_doc_ids"] == {"chunk_0000": 2, "chunk_0001": 1}


def test_load_dataset_skips_invalid_lines(tmp_path):
    samples = evaluation.load_dataset(_make_dataset(tmp_path, [{"query_id": "bad"}]))
    assert samples == []


def test_build_qrels():
    qrels = evaluation.build_qrels(SAMPLES)
    assert qrels == {
        "q1": {"chunk_0000": 2, "chunk_0001": 1},
        "q2": {"chunk_0002": 2},
    }


def test_build_run_uses_chunk_id():
    run = evaluation.build_run(SAMPLES, _fake_retrieve, top_k=10)
    assert set(run.keys()) == {"q1", "q2"}
    assert run["q1"]["chunk_0000"] == 1.0


def test_evaluate_with_real_pytrec_eval():
    """真实 pytrec_eval 集成测试；库缺失时跳过（如无 MSVC 的 Windows 环境）。"""
    pytest.importorskip("pytrec_eval")
    qrels = evaluation.build_qrels(SAMPLES)
    run = evaluation.build_run(SAMPLES, _fake_retrieve, top_k=10)
    result = evaluation.evaluate(qrels, run)
    assert set(result.keys()) >= {"aggregate", "per_query", "failed_queries", "num_queries"}
    assert result["num_queries"] == 2


def test_evaluate_output_structure_with_stub():
    """结构校验：用极简 stub 模拟 pytrec_eval（点号指标键转下划线），
    验证 evaluate 输出结构与指标键映射逻辑。真实库存在时此逻辑同样生效。"""
    stub = _pytrec_stub()
    sys.modules["pytrec_eval"] = stub
    try:
        qrels = evaluation.build_qrels(SAMPLES)
        run = evaluation.build_run(SAMPLES, _fake_retrieve, top_k=10)
        result = evaluation.evaluate(qrels, run, metrics=["map", "ndcg_cut.10", "recall.5"])
    finally:
        sys.modules.pop("pytrec_eval", None)

    assert set(result.keys()) >= {"aggregate", "per_query", "failed_queries", "num_queries"}
    assert result["num_queries"] == 2
    assert result["aggregate"]["ndcg_cut.10"] == pytest.approx(0.5)
    assert result["per_query"]["q1"]["ndcg_cut.10"] == pytest.approx(0.5)


def test_run_evaluation_report_fields(tmp_path):
    """run_evaluation 报告字段：dataset_path / top_k / aggregate / num_queries。"""
    dataset_path = _make_dataset(tmp_path, SAMPLES)
    stub = _pytrec_stub()
    sys.modules["pytrec_eval"] = stub
    try:
        report = evaluation.run_evaluation(
            dataset_path=dataset_path,
            retrieve_fn=_fake_retrieve,
            top_k=10,
            metrics=["map"],
        )
    finally:
        sys.modules.pop("pytrec_eval", None)

    assert "error" not in report
    assert report["top_k"] == 10
    assert report["num_queries"] == 2
    assert "aggregate" in report and "per_query" in report
    assert "failed_queries" in report


class _pytrec_stub:
    """按 pytrec_eval 行为模拟：指标键 '.' -> '_'。"""

    class RelevanceEvaluator:
        def __init__(self, qrels, measures):
            self.qrels = qrels
            self.measures = measures
            self._keys = [m.replace(".", "_") for m in measures]

        def evaluate(self, run):
            return {
                qid: {k: 0.5 for k in self._keys}
                for qid in run
            }
