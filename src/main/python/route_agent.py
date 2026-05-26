#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

try:
    from openai import OpenAI as NativeOpenAI
except ModuleNotFoundError:
    NativeOpenAI = None


ARK_BASE_URL = (os.getenv("ARK_BASE_URL") or os.getenv("AI_BASE_URL") or "https://ark.cn-beijing.volces.com/api/v3").rstrip("/")
ARK_API_KEY = (
    os.getenv("ARK_API_KEY")
    or os.getenv("DEEPSEEK_API_KEY")
    or os.getenv("AI_API_KEY")
    or os.getenv("OPENAI_API_KEY")
    or ""
)
ARK_MODEL = os.getenv("ROUTE_AGENT_MODEL") or os.getenv("ARK_MODEL") or os.getenv("AI_MODEL") or "doubao-seed-2-0-pro-260215"
AMAP_KEY = os.getenv("AMAP_WEB_SERVICE_KEY") or os.getenv("AMAP_KEY") or ""
TIMEOUT_SECONDS = float(os.getenv("ROUTE_AGENT_TIMEOUT_SECONDS", "20"))
MAX_TOOL_STEPS = int(os.getenv("ROUTE_AGENT_MAX_TOOL_STEPS", "4"))
COORD_RE = re.compile(r"^\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*$")
STYLE_HINTS = ("显白通勤款", "法式", "猫眼", "冰透", "短甲", "高级感")
MOCK_STORES = [
    {
        "name": "NailGlow 红谷滩万象城店",
        "address": "南昌市红谷滩区万象城 L3",
        "district": "红谷滩",
        "location": "115.858734,28.682892",
        "supportedStyles": ["显白通勤款", "法式", "冰透", "短甲"],
        "nextSlot": "今天 16:30",
    },
    {
        "name": "NailGlow 八一广场旗舰店",
        "address": "南昌市东湖区八一广场商圈",
        "district": "东湖",
        "location": "115.903632,28.676735",
        "supportedStyles": ["猫眼", "高级感", "法式"],
        "nextSlot": "今天 17:00",
    },
    {
        "name": "NailGlow 朝阳新城店",
        "address": "南昌市西湖区朝阳新城天虹",
        "district": "西湖",
        "location": "115.857236,28.640152",
        "supportedStyles": ["显白通勤款", "猫眼", "短甲"],
        "nextSlot": "今天 18:00",
    },
]


def write_json(payload: Dict[str, Any]) -> None:
    sys.stdout.buffer.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))


def http_json(method: str, url: str, params: Optional[Dict[str, Any]] = None, body: Optional[Dict[str, Any]] = None,
              headers: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
    if params:
        clean_params = {key: value for key, value in params.items() if value not in (None, "", [])}
        url = f"{url}?{urllib.parse.urlencode(clean_params)}"
    data = None
    request_headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=request_headers, method=method.upper())
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


class _CompatChatCompletions:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def create(self, **kwargs: Any) -> Dict[str, Any]:
        body = {key: value for key, value in kwargs.items() if value is not None}
        return http_json(
            "POST",
            f"{self.base_url}/chat/completions",
            body=body,
            headers={"Authorization": f"Bearer {ARK_API_KEY}"},
        )


class _CompatChat:
    def __init__(self, base_url: str) -> None:
        self.completions = _CompatChatCompletions(base_url)


class CompatOpenAI:
    def __init__(self, api_key: str, base_url: str) -> None:
        self.api_key = api_key
        self.chat = _CompatChat(base_url)


OpenAI = NativeOpenAI or CompatOpenAI


def build_client():
    return OpenAI(
        api_key=os.getenv("DEEPSEEK_API_KEY") or ARK_API_KEY,
        base_url=ARK_BASE_URL,
    )


def normalize_response(response: Any) -> Dict[str, Any]:
    if isinstance(response, dict):
        return response
    if hasattr(response, "model_dump"):
        return response.model_dump()
    if hasattr(response, "to_dict"):
        return response.to_dict()
    return json.loads(json.dumps(response, ensure_ascii=False, default=lambda item: item.__dict__))


