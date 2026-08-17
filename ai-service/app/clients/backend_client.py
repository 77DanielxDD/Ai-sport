from __future__ import annotations

import os
from typing import Any, Dict, List, Optional

import httpx

BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://localhost:8080")
API_PREFIX = "/api"

_client: Optional[httpx.Client] = None


def _get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(timeout=httpx.Timeout(15.0))
    return _client


def _url(path: str) -> str:
    return f"{BACKEND_BASE_URL}{API_PREFIX}{path}"


def get_video_trends(user_id: int, days: int = 30) -> Optional[Dict[str, Any]]:
    """Call GET /api/videos/trends?userId={userId}&days={days}"""
    try:
        r = _get_client().get(_url("/videos/trends"), params={"userId": user_id, "days": days})
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


def get_training_history(user_id: int, limit: int = 10) -> Optional[Dict[str, Any]]:
    """Call GET /api/videos?userId={userId}&limit={limit} or similar"""
    try:
        r = _get_client().get(_url("/videos"), params={"userId": user_id, "limit": limit})
        r.raise_for_status()
        data = r.json()
        if isinstance(data, list):
            return {"records": data, "totalCompleted": len(data)}
        return data
    except Exception:
        return None


def get_video_report_raw(video_id: int) -> Optional[Dict[str, Any]]:
    """Call GET /api/videos/{videoId}/analysis"""
    try:
        r = _get_client().get(_url(f"/videos/{video_id}/analysis"))
        if r.status_code == 202:
            return {"status": "processing"}
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


def get_user_profile(user_id: int) -> Optional[Dict[str, Any]]:
    """Call GET /api/users/{userId}/profile"""
    try:
        r = _get_client().get(_url(f"/users/{user_id}/profile"))
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


