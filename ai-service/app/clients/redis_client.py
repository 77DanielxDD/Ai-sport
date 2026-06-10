from __future__ import annotations

import hashlib
import json
import os
import time
from typing import Any, Dict, List, Optional

import redis


def _get_redis_client() -> Optional[redis.Redis]:
    url = os.getenv("REDIS_URL", "")
    if not url:
        return None
    try:
        return redis.Redis.from_url(url, socket_connect_timeout=2, decode_responses=True)
    except Exception:
        return None


_redis: Optional[redis.Redis] = None


def get_redis() -> Optional[redis.Redis]:
    global _redis
    if _redis is None:
        _redis = _get_redis_client()
    return _redis


def _cache_key(prefix: str, *parts: str) -> str:
    return f"aisport:{prefix}:" + ":".join(parts)


def _question_hash(question: str) -> str:
    return hashlib.md5(question.encode()).hexdigest()[:12]


def cache_get(key: str) -> Optional[str]:
    r = get_redis()
    if r is None:
        return None
    try:
        return r.get(key)
    except Exception:
        return None


def cache_set(key: str, value: str, ttl_seconds: int = 300) -> None:
    r = get_redis()
    if r is None:
        return
    try:
        r.setex(key, ttl_seconds, value)
    except Exception:
        pass


def cache_get_json(key: str) -> Optional[Any]:
    raw = cache_get(key)
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return None


def cache_set_json(key: str, value: Any, ttl_seconds: int = 300) -> None:
    try:
        cache_set(key, json.dumps(value, ensure_ascii=False, default=str), ttl_seconds)
    except Exception:
        pass


# ---- typed helpers ----

def get_agent_answer(user_id: int, video_id: Optional[int], question: str) -> Optional[Dict]:
    if video_id:
        key = _cache_key("agent:answer", str(user_id), str(video_id), _question_hash(question))
    else:
        key = _cache_key("agent:answer", str(user_id), "novideo", _question_hash(question))
    return cache_get_json(key)


def set_agent_answer(user_id: int, video_id: Optional[int], question: str, answer: Dict, ttl: int = 180) -> None:
    if video_id:
        key = _cache_key("agent:answer", str(user_id), str(video_id), _question_hash(question))
    else:
        key = _cache_key("agent:answer", str(user_id), "novideo", _question_hash(question))
    cache_set_json(key, answer, ttl)


def get_rewrite(question: str) -> Optional[str]:
    key = _cache_key("rag:rewrite", _question_hash(question))
    return cache_get(key)


def set_rewrite(question: str, rewritten: str, ttl: int = 600) -> None:
    key = _cache_key("rag:rewrite", _question_hash(question))
    cache_set(key, rewritten, ttl)


def get_retrieval(query: str) -> Optional[List[Dict]]:
    key = _cache_key("rag:retrieval", _question_hash(query))
    return cache_get_json(key)


def set_retrieval(query: str, results: List[Dict], ttl: int = 300) -> None:
    key = _cache_key("rag:retrieval", _question_hash(query))
    cache_set_json(key, results, ttl)


def get_video_report(video_id: int) -> Optional[Dict]:
    key = _cache_key("tool:video_report", str(video_id))
    return cache_get_json(key)


def set_video_report(video_id: int, report: Dict, ttl: int = 120) -> None:
    key = _cache_key("tool:video_report", str(video_id))
    cache_set_json(key, report, ttl)


def get_user_memory(user_id: int) -> Optional[Dict]:
    key = _cache_key("tool:user_memory", str(user_id))
    return cache_get_json(key)


def set_user_memory(user_id: int, profile: Dict, ttl: int = 180) -> None:
    key = _cache_key("tool:user_memory", str(user_id))
    cache_set_json(key, profile, ttl)