def extract_json(text: str) -> Dict[str, Any]:
    try:
        return json.loads(text or "")
    except json.JSONDecodeError:
        match = re.search(r"\{[\s\S]*\}", text or "")
        if match:
            return json.loads(match.group(0))
    return {"summary": text or ""}


def normalize_location(location: str, city: str = "") -> Dict[str, Any]:
    location = str(location or "").strip()
    if not location:
        raise ValueError("location is required")
    if COORD_RE.match(location):
        compact = location.replace(" ", "")
        return {"input": location, "location": compact, "formattedAddress": location, "source": "coordinate"}
    if not AMAP_KEY:
        return {
            "input": location,
            "location": "",
            "formattedAddress": location,
            "source": "missing_amap_key",
            "warning": "缺少 AMAP_KEY 或 AMAP_WEB_SERVICE_KEY，无法执行地址转经纬度。",
        }
    result = http_json(
        "GET",
        "https://restapi.amap.com/v3/geocode/geo",
        params={"key": AMAP_KEY, "address": location, "city": city, "output": "JSON"},
    )
    if str(result.get("status")) != "1" or not result.get("geocodes"):
        raise RuntimeError(f"高德地理编码失败：{result.get('info') or result}")
    geo = result["geocodes"][0]
    return {
        "input": location,
        "location": geo.get("location", ""),
        "formattedAddress": geo.get("formatted_address") or location,
        "city": geo.get("city") or city,
        "adcode": geo.get("adcode", ""),
        "source": "amap_geocode",
    }


def mode_to_endpoint(mode: str) -> Tuple[str, str]:
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
    normalized = aliases.get((mode or "driving").lower(), "driving")
    endpoints = {
        "driving": "/v5/direction/driving",
        "walking": "/v5/direction/walking",
        "bicycling": "/v5/direction/bicycling",
        "electrobike": "/v5/direction/electrobike",
        "transit": "/v5/direction/transit/integrated",
    }
    return normalized, endpoints[normalized]


def first(value: Any) -> Dict[str, Any]:
    return value[0] if isinstance(value, list) and value else {}


def seconds_to_minutes(value: Any) -> Optional[int]:
    try:
        return max(1, round(float(value) / 60))
    except (TypeError, ValueError):
        return None


def meters_to_km(value: Any) -> Optional[float]:
    try:
        return round(float(value) / 1000, 1)
    except (TypeError, ValueError):
        return None


def extract_instructions(path: Dict[str, Any], mode: str) -> List[str]:
    instructions: List[str] = []
    if mode == "transit":
        for segment in path.get("segments", []) if isinstance(path.get("segments"), list) else []:
            walking = segment.get("walking") or {}
            for step in walking.get("steps", []) if isinstance(walking.get("steps"), list) else []:
                if step.get("instruction"):
                    instructions.append(step["instruction"])
            buslines = (segment.get("bus") or {}).get("buslines", [])
            for line in buslines if isinstance(buslines, list) else []:
                name = line.get("name")
                departure = (line.get("departure_stop") or {}).get("name")
                arrival = (line.get("arrival_stop") or {}).get("name")
                if name:
                    instructions.append(f"乘坐{name}，{departure or '上车'} 到 {arrival or '下车'}")
        return instructions[:8]
    for step in path.get("steps", []) if isinstance(path.get("steps"), list) else []:
        text = step.get("instruction") or step.get("road_name")
        if text:
            instructions.append(text)
    return instructions[:8]


def build_amap_link(origin: str, destination: str, mode: str) -> str:
    link_mode = {"driving": "car", "walking": "walk", "transit": "bus", "bicycling": "ride", "electrobike": "ride"}.get(mode, "car")
    return "https://uri.amap.com/navigation?" + urllib.parse.urlencode({
        "from": origin,
        "to": destination,
        "mode": link_mode,
        "policy": "1",
        "src": "nailglow",
        "coordinate": "gaode",
        "callnative": "0",
    })


