#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""Persistent, namespaced working state for NailGlow specialist agents."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime
from typing import Any, Dict


ONLINE_AGENT_KEYS = (
    "customer_agent",
    "beauty_advisor_agent",
    "appointment_agent",
    "aftersales_agent",
    "fulfillment_agent",
)
MAX_RECENT_TURNS = 6


def _text(value: Any, limit: int = 240) -> str:
    return str(value or "").strip()[:limit]


def _default_specialist() -> Dict[str, Any]:
    return {
        "status": "IDLE",
        "turnCount": 0,
        "lastUserMessage": "",
        "lastAnswerSummary": "",
        "lastIntent": "",
        "workingMemory": {},
        "ragEvidence": [],
        "recentTurns": [],
        "updatedAt": "",
    }


def new_session() -> Dict[str, Any]:
    return {
        "schemaVersion": "nailglow-agent-session-v1",
        "turn": 0,
        "currentAgent": "customer_agent",
        "previousAgent": None,
        "handoffCount": 0,
        "specialists": {key: _default_specialist() for key in ONLINE_AGENT_KEYS},
    }


def restore(payload: Dict[str, Any]) -> Dict[str, Any]:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    raw = context.get("agentSessionState") if isinstance(context.get("agentSessionState"), dict) else {}
    session = new_session()
    if raw:
        session.update({
            "schemaVersion": _text(raw.get("schemaVersion"), 80) or session["schemaVersion"],
            "turn": max(0, int(raw.get("turn") or 0)),
            "currentAgent": _text(raw.get("currentAgent"), 80) or "customer_agent",
            "previousAgent": _text(raw.get("previousAgent"), 80) or None,
            "handoffCount": max(0, int(raw.get("handoffCount") or 0)),
        })
        raw_specialists = raw.get("specialists") if isinstance(raw.get("specialists"), dict) else {}
        for key in ONLINE_AGENT_KEYS:
            incoming = raw_specialists.get(key) if isinstance(raw_specialists.get(key), dict) else {}
            specialist = _default_specialist()
            specialist.update({
                "status": _text(incoming.get("status"), 32) or "IDLE",
                "turnCount": max(0, int(incoming.get("turnCount") or 0)),
                "lastUserMessage": _text(incoming.get("lastUserMessage")),
                "lastAnswerSummary": _text(incoming.get("lastAnswerSummary")),
                "lastIntent": _text(incoming.get("lastIntent"), 80),
                "workingMemory": deepcopy(incoming.get("workingMemory")) if isinstance(incoming.get("workingMemory"), dict) else {},
                "ragEvidence": list(incoming.get("ragEvidence") or [])[-4:] if isinstance(incoming.get("ragEvidence"), list) else [],
                "recentTurns": list(incoming.get("recentTurns") or [])[-MAX_RECENT_TURNS:] if isinstance(incoming.get("recentTurns"), list) else [],
                "updatedAt": _text(incoming.get("updatedAt"), 40),
            })
            session["specialists"][key] = specialist
    return session


def activate(
    session: Dict[str, Any],
    agent_key: str,
    payload: Dict[str, Any],
    *,
    count_turn: bool = True,
) -> Dict[str, Any]:
    updated = deepcopy(session)
    if agent_key not in updated["specialists"]:
        agent_key = "customer_agent"
    previous = str(updated.get("currentAgent") or "customer_agent")
    if previous != agent_key:
        if previous in updated["specialists"]:
            updated["specialists"][previous]["status"] = "PAUSED"
        updated["previousAgent"] = previous
        updated["handoffCount"] = int(updated.get("handoffCount") or 0) + 1
    updated["currentAgent"] = agent_key
    if count_turn:
        updated["turn"] = int(updated.get("turn") or 0) + 1
    specialist = updated["specialists"][agent_key]
    specialist["status"] = "ACTIVE"
    specialist["turnCount"] = int(specialist.get("turnCount") or 0) + 1
    specialist["lastUserMessage"] = _text(payload.get("message") or payload.get("query"))
    specialist["updatedAt"] = datetime.now().isoformat(timespec="seconds")
    _merge_input_memory(specialist["workingMemory"], agent_key, payload)
    return updated


