#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""LangGraph supervisor for the customer-to-route delegation path.

The graph deliberately keeps business side effects in Java.  The customer
agent can still request appointment/support tools; Java remains the only
component that executes those tools and writes the business database.  This
graph owns only the structured delegation from the customer agent to the
route subgraph.

When LangGraph is not installed, the script falls back to the existing
customer agent protocol so a deployment can install the new dependency in a
separate step without breaking the current API.
"""

from __future__ import annotations

import json
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
import route_agent


RouteMode = Literal["driving", "walking", "bicycling", "electrobike", "transit"]


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
    customer_result: Dict[str, Any]
    next_agent: str
    route_request: Dict[str, Any]
    route_result: Dict[str, Any]
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


def build_route_request(payload: Dict[str, Any], customer: Dict[str, Any]) -> Dict[str, Any]:
    """Build the handoff contract from trusted app data plus extracted origin."""

    origin = first_text(payload, "origin", "start")
    if not origin:
        origin = first_text(customer, "routeOrigin", "origin", "start")

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


def supervisor_node(state: GraphState) -> Dict[str, Any]:
    payload = dict(state.get("payload") or {})
    customer = customer_service_agent.run_agent(payload)
    if not isinstance(customer, dict):
        customer = {
            "answer": "你好，我是 NailGlow 智能客服。",
            "intent": "general",
            "toolCalls": [],
            "agentSource": "langgraph_customer_invalid_result",
        }

    # Business Tool Calls must return to Java.  They are not delegated to the
    # route subgraph and are never executed inside this Python graph.
    has_tool_calls = str(customer.get("status") or "") == "tool_calls" or bool(
        customer.get("toolCalls")
    )
    route_requested = not has_tool_calls and is_route_intent(payload, customer)
    next_agent = "route_agent" if route_requested else "finalize"
    route_request = build_route_request(payload, customer) if route_requested else {}
    return {
        "customer_result": customer,
        "next_agent": next_agent,
        "route_request": route_request,
    }


def route_agent_node(state: GraphState) -> Dict[str, Any]:
    payload = dict(state.get("payload") or {})
    request = dict(state.get("route_request") or {})
    origin = str(request.get("origin") or "").strip()

    if not origin:
        result = {
            "ok": False,
            "needsOrigin": True,
            "summary": "需要当前位置或出发地才能规划路线。请允许浏览器定位，或直接输入出发地。",
            "navigationUrl": "",
            "routeSteps": [],
            "agentSource": "langgraph_route_needs_origin",
        }
        return {"route_result": result}

    route_payload = dict(payload)
    route_payload.update(
        {
            "origin": origin,
            "destination": request.get("destination"),
            "mode": request.get("mode", "driving"),
            "city": request.get("city", ""),
            "styleName": request.get("style_name", ""),
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
    return {"route_result": result}


def finalize_node(state: GraphState) -> Dict[str, Any]:
    customer = dict(state.get("customer_result") or {})
    if state.get("next_agent") != "route_agent":
        customer.setdefault("orchestration", "langgraph_supervisor")
        return {"result": customer}

    route = dict(state.get("route_result") or {})
    result = dict(customer)
    result.pop("status", None)
    result["toolCalls"] = []
    result["intent"] = "route"
    result["delegatedRoute"] = route
    result["answer"] = str(route.get("summary") or result.get("answer") or "")
    result["agentSource"] = "langgraph_supervisor_route_subgraph"
    result["orchestration"] = "langgraph_supervisor"
    return {"result": result}


def choose_next(state: GraphState) -> str:
    return state.get("next_agent") or "finalize"


def build_graph():
    if not LANGGRAPH_AVAILABLE:
        return None
    builder = StateGraph(GraphState)
    builder.add_node("supervisor", supervisor_node)
    builder.add_node("route_agent", route_agent_node)
    builder.add_node("finalize", finalize_node)
    builder.add_edge(START, "supervisor")
    builder.add_conditional_edges(
        "supervisor",
        choose_next,
        {
            "route_agent": "route_agent",
            "finalize": "finalize",
        },
    )
    builder.add_edge("route_agent", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()


def run_compatibility_path(payload: Dict[str, Any], reason: str = "") -> Dict[str, Any]:
    result = customer_service_agent.run_agent(payload)
    if not isinstance(result, dict):
        result = {"answer": "客服暂时不可用，请稍后再试。", "intent": "general"}
    result.setdefault("toolCalls", [])
    result.setdefault("orchestration", "compatibility_customer_agent")
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
