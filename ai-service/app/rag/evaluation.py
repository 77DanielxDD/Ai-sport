from __future__ import annotations

from typing import Dict, List

_EVAL_DATASET: List[Dict] = []


def get_default_dataset() -> List[Dict]:
    """Small benchmark dataset for RAG quality evaluation."""
    return [
        {
            "question": "深蹲时膝盖内扣怎么改善？",
            "ground_truth": (
                "深蹲膝盖内扣需要通过以下方法改善：加强髋外展肌群（如臀中肌）力量，"
                "用弹力带绕膝进行侧向行走激活；下蹲时有意识地将膝盖朝脚尖方向打开；"
                "先降低负重，在较轻重量下建立正确的下肢力线后再逐步加重。"
            ),
        },
        {
            "question": "俯卧撑手腕疼是什么原因？",
            "ground_truth": (
                "俯卧撑手腕疼常见原因是手腕过度伸展、手掌位置不当或腕关节活动度不足。"
                "纠正方法：手掌放在肩膀正下方，手指张开分散压力；保持手腕中立位；"
                "可使用俯卧撑支架或拳卧撑减轻手腕伸展角度；训练前做手腕环绕热身。"
            ),
        },
        {
            "question": "硬拉时腰背应该保持什么姿势？",
            "ground_truth": (
                "硬拉时腰背应保持脊柱中立位，胸椎伸展，核心收紧建立腹内压。"
                "起拉前杠铃贴近小腿，保持背部张力，避免弓背或过度反弓。"
                "若动作变形应立即停止并降低负荷。"
            ),
        },
        {
            "question": "如何判断训练重量是否合适？",
            "ground_truth": (
                "训练重量合适的标准：能够完成目标次数且最后一两次有挑战但动作不变形。"
                "若动作幅度减小、身体代偿明显或无法控制离心阶段则重量过大。"
                "建议以技术优先，先用50%-70%负荷建立动作标准再逐步加重。"
            ),
        },
        {
            "question": "每次训练应该做多少组？",
            "ground_truth": (
                "一般建议每个动作做3-5组，根据训练目标调整：力量训练3-5组低次数（3-6次），"
                "增肌训练3-4组中等次数（8-12次），耐力训练2-3组高次数（15-20次）。"
                "初学者可从2-3组开始逐步增加。"
            ),
        },
    ]


def run_evaluation(retrieve_fn, generate_fn) -> Dict:
    """Run RAGAS evaluation.

    Args:
        retrieve_fn: function(question) -> List[Dict] with 'content' key
        generate_fn: function(question, contexts) -> str

    Returns dict with metric scores.
    """
    dataset = get_default_dataset()

    questions = []
    answers = []
    contexts_list = []
    ground_truths = []

    for item in dataset:
        q = item["question"]
        gt = item["ground_truth"]

        contexts_raw = retrieve_fn(q)
        contexts = [c["content"] if isinstance(c, dict) else str(c) for c in (contexts_raw or [])]
        answer = generate_fn(q, contexts)

        questions.append(q)
        answers.append(answer)
        contexts_list.append(contexts)
        ground_truths.append(gt)

    try:
        from ragas import evaluate
        from ragas.metrics import faithfulness, answer_relevancy, context_precision, context_recall
        from datasets import Dataset as HfDataset

        eval_data = HfDataset.from_dict({
            "question": questions,
            "answer": answers,
            "contexts": contexts_list,
            "ground_truth": ground_truths,
        })

        scores = evaluate(
            eval_data,
            metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
        )
        return {
            "faithfulness": round(float(scores.get("faithfulness", 0)), 4),
            "answer_relevancy": round(float(scores.get("answer_relevancy", 0)), 4),
            "context_precision": round(float(scores.get("context_precision", 0)), 4),
            "context_recall": round(float(scores.get("context_recall", 0)), 4),
        }
    except ImportError:
        return {
            "error": "ragas or datasets not installed",
            "note": "pip install ragas datasets to enable evaluation",
        }
    except Exception as e:
        return {"error": f"Evaluation failed: {e}"}


def evaluation_report() -> Dict:
    """Generate a full evaluation report on current RAG pipeline."""
    from ..rag import retriever as rag_retriever
    from ..rag.ingest import ensure_index

    ensure_index()

    def retrieve_fn(question: str) -> List[Dict]:
        return rag_retriever.hybrid_retrieve(question, top_k=5, use_cache=False)

    def generate_fn(question: str, contexts: List[str]) -> str:
        if not contexts:
            return "No knowledge found."
        context_text = "\n\n".join(contexts[:3])
        from ..clients.llm_client import get_llm

        try:
            llm = get_llm()
            prompt = (
                f"基于以下健身知识回答问题。\n\n知识：\n{context_text}\n\n问题：{question}\n\n回答："
            )
            result = llm.invoke(prompt)
            return result.content if hasattr(result, "content") else str(result)
        except Exception:
            return f"Based on fitness knowledge: {context_text[:300]}..."

    scores = run_evaluation(retrieve_fn, generate_fn)

    return {
        "dataset_size": len(get_default_dataset()),
        "metrics": scores,
        "note": "Baseline evaluation of the current RAG pipeline",
    }
