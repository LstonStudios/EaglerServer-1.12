#!/usr/bin/env python3
"""Serve the custom TNT pack with CORS for browser-based Eaglercraft clients."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

PACK_PATH = Path(__file__).resolve().parent.parent / "web" / "custom-tnt-pack.zip"


class PackHandler(BaseHTTPRequestHandler):
    def _send_headers(self, status, content_length=0):
        self.send_response(status)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Range")
        self.send_header("Access-Control-Expose-Headers", "Content-Length")
        self.send_header("Cache-Control", "no-store")
        if status == 200:
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(content_length))
        self.end_headers()

    def _serve_pack(self, include_body):
        if urlsplit(self.path).path != "/custom-tnt-pack.zip":
            self._send_headers(404)
            return

        pack = PACK_PATH.read_bytes()
        self._send_headers(200, len(pack))
        if include_body:
            self.wfile.write(pack)

    def do_GET(self):
        self._serve_pack(include_body=True)

    def do_HEAD(self):
        self._serve_pack(include_body=False)

    def do_OPTIONS(self):
        self._send_headers(204)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8082), PackHandler)
    print("CORS-enabled custom TNT pack server listening on 0.0.0.0:8082", flush=True)
    server.serve_forever()