def parse_style_preference(payload: Dict[str, Any]) -> str:
    text = " ".join(
        str(payload.get(key) or "")
        for key in ("styleName", "style", "message", "query", "preferredStyle")
    )
    for hint in STYLE_HINTS:
        if hint in text:
            return hint
    return "显白通勤款"


def parse_origin_coordinate(origin_text: str) -> tuple[float, float] | None:
    text = str(origin_text or "").strip()
    if not COORD_RE.match(text):
        return None
    try:
        lng_text, lat_text = [item.strip() for item in text.split(",", 1)]
        return float(lng_text), float(lat_text)
    except ValueError:
        return None


def haversine_km(lng1: float, lat1: float, lng2: float, lat2: float) -> float:
    radius = 6371.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lng2 - lng1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return radius * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def mock_store_candidates(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    origin_text = str(payload.get("origin") or payload.get("start") or "").strip()
    style_name = parse_style_preference(payload)
    origin_coord = parse_origin_coordinate(origin_text)
    candidates: List[Dict[str, Any]] = []
    for index, store in enumerate(MOCK_STORES):
        store_coord = parse_origin_coordinate(store["location"]) or (115.858734 + index * 0.01, 28.682892 - index * 0.01)
        if origin_coord:
            distance_km = max(1.1, round(haversine_km(origin_coord[0], origin_coord[1], store_coord[0], store_coord[1]) * 1.28, 1))
        elif store["district"] in origin_text or any(token in origin_text for token in ("红谷滩", "万象城", "八一广场", "朝阳")):
            distance_km = 4.2 if store["district"] in origin_text else 6.8 + index * 0.7
        else:
            distance_km = 5.8 + index * 1.3
        driving_minutes = max(12, round(distance_km * 1.8 + 2))
        transit_minutes = driving_minutes + 16
        supports_style = style_name in store["supportedStyles"]
        score = 100 - driving_minutes - index * 2 + (12 if supports_style else 0)
        candidates.append({
            **store,
            "distanceKm": round(distance_km, 1),
            "drivingMinutes": driving_minutes,
            "transitMinutes": transit_minutes,
            "supportsStyle": supports_style,
            "styleName": style_name,
            "score": score,
        })
    return sorted(candidates, key=lambda item: item["score"], reverse=True)


def build_mock_summary(store: Dict[str, Any]) -> str:
    return (
        f"推荐你去：{store['name']}\n\n"
        f"理由：\n"
        f"你当前位置到该店驾车约 {store['drivingMinutes']} 分钟，步行+地铁约 {store['transitMinutes']} 分钟。\n"
        f"该店{store['nextSlot']} 还有预约空位，并且支持你想做的“{store['styleName']}”。\n\n"
        f"你可以选择：\n"
        f"[一键导航] [立即预约]"
    )


def mock_route_plan(payload: Dict[str, Any]) -> Dict[str, Any]:
    candidates = mock_store_candidates(payload)
    best = candidates[0]
    origin_text = str(payload.get("origin") or payload.get("start") or "当前位置")
    navigation_url = build_amap_link(origin_text, best["location"], "driving")
    return {
        "ok": True,
        "provider": "mock_route_planner",
        "recommendedStoreName": best["name"],
        "storeName": best["name"],
        "storeAddress": best["address"],
        "distanceKm": best["distanceKm"],
        "durationMinutes": best["drivingMinutes"],
        "transitDurationMinutes": best["transitMinutes"],
        "bestMode": "driving",
        "travelMode": "驾车",
        "recommendedSlot": best["nextSlot"],
        "supportsStyle": best["supportsStyle"],
        "styleName": best["styleName"],
        "reason": f"{best['name']} 距离更近、空位更早，且支持 {best['styleName']}。",
        "routeSteps": [
            f"优先前往 {best['district']} 商圈的 {best['name']}",
            f"驾车预计 {best['drivingMinutes']} 分钟，步行+地铁约 {best['transitMinutes']} 分钟",
            f"建议预留 10 分钟找店和确认款式，当前最近可约时段为 {best['nextSlot']}",
        ],
        "arrivalAdvice": [
            f"建议按 {best['nextSlot']} 前 15 分钟出发，避免商圈停车或等电梯耗时。",
            f"到店后直接给美甲师展示“{best['styleName']}”试穿图，沟通会更快。",
        ],
        "navigationUrl": navigation_url,
        "bookingAction": "book_now",
        "storeCandidates": candidates[:3],
        "summary": build_mock_summary(best),
    }


def plan_route_tool(args: Dict[str, Any]) -> Dict[str, Any]:
    mode, endpoint = mode_to_endpoint(str(args.get("mode") or "driving"))
    city = str(args.get("city") or "")
    origin = normalize_location(args.get("origin") or args.get("start"), city)
    destination = normalize_location(args.get("destination") or args.get("end"), city)

    if not AMAP_KEY or not origin.get("location") or not destination.get("location"):
        fallback_from = origin.get("location") or origin.get("input", "")
        fallback_to = destination.get("location") or destination.get("input", "")
        fallback_url = build_amap_link(fallback_from, fallback_to, mode) if fallback_from and fallback_to else ""
        message = "请配置 AMAP_KEY 或 AMAP_WEB_SERVICE_KEY 后启用真实路线规划。"
        if fallback_url:
            message = "缺少 AMAP_KEY 或 AMAP_WEB_SERVICE_KEY，无法返回真实距离和时长；已提供高德导航跳转。"
        return {
            "ok": False,
            "provider": "amap",
            "reason": "missing_amap_key_or_coordinates",
            "message": message,
            "mode": mode,
            "origin": origin,
            "destination": destination,
            "navigationUrl": fallback_url,
        }

    params: Dict[str, Any] = {
        "key": AMAP_KEY,
        "origin": origin["location"],
        "destination": destination["location"],
        "show_fields": "cost,navi",
        "output": "JSON",
    }
    if mode == "transit":
        params.update({
            "city1": args.get("originCity") or city,
            "city2": args.get("destinationCity") or city,
            "date": args.get("date") or "",
            "time": args.get("time") or "",
        })
    elif mode == "driving":
        params["strategy"] = args.get("strategy") or "0"

    result = http_json("GET", f"https://restapi.amap.com{endpoint}", params=params)
    if str(result.get("status")) != "1" and str(result.get("infocode")) != "10000":
        raise RuntimeError(f"高德路径规划失败：{result.get('info') or result}")

    route = result.get("route") or {}
    if mode == "transit":
        path = first(route.get("transits"))
        cost = path.get("cost") or {}
    else:
        path = first(route.get("paths"))
        cost = path.get("cost") or {}
    duration = path.get("duration") or cost.get("duration")
    distance = path.get("distance") or route.get("distance")
    return {
        "ok": True,
        "provider": "amap",
        "mode": mode,
        "origin": origin,
        "destination": destination,
        "distanceMeters": distance,
        "distanceKm": meters_to_km(distance),
        "durationSeconds": duration,
        "durationMinutes": seconds_to_minutes(duration),
        "taxiFee": cost.get("taxi_fee") or path.get("taxi_cost"),
        "instructions": extract_instructions(path, mode),
        "navigationUrl": build_amap_link(origin["location"], destination["location"], mode),
        "rawInfo": {"info": result.get("info"), "infocode": result.get("infocode")},
    }


TOOLS = [{
    "type": "function",
    "function": {
        "name": "plan_route",
        "description": "使用高德地图 Web 服务规划到店路线，支持驾车、步行、骑行、电动车和公交。",
        "parameters": {
            "type": "object",
            "properties": {
                "origin": {"type": "string", "description": "出发地地址或经纬度，格式 lng,lat"},
                "destination": {"type": "string", "description": "目的地地址或经纬度，格式 lng,lat"},
                "city": {"type": "string", "description": "城市名，例如 北京、上海、杭州"},
                "mode": {"type": "string", "enum": ["driving", "walking", "bicycling", "electrobike", "transit"]},
                "date": {"type": "string"},
                "time": {"type": "string"},
            },
            "required": ["origin", "destination"],
        },
    },
}]


def deterministic_answer(payload: Dict[str, Any], route: Dict[str, Any]) -> Dict[str, Any]:
    if not route.get("ok"):
        navigation_url = route.get("navigationUrl", "")
        arrival_advice = [
            "请先配置 AMAP_KEY 或 AMAP_WEB_SERVICE_KEY。",
            "传入经纬度时格式为 lng,lat；传入地址时需要高德地理编码能力。",
        ]
        if navigation_url:
            arrival_advice = [
                "已提供高德导航跳转；真实距离和时长需要配置 AMAP_KEY 或 AMAP_WEB_SERVICE_KEY。",
                "导航页打开后请以高德地图实际结果为准。",
            ]
        return {
            "ok": False,
            "summary": route.get("message", "路线规划暂不可用。"),
            "bestMode": route.get("mode", payload.get("mode", "driving")),
            "distanceKm": None,
            "durationMinutes": None,
            "routeSteps": [],
            "arrivalAdvice": arrival_advice,
            "navigationUrl": navigation_url,
            "toolResult": route,
        }
    mode_name = {"driving": "驾车", "walking": "步行", "bicycling": "骑行", "electrobike": "电动车", "transit": "公交"}.get(route.get("mode"), "出行")
    return {
        "ok": True,
        "summary": f"推荐{mode_name}到店，约 {route.get('distanceKm')} 公里，预计 {route.get('durationMinutes')} 分钟。",
        "bestMode": route.get("mode"),
        "distanceKm": route.get("distanceKm"),
        "durationMinutes": route.get("durationMinutes"),
        "routeSteps": route.get("instructions", []),
        "arrivalAdvice": [
            "建议预约时间前 10 到 15 分钟出发，预留找店和沟通款式的时间。",
            "到店时可直接展示 AI 试穿结果，确认甲型、颜色和饰品密度。",
        ],
        "navigationUrl": route.get("navigationUrl", ""),
        "toolResult": route,
    }


def build_initial_state(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "currentStep": 0,
        "directArgs": {
            "origin": payload.get("origin") or payload.get("start"),
            "destination": payload.get("destination") or payload.get("storeAddress") or payload.get("end"),
            "city": payload.get("city") or payload.get("storeCity") or "",
            "mode": payload.get("mode") or "driving",
            "date": payload.get("date") or "",
            "time": payload.get("time") or "",
        },
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是 NailGlow 的到店路线规划 Agent。必须优先调用 plan_route 工具获取真实路线数据。"
                    "最后输出严格 JSON，字段包括 summary,bestMode,distanceKm,durationMinutes,routeSteps,arrivalAdvice,navigationUrl。"
                    "不要输出 Markdown。"
                ),
            },
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ],
    }


