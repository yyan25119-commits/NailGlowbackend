#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timedelta
from typing import Any, Dict, List

import agent_content_gate

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
ARK_MODEL = (
    os.getenv("CUSTOMER_AGENT_MODEL")
    or os.getenv("ARK_MODEL")
    or os.getenv("AI_MODEL")
    or "doubao-seed-2-0-pro-260215"
)
TIMEOUT_SECONDS = float(os.getenv("CUSTOMER_AGENT_TIMEOUT_SECONDS", "12"))
MAX_TOOL_STEPS = int(os.getenv("CUSTOMER_AGENT_MAX_TOOL_STEPS", "4"))
DEFAULT_QUICK_REPLIES = ["售前服务", "售后服务", "查看排队", "采纳并预约"]


def write_json(payload: Dict[str, Any]) -> None:
    sys.stdout.buffer.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))


def http_json(url: str, body: Dict[str, Any]) -> Dict[str, Any]:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {ARK_API_KEY}",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


class _CompatChatCompletions:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def create(self, **kwargs: Any) -> Dict[str, Any]:
        body = {key: value for key, value in kwargs.items() if value is not None}
        return http_json(f"{self.base_url}/chat/completions", body)


class _CompatChat:
    def __init__(self, base_url: str) -> None:
        self.completions = _CompatChatCompletions(base_url)


class CompatOpenAI:
    def __init__(self, api_key: str, base_url: str, timeout: float | None = None) -> None:
        self.api_key = api_key
        self.chat = _CompatChat(base_url)


OpenAI = NativeOpenAI or CompatOpenAI


def build_client():
    kwargs = {
        "api_key": os.getenv("DEEPSEEK_API_KEY") or ARK_API_KEY,
        "base_url": ARK_BASE_URL,
    }
    if NativeOpenAI is not None:
        kwargs["timeout"] = TIMEOUT_SECONDS
    return OpenAI(**kwargs)


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
            try:
                return json.loads(match.group(0))
            except json.JSONDecodeError:
                pass
    return {"answer": text or ""}


def normalize_messages(payload: Dict[str, Any]) -> List[Dict[str, str]]:
    raw = payload.get("messages")
    if not isinstance(raw, list):
        return []
    messages: List[Dict[str, str]] = []
    for item in raw[-5:]:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip()
        if role not in ("user", "assistant"):
            continue
        text = str(item.get("text") or item.get("content") or "").strip()
        if text:
            messages.append({"role": role, "content": text[:280]})
    return messages


