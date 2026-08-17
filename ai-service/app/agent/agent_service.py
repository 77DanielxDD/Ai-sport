from __future__ import annotations

import json
import os
import re
import time
from typing import Any, Dict, List, Optional

from ..clients import llm_client
from ..clients import redis_client as cache
from . import intent, prompts, schemas, tools


def _extract_json(text: str) -> Optional[str]:
    """Extract JSON from LLM response."""
    if not text:
        return None

    # Try ```json block
    m = re.search(r"```json\s*(.*?)\s*```", text, re.DOTALL)
    if m:
        return m.group(1).strip()

    # Try ``` block
    m = re.search(r"```\s*(.*?)\s*```", text, re.DOTALL)
    if m:
        return m.group(1).strip()

    # Try first { or [ to matching close
    brace = text.find("{")
    bracket = text.find("[")
    start = -1
    open_char = "{"
    close_char = "}"
    if brace >= 0 and bracket >= 0:
        start = min(brace, bracket)
    elif brace >= 0:
        start = brace
    elif bracket >= 0:
        start = bracket
        open_char = "["
        close_char = "]"

    if start >= 0:
        depth = 0
        in_string = False
        for i in range(start, len(text)):
            c = text[i]
            if c == '"' and (i == 0 or text[i - 1] != "\\"):
                in_string = not in_string
            if not in_string:
                if c == open_char:
                    depth += 1
                elif c == close_char:
                    depth -= 1
                    if depth == 0:
                        return text[start : i + 1]

    return None


def _parse_tool_plan(response: str) -> List[Dict[str, Any]]:
    """Parse LLM tool plan response into list of tool call dicts."""
    json_str = _extract_json(response)
    if not json_str:
        return []

    try:
        parsed = json.loads(json_str)
        if isinstance(parsed, list):
            return parsed
        if isinstance(parsed, dict):
            return [parsed]
    except json.JSONDecodeError:
        pass
    return []


def _execute_tool_calls(
    plan: List[Dict[str, Any]],
    user_id: int,
    username: str,
    focus_video_id: Optional[int],
) -> tuple[Dict[str, Any], List[schemas.ToolCallRecord]]:
    """Execute planned tool calls and return results + records."""
    results: Dict[str, Any] = {}
    records: List[schemas.ToolCallRecord] = []

    for call in plan:
        tool_name = str(call.get("tool", ""))
        args = call.get("args", {}) or {}

        if tool_name not in tools.TOOL_DEFINITIONS:
            records.append(schemas.ToolCallRecord(
                tool=tool_name, success=False,
                summary=f"Unknown tool: {tool_name}", duration_ms=0,
            ))
            continue

        start = int(time.time() * 1000)
        try:
            tool_def = tools.TOOL_DEFINITIONS[tool_name]
            func = tool_def["function"]

            # Map args based on tool type
            if tool_name == "get_video_report":
                video_id = args.get("videoId", focus_video_id)
                if video_id is None:
                    results[tool_name] = {"error": "videoId is required"}
                else:
                    results[tool_name] = func(video_id=int(video_id), user_id=user_id)
            elif tool_name == "get_training_history":
                limit = int(args.get("limit", 10))
                results[tool_name] = func(user_id=user_id, limit=limit)
            elif tool_name == "get_score_trend":
                days = int(args.get("days", 30))
                results[tool_name] = func(user_id=user_id, days=days)
            elif tool_name == "get_user_memory":
                results[tool_name] = func(user_id=user_id)
            elif tool_name == "search_knowledge":
                query = str(args.get("query", ""))
                top_k = int(args.get("topK", 5))
                results[tool_name] = func(query=query, top_k=top_k)
            else:
                results[tool_name] = {"error": f"Unhandled tool: {tool_name}"}

            elapsed = int(time.time() * 1000) - start
            success = "error" not in results.get(tool_name, {})
            summary = f"{tool_name} succeeded" if success else f"{tool_name} failed: {results[tool_name].get('error', '')}"
            records.append(schemas.ToolCallRecord(
                tool=tool_name, success=success, summary=summary, duration_ms=elapsed,
            ))
        except Exception as e:
            elapsed = int(time.time() * 1000) - start
            results[tool_name] = {"error": str(e)}
            records.append(schemas.ToolCallRecord(
                tool=tool_name, success=False, summary=f"{tool_name} error: {e}", duration_ms=elapsed,
            ))

    return results, records


