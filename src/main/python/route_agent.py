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
from datetime import datetime, timedelta
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
AMAP_WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo"
TIMEOUT_SECONDS = float(os.getenv("ROUTE_AGENT_TIMEOUT_SECONDS", "20"))
MAX_TOOL_STEPS = int(os.getenv("ROUTE_AGENT_MAX_TOOL_STEPS", "4"))
COORD_RE = re.compile(r"^\s*-?\d+(?:\.\d+)?\s*,\s*-?\d+(?:\.\d+)?\s*$")
STYLE_HINTS = ("显白通勤款", "法式", "猫眼", "冰透", "短甲", "高级感")
MOCK_STORES = [
    {
        "name": "NailGlow 红谷滩万象城店",
        "address": "南昌市红谷滩区万象城 L3",
        "district": "红谷滩",
        "adcode": "360100",
        "location": "115.858734,28.682892",
        "supportedStyles": ["显白通勤款", "法式", "冰透", "短甲"],
        "nextSlot": "今天 16:30",
        "capacity": 4,
        "parkingAvailable": True,
        "parkingFeePerHour": 6,
        "parkingNote": "万象城地下停车场，消费可抵扣部分停车费",
    },
    {
        "name": "NailGlow 八一广场旗舰店",
        "address": "南昌市东湖区八一广场商圈",
        "district": "东湖",
        "adcode": "360100",
        "location": "115.903632,28.676735",
        "supportedStyles": ["猫眼", "高级感", "法式"],
        "nextSlot": "今天 17:00",
        "capacity": 3,
        "parkingAvailable": True,
        "parkingFeePerHour": 8,
        "parkingNote": "商圈停车位紧张，建议地铁出行",
    },
    {
        "name": "NailGlow 朝阳新城店",
        "address": "南昌市西湖区朝阳新城天虹",
        "district": "西湖",
        "adcode": "360100",
        "location": "115.857236,28.640152",
        "supportedStyles": ["显白通勤款", "猫眼", "短甲"],
        "nextSlot": "今天 18:00",
        "capacity": 5,
        "parkingAvailable": True,
        "parkingFeePerHour": 4,
        "parkingNote": "天虹停车场，工作日下午余位较多",
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


def parse_server_time(payload: Dict[str, Any]) -> datetime:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    raw = payload.get("currentServerTime") or context.get("currentServerTime")
    for pattern in ("%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(str(raw or ""), pattern)
        except ValueError:
            continue
    return datetime.now().replace(second=0, microsecond=0)


def parse_datetime_text(value: Any, now: datetime) -> Optional[datetime]:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).replace(tzinfo=None)
    except ValueError:
        pass
    day_offset = 2 if "后天" in text else 1 if "明天" in text else 0
    match = re.search(r"(上午|中午|下午|晚上)?\s*(\d{1,2})(?:[:点时](\d{1,2})?|点半)", text)
    if not match:
        return None
    period, hour_text, minute_text = match.groups()
    hour = int(hour_text)
    minute = 30 if "点半" in match.group(0) else int(minute_text or 0)
    if period in {"下午", "晚上"} and hour < 12:
        hour += 12
    if period == "中午" and hour < 11:
        hour += 12
    try:
        candidate = (now + timedelta(days=day_offset)).replace(hour=hour, minute=minute, second=0, microsecond=0)
    except ValueError:
        return None
    if day_offset == 0 and candidate < now - timedelta(minutes=5):
        candidate += timedelta(days=1)
    return candidate


def extract_time_constraints(payload: Dict[str, Any]) -> Dict[str, Any]:
    now = parse_server_time(payload)
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    message = str(payload.get("message") or payload.get("query") or "")
    requested_start = parse_datetime_text(
        payload.get("requestedStart")
        or payload.get("preferredSlot")
        or context.get("recommendedSlot"),
        now,
    )
    deadline = parse_datetime_text(
        payload.get("finishBy") or payload.get("deadline") or payload.get("mustFinishBy"),
        now,
    )
    found = [parse_datetime_text(match.group(0), now) for match in re.finditer(
        r"(?:今天|明天|后天)?\s*(?:上午|中午|下午|晚上)?\s*\d{1,2}(?::\d{1,2}|点半|点\d{0,2}分?)",
        message,
    )]
    found = [item for item in found if item is not None]
    if requested_start is None and found:
        requested_start = found[0]
    if deadline is None:
        if len(found) >= 2:
            deadline = max(found)
        elif found and any(token in message for token in ("之前", "有事", "做完", "搞定", "结束")):
            deadline = found[-1]
    return {
        "now": now,
        "requestedStart": requested_start,
        "finishBy": deadline,
    }


def candidate_stores(payload: Dict[str, Any], now: datetime) -> List[Dict[str, Any]]:
    configured = payload.get("storeCandidates")
    if not isinstance(configured, list):
        context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
        configured = context.get("storeCandidates")
    if isinstance(configured, list) and configured:
        return [dict(item) for item in configured if isinstance(item, dict)]

    stores = []
    for index, store in enumerate(mock_store_candidates(payload)):
        first_slot = parse_datetime_text(store.get("nextSlot"), now) or now + timedelta(hours=2 + index)
        candidate = {
            **store,
            "storeId": f"demo_store_{index + 1}",
            "availableSlots": [
                first_slot.isoformat(timespec="seconds"),
                (first_slot + timedelta(hours=2)).isoformat(timespec="seconds"),
            ],
            "baseServicePrice": 268 + index * 12,
        }
        if payload.get("weather"):
            candidate["weather"] = payload.get("weather")
        stores.append(candidate)
    return stores


def fetch_weather_for_store(
    payload: Dict[str, Any],
    store: Dict[str, Any],
    cache: Optional[Dict[str, Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Return normalized live weather, with an explicit demo fallback."""

    configured = store.get("weather") or payload.get("weather")
    if isinstance(configured, dict):
        return {
            "weather": str(configured.get("weather") or configured.get("condition") or "多云"),
            "temperature": str(configured.get("temperature") or "28"),
            "windDirection": str(configured.get("windDirection") or configured.get("winddirection") or "东南"),
            "windPower": str(configured.get("windPower") or configured.get("windpower") or "3"),
            "humidity": str(configured.get("humidity") or "62"),
            "reportTime": str(configured.get("reportTime") or configured.get("reporttime") or ""),
            "source": str(configured.get("source") or "payload"),
        }

    adcode = str(store.get("adcode") or payload.get("weatherAdcode") or "360100")
    weather_cache = cache if cache is not None else {}
    if adcode in weather_cache:
        return dict(weather_cache[adcode])

    if AMAP_KEY:
        try:
            response = http_json(
                "GET",
                AMAP_WEATHER_URL,
                params={"key": AMAP_KEY, "city": adcode, "extensions": "base", "output": "JSON"},
            )
            lives = response.get("lives") if isinstance(response.get("lives"), list) else []
            live = first(lives)
            if str(response.get("status") or "") == "1" and live:
                normalized = {
                    "weather": str(live.get("weather") or "未知"),
                    "temperature": str(live.get("temperature") or ""),
                    "windDirection": str(live.get("winddirection") or ""),
                    "windPower": str(live.get("windpower") or ""),
                    "humidity": str(live.get("humidity") or ""),
                    "reportTime": str(live.get("reporttime") or ""),
                    "province": str(live.get("province") or ""),
                    "city": str(live.get("city") or ""),
                    "adcode": str(live.get("adcode") or adcode),
                    "source": "amap_live",
                }
                weather_cache[adcode] = normalized
                return dict(normalized)
        except Exception:
            pass

    fallback_text = str(configured or payload.get("mockWeather") or "多云")
    fallback = {
        "weather": fallback_text,
        "temperature": str(payload.get("mockTemperature") or "28"),
        "windDirection": str(payload.get("mockWindDirection") or "东南"),
        "windPower": str(payload.get("mockWindPower") or "3"),
        "humidity": str(payload.get("mockHumidity") or "62"),
        "reportTime": "",
        "adcode": adcode,
        "source": "demo_fallback",
    }
    weather_cache[adcode] = fallback
    return dict(fallback)


def weather_route_effect(weather: Dict[str, Any], mode: str) -> Dict[str, Any]:
    condition = str(weather.get("weather") or "未知")
    temperature_match = re.search(r"-?\d+(?:\.\d+)?", str(weather.get("temperature") or ""))
    temperature = float(temperature_match.group(0)) if temperature_match else None
    wind_match = re.search(r"\d+", str(weather.get("windPower") or ""))
    wind_power = int(wind_match.group(0)) if wind_match else 0
    severe = any(token in condition for token in ("暴雨", "大雨", "雷电", "暴雪", "冰雹", "冻雨"))
    wet = any(token in condition for token in ("雨", "雪", "雾"))
    exposed_mode = mode in {"walking", "bicycling", "electrobike"}
    extra_minutes = 0
    score_penalty = 0.0
    advice = f"当前天气{condition}"

    if severe:
        extra_minutes += 15 if mode == "driving" else 20
        score_penalty += 24 if exposed_mode else 14
        advice += "，建议预留更多通勤缓冲并避免骑行"
    elif wet:
        extra_minutes += 8 if mode == "driving" else 10 if mode == "transit" else 14
        score_penalty += 6 if mode in {"driving", "transit"} else 12
        advice += "，建议优先驾车或公共交通并预留路滑缓冲"
    elif temperature is not None and temperature >= 35 and exposed_mode:
        extra_minutes += 8
        score_penalty += 10
        advice += "，高温下不建议长时间步行或骑行"
    elif wind_power >= 6 and mode in {"bicycling", "electrobike"}:
        extra_minutes += 10
        score_penalty += 12
        advice += "，风力较大，建议改用公交或驾车"
    else:
        advice += "，对当前行程影响较小"

    return {
        "extraMinutes": extra_minutes,
        "scorePenalty": score_penalty,
        "risk": "high" if severe else "medium" if wet or score_penalty >= 10 else "low",
        "unsafe": bool(severe and exposed_mode),
        "advice": advice,
    }


def mode_cost(mode: str, distance_km: float, duration_minutes: int, store: Dict[str, Any]) -> float:
    if mode == "driving":
        parking = float(store.get("parkingFeePerHour") or 0) * 2
        return round(12 + distance_km * 2.2 + parking, 2)
    if mode == "transit":
        return round(2 + distance_km * 0.45, 2)
    if mode in {"bicycling", "electrobike"}:
        return round(1 + distance_km * 0.12, 2)
    return 0.0


def route_estimate_for_store(payload: Dict[str, Any], store: Dict[str, Any], mode: str) -> Dict[str, Any]:
    origin = payload.get("origin") or payload.get("start")
    destination = store.get("location") or store.get("address")
    if AMAP_KEY and origin and destination:
        try:
            return plan_route_tool({
                "origin": origin,
                "destination": destination,
                "city": payload.get("city") or "南昌",
                "mode": mode,
            })
        except Exception:
            pass
    distance = float(store.get("distanceKm") or 5.0)
    minutes = int(store.get("drivingMinutes") or max(10, round(distance * 1.9 + 3)))
    if mode == "transit":
        minutes = int(store.get("transitMinutes") or minutes + 15)
    elif mode == "walking":
        minutes = max(minutes, round(distance * 12))
    return {
        "ok": True,
        "provider": "demo_estimator",
        "mode": mode,
        "distanceKm": round(distance, 1),
        "durationMinutes": minutes,
        "navigationUrl": build_amap_link(str(origin or "当前位置"), str(destination or ""), mode),
        "instructions": [f"从当前位置前往{store.get('name') or store.get('storeName')}"]
    }


def evaluate_store_fulfillment(payload: Dict[str, Any]) -> Dict[str, Any]:
    constraints = extract_time_constraints(payload)
    now = constraints["now"]
    requested_start = constraints["requestedStart"]
    finish_by = constraints["finishBy"]
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    loop = payload.get("agentLoop") if isinstance(payload.get("agentLoop"), dict) else {}
    booking_result: Dict[str, Any] = {}
    for item in loop.get("toolResults", []) if isinstance(loop.get("toolResults"), list) else []:
        if not isinstance(item, dict) or str(item.get("toolName") or "") != "create_or_reschedule_appointment":
            continue
        candidate_result = item.get("result") if isinstance(item.get("result"), dict) else {}
        if candidate_result.get("ok"):
            booking_result = dict(candidate_result)
            break
    service_duration = int(payload.get("serviceDurationMinutes") or context.get("serviceDurationMinutes") or 110)
    style_name = str(payload.get("styleName") or context.get("styleName") or parse_style_preference(payload))
    preferred_mode = str(payload.get("mode") or payload.get("routeMode") or "driving")
    if preferred_mode not in {"driving", "walking", "bicycling", "electrobike", "transit"}:
        preferred_mode = "driving"
    modes = [preferred_mode] if payload.get("travelModeFixed") else list(dict.fromkeys([preferred_mode, "transit"]))
    evaluated: List[Dict[str, Any]] = []
    weather_cache: Dict[str, Dict[str, Any]] = {}

    for store in candidate_stores(payload, now):
        store_name = str(store.get("name") or store.get("storeName") or "候选门店")
        supported = bool(store.get("supportsStyle", True)) or style_name in list(store.get("supportedStyles") or [])
        weather = fetch_weather_for_store(payload, store, weather_cache)
        slots = [parse_datetime_text(item, now) for item in store.get("availableSlots", [])]
        slots = sorted(item for item in slots if item is not None and item >= now)
        if requested_start:
            slots = sorted(slots, key=lambda item: (abs((item - requested_start).total_seconds()), item))
        for mode in modes:
            route = route_estimate_for_store(payload, store, mode)
            raw_travel_minutes = int(route.get("durationMinutes") or 999)
            weather_effect = weather_route_effect(weather, mode)
            travel_minutes = raw_travel_minutes + int(weather_effect["extraMinutes"])
            earliest_arrival_at = now + timedelta(minutes=travel_minutes)
            ready_at = earliest_arrival_at + timedelta(minutes=10)
            feasible_slots = [slot for slot in slots if ready_at <= slot]
            selected_slot = feasible_slots[0] if feasible_slots else None
            service_end = selected_slot + timedelta(minutes=service_duration) if selected_slot else None
            before_deadline = finish_by is None or (service_end is not None and service_end <= finish_by)
            feasible = bool(selected_slot and before_deadline and supported and not weather_effect["unsafe"])
            distance = float(route.get("distanceKm") or store.get("distanceKm") or 0)
            travel_cost = mode_cost(mode, distance, travel_minutes, store)
            service_price = float(store.get("servicePrice") or store.get("baseServicePrice") or context.get("amount") or 268)
            deadline_buffer = int((finish_by - service_end).total_seconds() / 60) if finish_by and service_end else 0
            recommended_departure = (
                selected_slot - timedelta(minutes=travel_minutes + 10)
                if selected_slot else None
            )
            planned_arrival = selected_slot - timedelta(minutes=10) if selected_slot else None
            score = (
                (100 if feasible else 0)
                + min(30, max(-30, deadline_buffer / 3))
                - travel_minutes * 0.65
                - travel_cost * 0.12
                + (8 if store.get("parkingAvailable") and mode == "driving" else 0)
                + (8 if supported else -25)
                - float(weather_effect["scorePenalty"])
            )
            evaluated.append({
                "storeId": store.get("storeId"),
                "storeName": store_name,
                "storeAddress": store.get("address") or store.get("storeAddress"),
                "storeLocation": store.get("location"),
                "mode": mode,
                "distanceKm": round(distance, 1),
                "rawTravelMinutes": raw_travel_minutes,
                "travelMinutes": travel_minutes,
                "weatherAdjustmentMinutes": int(weather_effect["extraMinutes"]),
                "travelCost": travel_cost,
                "servicePrice": service_price,
                "estimatedTotalCost": round(service_price + travel_cost, 2),
                "selectedSlot": selected_slot.isoformat(timespec="seconds") if selected_slot else None,
                "earliestArrivalAt": earliest_arrival_at.isoformat(timespec="seconds"),
                "recommendedDepartAt": recommended_departure.isoformat(timespec="seconds") if recommended_departure else None,
                "arrivalAt": planned_arrival.isoformat(timespec="seconds") if planned_arrival else None,
                "serviceEndAt": service_end.isoformat(timespec="seconds") if service_end else None,
                "finishBy": finish_by.isoformat(timespec="seconds") if finish_by else None,
                "deadlineBufferMinutes": deadline_buffer,
                "parkingAvailable": bool(store.get("parkingAvailable")),
                "parkingNote": store.get("parkingNote") or "",
                "weather": weather.get("weather") or "未知",
                "weatherDetail": weather,
                "weatherSource": weather.get("source") or "demo_fallback",
                "weatherRisk": weather_effect["risk"],
                "weatherAdvice": weather_effect["advice"],
                "supportsStyle": supported,
                "feasible": feasible,
                "score": round(score, 3),
                "navigationUrl": route.get("navigationUrl") or "",
                "routeSteps": route.get("instructions") or [],
            })

    ranked = sorted(evaluated, key=lambda item: (item["feasible"], item["score"]), reverse=True)
    best = next((item for item in ranked if item["feasible"]), None)
    if best is None:
        return {
            "ok": False,
            "intent": "fulfillment",
            "summary": "当前候选门店无法同时满足空位、通勤和结束时间约束，建议放宽时间或更换服务项目。",
            "constraints": {
                "requestedStart": requested_start.isoformat(timespec="seconds") if requested_start else None,
                "finishBy": finish_by.isoformat(timespec="seconds") if finish_by else None,
                "serviceDurationMinutes": service_duration,
            },
            "storeCandidates": ranked[:6],
            "toolCalls": [],
            "agentSource": "fulfillment_constraint_planner",
        }

    pending_action = context.get("pendingAction") if isinstance(context.get("pendingAction"), dict) else {}
    rejected_pending = bool(pending_action) and any(
        token in str(payload.get("message") or "")
        for token in ("不要了", "取消", "算了", "不去了", "先不约")
    )
    if rejected_pending:
        return {
            "ok": True,
            "intent": "fulfillment",
            "summary": "已取消上一轮门店履约方案，不会创建或修改预约。",
            "answer": "已取消上一轮门店履约方案，不会创建或修改预约。",
            "pendingAction": None,
            "requiresBookingConfirmation": False,
            "toolCalls": [],
            "storeCandidates": ranked[:6],
            "agentSource": "fulfillment_pending_cancelled",
        }
    confirmed_pending = bool(pending_action) and any(
        token in str(payload.get("message") or "")
        for token in ("确认", "同意", "可以", "就这个", "帮我约")
    )
    auto_book = not booking_result and (bool(payload.get("autoBook")) or confirmed_pending or any(token in str(payload.get("message") or "") for token in (
        "自动预约", "直接预约", "帮我预约", "就约这家", "可以，预约",
    )))
    booking_call = {
        "id": f"fulfillment_booking_{best.get('storeId') or 'store'}",
        "name": "create_or_reschedule_appointment",
        "arguments": {
            "scheduledAtIso": best["selectedSlot"],
            "userFacingSlotText": best["selectedSlot"],
            "requestSummary": f"门店履约Agent推荐{best['storeName']}并满足结束时间约束",
            "action": "reschedule" if context.get("appointment") else "create",
            "storeId": best.get("storeId"),
            "storeName": best["storeName"],
            "storeAddress": best.get("storeAddress"),
        },
    }
    finish_text = f"，预计 {best['serviceEndAt'][11:16]} 完成" if best.get("serviceEndAt") else ""
    depart_text = best.get("recommendedDepartAt", "")[11:16] if best.get("recommendedDepartAt") else "现在"
    arrival_text = best.get("arrivalAt", "")[11:16] if best.get("arrivalAt") else "到店前"
    weather_source_text = "高德实况天气" if best.get("weatherSource") == "amap_live" else "Demo 天气"
    summary = (
        f"推荐前往{best['storeName']}，{best['selectedSlot'][11:16]}可开始，"
        f"建议 {depart_text} 出发、{arrival_text} 到店，通勤约{best['travelMinutes']}分钟{finish_text}；"
        f"{weather_source_text}为{best['weather']}，{best['weatherAdvice']}；"
        f"交通费用约{best['travelCost']}元，含服务预计{best['estimatedTotalCost']}元。"
    )
    if booking_result:
        summary += f" 已完成预约，预约门店为{booking_result.get('storeName') or best['storeName']}。"
    return {
        "ok": True,
        "intent": "fulfillment",
        "summary": summary,
        "answer": summary,
        "recommendedStoreName": best["storeName"],
        "storeName": best["storeName"],
        "storeAddress": best.get("storeAddress"),
        "recommendedSlot": best["selectedSlot"],
        "serviceEndAt": best.get("serviceEndAt"),
        "finishBy": best.get("finishBy"),
        "bestMode": best["mode"],
        "distanceKm": best["distanceKm"],
        "durationMinutes": best["travelMinutes"],
        "recommendedDepartAt": best.get("recommendedDepartAt"),
        "arrivalAt": best.get("arrivalAt"),
        "travelCost": best["travelCost"],
        "estimatedTotalCost": best["estimatedTotalCost"],
        "parkingNote": best["parkingNote"],
        "weather": best["weather"],
        "weatherDetail": best["weatherDetail"],
        "weatherSource": best["weatherSource"],
        "weatherRisk": best["weatherRisk"],
        "weatherAdvice": best["weatherAdvice"],
        "weatherAdjustmentMinutes": best["weatherAdjustmentMinutes"],
        "navigationUrl": best["navigationUrl"],
        "routeSteps": best["routeSteps"],
        "travelPlan": {
            "departAt": best.get("recommendedDepartAt"),
            "arriveAt": best.get("arrivalAt"),
            "serviceStartAt": best.get("selectedSlot"),
            "serviceEndAt": best.get("serviceEndAt"),
            "finishBy": best.get("finishBy"),
            "deadlineBufferMinutes": best.get("deadlineBufferMinutes"),
            "mode": best.get("mode"),
            "weatherAdvice": best.get("weatherAdvice"),
            "parkingNote": best.get("parkingNote"),
        },
        "storeCandidates": ranked[:6],
        "requiresBookingConfirmation": not auto_book,
        "pendingAction": None if auto_book else booking_call["arguments"],
        "status": "tool_calls" if auto_book else "completed",
        "toolCalls": [booking_call] if auto_book else [],
        "appointment": booking_result or None,
        "agentSource": "fulfillment_constraint_planner",
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
        message = str(payload.get("message") or payload.get("query") or "")
        if (
            payload.get("fulfillmentPlan")
            or payload.get("storeCandidates")
            or payload.get("finishBy")
            or payload.get("deadline")
            or any(token in message for token in (
                "哪家店", "店满", "空位", "几点前", "有事", "做完", "搞定",
                "停车", "费用", "赶得上", "自动预约",
            ))
        ):
            return evaluate_store_fulfillment(payload)
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
