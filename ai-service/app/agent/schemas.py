from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class AgentChatRequest(BaseModel):
    user_id: int = Field(..., ge=1)
    username: str
    question: str
    focus_video_id: Optional[int] = None


class DiagnosisItem(BaseModel):
    issue: str
    evidence: str
    severity: str = "medium"


class Recommendation(BaseModel):
    title: str
    detail: str
    priority: str = "medium"


class TrainingPlanItem(BaseModel):
    day: str
    content: str
    focus: str = ""


class ReferenceItem(BaseModel):
    type: str
    title: str
    snippet: str


class ToolCallRecord(BaseModel):
    tool: str
    success: bool
    summary: str
    duration_ms: int


class AgentChatResponse(BaseModel):
    summary: str = ""
    diagnosis: List[DiagnosisItem] = Field(default_factory=list)
    recommendations: List[Recommendation] = Field(default_factory=list)
    training_plan: List[TrainingPlanItem] = Field(default_factory=list)
    references: List[ReferenceItem] = Field(default_factory=list)
    tool_calls: List[ToolCallRecord] = Field(default_factory=list)


class ReindexResponse(BaseModel):
    status: str
    chunk_count: int
    message: str = ""


class RagStatusResponse(BaseModel):
    status: str
    vector_store: str
    chunk_count: int
    has_bm25: bool
