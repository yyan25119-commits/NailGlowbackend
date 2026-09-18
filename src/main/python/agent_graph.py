#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import json
import re
import sys
from typing import Any, Dict, Literal, TypedDict

try:
    from pydantic import BaseModel, Field, ValidationError
except ModuleNotFoundError:  # pragma: no cover - compatibility path
    BaseModel = None  # type: ignore[assignment,misc]
    Field = None  # type: ignore[assignment,misc]
    ValidationError = Exception  # type: ignore[assignment,misc]

try:
    from langgraph.graph import END, START, StateGraph

    LANGGRAPH_AVAILABLE = True
except ModuleNotFoundError:  # pragma: no cover - compatibility path
    END = START = StateGraph = None  # type: ignore[assignment,misc]
    LANGGRAPH_AVAILABLE = False

import customer_service_agent
import agent_session_state
import rag_knowledge_base
import route_agent
import specialist_agents


RouteMode = Literal["driving", "walking", "bicycling", "electrobike", "transit"]
FINAL_RAG_SOURCE_LIMIT = 4


if BaseModel is not None:

    class RouteHandoff(BaseModel):
        """Validated, minimal contract passed to the route subgraph."""

        origin: str | None = Field(default=None, description="User-provided origin")
        destination: str = Field(description="Trusted store destination")
        mode: RouteMode = "driving"
        city: str = ""
        style_name: str | None = None

else:

    class RouteHandoff:  # type: ignore[no-redef]
        """Small compatibility stand-in when pydantic is unavailable."""

        def __init__(self, **values: Any) -> None:
            self.values = values

        def model_dump(self, **_: Any) -> Dict[str, Any]:
            return dict(self.values)


class GraphState(TypedDict, total=False):
    payload: Dict[str, Any]
    rag_context: Dict[str, Any]
    customer_result: Dict[str, Any]
    next_agent: str
    route_request: Dict[str, Any]
    route_result: Dict[str, Any]
    specialist_agent: str
    agent_session_state: Dict[str, Any]
    semantic_next_agent: str
    agent_events: list[Dict[str, Any]]
    result: Dict[str, Any]