def run_llm_once(state: Dict[str, Any], tools: List[Dict[str, Any]]) -> Dict[str, Any]:
    client = build_client()
    request_kwargs: Dict[str, Any] = {
        "model": ARK_MODEL,
        "messages": state["messages"],
        "temperature": 0.2,
        "tools": tools or None,
        "tool_choice": "auto" if tools else None,
        "stream": False,
        "thinking": {"type": "disabled"},
    }
    try:
        response = client.chat.completions.create(**request_kwargs)
    except TypeError as exc:
        if "unexpected keyword argument 'thinking'" not in str(exc):
            raise
        request_kwargs.pop("thinking", None)
        response = client.chat.completions.create(**request_kwargs)
    return normalize_response(response)


def collect_tool_calls(response: Dict[str, Any]) -> List[Dict[str, Any]]:
    message = response.get("choices", [{}])[0].get("message", {}) or {}
    tool_calls = message.get("tool_calls") or []
    result: List[Dict[str, Any]] = []
    for item in tool_calls:
        function = item.get("function") or {}
        arguments = function.get("arguments") or "{}"
        try:
            parsed = json.loads(arguments)
        except json.JSONDecodeError:
            parsed = {}
        result.append({
            "id": item.get("id"),
            "name": function.get("name"),
            "arguments": parsed,
        })
    return result