def complete(
    session: Dict[str, Any],
    agent_key: str,
    payload: Dict[str, Any],
    result: Dict[str, Any],
    rag_context: Dict[str, Any] | None = None,
    status: str = "COMPLETED",
) -> Dict[str, Any]:
    updated = deepcopy(session)
    if agent_key not in updated["specialists"]:
        return updated
    specialist = updated["specialists"][agent_key]
    specialist["status"] = status
    specialist["lastAnswerSummary"] = _text(result.get("answer") or result.get("summary"))
    specialist["lastIntent"] = _text(result.get("intent"), 80)
    specialist["updatedAt"] = datetime.now().isoformat(timespec="seconds")
    turn = {
        "user": _text(payload.get("message") or payload.get("query"), 180),
        "assistant": specialist["lastAnswerSummary"],
        "intent": specialist["lastIntent"],
    }
    specialist["recentTurns"] = [*list(specialist.get("recentTurns") or []), turn][-MAX_RECENT_TURNS:]
    hits = rag_context.get("hits") if isinstance(rag_context, dict) and isinstance(rag_context.get("hits"), list) else []
    specialist["ragEvidence"] = [
        {
            "title": _text(item.get("title") or item.get("source"), 120),
            "sectionPath": _text(item.get("sectionPath"), 180),
            "score": item.get("score"),
        }
        for item in hits[:4]
        if isinstance(item, dict)
    ]
    _merge_result_memory(specialist["workingMemory"], agent_key, result)
    updated["currentAgent"] = agent_key
    return updated


def agent_context(session: Dict[str, Any], agent_key: str) -> Dict[str, Any]:
    specialists = session.get("specialists") if isinstance(session.get("specialists"), dict) else {}
    specialist = specialists.get(agent_key) if isinstance(specialists.get(agent_key), dict) else _default_specialist()
    return {
        "currentAgent": session.get("currentAgent") or "customer_agent",
        "previousAgent": session.get("previousAgent"),
        "turn": session.get("turn", 0),
        "handoffCount": session.get("handoffCount", 0),
        "specialistState": deepcopy(specialist),
    }


def _merge_input_memory(memory: Dict[str, Any], agent_key: str, payload: Dict[str, Any]) -> None:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    if agent_key == "beauty_advisor_agent":
        for key in ("handProfile", "scoreMetrics", "scoreReasons", "styleCandidates", "styleName", "tryOnScore", "confidence"):
            value = context.get(key, payload.get(key))
            if value not in (None, "", [], {}):
                memory[key] = deepcopy(value)
        facts = memory.setdefault("userFacts", {})
        message = _text(payload.get("message") or payload.get("query"), 240)
        if any(token in message for token in ("手指偏短", "短指", "甲床短")):
            facts["handLength"] = "手指或甲床偏短"
        elif any(token in message for token in ("手指偏长", "长指", "甲床长")):
            facts["handLength"] = "手指或甲床偏长"
        if any(token in message for token in ("肉肉", "比较肉", "肉乎乎", "圆润", "软润")):
            facts["handTexture"] = "肉感或软润"
        elif any(token in message for token in ("骨感", "偏瘦", "纤细")):
            facts["handTexture"] = "骨感或纤细"
        if any(token in message for token in ("偏白", "冷白", "白皮", "白的")):
            facts["skinTone"] = "偏白"
        elif any(token in message for token in ("暖黄", "偏黄", "黄皮")):
            facts["skinTone"] = "偏暖或偏黄"
        if any(token in message for token in ("上班", "通勤", "办公", "学生", "面试")):
            facts["scene"] = "上班通勤或日常办公"
        elif any(token in message for token in ("约会", "宴会", "拍照", "婚礼", "节日")):
            facts["scene"] = "约会、活动或拍照"
        if any(token in message for token in ("短甲", "中短")):
            facts["preferredLength"] = "短甲或中短甲"
        elif "中长" in message or "长甲" in message:
            facts["preferredLength"] = "中长甲"
    elif agent_key == "appointment_agent":
        for key in ("appointment", "recommendedSlot", "serviceName", "serviceDurationMinutes", "queueAhead"):
            value = context.get(key)
            if value not in (None, "", [], {}):
                memory[key] = deepcopy(value)
    elif agent_key == "aftersales_agent":
        if context.get("appointment"):
            memory["appointment"] = deepcopy(context["appointment"])
    elif agent_key == "fulfillment_agent":
        for key in ("pendingAction", "storeCandidates", "serviceDurationMinutes", "styleName", "amount"):
            value = context.get(key)
            if value not in (None, "", [], {}):
                memory[key] = deepcopy(value)


def _merge_result_memory(memory: Dict[str, Any], agent_key: str, result: Dict[str, Any]) -> None:
    if agent_key == "beauty_advisor_agent":
        for key in ("recommendedStyles", "recommendationReasons", "missingPreferences"):
            if result.get(key) not in (None, "", [], {}):
                memory[key] = deepcopy(result[key])
    elif agent_key == "appointment_agent" and isinstance(result.get("appointment"), dict):
        memory["appointment"] = deepcopy(result["appointment"])
    elif agent_key == "aftersales_agent":
        for key in ("category", "severity", "summary", "importantItems"):
            if result.get(key) not in (None, "", [], {}):
                memory[key] = deepcopy(result[key])
    elif agent_key == "fulfillment_agent":
        for key in ("recommendedStoreName", "recommendedSlot", "travelPlan", "weatherDetail", "pendingAction"):
            if result.get(key) not in (None, "", [], {}):
                memory[key] = deepcopy(result[key])
