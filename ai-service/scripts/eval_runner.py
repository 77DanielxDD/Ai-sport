#!/usr/bin/env python3
"""
离线检索评测脚本

使用 pytrec_eval 评估 RAG 检索质量。

用法:
    python eval_runner.py [选项]

选项:
    --dataset PATH    评测数据集路径 (默认: ai-service/app/rag/eval_dataset.jsonl)
    --top-k K         每个查询返回的文档数 (默认: 10)
    --metrics M1,M2   评估指标列表 (默认: map,ndcg_cut.10,recall.1,recall.5,recall.10,P.1,P.5,P.10,mrr_cut.10)
    --output PATH     输出结果 JSON 路径 (可选)
    --verbose         显示每个查询的详细结果
"""
import argparse
import json
import sys
from pathlib import Path

# 添加 ai-service 目录到 Python 路径，使 `from app.rag import evaluation` 可解析
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.rag import evaluation


def main():
    parser = argparse.ArgumentParser(description="离线检索评测")
    parser.add_argument(
        "--dataset",
        type=str,
        default=None,
        help="评测数据集路径 (默认: ai-service/app/rag/eval_dataset.jsonl)",
    )
    parser.add_argument(
        "--top-k",
        type=int,
        default=10,
        help="每个查询返回的文档数 (默认: 10)",
    )
    parser.add_argument(
        "--metrics",
        type=str,
        default=None,
        help="评估指标列表，逗号分隔 (默认: map,ndcg_cut.10,recall.1,recall.5,recall.10,P.1,P.5,P.10,mrr_cut.10)",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="输出结果 JSON 路径 (可选)",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="显示每个查询的详细结果",
    )
    args = parser.parse_args()

    # 解析指标列表
    metrics = None
    if args.metrics:
        metrics = [m.strip() for m in args.metrics.split(",")]

    print("=" * 80)
    print("离线检索评测")
    print("=" * 80)
    print(f"数据集: {args.dataset or '默认 (ai-service/app/rag/eval_dataset.jsonl)'}")
    print(f"Top-K: {args.top_k}")
    print(f"指标: {metrics or '默认'}")
    print()

    # 运行评估
    try:
        result = evaluation.run_evaluation(
            dataset_path=args.dataset,
            top_k=args.top_k,
            metrics=metrics,
        )
    except FileNotFoundError as e:
        print(f"错误: {e}", file=sys.stderr)
        print("请创建评测数据集文件，格式参考: ai-service/app/rag/eval_dataset.jsonl", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"评估失败: {e}", file=sys.stderr)
        sys.exit(1)

    # 检查错误
    if "error" in result:
        print(f"错误: {result['error']}", file=sys.stderr)
        sys.exit(1)

    # 输出结果
    print("-" * 80)
    print("聚合指标")
    print("-" * 80)
    for metric, value in result["aggregate"].items():
        print(f"  {metric:20s}: {value:.4f}")
    print()

    print(f"查询总数: {result['num_queries']}")
    print(f"失败查询数: {len(result['failed_queries'])}")
    if result["failed_queries"]:
        print(f"失败查询ID: {', '.join(result['failed_queries'])}")
    print()

    # 详细结果
    if args.verbose:
        print("-" * 80)
        print("每个查询的详细结果")
        print("-" * 80)
        for qid, scores in result["per_query"].items():
            print(f"\n查询 {qid}:")
            for metric, value in scores.items():
                print(f"  {metric:20s}: {value:.4f}")

    # 保存结果
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"\n结果已保存到: {output_path}")

    print("=" * 80)


if __name__ == "__main__":
    main()
