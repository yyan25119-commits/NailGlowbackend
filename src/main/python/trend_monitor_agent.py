#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from typing import Any, Dict, List

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
    os.getenv("TREND_AGENT_MODEL")
    or os.getenv("ARK_MODEL")
    or os.getenv("AI_MODEL")
    or "doubao-seed-2-0-pro-260215"
)
TIMEOUT_SECONDS = float(os.getenv("TREND_AGENT_TIMEOUT_SECONDS", "18"))


def read_payload() -> Dict[str, Any]:
    raw = sys.stdin.buffer.read().decode("utf-8")
    return json.loads(raw or "{}")


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
        self.chat = _CompatChat(base_url)


OpenAI = NativeOpenAI or CompatOpenAI


def build_client():
    kwargs = {
        "api_key": ARK_API_KEY,
        "base_url": ARK_BASE_URL,
    }
    if NativeOpenAI is not None:
        kwargs["timeout"] = TIMEOUT_SECONDS
    return OpenAI(**kwargs)


def create_chat_completion(body: Dict[str, Any]) -> Dict[str, Any]:
    request_kwargs = dict(body)
    request_kwargs["thinking"] = {"type": "disabled"}
    client = build_client()
    try:
        response = client.chat.completions.create(**request_kwargs)
    except TypeError as exc:
        if "unexpected keyword argument 'thinking'" not in str(exc):
            raise
        request_kwargs.pop("thinking", None)
        response = client.chat.completions.create(**request_kwargs)
    return normalize_response(response)


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
    return {}


def safe_list(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def top_keywords(items: List[Dict[str, Any]]) -> List[str]:
    counts: Dict[str, int] = {}
    for item in items:
        for keyword in safe_list(item.get("keywords")):
            text = str(keyword).strip()
            if text:
                counts[text] = counts.get(text, 0) + 1
    return [keyword for keyword, _ in sorted(counts.items(), key=lambda row: row[1], reverse=True)[:4]]


def fallback(payload: Dict[str, Any], reason: str = "") -> Dict[str, Any]:
    items = [item for item in safe_list(payload.get("items")) if isinstance(item, dict)]
    platforms = [item for item in safe_list(payload.get("platforms")) if isinstance(item, dict)]
    keywords = top_keywords(items)
    top_items = sorted(items, key=lambda item: float(item.get("likeCount") or 0), reverse=True)[:4]
    signals = [
        {
            "label": str(item.get("styleName") or item.get("title") or "热门款式"),
            "platform": str(item.get("platformLabel") or item.get("platform") or "站外平台"),
            "value": f"点赞 {item.get('likeText') or item.get('likeCount') or 0}",
        }
        for item in top_items
    ]
    platform_text = " / ".join(
        f"{platform.get('label', platform.get('platform', '站外平台'))} {platform.get('count', 0)} 条"
        for platform in platforms
    )
    summary = (
        f"当前站外热门样本主要集中在“{' / '.join(keywords)}”，建议优先承接高点赞的显白、清透和法式方向。"
        if keywords
        else "当前站外热门样本较少，建议先刷新抓取，再结合高点赞款做运营判断。"
    )
    actions = []
    if keywords:
        actions.append(f"先上架点赞最高的“{keywords[0]}”方向款式，并把同风格款前置到试穿推荐位。")
    if top_items:
        actions.append("把站外点赞最高的 3 款做成专题卡片，搭配返图和预约入口承接转化。")
    if platform_text:
        actions.append(f"当前采集覆盖：{platform_text}，后续可继续补充更多平台来源。")
    if not actions:
        actions.append("先完成一次真实抓取，后台 AI Agent 才能输出稳定策略。")
    operation_script = (
        f"建议本周围绕“{'、'.join(keywords[:3]) if keywords else '高点赞热门款'}”做首页专题上新，"
        f"把点赞最高的 {min(3, len(top_items)) or 1} 款优先放进试穿推荐位，并在客服推荐话术里突出“显白、好搭配、拍照出片”。"
        f"对预约用户可引导选择同风格衍生款，形成从站外热度到站内试穿、再到预约下单的一条承接链路。"
    )
    return {
        "summary": summary,
        "signals": signals,
        "actions": actions[:3],
        "operationScript": operation_script,
        "agentSource": "python_trend_monitor_fallback",
        "agentReason": reason,
    }


def fallback_keyword_plan(payload: Dict[str, Any], reason: str = "") -> Dict[str, Any]:
    styles = [item for item in safe_list(payload.get("styles")) if isinstance(item, dict)]
    queries: List[str] = []
    for style in styles[:10]:
        name = str(style.get("name") or "").strip()
        if name:
            queries.append(name)
        for tag in safe_list(style.get("tags")):
            text = str(tag).strip()
            if text:
                queries.append(f"{text}美甲")
        if len(queries) >= 8:
            break
    if not queries:
        queries = ["显白美甲", "法式美甲", "猫眼美甲", "夏日美甲", "短甲美甲", "裸粉美甲"]
    deduped: List[str] = []
    for query in queries:
        if query not in deduped:
            deduped.append(query)
    return {
        "queries": deduped[:8],
        "agentSource": "python_trend_keyword_fallback",
        "agentReason": reason,
    }


def ai_analyze(payload: Dict[str, Any]) -> Dict[str, Any]:
    items = [item for item in safe_list(payload.get("items")) if isinstance(item, dict)]
    if not items:
        return fallback(payload, "no_items")

    sample = []
    for item in items[:10]:
        sample.append(
            {
                "platform": item.get("platformLabel") or item.get("platform"),
                "styleName": item.get("styleName"),
                "title": item.get("title"),
                "authorName": item.get("authorName"),
                "keywords": safe_list(item.get("keywords")),
                "likeCount": item.get("likeCount"),
                "likeText": item.get("likeText"),
                "heatScore": item.get("heatScore"),
            }
        )

    system_prompt = (
        "你是美甲平台运营分析助手。请根据站外热门美甲样本，输出适合后台运营直接执行的中文 JSON。"
        "你必须只输出 JSON，不要输出 Markdown。"
    )
    user_prompt = {
        "task": "分析站外热门美甲，生成摘要、关键信号、运营动作和一段完整运营话术。",
        "requirements": {
            "summary": "1 句中文摘要，点出当前最热风格和运营重点",
            "signals": "3 到 4 条信号，每条包含 label、platform、value",
            "actions": "3 条可执行运营策略，要具体，不空泛",
            "operationScript": "1 段完整中文运营话术，120 到 180 字，可直接给商家用于首页专题说明、客服推荐或活动预告",
        },
        "samples": sample,
        "platforms": payload.get("platforms", []),
    }

    body = {
        "model": ARK_MODEL,
        "temperature": 0.4,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(user_prompt, ensure_ascii=False)},
        ],
    }

    response = create_chat_completion(body)
    choices = safe_list(response.get("choices"))
    message = choices[0].get("message", {}) if choices else {}
    content = message.get("content", "") if isinstance(message, dict) else ""
    parsed = extract_json(content)
    if not parsed.get("summary"):
        return fallback(payload, "invalid_ai_response")
    parsed["signals"] = safe_list(parsed.get("signals"))[:4]
    parsed["actions"] = [str(item) for item in safe_list(parsed.get("actions"))[:3]]
    parsed["operationScript"] = str(parsed.get("operationScript") or "").strip()
    if not parsed["operationScript"]:
        return fallback(payload, "missing_operation_script")
    parsed["agentSource"] = "python_trend_monitor_ai"
    return parsed


