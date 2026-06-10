from __future__ import annotations

from typing import Any, Dict, List, Optional

import httpx

from ..clients import backend_client as backend
from ..clients import redis_client as cache
from ..rag import retriever as rag_retriever
from ..rag.ingest import ensure_index


def get_video_report(video_id: int, user_id: Optional[int] = None) -> Dict[str, Any]:
    cached = cache.get_video_report(video_id)
    if cached:
        return cached

    report = backend.get_video_report_raw(video_id)
    if report is None:
        return {"error": f"Failed to get report for video {video_id}"}

    cache.set_video_report(video_id, report)
    return report


def get_training_history(user_id: int, limit: int = 10) -> Dict[str, Any]:
    data = backend.get_training_history(user_id, limit)
    if data is None:
        return {"error": f"Failed to get training history for user {user_id}"}
    return data


def get_score_trend(user_id: int, days: int = 30) -> Dict[str, Any]:
    data = backend.get_video_trends(user_id, days)
    if data is None:
        return {"error": f"Failed to get score trends for user {user_id}"}
    return data


def get_user_memory(user_id: int) -> Dict[str, Any]:
    cached = cache.get_user_memory(user_id)
    if cached:
        return cached

    profile = backend.get_user_profile(user_id)
    if profile is None:
        # Try trends as fallback for training profile
        trends = backend.get_video_trends(user_id, 90)
        if trends:
            profile = {
                "totalSessions": trends.get("completedSessions", 0),
                "overallScore": trends.get("overallScore", 0),
                "trend": trends,
            }
        else:
            return {"message": "No user profile yet"}

    cache.set_user_memory(user_id, profile)
    return profile


def search_knowledge(query: str, top_k: int = 5) -> Dict[str, Any]:
    ensure_index()

    try:
        results = rag_retriever.hybrid_retrieve(query, top_k=top_k)
        docs = []
        for r in results:
            docs.append({
                "content": r["content"],
                "title": r.get("metadata", {}).get("source", ""),
                "score": r["final_score"],
                "matched_by": r["matched_by"],
            })

        return {
            "query": query,
            "totalResults": len(docs),
            "results": docs,
        }
    except Exception as e:
        return {"error": f"Search failed: {e}", "query": query, "results": []}


TOOL_DEFINITIONS = {
    "get_video_report": {
        "name": "get_video_report",
        "description": "获取指定视频的分析报告，包含动作次数、角度、评分、节奏、对称性。用于分析单次训练表现。",
        "function": get_video_report,
        "args": ["videoId"],
    },
    "get_training_history": {
        "name": "get_training_history",
        "description": "获取用户最近的训练记录，包含运动类型、评分、完成次数。用于了解训练频率和近况。",
        "function": get_training_history,
        "args": ["limit"],
    },
    "get_score_trend": {
        "name": "get_score_trend",
        "description": "获取用户训练评分趋势数据，包含每日分数和整体趋势。用于分析进步情况。",
        "function": get_score_trend,
        "args": ["days"],
    },
    "get_user_memory": {
        "name": "get_user_memory",
        "description": "获取用户的长期训练画像，包含总训练次数、平均分、弱项运动、常见错误。用于个性化建议。",
        "function": get_user_memory,
        "args": [],
    },
    "search_knowledge": {
        "name": "search_knowledge",
        "description": "搜索健身知识库，包含动作要领、常见错误、纠正方法、训练原则。用于获取循证训练建议。",
        "function": search_knowledge,
        "args": ["query", "topK"],
    },
}


def build_tool_descriptions() -> str:
    lines = []
    for name, defn in TOOL_DEFINITIONS.items():
        args_str = ", ".join(defn["args"]) if defn["args"] else "none"
        lines.append(f"- {name}({args_str}): {defn['description']}")
    return "\n".join(lines)
