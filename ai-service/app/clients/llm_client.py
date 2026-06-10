from __future__ import annotations

import os
from typing import Optional

LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.deepseek.com/v1")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "deepseek-chat")


def get_llm() -> object:
    """Return a LangChain ChatOpenAI-compatible LLM configured for DeepSeek."""
    try:
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(
            model=LLM_MODEL,
            openai_api_key=LLM_API_KEY,
            openai_api_base=LLM_BASE_URL,
            temperature=0.3,
            max_tokens=2048,
            timeout=60,
        )
    except ImportError:
        raise RuntimeError("langchain-openai is not installed")


def get_llm_json() -> object:
    """LLM with JSON mode for structured output."""
    try:
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(
            model=LLM_MODEL,
            openai_api_key=LLM_API_KEY,
            openai_api_base=LLM_BASE_URL,
            temperature=0.1,
            max_tokens=2048,
            timeout=60,
            model_kwargs={"response_format": {"type": "json_object"}},
        )
    except ImportError:
        raise RuntimeError("langchain-openai is not installed")


def get_llm_high_temp() -> object:
    """LLM with higher temperature for creative generation."""
    try:
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(
            model=LLM_MODEL,
            openai_api_key=LLM_API_KEY,
            openai_api_base=LLM_BASE_URL,
            temperature=0.7,
            max_tokens=2048,
            timeout=60,
        )
    except ImportError:
        raise RuntimeError("langchain-openai is not installed")