def ai_keyword_plan(payload: Dict[str, Any]) -> Dict[str, Any]:
    styles = [item for item in safe_list(payload.get("styles")) if isinstance(item, dict)]
    if not styles:
        return fallback_keyword_plan(payload, "no_styles")

    style_seed = []
    for item in styles[:12]:
        style_seed.append(
            {
                "name": item.get("name"),
                "tags": safe_list(item.get("tags")),
                "tryCount": item.get("tryCount"),
            }
        )

    system_prompt = (
        "你是美甲平台趋势搜索 Agent。你的任务不是直接搜索，而是先根据店铺已有款式、季节和社交平台语言，生成下一轮抓取要用的中文搜索词。"
        "你必须只输出 JSON，不要输出 Markdown。"
    )
    user_prompt = {
        "task": "生成小红书热门美甲抓取关键词",
        "requirements": {
            "queries": "返回 6 到 8 条中文搜索词，必须是适合小红书真实搜索的词，不要太长，不要解释",
            "style": "优先结合店铺现有高热款、季节感、显白/法式/猫眼/短甲等平台常见表达",
        },
        "shopStyles": style_seed,
    }
    body = {
        "model": ARK_MODEL,
        "temperature": 0.5,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(user_prompt, ensure_ascii=False)},
        ],
    }
    response = create_chat_completion(body)
    choices = safe_list(response.get("choices"))
    message = choices[0].get("message", {}) if choices else {}
    content = message.get("content", "") if isinstance(message, dict) else ""
    parsed = extract_json(content)
    queries = [str(item).strip() for item in safe_list(parsed.get("queries")) if str(item).strip()]
    if not queries:
        return fallback_keyword_plan(payload, "invalid_keyword_plan")
    deduped: List[str] = []
    for query in queries:
        if query not in deduped:
            deduped.append(query)
    return {
        "queries": deduped[:8],
        "agentSource": "python_trend_keyword_ai",
    }


def main() -> None:
    payload = read_payload()
    mode = str(payload.get("mode") or "analyze")
    if not ARK_API_KEY:
        if mode == "keyword_plan":
            write_json(fallback_keyword_plan(payload, "missing_api_key"))
        else:
            write_json(fallback(payload, "missing_api_key"))
        return
    try:
        if mode == "keyword_plan":
            write_json(ai_keyword_plan(payload))
        else:
            write_json(ai_analyze(payload))
    except Exception as exc:
        if mode == "keyword_plan":
            write_json(fallback_keyword_plan(payload, str(exc)))
        else:
            write_json(fallback(payload, str(exc)))


if __name__ == "__main__":
    main()
