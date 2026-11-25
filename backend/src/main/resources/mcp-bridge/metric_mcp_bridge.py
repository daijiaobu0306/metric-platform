#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""metric-code MCP stdio 桥接器

有些 MCP 客户端只支持 stdio（在本机拉起一个进程），不支持直接连远程 HTTP。
本脚本把 stdio 上的 JSON-RPC 消息原样转发到平台的 Streamable HTTP 端点，
让这类客户端也能用平台的只读 MCP 服务。

只依赖 Python 3.8+ 标准库，不需要 pip install，适合内网无外网环境。

客户端配置示例：
  command: python
  args: ["/绝对路径/metric_mcp_bridge.py",
         "--url", "http://<平台地址>/metric-code/mcp/alarm",
         "--token", "mcp_xxxxxxxx"]

说明：
  - stdout 只输出 JSON-RPC 消息，日志一律走 stderr，避免污染协议流；
  - 自动维护 Mcp-Session-Id（initialize 响应头下发，后续请求带上）；
  - 令牌校验失败会原样返回，客户端能看到 401 原因。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

DEFAULT_TIMEOUT = 120


def log(message):
    sys.stderr.write("[metric-mcp-bridge] " + str(message) + "\n")
    sys.stderr.flush()


def ensure_utf8():
    """Windows 控制台默认 GBK，中文会直接抛 UnicodeEncodeError。"""
    for stream in (sys.stdin, sys.stdout):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass


def parse_args():
    parser = argparse.ArgumentParser(description="metric-code MCP stdio -> HTTP 桥接器")
    parser.add_argument("--url", required=True, help="平台 MCP 端点，如 http://host/metric-code/mcp/alarm")
    parser.add_argument("--token", required=True, help="平台「MCP 管理」页签里创建的访问令牌")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT, help="单次请求超时秒数，默认 120")
    return parser.parse_args()


def parse_response_body(body, content_type):
    if not body:
        return None
    if "text/event-stream" in (content_type or ""):
        reply = None
        for line in body.splitlines():
            if line.startswith("data:"):
                payload = line[5:].strip()
                if not payload:
                    continue
                try:
                    reply = json.loads(payload)
                except json.JSONDecodeError:
                    continue
        return reply
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        log("无法解析响应：" + body[:200])
        return None


def write_message(message):
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    ensure_utf8()
    args = parse_args()
    session_id = None

    log("已启动，目标端点 " + args.url)

    while True:
        line = sys.stdin.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue

        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            log("跳过非法 JSON：" + line[:200])
            continue

        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": "Bearer " + args.token,
        }
        if session_id:
            headers["Mcp-Session-Id"] = session_id

        payload = json.dumps(message, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(args.url, data=payload, headers=headers, method="POST")

        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                new_session = response.headers.get("Mcp-Session-Id")
                if new_session:
                    session_id = new_session
                body = response.read().decode("utf-8", errors="replace")
                reply = parse_response_body(body, response.headers.get("Content-Type", ""))
                if reply is not None:
                    write_message(reply)
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            log("HTTP %s：%s" % (error.code, body[:300]))
            reply = parse_response_body(body, error.headers.get("Content-Type", ""))
            if reply is None and "id" in message:
                reply = {
                    "jsonrpc": "2.0",
                    "id": message.get("id"),
                    "error": {"code": -32000, "message": "HTTP %s：%s" % (error.code, body[:200])},
                }
            if reply is not None:
                write_message(reply)
        except Exception as error:  # 网络异常、超时等
            log("请求失败：%r" % (error,))
            if "id" in message:
                write_message({
                    "jsonrpc": "2.0",
                    "id": message.get("id"),
                    "error": {"code": -32000, "message": str(error)},
                })


if __name__ == "__main__":
    main()