def compact_context(context: Dict[str, Any]) -> Dict[str, Any]:
    appointment = context.get("appointment") if isinstance(context.get("appointment"), dict) else {}
    route = context.get("route") if isinstance(context.get("route"), dict) else {}
    pending_action = context.get("pendingAction") if isinstance(context.get("pendingAction"), dict) else {}
    style_candidates = context.get("styleCandidates") if isinstance(context.get("styleCandidates"), list) else []
    hand_profile = context.get("handProfile") if isinstance(context.get("handProfile"), dict) else {}
    score_metrics = context.get("scoreMetrics") if isinstance(context.get("scoreMetrics"), dict) else {}
    specialist_state = context.get("specialistState") if isinstance(context.get("specialistState"), dict) else {}
    specialist_memory = specialist_state.get("specialistState") if isinstance(specialist_state.get("specialistState"), dict) else {}
    return {
        "currentServerTime": context.get("currentServerTime") or "",
        "quickSlots": context.get("quickSlots") or [],
        "recommendedSlot": context.get("recommendedSlot") or "",
        "queueAhead": context.get("queueAhead", 0),
        "estimatedWaitMinutes": context.get("estimatedWaitMinutes", 0),
        "serviceName": context.get("serviceName") or "AI 试穿复刻",
        "serviceDurationMinutes": context.get("serviceDurationMinutes", 110),
        "amount": context.get("amount", 268),
        "storeName": context.get("storeName") or "NailGlow 市中心旗舰店",
        "handProfile": hand_profile,
        "scoreMetrics": score_metrics,
        "scoreReasons": list(context.get("scoreReasons") or [])[:6] if isinstance(context.get("scoreReasons"), list) else [],
        "tryOnScore": context.get("tryOnScore", 0),
        "confidence": context.get("confidence", 0),
        "styleCandidates": [
            {
                "styleId": item.get("styleId"),
                "styleCode": item.get("styleCode"),
                "name": item.get("name"),
                "tags": item.get("tags"),
                "description": item.get("description"),
                "averageScore": item.get("averageScore"),
            }
            for item in style_candidates[:8]
            if isinstance(item, dict)
        ],
        "appointment": {
            "id": appointment.get("id"),
            "status": appointment.get("status"),
            "slotTime": appointment.get("slotTime"),
            "slotTimeUser": appointment.get("slotTimeUser"),
            "slotTimeAdmin": appointment.get("slotTimeAdmin"),
            "scheduledAt": appointment.get("scheduledAt"),
        } if appointment else {},
        "pendingAction": {
            "type": pending_action.get("type"),
            "awaitingConfirmation": pending_action.get("awaitingConfirmation", False),
            "action": pending_action.get("action"),
            "scheduledAtIso": pending_action.get("scheduledAtIso"),
            "userFacingSlotText": pending_action.get("userFacingSlotText"),
            "requestSummary": pending_action.get("requestSummary"),
            "storeId": pending_action.get("storeId"),
            "storeName": pending_action.get("storeName"),
            "storeAddress": pending_action.get("storeAddress"),
        } if pending_action else {},
        "route": {
            "ok": route.get("ok"),
            "summary": route.get("summary") or "",
            "needsOrigin": route.get("needsOrigin", False),
            "navigationUrl": route.get("navigationUrl") or "",
        } if route else {},
        "specialistState": {
            "currentAgent": specialist_state.get("currentAgent"),
            "previousAgent": specialist_state.get("previousAgent"),
            "turn": specialist_state.get("turn", 0),
            "status": specialist_memory.get("status"),
            "turnCount": specialist_memory.get("turnCount", 0),
            "workingMemory": specialist_memory.get("workingMemory") if isinstance(specialist_memory.get("workingMemory"), dict) else {},
            "userFacts": (
                specialist_memory.get("workingMemory", {}).get("userFacts", {})
                if isinstance(specialist_memory.get("workingMemory"), dict)
                else {}
            ),
            "recentTurns": list(specialist_memory.get("recentTurns") or [])[-4:] if isinstance(specialist_memory.get("recentTurns"), list) else [],
            "ragEvidence": list(specialist_memory.get("ragEvidence") or [])[-4:] if isinstance(specialist_memory.get("ragEvidence"), list) else [],
        } if specialist_state else {},
    }


