#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List

try:
    from websocket import create_connection
except ModuleNotFoundError as exc:
    raise SystemExit(
        "Missing dependency: websocket-client. Install it with `pip install websocket-client`."
    ) from exc

HTTP_TIMEOUT_SECONDS = int(os.environ.get("XHS_HTTP_TIMEOUT_SECONDS", "20"))
CDP_TIMEOUT_SECONDS = int(os.environ.get("XHS_CDP_TIMEOUT_SECONDS", "25"))
BROWSER_START_TIMEOUT_MS = int(os.environ.get("XHS_BROWSER_START_TIMEOUT_MS", "30000"))


def read_stdin() -> Dict[str, Any]:
    raw = sys.stdin.buffer.read().decode("utf-8")
    return json.loads(raw or "{}")


def write_json(payload: Dict[str, Any]) -> None:
    sys.stdout.buffer.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))


def delay(milliseconds: int) -> None:
    time.sleep(milliseconds / 1000)


def http_get_json(url: str) -> Any:
    with urllib.request.urlopen(url, timeout=HTTP_TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def http_get_text(url: str) -> str:
    with urllib.request.urlopen(url, timeout=HTTP_TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8")


def wait_for_browser(debug_port: int, timeout_ms: int = BROWSER_START_TIMEOUT_MS) -> bool:
    deadline = time.time() + timeout_ms / 1000
    while time.time() < deadline:
        try:
            version = http_get_json(f"http://127.0.0.1:{debug_port}/json/version")
            if isinstance(version, dict) and version.get("webSocketDebuggerUrl"):
                return True
        except Exception:
            pass
        delay(300)
    return False


def get_page_target(debug_port: int) -> Dict[str, Any] | None:
    tabs = http_get_json(f"http://127.0.0.1:{debug_port}/json/list")
    if isinstance(tabs, list):
        for tab in tabs:
            if isinstance(tab, dict) and tab.get("type") == "page" and tab.get("webSocketDebuggerUrl"):
                return tab
    try:
        target = http_get_json(
            f"http://127.0.0.1:{debug_port}/json/new?{urllib.parse.quote('about:blank')}"
        )
        if isinstance(target, dict) and target.get("webSocketDebuggerUrl"):
            return target
    except Exception:
        pass
    refreshed = http_get_json(f"http://127.0.0.1:{debug_port}/json/list")
    if isinstance(refreshed, list):
        for tab in refreshed:
            if isinstance(tab, dict) and tab.get("type") == "page":
                return tab
    return None


def close_target(debug_port: int, target_id: str) -> None:
    if not target_id:
        return
    try:
        http_get_text(f"http://127.0.0.1:{debug_port}/json/close/{target_id}")
    except Exception:
        pass


class CDPSession:
    def __init__(self, websocket_url: str) -> None:
        self.websocket_url = websocket_url
        self.ws = None
        self.seq = 0

    def __enter__(self) -> "CDPSession":
        self.ws = create_connection(
            self.websocket_url,
            timeout=CDP_TIMEOUT_SECONDS,
            enable_multithread=False,
            suppress_origin=True,
        )
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self.ws is not None:
            try:
                self.ws.close()
            except Exception:
                pass

    def send(self, method: str, params: Dict[str, Any] | None = None) -> Dict[str, Any]:
        if self.ws is None:
            raise RuntimeError("CDP websocket is not connected")
        self.seq += 1
        payload = {
            "id": self.seq,
            "method": method,
            "params": params or {},
        }
        self.ws.send(json.dumps(payload, ensure_ascii=False))
        while True:
            message = json.loads(self.ws.recv())
            if message.get("id") != self.seq:
                continue
            if message.get("error"):
                raise RuntimeError(json.dumps(message["error"], ensure_ascii=False))
            return message


def with_page_session(debug_port: int, task):
    page = get_page_target(debug_port)
    if not page or not page.get("webSocketDebuggerUrl"):
        raise RuntimeError("No Chrome page target found")
    websocket_url = str(page["webSocketDebuggerUrl"])
    with CDPSession(websocket_url) as cdp:
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        cdp.send("Network.enable")
        return task(cdp, page)


def evaluate_page_state(cdp: CDPSession) -> Dict[str, Any]:
    response = cdp.send(
        "Runtime.evaluate",
        {
            "expression": """(() => ({
              title: document.title || '',
              url: location.href || '',
              bodyText: (document.body?.innerText || '').slice(0, 1200),
              noteItems: document.querySelectorAll('section.note-item, .note-item').length,
              exploreLinks: document.querySelectorAll('a[href*="/explore/"]').length
            }))()""",
            "returnByValue": True,
        },
    )
    value = response.get("result", {}).get("result", {}).get("value", {})
    return value if isinstance(value, dict) else {}


def xhs_error_from_url(url: str) -> str:
    try:
        parsed = urllib.parse.urlparse(url)
        query = urllib.parse.parse_qs(parsed.query)
        message = (query.get("error_msg") or [""])[0]
        code = (query.get("error_code") or [""])[0]
        if message and code:
            return f"{message}（错误码 {code}）"
        return message or (f"错误码 {code}" if code else "")
    except Exception:
        return ""


def raise_if_page_blocked(state: Dict[str, Any]) -> None:
    title = str(state.get("title") or "")
    url = str(state.get("url") or "")
    body_text = str(state.get("bodyText") or "")
    combined = f"{title}\n{url}\n{body_text}"

    if "website-login/error" in url or "安全限制" in combined or "IP存在风险" in combined:
        detail = xhs_error_from_url(url) or "IP存在风险，请切换可靠网络环境后重试"
        raise RuntimeError(f"小红书安全限制：{detail}")
    if "验证码" in combined or "扫码验证" in combined or "安全验证" in combined:
        raise RuntimeError("小红书要求完成验证码或安全验证，请在登录浏览器中处理后重新保存登录态")
    if "扫码登录" in combined or "请先登录" in combined or "登录后查看更多" in combined:
        raise RuntimeError("小红书登录态不可用，请重新打开登录浏览器登录并保存登录态")


def parse_like_count(value: Any) -> int:
    text = str(value or "").strip().lower()
    if not text:
        return 0
    normalized = text.replace(",", "")
    if normalized.endswith("万"):
        try:
            return round(float(normalized[:-1]) * 10000)
        except ValueError:
            return 0
    if normalized.endswith("k"):
        try:
            return round(float(normalized[:-1]) * 1000)
        except ValueError:
            return 0
    try:
        return round(float(normalized))
    except ValueError:
        return 0


def title_looks_useful(title: str) -> bool:
    text = str(title or "").strip()
    if not text:
        return False
    keywords = ("美甲", "法式", "猫眼", "渐变", "裸", "冰透", "珍珠", "格纹", "香槟", "短甲", "显白", "千金", "多巴胺", "爱心", "酒红", "车厘子", "夏天", "夏日")
    return any(token in text for token in keywords)


def normalize_cookie(cookie: Dict[str, Any]) -> Dict[str, Any]:
    normalized = {
        "name": cookie.get("name"),
        "value": cookie.get("value"),
        "domain": cookie.get("domain"),
        "path": cookie.get("path") or "/",
        "secure": bool(cookie.get("secure")),
        "httpOnly": bool(cookie.get("httpOnly")),
    }
    expires = cookie.get("expires")
    if isinstance(expires, (int, float)) and expires > 0:
        normalized["expires"] = expires
    same_site = cookie.get("sameSite")
    if same_site and same_site != "None":
        normalized["sameSite"] = same_site
    return normalized


def export_session(debug_port: int, session_state_path: str) -> Dict[str, Any]:
    def task(cdp: CDPSession, _page: Dict[str, Any]) -> Dict[str, Any]:
        response = cdp.send(
            "Network.getCookies",
            {"urls": ["https://www.xiaohongshu.com/", "https://edith.xiaohongshu.com/"]},
        )
        cookies = [
            normalize_cookie(cookie)
            for cookie in response.get("result", {}).get("cookies", [])
            if isinstance(cookie, dict)
        ]
        return {
            "exportedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "cookies": cookies,
        }

    result = with_page_session(debug_port, task)
    if session_state_path:
        session_path = Path(session_state_path)
        session_path.parent.mkdir(parents=True, exist_ok=True)
        session_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def read_session_state(session_state_path: str) -> Dict[str, Any]:
    if not session_state_path:
        return {"cookies": []}
    path = Path(session_state_path)
    if not path.exists():
        return {"cookies": []}
    return json.loads(path.read_text(encoding="utf-8") or "{}")


def inject_session(debug_port: int, session_state: Dict[str, Any]) -> None:
    cookies = session_state.get("cookies")
    if not isinstance(cookies, list) or not cookies:
        return

    def task(cdp: CDPSession, _page: Dict[str, Any]) -> bool:
        cdp.send("Network.clearBrowserCookies")
        payload = []
        for cookie in cookies:
            if not isinstance(cookie, dict):
                continue
            row = {
                "name": cookie.get("name"),
                "value": cookie.get("value"),
                "domain": cookie.get("domain"),
                "path": cookie.get("path") or "/",
                "secure": bool(cookie.get("secure")),
                "httpOnly": bool(cookie.get("httpOnly")),
            }
            if isinstance(cookie.get("expires"), (int, float)):
                row["expires"] = cookie["expires"]
            if cookie.get("sameSite"):
                row["sameSite"] = cookie["sameSite"]
            payload.append(row)
        cdp.send("Network.setCookies", {"cookies": payload})
        cdp.send("Page.navigate", {"url": "https://www.xiaohongshu.com/explore"})
        delay(3500)
        return True

    with_page_session(debug_port, task)


def collect_query(debug_port: int, query: str) -> List[Dict[str, Any]]:
    def task(cdp: CDPSession, _page: Dict[str, Any]) -> List[Dict[str, Any]]:
        search_url = (
            "https://www.xiaohongshu.com/search_result?"
            f"keyword={urllib.parse.quote(query)}&source=web_explore_feed&type=51"
        )
        cdp.send("Page.navigate", {"url": search_url})
        delay(5200)
        cdp.send(
            "Runtime.evaluate",
            {"expression": "window.scrollTo(0, Math.max(760, document.body.scrollHeight * 0.45));"},
        )
        delay(1400)
        raise_if_page_blocked(evaluate_page_state(cdp))
        response = cdp.send(
            "Runtime.evaluate",
            {
                "expression": """(() => {
                  const cards = Array.from(document.querySelectorAll('section.note-item, .note-item'));
                  return cards.map((card) => {
                    const text = (card.innerText || '').split('\\n').map((line) => line.trim()).filter(Boolean);
                    const link = card.querySelector('a[href*="/explore/"]');
                    const image = card.querySelector('img');
                    return {
                      title: text[0] || (image ? image.alt || '' : ''),
                      authorName: text[1] || '',
                      noteDate: text.length >= 3 ? text[text.length - 2] : '',
                      likeText: text.length >= 2 ? text[text.length - 1] : '',
                      sourceUrl: link ? link.href : '',
                      imageUrl: image ? (image.currentSrc || image.src || '') : '',
                    };
                  });
                })()""",
                "returnByValue": True,
            },
        )
        items = response.get("result", {}).get("result", {}).get("value", [])
        normalized: List[Dict[str, Any]] = []
        if not isinstance(items, list):
            return normalized
        for item in items:
            if not isinstance(item, dict):
                continue
            title = str(item.get("title") or "")
            if not title_looks_useful(title):
                continue
            normalized.append(
                {
                    **item,
                    "likeCount": parse_like_count(item.get("likeText")),
                    "queryKeyword": query,
                }
            )
        return normalized

    return with_page_session(debug_port, task)


def launch_chrome(chrome_executable: str, debug_port: int, user_data_dir: str, headless: bool) -> subprocess.Popen:
    args = [
        chrome_executable,
        f"--remote-debugging-port={debug_port}",
        f"--user-data-dir={user_data_dir}",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-background-networking",
        "--disable-sync",
        "--hide-crash-restore-bubble",
        "--disable-features=Translate,MediaRouter",
        "--remote-allow-origins=*",
    ]
    if os.name != "nt":
        args.append("--disable-dev-shm-usage")
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            args.append("--no-sandbox")
    if headless:
        args.extend(["--headless=new", "--disable-gpu"])
    else:
        args.append("--new-window")
    args.append("about:blank")
    kwargs: Dict[str, Any] = {
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.DEVNULL,
        "stderr": subprocess.DEVNULL,
    }
    if os.name == "nt":
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return subprocess.Popen(args, **kwargs)


def kill_process_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        return
    process.kill()


def ensure_browser_for_collection(payload: Dict[str, Any]) -> Dict[str, Any]:
    debug_port = int(payload.get("debugPort") or 9233)
    session_state_path = str(payload.get("sessionStatePath") or "")
    temp_user_data_dir = str(payload.get("tempUserDataDir") or "")
    chrome_executable = str(payload.get("chromeExecutable") or "")
    headless = bool(payload.get("headless", True))

    if wait_for_browser(debug_port, 1200):
        inject_session(debug_port, read_session_state(session_state_path))
        return {
            "debugPort": debug_port,
            "launchedProcess": None,
            "launchedTargetId": None,
            "launchedUserDataDir": None,
        }

    if not chrome_executable:
        raise RuntimeError("Missing chromeExecutable")

    temp_root = Path(temp_user_data_dir) if temp_user_data_dir else Path.cwd() / "runtime" / "trend-agent-headless-profile"
    temp_root.mkdir(parents=True, exist_ok=True)
    for stale in temp_root.glob("session-*"):
        shutil.rmtree(stale, ignore_errors=True)
    launched_user_data_dir = tempfile.mkdtemp(prefix="session-", dir=str(temp_root))
    launched_process = launch_chrome(chrome_executable, debug_port, launched_user_data_dir, headless)

    ready = wait_for_browser(debug_port, BROWSER_START_TIMEOUT_MS)
    if not ready:
        launched_process.kill()
        raise RuntimeError("Chrome did not start in time")

    inject_session(debug_port, read_session_state(session_state_path))
    target = get_page_target(debug_port) or {}
    return {
        "debugPort": debug_port,
        "launchedProcess": launched_process,
        "launchedTargetId": target.get("id"),
        "launchedUserDataDir": launched_user_data_dir,
    }


def collect(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    debug_port = int(payload.get("debugPort") or 9233)
    session_state_path = str(payload.get("sessionStatePath") or "")
    raw_queries = payload.get("queries")
    queries = raw_queries if isinstance(raw_queries, list) and raw_queries else ["显白美甲", "法式美甲", "猫眼美甲", "美甲"]
    target_count = int(payload.get("targetCount") or 10)
    session = ensure_browser_for_collection(payload)
    seen: Dict[str, Dict[str, Any]] = {}

    try:
        for query in queries:
            rows = collect_query(debug_port, str(query))
            for row in rows:
                source_url = str(row.get("sourceUrl") or "")
                image_url = str(row.get("imageUrl") or "")
                if not source_url or not image_url:
                    continue
                existing = seen.get(source_url)
                if existing is None or int(row.get("likeCount") or 0) > int(existing.get("likeCount") or 0):
                    seen[source_url] = row
            if len(seen) >= target_count * 2:
                break

        if session_state_path and wait_for_browser(debug_port, 1000):
            export_session(debug_port, session_state_path)

        ranked = sorted(seen.values(), key=lambda item: int(item.get("likeCount") or 0), reverse=True)
        return ranked[:target_count]
    finally:
        if session.get("launchedTargetId"):
            close_target(debug_port, str(session["launchedTargetId"]))
        launched_process = session.get("launchedProcess")
        if launched_process is not None:
            try:
                kill_process_tree(launched_process)
            except Exception:
                pass
        launched_user_data_dir = session.get("launchedUserDataDir")
        if launched_user_data_dir:
            shutil.rmtree(str(launched_user_data_dir), ignore_errors=True)


def main() -> None:
    payload = read_stdin()
    action = str(payload.get("action") or "collect")

    if action == "export_session":
        result = export_session(int(payload.get("debugPort") or 9233), str(payload.get("sessionStatePath") or ""))
        write_json(result)
        return

    if action == "collect":
        items = collect(payload)
        write_json({"items": items})
        return

    raise RuntimeError(f"Unsupported action: {action}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as exc:
        sys.stderr.write(str(exc))
        sys.exit(1)
    except Exception as exc:
        sys.stderr.write(str(exc))
        sys.exit(1)