def first_text(source: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = source.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def normalize_mode(value: Any) -> str:
    aliases = {
        "car": "driving",
        "drive": "driving",
        "driving": "driving",
        "walk": "walking",
        "walking": "walking",
        "bike": "bicycling",
        "bicycling": "bicycling",
        "ride": "bicycling",
        "ebike": "electrobike",
        "electric": "electrobike",
        "electrobike": "electrobike",
        "bus": "transit",
        "transit": "transit",
    }
    return aliases.get(str(value or "driving").strip().lower(), "driving")


def is_route_intent(payload: Dict[str, Any], customer: Dict[str, Any]) -> bool:
    intent = str(customer.get("intent") or "").strip().lower()
    category = str(customer.get("category") or "").strip().lower()
    requested_mode = str(payload.get("mode") or "").strip().lower()
    return (
        intent in {"route", "路线"}
        or category in {"route", "路线"}
        or requested_mode == "route"
    )


def extract_origin_from_message(message: str) -> str:
    text = str(message or "").strip()
    patterns = (
        r"从\s*(.+?)(?:\s*去|\s*到|\s*出发|\s*怎么走|\s*怎么去)",
        r"我在\s*([^?？,，。！!]{2,40})",
        r"当前位置(?:是|在)?\s*([^?？,，。！!]{2,40})",
    )
    for pattern in patterns:
        match = re.search(pattern, text)
        if match and str(match.group(1)).strip():
            return str(match.group(1)).strip()
    return ""


def build_route_request(payload: Dict[str, Any], customer: Dict[str, Any]) -> Dict[str, Any]:
    """Build the handoff contract from trusted app data plus extracted origin."""

    origin = first_text(payload, "origin", "start")
    if not origin:
        origin = first_text(customer, "routeOrigin", "origin", "start")
    if not origin:
        origin = extract_origin_from_message(first_text(payload, "message", "query"))

    destination = first_text(payload, "destination", "storeAddress", "end")
    destination = destination or "NailGlow 市中心旗舰店"
    style_name = first_text(payload, "styleName", "style", "preferredStyle") or None
    city = first_text(payload, "city", "storeCity")
    mode = normalize_mode(payload.get("routeMode") or payload.get("travelMode") or "driving")

    raw = {
        "origin": origin or None,
        "destination": destination,
        "mode": mode,
        "city": city,
        "style_name": style_name,
    }

    if BaseModel is None:
        return raw

    try:
        return RouteHandoff.model_validate(raw).model_dump(exclude_none=True)
    except ValidationError:
        # The route branch must never receive an unvalidated model object.
        # Keep the deterministic destination/mode and let the route node ask
        # for an origin if the user did not provide one.
        return {
            "origin": origin or None,
            "destination": "NailGlow 市中心旗舰店",
            "mode": "driving",
            "city": city,
            "style_name": style_name,
        }


def retrieve_knowledge_node(state: GraphState) -> Dict[str, Any]:
    """Retrieve compact, traceable evidence before the customer model runs."""

    payload = dict(state.get("payload") or {})
    retrieval = rag_knowledge_base.retrieve_for_chat(payload)
    payload["ragContext"] = retrieval
    return {"payload": payload, "rag_context": retrieval}


def agent_event(agent_key: str, status: str, detail: str, sequence: int) -> Dict[str, Any]:
    descriptor = specialist_agents.AGENT_REGISTRY.get(agent_key, {})
    return {
        "sequence": sequence,
        "agentKey": agent_key,
        "agentName": descriptor.get("name", agent_key),
        "status": status,
        "detail": detail,
        "online": descriptor.get("online", True),
    }


def supervisor_node(state: GraphState) -> Dict[str, Any]:
    payload = dict(state.get("payload") or {})
    previous_session = agent_session_state.restore(payload)
    specialist = specialist_agents.classify(payload)
    session = agent_session_state.activate(previous_session, specialist, payload)
    next_agent = specialist
    route_request = build_route_request(payload, {}) if specialist == "fulfillment_agent" else {}
    continuing_specialist = (
        specialist != "customer_agent"
        and previous_session.get("currentAgent") == specialist
    )
    if continuing_specialist:
        events = [
            agent_event(specialist, "ACTIVE", "继续当前专业会话并读取已有工作状态", 1),
        ]
    elif specialist == "customer_agent":
        events = [
            agent_event("customer_agent", "ACTIVE", "接收用户问题并直接处理", 1),
        ]
    else:
        events = [
            agent_event("customer_agent", "ACTIVE", "接收用户问题并整理会话上下文", 1),
            agent_event("customer_agent", "HANDOFF", f"将本轮会话交给{specialist_agents.AGENT_REGISTRY[specialist]['name']}", 2),
            agent_event(specialist, "ACTIVE", "已接管本轮会话并开始处理", 3),
        ]
    return {
        "specialist_agent": specialist,
        "next_agent": next_agent,
        "route_request": route_request,
        "agent_events": events,
        "agent_session_state": session,
    }


def specialist_node(state: GraphState, agent_key: str) -> Dict[str, Any]:
    payload = dict(state.get("payload") or {})
    context = dict(payload.get("context") or {})
    session = dict(state.get("agent_session_state") or agent_session_state.restore(payload))
    context["agentSessionState"] = session
    context["specialistState"] = agent_session_state.agent_context(session, agent_key)
    payload["context"] = context
    result = specialist_agents.run(agent_key, payload)
    session = agent_session_state.complete(
        session,
        agent_key,
        payload,
        result,
        dict(state.get("rag_context") or {}),
    )
    events = list(state.get("agent_events") or [])
    events.append(agent_event(agent_key, "COMPLETED", "已完成专业判断并直接回复用户", len(events) + 1))
    return {"customer_result": result, "agent_events": events, "agent_session_state": session}


def customer_agent_node(state: GraphState) -> Dict[str, Any]:
    payload = dict(state.get("payload") or {})
    context = dict(payload.get("context") or {})
    session = dict(state.get("agent_session_state") or agent_session_state.restore(payload))
    context["agentSessionState"] = session
    context["specialistState"] = agent_session_state.agent_context(session, "customer_agent")
    payload["context"] = context
    result = specialist_agents.run("customer_agent", payload)
    semantic_target = specialist_from_customer_result(result)
    events = list(state.get("agent_events") or [])
    if semantic_target != "customer_agent":
        session = agent_session_state.activate(session, semantic_target, payload, count_turn=False)
        events.append(agent_event("customer_agent", "HANDOFF", f"语义识别后交给{specialist_agents.AGENT_REGISTRY[semantic_target]['name']}", len(events) + 1))
        events.append(agent_event(semantic_target, "ACTIVE", "已接管本轮会话并读取独立状态", len(events) + 1))
        route_request = build_route_request(payload, result) if semantic_target == "fulfillment_agent" else {}
        return {
            "customer_result": result,
            "semantic_next_agent": semantic_target,
            "next_agent": semantic_target,
            "specialist_agent": semantic_target,
            "route_request": route_request,
            "agent_events": events,
            "agent_session_state": session,
        }
    session = agent_session_state.complete(
        session,
        "customer_agent",
        payload,
        result,
        dict(state.get("rag_context") or {}),
    )
    events.append(agent_event("customer_agent", "COMPLETED", "客服接待 Agent 已直接回复", len(events) + 1))
    return {"customer_result": result, "agent_events": events, "agent_session_state": session}


def specialist_from_customer_result(result: Dict[str, Any]) -> str:
    intent = str(result.get("intent") or "").strip().lower()
    if intent in {"route", "fulfillment", "mobility"}:
        return "fulfillment_agent"
    if intent in {"presale", "recommendation", "beauty"}:
        return "beauty_advisor_agent"
    if intent in {"appointment", "queue", "booking"}:
        return "appointment_agent"
    if intent in {"aftersale", "support", "complaint"}:
        return "aftersales_agent"
    return "customer_agent"


def beauty_advisor_node(state: GraphState) -> Dict[str, Any]:
    return specialist_node(state, "beauty_advisor_agent")


def appointment_agent_node(state: GraphState) -> Dict[str, Any]:
    return specialist_node(state, "appointment_agent")


def aftersales_agent_node(state: GraphState) -> Dict[str, Any]:
    return specialist_node(state, "aftersales_agent")


def route_agent_node(state: GraphState) -> Dict[str, Any]:
    payload = dict(state.get("payload") or {})
    request = dict(state.get("route_request") or {})
    origin = str(request.get("origin") or "").strip()
    session = dict(state.get("agent_session_state") or agent_session_state.restore(payload))

    if not origin:
        result = {
            "ok": False,
            "needsOrigin": True,
            "summary": "需要当前位置或出发地才能规划路线。请允许浏览器定位，或直接输入出发地。",
            "navigationUrl": "",
            "routeSteps": [],
            "agentSource": "langgraph_route_needs_origin",
        }
        events = list(state.get("agent_events") or [])
        events.append(agent_event("fulfillment_agent", "WAITING_INPUT", "等待用户授权定位或提供出发地", len(events) + 1))
        session = agent_session_state.complete(session, "fulfillment_agent", payload, result, status="WAITING_INPUT")
        return {"route_result": result, "agent_events": events, "agent_session_state": session}

    route_payload = dict(payload)
    # Route planning needs only the structured handoff contract; it should not
    # consume customer-service RAG passages or inflate its context window.
    route_payload.pop("ragContext", None)
    route_payload.update(
        {
            "origin": origin,
            "destination": request.get("destination"),
            "mode": request.get("mode", "driving"),
            "city": request.get("city", ""),
            "styleName": request.get("style_name", ""),
            "fulfillmentPlan": True,
        }
    )
    result = route_agent.run_agent(route_payload)
    if not isinstance(result, dict):
        result = {
            "ok": False,
            "summary": "路线规划暂时不可用。",
            "navigationUrl": "",
            "routeSteps": [],
            "agentSource": "langgraph_route_invalid_result",
        }
    events = list(state.get("agent_events") or [])
    events.append(agent_event("fulfillment_agent", "COMPLETED", "已完成门店、时间、路线和费用联合评估并直接回复", len(events) + 1))
    session = agent_session_state.complete(session, "fulfillment_agent", payload, result)
    return {"route_result": result, "agent_events": events, "agent_session_state": session}


def finalize_node(state: GraphState) -> Dict[str, Any]:
    customer = dict(state.get("customer_result") or {})
    retrieval = dict(state.get("rag_context") or {})
    specialist = state.get("specialist_agent") or state.get("next_agent") or "customer_agent"
    events = list(state.get("agent_events") or [])
    session = dict(state.get("agent_session_state") or agent_session_state.new_session())

    def attach_orchestration(result: Dict[str, Any]) -> Dict[str, Any]:
        result["orchestration"] = "langgraph_business_supervisor_v2"
        result["specialistAgent"] = specialist
        result["activeAgent"] = specialist
        result["responseAgent"] = specialist
        result["responseAgentName"] = specialist_agents.AGENT_REGISTRY.get(specialist, {}).get("name", specialist)
        result["agentEvents"] = events
        result["agentRegistry"] = specialist_agents.registry_view()
        result["agentSessionState"] = session
        result["specialistState"] = agent_session_state.agent_context(session, specialist)
        return result

    def attach_rag_metadata(result: Dict[str, Any]) -> Dict[str, Any]:
        hits = retrieval.get("hits") if isinstance(retrieval.get("hits"), list) else []
        if hits:
            result["ragSources"] = [
                {
                    "title": item.get("title") or item.get("source") or "知识库",
                    "sectionPath": item.get("sectionPath") or "",
                }
                for item in hits[:FINAL_RAG_SOURCE_LIMIT]
                if isinstance(item, dict)
            ]
            result["ragRetrievalMode"] = retrieval.get("retrievalMode", "")
        elif retrieval.get("reason"):
            result["ragReason"] = retrieval.get("reason")
        return result

    if state.get("next_agent") != "fulfillment_agent":
        return {"result": attach_orchestration(attach_rag_metadata(customer))}

    route = dict(state.get("route_result") or {})
    result = dict(customer)
    result.pop("status", None)
    result["toolCalls"] = list(route.get("toolCalls") or [])
    if result["toolCalls"]:
        result["status"] = "tool_calls"
    result["intent"] = "fulfillment"
    result["delegatedRoute"] = route
    result["answer"] = str(route.get("summary") or result.get("answer") or "")
    result["pendingAction"] = route.get("pendingAction")
    result["agentSource"] = "langgraph_supervisor_fulfillment_subgraph"
    # Fulfillment uses live store/route/appointment facts. Customer-service RAG
    # evidence is intentionally not attached to this specialist result.
    return {"result": attach_orchestration(result)}


def choose_next(state: GraphState) -> str:
    return state.get("next_agent") or "finalize"


def choose_after_customer(state: GraphState) -> str:
    return state.get("semantic_next_agent") or "finalize"


def build_graph():
    if not LANGGRAPH_AVAILABLE:
        return None
    builder = StateGraph(GraphState)
    builder.add_node("retrieve_knowledge", retrieve_knowledge_node)
    builder.add_node("supervisor", supervisor_node)
    builder.add_node("customer_agent", customer_agent_node)
    builder.add_node("beauty_advisor_agent", beauty_advisor_node)
    builder.add_node("appointment_agent", appointment_agent_node)
    builder.add_node("aftersales_agent", aftersales_agent_node)
    builder.add_node("fulfillment_agent", route_agent_node)
    builder.add_node("finalize", finalize_node)
    builder.add_edge(START, "retrieve_knowledge")
    builder.add_edge("retrieve_knowledge", "supervisor")
    builder.add_conditional_edges(
        "supervisor",
        choose_next,
        {
            "customer_agent": "customer_agent",
            "beauty_advisor_agent": "beauty_advisor_agent",
            "appointment_agent": "appointment_agent",
            "aftersales_agent": "aftersales_agent",
            "fulfillment_agent": "fulfillment_agent",
        },
    )
    builder.add_conditional_edges(
        "customer_agent",
        choose_after_customer,
        {
            "beauty_advisor_agent": "beauty_advisor_agent",
            "appointment_agent": "appointment_agent",
            "aftersales_agent": "aftersales_agent",
            "fulfillment_agent": "fulfillment_agent",
            "finalize": "finalize",
        },
    )
    builder.add_edge("beauty_advisor_agent", "finalize")
    builder.add_edge("appointment_agent", "finalize")
    builder.add_edge("aftersales_agent", "finalize")
    builder.add_edge("fulfillment_agent", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()


def run_compatibility_path(payload: Dict[str, Any], reason: str = "") -> Dict[str, Any]:
    specialist = specialist_agents.classify(payload)
    session = agent_session_state.activate(agent_session_state.restore(payload), specialist, payload)
    semantic_handoff = False
    customer_result: Dict[str, Any] = {}
    if specialist == "customer_agent":
        customer_result = specialist_agents.run("customer_agent", payload)
        semantic_target = specialist_from_customer_result(customer_result)
        if semantic_target != "customer_agent":
            specialist = semantic_target
            semantic_handoff = True
            session = agent_session_state.activate(session, specialist, payload, count_turn=False)
        else:
            result = customer_result
    if specialist == "fulfillment_agent":
        route_payload = dict(payload)
        route_payload.update(build_route_request(payload, customer_result))
        route_payload["fulfillmentPlan"] = True
        result = route_agent.run_agent(route_payload)
        result["delegatedRoute"] = dict(result)
        result.setdefault("intent", "fulfillment")
    elif specialist != "customer_agent":
        result = specialist_agents.run(specialist, payload)
    if not isinstance(result, dict):
        result = {"answer": "客服暂时不可用，请稍后再试。", "intent": "general"}
    result.setdefault("toolCalls", [])
    result.setdefault("answer", str(result.get("summary") or ""))
    result.setdefault("specialistAgent", specialist)
    result.setdefault("activeAgent", specialist)
    result.setdefault("responseAgent", specialist)
    result.setdefault("responseAgentName", specialist_agents.AGENT_REGISTRY.get(specialist, {}).get("name", specialist))
    session = agent_session_state.complete(session, specialist, payload, result)
    result.setdefault("agentSessionState", session)
    result.setdefault("specialistState", agent_session_state.agent_context(session, specialist))
    result.setdefault("agentRegistry", specialist_agents.registry_view())
    compatibility_events = [agent_event("customer_agent", "ACTIVE", "接收用户问题", 1)]
    if specialist != "customer_agent":
        compatibility_events.extend([
            agent_event("customer_agent", "HANDOFF", f"{'语义识别后' if semantic_handoff else ''}将本轮会话交给{specialist_agents.AGENT_REGISTRY[specialist]['name']}", 2),
            agent_event(specialist, "ACTIVE", "已接管本轮会话并开始处理", 3),
        ])
    compatibility_events.append(agent_event(specialist, "COMPLETED", "已直接回复用户", len(compatibility_events) + 1))
    result.setdefault("agentEvents", compatibility_events)
    result.setdefault("orchestration", "compatibility_business_supervisor_v2")
    if reason:
        result.setdefault("agentReason", reason)
    return result


def run_agent(payload: Dict[str, Any]) -> Dict[str, Any]:
    if not LANGGRAPH_AVAILABLE:
        return run_compatibility_path(payload, "langgraph_dependency_missing")
    try:
        graph = build_graph()
        output = graph.invoke({"payload": payload})
        result = output.get("result") if isinstance(output, dict) else None
        if isinstance(result, dict):
            return result
        return run_compatibility_path(payload, "langgraph_empty_result")
    except Exception as exc:
        return run_compatibility_path(payload, f"langgraph_execution_failed: {exc}")


def main() -> None:
    try:
        payload = json.loads(sys.stdin.buffer.read().decode("utf-8-sig") or "{}")
        result = run_agent(payload if isinstance(payload, dict) else {})
        sys.stdout.buffer.write(json.dumps(result, ensure_ascii=False).encode("utf-8"))
    except Exception as exc:
        sys.stdout.buffer.write(
            json.dumps(
                {
                    "answer": "客服暂时不可用，请稍后再试。",
                    "intent": "general",
                    "toolCalls": [],
                    "agentSource": "langgraph_process_exception",
                    "agentReason": str(exc),
                },
                ensure_ascii=False,
            ).encode("utf-8")
        )


if __name__ == "__main__":
    main()