def compact_rag_context(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {"available": False, "contextText": "", "sources": []}
    raw_sources = value.get("hits") if isinstance(value.get("hits"), list) else []
    sources: List[Dict[str, str]] = []
    for item in raw_sources[:4]:
        if not isinstance(item, dict):
            continue
        sources.append({
            "title": str(item.get("title") or item.get("source") or "知识库"),
            "sectionPath": str(item.get("sectionPath") or ""),
        })
    return {
        "available": bool(value.get("available")),
        "retrievalMode": str(value.get("retrievalMode") or ""),
        "contextText": str(value.get("contextText") or "")[:3000],
        "sources": sources,
    }


def normalize_tool_results(raw: Any) -> List[Dict[str, Any]]:
    if not isinstance(raw, list):
        return []
    results: List[Dict[str, Any]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        results.append({
            "toolCallId": str(item.get("toolCallId") or item.get("id") or ""),
            "toolName": str(item.get("toolName") or item.get("name") or ""),
            "result": item.get("result") if isinstance(item.get("result"), dict) else {"ok": False, "message": "工具执行结果缺失"},
        })
    return results


def build_system_prompt(
    has_tool_results: bool,
    disable_tools: bool,
    specialist_instruction: str = "",
    allowed_tools: List[str] | None = None,
) -> str:
    allowed_tools = allowed_tools or []
    specialist_policy = (
        f"当前身份与边界：{specialist_instruction}"
        if specialist_instruction else ""
    )
    tool_scope = (
        "本专家只允许输出这些工具：" + ",".join(allowed_tools) + "。其他工具一律不得输出。"
        if allowed_tools else "当前专家没有业务写工具权限。"
    )
    action_policy = (
        "本轮已经拿到工具执行结果或被禁止触发动作，你只能输出最终答复，toolCalls 必须为空数组。"
        if has_tool_results or disable_tools else
        "普通咨询 toolCalls 必须为空数组；只有创建/改约预约、投诉/退款/售后升级、转人工时才输出 toolCalls。"
    )
    return (
        "你是 NailGlow 美甲店智能客服。性能优先，回答要短、准、可执行。"
        "必须输出严格 JSON，不要 Markdown，不要额外文字。"
        "JSON 字段：answer 字符串；intent 为 general/route/presale/aftersale/queue/appointment/support；"
        "routeOrigin 字符串；quickReplies 字符串数组；toolCalls 数组；pendingAction 对象或 null；"
        "美甲顾问可额外输出 recommendedStyles、recommendationReasons、missingPreferences 数组。"
        f"{specialist_policy}{tool_scope}"
        f"{action_policy}"
        "可用 toolCalls："
        "1 create_or_reschedule_appointment，参数 scheduledAtIso、userFacingSlotText、requestSummary、action。"
        "2 update_support_case，参数 category、severity、shouldNotifyMerchant、important、summary、importantItems。"
        "3 request_human_handoff，参数 handoffReason、handoffMessage。"
        "预约规则：用户明确要预约/改约且时间明确时才触发预约动作；scheduledAtIso 必须是 YYYY-MM-DDTHH:mm:ss；"
        "今天/明天/后天/下午一点半等相对说法必须结合 currentServerTime 换成绝对时间。"
        "当前系统每个账号只允许保留一个有效预约，不能同时保留两条有效预约。"
        "如果 context.pendingAction.awaitingConfirmation=true，说明上一轮已经识别出待确认的预约动作。"
        "当用户本轮表达确认、同意、就这个、帮我改约、可以执行时，必须直接输出对应 toolCalls，不要再次追问时间。"
        "当用户拒绝或取消时，toolCalls 为空，pendingAction 为 null，并明确说明已保留原预约。"
        "如果已有 appointment.status=已确认，而用户要求新增、再约一个、保留原预约、不是改约，必须明确说明：当前只能覆盖原预约，未确认前不要说预约成功，不要说原预约仍保留。"
        "路线规则：问路线时 intent=route；如果用户给了出发地，routeOrigin 填原文，否则为空。"
        "会话状态规则：context.specialistState 是当前专业 Agent 的独立工作状态，"
        "连续追问时要继承 workingMemory、recentTurns 和 ragEvidence，不要让用户重复提供已知信息。"
        "美甲推荐规则：优先联合 handProfile、scoreMetrics、styleCandidates 和 ragContext 给出 2-3 个具体候选，"
        "说明手型修饰、肤色协调、场景适配和日常维护理由；数据不足时只追问缺少的维度。"
        "如果 specialistState.userFacts 已经记录手指长短、手部质感、肤色、场景或甲长，不要重复追问，直接用于推荐。"
        "如果 appointment.status=已确认，除非用户明确改约，否则不要再次预约。"
        "人工规则：用户要求人工或问题无法闭环时触发 request_human_handoff，handoffMessage 固定为：已为你转接人工客服，请在当前对话等待回复。"
        "知识库规则：如果 ragContext.available=true，优先使用 ragContext.contextText 中的证据回答；"
        "预约、排队、金额、当前门店等 context 实时业务字段优先级高于知识库。"
        "知识库没有明确依据时不要编造门店政策、退款金额、赔偿或医学结论，应继续追问或按人工规则处理。"
    )


def build_initial_state(payload: Dict[str, Any]) -> Dict[str, Any]:
    loop = payload.get("agentLoop") if isinstance(payload.get("agentLoop"), dict) else {}
    tool_results = normalize_tool_results(loop.get("toolResults"))
    context = compact_context(payload.get("context") if isinstance(payload.get("context"), dict) else {})
    user_packet: Dict[str, Any] = {
        "mode": payload.get("mode") or "general",
        "message": payload.get("message") or "",
        "context": context,
        "ragContext": compact_rag_context(payload.get("ragContext")),
        "history": normalize_messages(payload),
    }
    if tool_results:
        user_packet["toolResults"] = tool_results

    messages = [
        {
            "role": "system",
            "content": build_system_prompt(
                bool(tool_results),
                bool(payload.get("disableTools")),
                str(payload.get("specialistInstruction") or ""),
                [str(item) for item in payload.get("allowedTools", [])]
                if isinstance(payload.get("allowedTools"), list) else [],
            ),
        },
        {
            "role": "user",
            "content": json.dumps(user_packet, ensure_ascii=False, separators=(",", ":")),
        },
    ]
    return {
        "currentStep": int(loop.get("currentStep") or 0),
        "messages": messages,
    }


def run_llm_once(state: Dict[str, Any]) -> Dict[str, Any]:
    client = build_client()
    request_kwargs: Dict[str, Any] = {
        "model": ARK_MODEL,
        "messages": state["messages"],
        "temperature": 0.2,
        "stream": False,
    }
    # Some production environments pin older openai-python versions that
    # don't support the `thinking` argument yet.
    request_kwargs["thinking"] = {"type": "disabled"}
    try:
        response = client.chat.completions.create(**request_kwargs)
    except TypeError as exc:
        if "unexpected keyword argument 'thinking'" not in str(exc):
            raise
        request_kwargs.pop("thinking", None)
        response = client.chat.completions.create(**request_kwargs)
    return normalize_response(response)


CN_NUMBERS = {
    "零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
    "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10,
}


def parse_number(value: str) -> int | None:
    text = str(value or "").strip()
    if not text:
        return None
    if text.isdigit():
        return int(text)
    if text in CN_NUMBERS:
        return CN_NUMBERS[text]
    if "十" in text:
        left, _, right = text.partition("十")
        tens = CN_NUMBERS.get(left, 1) if left else 1
        ones = CN_NUMBERS.get(right, 0) if right else 0
        return tens * 10 + ones
    return None


def parse_base_time(context: Dict[str, Any]) -> datetime:
    raw = str(context.get("currentServerTime") or "").strip()
    for pattern in ("%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(raw, pattern)
        except ValueError:
            pass
    return datetime.now()


def normalize_datetime_text(value: str) -> str:
    text = str(value or "").strip().replace("/", "-")
    if re.match(r"^\d{4}-\d{1,2}-\d{1,2} \d{1,2}:\d{1,2}(:\d{1,2})?$", text):
        text = text.replace(" ", "T", 1)
    if re.match(r"^\d{4}-\d{1,2}-\d{1,2}T\d{1,2}:\d{1,2}$", text):
        text = f"{text}:00"
    return text


def format_slot_label(dt: datetime) -> str:
    return f"{dt.month}月{dt.day}日 {dt.hour:02d}:{dt.minute:02d}"


def parse_datetime_from_message(message: str, context: Dict[str, Any]) -> datetime | None:
    text = str(message or "").strip()
    base = parse_base_time(context)

    iso_match = re.search(r"(\d{4})[-/](\d{1,2})[-/](\d{1,2})\s*(?:T|\s)?(上午|下午|晚上|中午|早上)?\s*(\d{1,2})[:点时](\d{1,2})?", text)
    if iso_match:
        year, month, day = map(int, iso_match.group(1, 2, 3))
        period = iso_match.group(4) or ""
        hour = int(iso_match.group(5))
        minute = int(iso_match.group(6) or 0)
        if period in ("下午", "晚上") and hour < 12:
            hour += 12
        if period == "中午" and hour < 11:
            hour += 12
        return datetime(year, month, day, hour, minute)

    month_day = re.search(r"(\d{1,2})月(\d{1,2})(?:日|号)?.{0,8}?(上午|下午|晚上|中午|早上)?\s*([零〇一二两三四五六七八九十\d]{1,3})[点时:：](?:([零〇一二两三四五六七八九十\d]{1,3})分?)?", text)
    if month_day:
        month = int(month_day.group(1))
        day = int(month_day.group(2))
        period = month_day.group(3) or ""
        hour = parse_number(month_day.group(4))
        minute = parse_number(month_day.group(5) or "0")
        if hour is None or minute is None:
            return None
        year = base.year + (1 if (month, day) < (base.month, base.day) else 0)
        if period in ("下午", "晚上") and hour < 12:
            hour += 12
        if period == "中午" and hour < 11:
            hour += 12
        return datetime(year, month, day, hour, minute)

    relative_match = re.search(r"(今天|明天|后天).{0,10}?(上午|下午|晚上|中午|早上)?\s*([零〇一二两三四五六七八九十\d]{1,3})[点时:：](?:([零〇一二两三四五六七八九十\d]{1,3})分?)?", text)
    if relative_match:
        day_offset = {"今天": 0, "明天": 1, "后天": 2}.get(relative_match.group(1), 0)
        period = relative_match.group(2) or ""
        hour = parse_number(relative_match.group(3))
        minute = parse_number(relative_match.group(4) or "0")
        if hour is None or minute is None:
            return None
        if period in ("下午", "晚上") and hour < 12:
            hour += 12
        if period == "中午" and hour < 11:
            hour += 12
        target = base + timedelta(days=day_offset)
        return target.replace(hour=hour, minute=minute, second=0, microsecond=0)

    return None


def normalize_tool_name(name: str) -> str:
    text = str(name or "").strip()
    lower = text.lower()
    if text in ("create_or_reschedule_appointment", "update_support_case", "request_human_handoff"):
        return text
    if any(token in lower for token in ("appointment", "schedule", "booking")) or any(token in text for token in ("预约", "改约", "日程")):
        return "create_or_reschedule_appointment"
    if any(token in lower for token in ("handoff", "human", "manual")) or any(token in text for token in ("人工", "转接", "客服介入")):
        return "request_human_handoff"
    if any(token in lower for token in ("support", "complaint", "refund", "case")) or any(token in text for token in ("投诉", "退款", "售后", "工单")):
        return "update_support_case"
    return text


def first_arg(arguments: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = arguments.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def normalize_tool_arguments(name: str, arguments: Dict[str, Any], payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = dict(arguments)
    if name == "create_or_reschedule_appointment":
        scheduled = first_arg(
            normalized,
            "scheduledAtIso", "scheduledAt", "appointmentTime", "appointment_time",
            "startTime", "start_time", "scheduled_at", "time",
        )
        if scheduled:
            normalized["scheduledAtIso"] = normalize_datetime_text(scheduled)
            try:
                normalized["userFacingSlotText"] = format_slot_label(datetime.fromisoformat(normalized["scheduledAtIso"]))
            except ValueError:
                pass
        else:
            context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
            parsed = parse_datetime_from_message(str(payload.get("message") or ""), context)
            if parsed:
                normalized["scheduledAtIso"] = parsed.strftime("%Y-%m-%dT%H:%M:%S")
                normalized.setdefault("userFacingSlotText", format_slot_label(parsed))
        normalized.setdefault("action", first_arg(normalized, "operation") or "create")
        if normalized.get("action") in ("update", "change"):
            normalized["action"] = "reschedule"
    elif name == "request_human_handoff":
        normalized.setdefault("handoffMessage", "已为你转接人工客服，请在当前对话等待回复。")
    elif name == "update_support_case":
        normalized.setdefault("category", "其他")
        normalized.setdefault("severity", "medium")
        normalized.setdefault("shouldNotifyMerchant", True)
    return normalized


def build_pending_action(payload: Dict[str, Any], result: Dict[str, Any]) -> Dict[str, Any] | None:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    existing = context.get("pendingAction") if isinstance(context.get("pendingAction"), dict) else {}
    if existing and existing.get("awaitingConfirmation"):
        return existing
    appointment = context.get("appointment") if isinstance(context.get("appointment"), dict) else {}
    if appointment.get("status") != "已确认":
        return None
    if str(result.get("intent") or "").strip() != "appointment":
        return None
    parsed = parse_datetime_from_message(str(payload.get("message") or ""), context)
    if not parsed:
        return None
    return {
        "type": "appointment_reschedule",
        "awaitingConfirmation": True,
        "action": "reschedule",
        "scheduledAtIso": parsed.strftime("%Y-%m-%dT%H:%M:%S"),
        "userFacingSlotText": format_slot_label(parsed),
        "requestSummary": str(payload.get("message") or "")[:120],
    }


def has_any_token(text: str, tokens: List[str]) -> bool:
    return any(token in text for token in tokens)


def is_reschedule_request(message: str) -> bool:
    text = str(message or "")
    return has_any_token(text, ["改约", "改预约", "改到", "改成", "改为", "改时间", "换到", "换成", "调整", "重新预约", "重新安排"])


def is_additional_create_request(message: str) -> bool:
    text = str(message or "")
    return has_any_token(text, ["新增", "再约", "再加", "加一个", "另约", "另外约", "保留原预约", "保留原有预约", "不是改约", "不是改预约"])


def build_single_active_policy_response(payload: Dict[str, Any], result: Dict[str, Any]) -> Dict[str, Any] | None:
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    appointment = context.get("appointment") if isinstance(context.get("appointment"), dict) else {}
    if appointment.get("status") != "已确认":
        return None
    intent = str(result.get("intent") or payload.get("mode") or "general").strip()
    if intent != "appointment":
        return None

    message = str(payload.get("message") or "").strip()
    current_slot = str(
        appointment.get("slotTimeAdmin")
        or appointment.get("slotTimeUser")
        or appointment.get("slotTime")
        or "当前预约"
    ).strip()
    pending_action = context.get("pendingAction") if isinstance(context.get("pendingAction"), dict) else {}

    if pending_action.get("awaitingConfirmation") and is_additional_create_request(message):
        target_slot = str(
            pending_action.get("userFacingSlotText")
            or pending_action.get("scheduledAtIso")
            or context.get("recommendedSlot")
            or ""
        ).strip()
        if target_slot:
            return {
                "answer": f"当前账号只能保留一个有效预约，不能同时保留 {current_slot} 和 {target_slot} 两条预约。若继续处理，我只能把当前预约改到 {target_slot}；确认请直接说“确认改到这个时间”。",
                "intent": "appointment",
                "pendingAction": pending_action,
            }

    parsed = parse_datetime_from_message(message, context)
    if not parsed:
        return None
    if not is_additional_create_request(message) or is_reschedule_request(message):
        return None

    target_slot = format_slot_label(parsed)
    return {
        "answer": f"你当前已经有 {current_slot} 的有效预约。系统暂不支持同时保留两个有效预约；如果继续处理，{target_slot} 会覆盖当前预约。确认的话我就帮你改到 {target_slot}。",
        "intent": "appointment",
        "pendingAction": {
            "type": "appointment_reschedule",
            "awaitingConfirmation": True,
            "action": "reschedule",
            "scheduledAtIso": parsed.strftime("%Y-%m-%dT%H:%M:%S"),
            "userFacingSlotText": target_slot,
            "requestSummary": message[:120],
        },
    }


def normalize_tool_calls(result: Dict[str, Any], payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    raw_calls = result.get("toolCalls") or result.get("actions") or []
    if not isinstance(raw_calls, list):
        return []
    normalized: List[Dict[str, Any]] = []
    configured_allowed = payload.get("allowedTools")
    allowed_tools = {
        normalize_tool_name(str(item))
        for item in configured_allowed
        if str(item).strip()
    } if isinstance(configured_allowed, list) else None
    for index, item in enumerate(raw_calls):
        if not isinstance(item, dict):
            continue
        name = normalize_tool_name(str(item.get("name") or item.get("toolName") or "").strip())
        arguments = item.get("arguments") if isinstance(item.get("arguments"), dict) else item.get("parameters")
        arguments = arguments if isinstance(arguments, dict) else {}
        if name and (allowed_tools is None or name in allowed_tools):
            normalized.append({
                "id": str(item.get("id") or f"call_{index + 1}"),
                "name": name,
                "arguments": normalize_tool_arguments(name, arguments, payload),
            })
    return normalized


def fallback(payload: Dict[str, Any], reason: str = "") -> Dict[str, Any]:
    mode = str(payload.get("mode") or "general")
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    route = context.get("route") if isinstance(context.get("route"), dict) else {}
    appointment = context.get("appointment") if isinstance(context.get("appointment"), dict) else {}
    queue = context.get("queueAhead", 0)
    wait = context.get("estimatedWaitMinutes", 0)
    amount = context.get("amount", 268)
    service = context.get("serviceName", "AI 试穿复刻")
    slot = appointment.get("slotTimeUser") or appointment.get("slotTime") or context.get("recommendedSlot", "今天 18:00")
    recommended_styles: List[Dict[str, Any]] = []
    recommendation_reasons: List[str] = []

    if mode == "route":
        answer = str(route.get("summary") or "请告诉我你的出发地，我会继续帮你规划到店路线。")
    elif mode == "presale":
        candidates = context.get("styleCandidates") if isinstance(context.get("styleCandidates"), list) else []
        recommended_styles = [
            {"styleId": item.get("styleId"), "name": item.get("name"), "tags": item.get("tags")}
            for item in candidates[:3]
            if isinstance(item, dict)
        ]
        hand_profile = context.get("handProfile") if isinstance(context.get("handProfile"), dict) else {}
        profile_text = "、".join(f"{key}{value}" for key, value in list(hand_profile.items())[:3])
        names = "、".join(str(item.get("name") or "候选款式") for item in recommended_styles)
        recommendation_reasons = [
            "优先从当前上架款式和历史评分较高的候选中筛选",
            f"手型画像已记录：{profile_text}" if profile_text else "尚缺少手型量化画像，可先用试穿结果补充",
            "最终再结合肤色、使用场景和可接受甲长重排",
        ]
        answer = (
            f"结合当前款式库{f'和你的手型画像（{profile_text}）' if profile_text else ''}，"
            f"可以先看{names or '低饱和细法式、透明渐变和低闪猫眼'}。"
            "你再告诉我主要场景和可接受甲长，我会在当前顾问状态中继续缩小范围。"
        )
    elif mode == "aftersale":
        answer = "售后可以咨询补甲、翘边、饰品松动、卸甲护理和日常保养。"
    elif mode == "queue":
        answer = f"当前前方约 {queue} 名顾客，预计等待 {wait} 分钟，推荐预约时间是 {slot}。"
    else:
        answer = f"你好，我是 NailGlow 智能客服。你可以问款式、价格、预约、排队、路线、售前或售后问题。当前推荐预约 {slot}。"

    response = {
        "answer": answer,
        "intent": mode,
        "routeOrigin": "",
        "quickReplies": DEFAULT_QUICK_REPLIES,
        "toolCalls": [],
        "agentSource": "python_customer_fallback",
        "agentReason": reason,
    }
    if recommended_styles:
        response["recommendedStyles"] = recommended_styles
        response["recommendationReasons"] = recommendation_reasons
        response["missingPreferences"] = ["使用场景", "可接受甲长"]
    return response


def fallback_appointment_tool(payload: Dict[str, Any], reason: str) -> Dict[str, Any] | None:
    if payload.get("disableTools") or payload.get("agentLoop"):
        return None
    message = str(payload.get("message") or "")
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    appointment = context.get("appointment") if isinstance(context.get("appointment"), dict) else {}
    if appointment.get("status") == "已确认" and not any(token in message for token in ("改", "换", "调整", "重新")):
        return None
    if not any(token in message for token in ("预约", "改约", "约", "安排", "到店", "有空")):
        return None
    parsed = parse_datetime_from_message(message, context)
    if not parsed:
        return None
    action = "reschedule" if appointment.get("id") else "create"
    return {
        "status": "tool_calls",
        "toolCalls": [{
            "id": "call_fallback_appointment",
            "name": "create_or_reschedule_appointment",
            "arguments": {
                "scheduledAtIso": parsed.strftime("%Y-%m-%dT%H:%M:%S"),
                "userFacingSlotText": format_slot_label(parsed),
                "requestSummary": message[:120],
                "action": action,
            },
        }],
        "agentSource": "python_customer_timeout_appointment_parser",
        "agentReason": reason,
        "agentState": {
            "messages": build_initial_state(payload)["messages"],
            "currentStep": 1,
        },
    }


def build_final_result(payload: Dict[str, Any], result: Dict[str, Any], state: Dict[str, Any]) -> Dict[str, Any]:
    backup = fallback(payload)
    policy_override = build_single_active_policy_response(payload, result)
    if policy_override:
        result.update(policy_override)
    result.setdefault("answer", backup["answer"])
    result.setdefault("intent", str(payload.get("mode") or "general"))
    result.setdefault("routeOrigin", "")
    result.setdefault("quickReplies", DEFAULT_QUICK_REPLIES)
    pending_action = result.get("pendingAction") if isinstance(result.get("pendingAction"), dict) else None
    if not pending_action:
        pending_action = build_pending_action(payload, result)
    result["pendingAction"] = pending_action
    result["toolCalls"] = []
    result["agentSource"] = "python_customer_chat_completion"
    result["agentState"] = {
        "messages": state["messages"],
        "currentStep": state["currentStep"],
    }
    return result


def run_tool_loop(payload: Dict[str, Any]) -> Dict[str, Any]:
    state = build_initial_state(payload)
    state["currentStep"] += 1
    if state["currentStep"] > MAX_TOOL_STEPS:
        return fallback(payload, "customer_agent_tool_loop_exceeded")

    response = run_llm_once(state)
    content = response.get("choices", [{}])[0].get("message", {}).get("content", "") or ""
    result = agent_content_gate.normalize_result(extract_json(content), payload)
    tool_calls = normalize_tool_calls(result, payload)
    if tool_calls:
        return {
            "status": "tool_calls",
            "toolCalls": tool_calls,
            "agentState": {
                "messages": state["messages"],
                "currentStep": state["currentStep"],
            },
            "agentSource": "python_customer_action_request",
        }
    return build_final_result(payload, result, state)


def run_agent(payload: Dict[str, Any]) -> Dict[str, Any]:
    if not ARK_API_KEY:
        return fallback(payload, "missing_ark_api_key")
    try:
        return run_tool_loop(payload)
    except Exception as exc:
        appointment_action = fallback_appointment_tool(payload, str(exc))
        if appointment_action:
            return appointment_action
        return fallback(payload, str(exc))


def main() -> None:
    try:
        payload = json.loads(sys.stdin.buffer.read().decode("utf-8-sig") or "{}")
        write_json(run_agent(payload))
    except Exception as exc:
        write_json({
            "answer": "客服暂时不可用，请稍后再试。",
            "intent": "general",
            "routeOrigin": "",
            "quickReplies": DEFAULT_QUICK_REPLIES,
            "toolCalls": [],
            "agentSource": "python_customer_exception",
            "agentReason": str(exc),
        })


if __name__ == "__main__":
    main()