def build_final_result(response: Dict[str, Any], tool_result: Dict[str, Any]) -> Dict[str, Any]:
    content = response.get("choices", [{}])[0].get("message", {}).get("content", "")
    answer = extract_json(content)
    answer.setdefault("toolResult", tool_result)
    answer.setdefault("navigationUrl", tool_result.get("navigationUrl", ""))
    answer["ok"] = tool_result.get("ok", True)
    answer["agentSource"] = "python_ark_tool_call"
    return answer


def run_tool_loop(payload: Dict[str, Any]) -> Dict[str, Any]:
    state = build_initial_state(payload)
    direct_args = state["directArgs"]
    if not AMAP_KEY:
        if not direct_args["origin"]:
            return {
                "ok": False,
                "summary": "需要当前位置或出发地才能规划路线。请允许浏览器定位，或直接输入“从你的出发地到店怎么走？”",
                "needsOrigin": True,
                "navigationUrl": "",
                "routeSteps": [],
                "agentSource": "python_route_mock_needs_origin",
            }
        route = mock_route_plan(payload)
        route["agentSource"] = "python_route_mock"
        return route
    if not direct_args["origin"] or not direct_args["destination"]:
        return {"ok": False, "message": "origin 和 destination 必填。", "agentSource": "python_route_invalid_payload"}
    if not ARK_API_KEY:
        route = plan_route_tool(direct_args)
        answer = deterministic_answer(payload, route)
        answer["agentSource"] = "python_no_ark_key"
        return answer

    tool_result: Dict[str, Any] = {}
    for _ in range(MAX_TOOL_STEPS):
        state["currentStep"] += 1
        response = run_llm_once(state, TOOLS if not tool_result else [])
        message = response.get("choices", [{}])[0].get("message", {}) or {}
        state["messages"].append({
            "role": "assistant",
            "content": message.get("content") or "",
            **({"tool_calls": message.get("tool_calls")} if message.get("tool_calls") else {}),
        })
        tool_calls = collect_tool_calls(response)
        if not tool_calls:
            if tool_result:
                return build_final_result(response, tool_result)
            route = plan_route_tool(direct_args)
            answer = deterministic_answer(payload, route)
            answer["agentSource"] = "python_ark_no_tool_call"
            return answer
        for tool_call in tool_calls:
            if tool_call["name"] != "plan_route":
                continue
            args = dict(direct_args)
            args.update(tool_call.get("arguments") or {})
            tool_result = plan_route_tool(args)
            state["messages"].append({
                "role": "tool",
                "tool_call_id": tool_call["id"],
                "content": json.dumps(tool_result, ensure_ascii=False),
            })
    route = plan_route_tool(direct_args)
    answer = deterministic_answer(payload, route)
    answer["agentSource"] = "python_route_tool_loop_exceeded"
    return answer


def run_agent(payload: Dict[str, Any]) -> Dict[str, Any]:
    try:
        return run_tool_loop(payload)
    except Exception as exc:
        direct_args = {
            "origin": payload.get("origin") or payload.get("start"),
            "destination": payload.get("destination") or payload.get("storeAddress") or payload.get("end"),
            "city": payload.get("city") or payload.get("storeCity") or "",
            "mode": payload.get("mode") or "driving",
            "date": payload.get("date") or "",
            "time": payload.get("time") or "",
        }
        if direct_args["origin"] and direct_args["destination"]:
            route = plan_route_tool(direct_args)
            answer = deterministic_answer(payload, route)
            answer["agentSource"] = "python_fallback_after_error"
            answer["agentError"] = str(exc)
            return answer
        return {"ok": False, "message": str(exc), "agentSource": "python_exception"}


def main() -> None:
    try:
        payload = json.loads(sys.stdin.buffer.read().decode("utf-8-sig") or "{}")
        write_json(run_agent(payload))
    except Exception as exc:
        write_json({"ok": False, "message": str(exc), "agentSource": "python_exception"})


if __name__ == "__main__":
    main()