def _generate_answer(
    question: str,
    tool_results: Dict[str, Any],
    records: List[schemas.ToolCallRecord],
) -> schemas.AgentChatResponse:
    """Generate structured answer from tool results via LLM."""
    try:
        llm = llm_client.get_llm_json()
    except RuntimeError:
        # Fallback: build answer without LLM
        return _build_rule_answer(question, tool_results, records)

    try:
        from langchain.prompts import ChatPromptTemplate

        prompt = ChatPromptTemplate.from_messages([
            ("system", prompts.ANSWER_SYSTEM),
            ("user", prompts.ANSWER_USER),
        ])

        chain = prompt | llm
        response = chain.invoke({
            "question": question,
            "tool_results": json.dumps(tool_results, ensure_ascii=False, indent=2),
        })

        json_str = _extract_json(response.content if hasattr(response, "content") else str(response))
        if not json_str:
            return _build_rule_answer(question, tool_results, records)

        data = json.loads(json_str)

        # Build response
        result = schemas.AgentChatResponse(
            summary=data.get("summary", ""),
            diagnosis=[schemas.DiagnosisItem(**d) for d in data.get("diagnosis", [])],
            recommendations=[schemas.Recommendation(**r) for r in data.get("recommendations", [])],
            training_plan=[schemas.TrainingPlanItem(**p) for p in data.get("trainingPlan", [])],
            tool_calls=records,
        )

        # Extract references from knowledge search results
        knowledge_result = tool_results.get("search_knowledge", {})
        if isinstance(knowledge_result, dict):
            ref_results = knowledge_result.get("results", [])
            for ref in ref_results[:5]:
                result.references.append(schemas.ReferenceItem(
                    type="knowledge",
                    title=ref.get("title", ""),
                    snippet=ref.get("content", "")[:200],
                ))

        # Also include refs from the LLM response
        for ref in data.get("references", []):
            if isinstance(ref, dict):
                result.references.append(schemas.ReferenceItem(
                    type=ref.get("type", "knowledge"),
                    title=ref.get("title", ""),
                    snippet=ref.get("snippet", ""),
                ))

        return result
    except Exception as e:
        return _build_rule_answer(question, tool_results, records)


def _build_rule_answer(
    question: str,
    tool_results: Dict[str, Any],
    records: List[schemas.ToolCallRecord],
) -> schemas.AgentChatResponse:
    """Rule-based fallback when LLM is unavailable."""
    response = schemas.AgentChatResponse(
        summary="基于训练数据的分析结果",
        tool_calls=records,
    )

    # Extract knowledge results
    knowledge = tool_results.get("search_knowledge", {})
    if isinstance(knowledge, dict) and knowledge.get("results"):
        response.summary = f"根据知识库搜索结果，为您找到 {knowledge['totalResults']} 条相关知识。"
        for r in knowledge["results"][:3]:
            response.references.append(schemas.ReferenceItem(
                type="knowledge",
                title=r.get("title", ""),
                snippet=r.get("content", "")[:200],
            ))

    # Extract video report insights
    report = tool_results.get("get_video_report", {})
    if isinstance(report, dict) and "error" not in report:
        ex_type = report.get("exerciseType", "训练")
        score = report.get("scoreBreakdown", {})
        final = score.get("finalScore", 0)
        response.diagnosis.append(schemas.DiagnosisItem(
            issue=f"{ex_type} 综合评分 {final}",
            evidence=f"动作完成度评估",
            severity="medium",
        ))

    # Extract trends
    trends = tool_results.get("get_score_trend", {})
    if isinstance(trends, dict) and "overallScore" in trends:
        overall = trends.get("overallScore", 0)
        response.summary += f" 近期整体评分 {overall}。"

    response.recommendations.append(schemas.Recommendation(
        title="基础训练建议",
        detail="保持动作质量优先，每次训练录制视频进行对比分析。",
        priority="medium",
    ))

    response.training_plan.append(schemas.TrainingPlanItem(
        day="第1-3天",
        content="技术优先训练，50%-70%负荷，注重动作幅度和稳定性",
        focus="动作质量",
    ))

    return response


def process_question(req: schemas.AgentChatRequest) -> schemas.AgentChatResponse:
    """Main entry: process a user question through the Agent pipeline."""
    start_time = time.time()

    # Check cache
    cached = cache.get_agent_answer(req.user_id, req.focus_video_id, req.question)
    if cached:
        return schemas.AgentChatResponse(**cached)

    # Stage 1: Classify intent (GLM) then decide which tools to call
    try:
        llm = llm_client.get_llm()
        tool_descriptions = tools.build_tool_descriptions()

        focus_info = ""
        if req.focus_video_id:
            focus_info = f"Focus VideoId: {req.focus_video_id}\n(user is asking about this specific training session)"

        intent_result = intent.classify_intent(req.question)

        from langchain.prompts import ChatPromptTemplate
        plan_prompt = ChatPromptTemplate.from_messages([
            ("system", prompts.TOOL_PLAN_SYSTEM),
            ("user", prompts.TOOL_PLAN_USER),
        ])

        chain = plan_prompt | llm
        plan_response = chain.invoke({
            "username": req.username,
            "user_id": req.user_id,
            "focus_info": focus_info,
            "intent_label": intent_result["label"],
            "tool_descriptions": tool_descriptions,
            "question": req.question,
        })

        plan_text = plan_response.content if hasattr(plan_response, "content") else str(plan_response)
        tool_plan = _parse_tool_plan(plan_text)
    except Exception:
        # Fallback: use all relevant tools
        tool_plan = [{"tool": "search_knowledge", "args": {"query": req.question, "topK": 3}}]
        if req.focus_video_id:
            tool_plan.insert(0, {"tool": "get_video_report", "args": {"videoId": req.focus_video_id}})

    # Stage 2: Execute tool calls
    tool_results, records = _execute_tool_calls(
        tool_plan, req.user_id, req.username, req.focus_video_id,
    )

    # Stage 3: Generate structured answer
    answer = _generate_answer(req.question, tool_results, records)

    # Cache the answer
    cache.set_agent_answer(
        req.user_id, req.focus_video_id, req.question,
        answer.model_dump(), ttl=180,
    )

    elapsed = int((time.time() - start_time) * 1000)
    # Log call summary
    for r in records:
        r.duration_ms = r.duration_ms if r.duration_ms > 0 else 0

    return answer
