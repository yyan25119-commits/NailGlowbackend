#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""Online specialist registry and deterministic supervisor policy for NailGlow."""

from __future__ import annotations

from typing import Any, Dict

import customer_service_agent


AGENT_REGISTRY = {
    "customer_agent": {
        "name": "客服接待 Agent",
        "icon": "headset",
        "description": "统一接待、上下文汇总和最终答复",
        "online": True,
    },
    "beauty_advisor_agent": {
        "name": "美甲顾问 Agent",
        "icon": "sparkles",
        "description": "款式知识、试穿评分和个性化推荐",
        "online": True,
    },
    "appointment_agent": {
        "name": "预约 Agent",
        "icon": "calendar",
        "description": "查询时段、创建预约、改约和确认状态",
        "online": True,
    },
    "aftersales_agent": {
        "name": "售后 Agent",
        "icon": "shield",
        "description": "问题分级、工单升级和人工接管",
        "online": True,
    },
    "fulfillment_agent": {
        "name": "门店履约 Agent",
        "icon": "navigation",
        "description": "多门店余位、服务时长、通勤、天气、停车和成本联合规划",
        "online": True,
    },
    "trend_agent": {
        "name": "运营趋势 Agent",
        "icon": "chart",
        "description": "离线趋势采集、热词分析、款式映射和运营建议",
        "online": False,
    },
}


SPECIALIST_CONFIG = {
    "customer_agent": {
        "mode": "general",
        "allowedTools": [],
        "instruction": (
            "负责通用接待和问题澄清，不越权执行专业业务动作。"
            "如果语义上属于款式、预约、售后或门店履约，要输出对应 intent，供 Supervisor 二次委派。"
        ),
    },
    "beauty_advisor_agent": {
        "mode": "presale",
        "allowedTools": [],
        "instruction": (
            "你是美甲顾问专家。只处理款式推荐、手型适配、肤色协调、场景和试穿结果解释；"
            "必须优先使用手型量化画像、试穿评分、真实款式候选和RAG证据，"
            "给出可继续追问和调整的2-3个具体推荐；缺少手图或偏好时只追问缺失维度。"
        ),
    },
    "appointment_agent": {
        "mode": "appointment",
        "allowedTools": ["create_or_reschedule_appointment"],
        "instruction": (
            "你是预约专家。只处理时段、排队、创建和改约；严格遵守单账号一条有效预约，"
            "涉及覆盖原预约时必须先确认。"
        ),
    },
    "aftersales_agent": {
        "mode": "aftersale",
        "allowedTools": ["update_support_case", "request_human_handoff"],
        "instruction": (
            "你是售后专家。负责问题分类、严重程度、工单升级和人工转接；"
            "不得承诺知识库没有依据的退款、赔偿或医学结论。"
        ),
    },
}


def registry_view() -> list[Dict[str, Any]]:
    return [{"key": key, **value} for key, value in AGENT_REGISTRY.items()]


def classify(payload: Dict[str, Any]) -> str:
    mode = str(payload.get("mode") or "").strip().lower()
    text = " ".join(
        str(payload.get(key) or "")
        for key in ("message", "query", "intent", "mode")
    ).lower()
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    pending_action = context.get("pendingAction") if isinstance(context.get("pendingAction"), dict) else {}
    pending_fulfillment = bool(pending_action.get("storeName") or pending_action.get("storeId"))
    pending_confirmation = any(token in text for token in (
        "确认", "同意", "可以", "就这个", "就这家", "帮我约", "自动预约", "直接预约",
        "不要了", "取消", "算了", "不去了",
    ))
    pending_follow_up = any(token in text for token in (
        "换家店", "换一家", "换个时间", "太远", "太贵", "停车", "公交", "地铁", "驾车", "走路", "几点做完",
    ))
    if pending_fulfillment and (pending_confirmation or pending_follow_up):
        return "fulfillment_agent"

    if mode in {"route", "fulfillment", "mobility"} or any(token in text for token in (
        "路线", "导航", "怎么走", "怎么去", "去哪", "去哪里", "哪家店", "附近门店", "最近的店", "停车",
        "交通", "赶得上", "几点前", "几点之后有事", "能不能做完", "店满",
    )):
        return "fulfillment_agent"
    if mode in {"appointment", "queue", "booking"} or any(token in text for token in (
        "预约", "改约", "排队", "空位", "几点能约", "到店时间",
    )):
        return "appointment_agent"
    if mode in {"aftersale", "support", "complaint"} or any(token in text for token in (
        "售后", "退款", "投诉", "意见", "反馈", "翘边", "脱落", "过敏", "不满意", "人工客服",
    )):
        return "aftersales_agent"
    if mode in {"presale", "recommendation", "beauty"} or any(token in text for token in (
        "推荐", "适合我", "显白", "手型", "肤色", "款式", "试穿", "美甲顾问",
    )):
        return "beauty_advisor_agent"

    session = context.get("agentSessionState") if isinstance(context.get("agentSessionState"), dict) else {}
    current_agent = str(session.get("currentAgent") or "customer_agent")
    continuation_tokens = {
        "beauty_advisor_agent": (
            "颜色", "款", "风格", "甲型", "短一点", "长一点", "素一点", "闪一点", "换一个", "换一款", "还有吗", "还有别的", "再来几个", "比较肉", "肉肉的", "偏白", "偏黄", "上班", "通勤", "学生", "手指", "掌宽", "骨感", "圆润", "甲床", "长度", "饰品",
        ),
        "appointment_agent": (
            "换时间", "早一点", "晚一点", "明天呢", "后天呢", "这个时间", "那个时间",
        ),
        "aftersales_agent": (
            "几天", "怎么处理", "需要拍照", "照片", "怎么补", "补一下", "还能修", "问题还在", "继续反馈",
        ),
        "fulfillment_agent": (
            "换家店", "太远", "太贵", "换交通方式", "什么时候出发", "到店几点", "能提前吗",
        ),
    }
    if current_agent in continuation_tokens and any(token in text for token in continuation_tokens[current_agent]):
        return current_agent
    return "customer_agent"


def run(agent_key: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    config = SPECIALIST_CONFIG.get(agent_key, SPECIALIST_CONFIG["customer_agent"])
    specialist_payload = dict(payload)
    specialist_payload["mode"] = config["mode"]
    specialist_payload["specialistAgent"] = agent_key
    specialist_payload["specialistInstruction"] = config["instruction"]
    specialist_payload["allowedTools"] = list(config["allowedTools"])
    result = customer_service_agent.run_agent(specialist_payload)
    if not isinstance(result, dict):
        result = {
            "answer": "当前专家暂时不可用，已交回客服接待。",
            "intent": config["mode"],
            "toolCalls": [],
        }
    result.setdefault("specialistAgent", agent_key)
    result.setdefault("agentSource", f"specialist_{agent_key}")
    return result
