#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""Normalize model output before it becomes user-visible content."""

from __future__ import annotations

import json
import re
from typing import Any, Dict, Iterable


ALLOWED_INTENTS = {
    "general",
    "route",
    "presale",
    "recommendation",
    "beauty",
    "aftersale",
    "queue",
    "appointment",
    "support",
    "fulfillment",
}
STRUCTURED_MARKERS = (
    '"intent"',
    '"toolCalls"',
    '"recommendedStyles"',
    '"pendingAction"',
    '"quickReplies"',
)
JSON_FENCE_RE = re.compile(r"```(?:json)?\s*(\{[\s\S]*?\})\s*```", re.IGNORECASE)


def _as_dict(value: Any) -> Dict[str, Any]:
    return dict(value) if isinstance(value, dict) else {}


def _try_parse_object(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    text = str(value or "").strip()
    if not text:
        return {}
    candidates = [text]
    fenced = JSON_FENCE_RE.search(text)
    if fenced:
        candidates.insert(0, fenced.group(1))
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        candidates.append(text[start:end + 1])
    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
        except (TypeError, json.JSONDecodeError):
            continue
        if isinstance(parsed, dict):
            return parsed
    return {}


def looks_like_structured_payload(value: Any) -> bool:
    text = str(value or "").strip()
    if not text:
        return False
    if text.startswith("{") or text.startswith("```"):
        return bool(_try_parse_object(text)) or sum(marker in text for marker in STRUCTURED_MARKERS) >= 2
    return sum(marker in text for marker in STRUCTURED_MARKERS) >= 2


def human_fallback(payload: Dict[str, Any]) -> str:
    mode = str(payload.get("mode") or "general").strip().lower()
    if mode in {"presale", "recommendation", "beauty"}:
        context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
        candidates = context.get("styleCandidates") if isinstance(context.get("styleCandidates"), list) else []
        names = [str(item.get("name") or "候选款式") for item in candidates[:3] if isinstance(item, dict)]
        if names:
            return f"结合当前上架款式，可以先看{'、'.join(names)}。我会再结合你的手型、肤色和使用场景细化推荐。"
        return "我已经整理好你的美甲推荐条件。请继续告诉我肤色、手型、使用场景或偏好的风格，我会给出具体候选。"
    if mode in {"route", "fulfillment", "mobility"}:
        return "我正在为你整理门店、出行路线和到店时间。请提供出发地，或允许浏览器使用当前位置。"
    if mode in {"aftersale", "support", "complaint"}:
        return "我可以帮你记录售后问题。请补充具体情况、发生时间和对应款式。"
    if mode in {"appointment", "queue", "booking"}:
        return "我可以帮你查询或调整预约。请告诉我希望到店的日期和时间。"
    return "我已经收到你的问题，请告诉我想咨询的具体内容。"


def normalize_text(value: Any, payload: Dict[str, Any]) -> str:
    text = str(value or "").replace("\x00", "").strip()
    nested = _try_parse_object(text) if looks_like_structured_payload(text) else {}
    if nested:
        nested_answer = str(nested.get("answer") or nested.get("summary") or "").strip()
        if nested_answer and not looks_like_structured_payload(nested_answer):
            return nested_answer[:4000]
        return human_fallback(payload)
    if looks_like_structured_payload(text):
        return human_fallback(payload)
    return text[:4000]


def normalize_result(raw: Any, payload: Dict[str, Any]) -> Dict[str, Any]:
    result = _as_dict(raw)
    answer = result.get("answer") or result.get("summary") or ""
    nested = _try_parse_object(answer) if looks_like_structured_payload(answer) else {}
    if nested:
        for key in (
            "intent",
            "routeOrigin",
            "quickReplies",
            "toolCalls",
            "pendingAction",
            "recommendedStyles",
            "recommendationReasons",
            "missingPreferences",
        ):
            if key not in result and key in nested:
                result[key] = nested[key]
        answer = nested.get("answer") or nested.get("summary") or ""

    result["answer"] = normalize_text(answer, payload)
    intent = str(result.get("intent") or payload.get("mode") or "general").strip().lower()
    result["intent"] = intent if intent in ALLOWED_INTENTS else "general"

    quick_replies = result.get("quickReplies")
    result["quickReplies"] = [
        str(item).strip()[:40]
        for item in quick_replies
        if str(item).strip()
    ][:8] if isinstance(quick_replies, list) else []
    result["toolCalls"] = result.get("toolCalls") if isinstance(result.get("toolCalls"), list) else []
    pending = result.get("pendingAction")
    result["pendingAction"] = pending if isinstance(pending, dict) else None
    return result
