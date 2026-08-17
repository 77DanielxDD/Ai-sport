"""用户问题意图分类：用 GLM-4-flash 做显式路由。

替代原 Java RuleBasedQuestionRouter。LLM 不可用时回退到关键词规则打分，
保证路由不断链。意图类别沿用原四类。
"""
from __future__ import annotations

import hashlib
import re
from typing import Dict, Optional

from ..clients import llm_client
from ..clients import redis_client as cache

INTENT_OPTIONS = [
    "form_correction",  # 动作纠正
    "training_plan",    # 训练计划
    "trend_review",     # 趋势回顾
    "general_knowledge",  # 通用知识
]

_INTENT_LABELS = {
    "form_correction": "动作纠正",
    "training_plan": "训练计划",
    "trend_review": "趋势回顾",
    "general_knowledge": "通用知识",
}

# 规则兜底关键词（沿用原 RuleBasedQuestionRouter 的计分逻辑）
_PLAN_KEYWORDS = ("计划", "怎么练", "训练方案", "一周", "每天", "安排", "plan", "routine", "schedule", "program")
_FORM_KEYWORDS = ("纠正", "错误", "姿势", "动作", "角度", "深度", "幅度", "form", "correct", "fix", "mistake",
                  "技术", "technique", "标准", "standard")
_TREND_KEYWORDS = ("趋势", "进步", "变化", "最近", "历史", "对比", "trend", "progress", "history", "compare",
                   "评分", "分数", "score")

_INTENT_SYSTEM = """你是健身领域的意图分类器。根据用户问题判断意图类型，只返回 JSON：
{"intent": "form_correction" 或 "training_plan" 或 "trend_review" 或 "general_knowledge"}

含义：
- form_correction：动作姿势、技术纠错、常见错误
- training_plan：训练计划、方案安排、训练量
- trend_review：训练趋势、进步、历史对比、评分变化
- general_knowledge：通用健身知识、原理、其他

只输出 JSON，不要解释。"""


def _rule_intent(question: str) -> str:
    q = (question or "").lower()
    plan = sum(1 for k in _PLAN_KEYWORDS if k in q)
    form = sum(1 for k in _FORM_KEYWORDS if k in q)
    trend = sum(1 for k in _TREND_KEYWORDS if k in q)

    if form >= plan and form >= trend:
        return "form_correction"
    if trend >= plan:
        return "trend_review"
    if plan > 0:
        return "training_plan"
    return "general_knowledge"


def classify_intent(question: str) -> Dict:
    """返回 {"intent": "...", "label": "动作纠正"}。LLM 失败自动回退规则。"""
    key = "rag:intent:" + _hash(question)
    cached = cache.cache_get_json(key)
    if cached and cached.get("intent"):
        return cached

    intent = _llm_intent(question) or _rule_intent(question)
    result = {"intent": intent, "label": _INTENT_LABELS.get(intent, "通用知识")}
    cache.cache_set_json(key, result, ttl_seconds=600)
    return result


def _hash(text: str) -> str:
    return hashlib.md5(text.encode()).hexdigest()[:12]


def _llm_intent(question: str) -> Optional[str]:
    try:
        llm = llm_client.get_llm_json()
        from langchain.prompts import ChatPromptTemplate

        prompt = ChatPromptTemplate.from_messages([
            ("system", _INTENT_SYSTEM),
            ("user", "用户问题：{question}"),
        ])
        chain = prompt | llm
        resp = chain.invoke({"question": question})
        text = resp.content if hasattr(resp, "content") else str(resp)
        m = re.search(r'"(form_correction|training_plan|trend_review|general_knowledge)"', text)
        if m:
            return m.group(1)
    except Exception:
        pass
    return None
