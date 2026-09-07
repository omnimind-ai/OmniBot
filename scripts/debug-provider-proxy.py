#!/usr/bin/env python3
"""ADB-only debug proxy for devices that cannot reach the model provider.

The Android client connects to an adb-reversed loopback port. This process
forwards the request through the development host without logging credentials
or request bodies. It is intentionally a debug tool, not a production route.
"""

from __future__ import annotations

import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from urllib.parse import urlsplit


HOP_BY_HOP_HEADERS = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}


class ProviderProxyServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, server_address: tuple[str, int], upstream: str):
        super().__init__(server_address, ProviderProxyHandler)
        parsed = urlsplit(upstream.rstrip("/"))
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("upstream must be an absolute HTTP(S) URL")
        self.upstream = parsed


class ProviderProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        if self.path == "/__health":
            payload = json.dumps({"ready": True}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self._forward()

    def do_HEAD(self) -> None:
        self._forward()

    def do_POST(self) -> None:
        self._forward()

    def do_PUT(self) -> None:
        self._forward()

    def do_DELETE(self) -> None:
        self._forward()

    def _forward(self) -> None:
        upstream = self.server.upstream
        connection_type = (
            http.client.HTTPSConnection
            if upstream.scheme == "https"
            else http.client.HTTPConnection
        )
        connection = connection_type(
            upstream.hostname,
            upstream.port,
            timeout=120,
        )
        content_length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(content_length) if content_length else None
        request_headers = {
            name: value
            for name, value in self.headers.items()
            if name.lower() not in HOP_BY_HOP_HEADERS | {"host", "content-length"}
        }
        request_headers["Host"] = upstream.netloc
        if body is not None:
            request_headers["Content-Length"] = str(len(body))
        base_path = upstream.path.rstrip("/")
        target_path = f"{base_path}{self.path}" or "/"
        try:
            connection.request(
                self.command,
                target_path,
                body=body,
                headers=request_headers,
            )
            response = connection.getresponse()
            self.send_response(response.status, response.reason)
            for name, value in response.getheaders():
                if name.lower() not in HOP_BY_HOP_HEADERS | {"content-length"}:
                    self.send_header(name, value)
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except (OSError, http.client.HTTPException) as error:
            if not self.wfile.closed:
                payload = json.dumps(
                    {
                        "error": {
                            "type": "debug_provider_proxy_error",
                            "message": str(error),
                        }
                    }
                ).encode("utf-8")
                self.send_response(502)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(payload)
        finally:
            self.close_connection = True
            connection.close()

    def log_message(self, format: str, *args: object) -> None:
        # Deliberately log method/path/status only. Headers and bodies may hold keys.
        print(f"provider-proxy {self.address_string()} {format % args}", flush=True)


def create_server(host: str, port: int, upstream: str) -> ProviderProxyServer:
    return ProviderProxyServer((host, port), upstream)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--upstream", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    server = create_server(args.host, args.port, args.upstream)
    print(
        f"provider-proxy ready http://{args.host}:{server.server_port} "
        f"-> {args.upstream}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
