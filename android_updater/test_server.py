#!/usr/bin/env python3
"""Serwer testowy wbudowanego updatera: udaje GitHub Releases API.

Podaje plik JSON w formacie GitHub Releases (tag_name, assets[].name/size/digest/
browser_download_url) oraz plik APK. Obsługuje nagłówek Range -- python3 -m
http.server go ignoruje, więc bez tego serwera nie da się przetestować
wznawiania pobierania.

Użycie:
    python3 android_updater/test_server.py <plik.apk> --tag <wersja>-<N> [--port 8099]

Na urządzeniu ma działać APK zbudowany z
    ./build_android.sh --update-api-url http://127.0.0.1:8099/latest.json
a port serwera trzeba mu udostępnić po USB:
    adb reverse tcp:8099 tcp:8099

--tag to tag "wydania", które serwer ogłasza; updater zainstaluje podany plik,
jeśli ten tag jest nowszy od tagu wpieczonego w działającą przeglądarkę.
W JSON plik nazywa się <tag>-arm64-v8a.apk (po tym sufiksie updater go
rozpoznaje).
"""
import argparse
import hashlib
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    # Ustawiane przez main().
    apk_bytes = b""
    apk_name = ""
    tag = ""
    port = 8099

    def log_message(self, fmt, *args):  # cichy, ale z tagiem do logcat
        sys.stderr.write("[test-server] %s\n" % (fmt % args))

    def _send(self, code, body, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/latest.json", "/latest"):
            digest = hashlib.sha256(self.apk_bytes).hexdigest()
            obj = {
                "tag_name": self.tag,
                "body": "",
                "assets": [{
                    "name": self.apk_name,
                    "size": len(self.apk_bytes),
                    "digest": "sha256:" + digest,
                    "browser_download_url":
                        "http://127.0.0.1:%d/%s" % (self.port, self.apk_name),
                }],
            }
            self._send(200, json.dumps(obj).encode())
            return
        if path == "/" + self.apk_name:
            self._serve_apk()
            return
        self._send(404, b"{}", ctype="application/json")

    def _serve_apk(self):
        data = self.apk_bytes
        total = len(data)
        start = 0
        end = total - 1
        rng = self.headers.get("Range")
        if rng:
            # "bytes=N-"
            spec = rng.split("=")[-1]
            lo, _, hi = spec.partition("-")
            if lo:
                start = int(lo)
            if hi:
                end = int(hi)
            end = min(end, total - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % total)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, total))
        else:
            self.send_response(200)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        self.wfile.write(data[start:end + 1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", help="ścieżka do pliku .apk")
    ap.add_argument("--tag", required=True, help="tag ogłaszanego wydania, np. 157.0.8103.78-2")
    ap.add_argument("--port", type=int, default=8099)
    args = ap.parse_args()

    with open(args.apk, "rb") as f:
        apk_bytes = f.read()
    apk_name = args.tag + "-arm64-v8a.apk"

    Handler.apk_bytes = apk_bytes
    Handler.apk_name = apk_name
    Handler.tag = args.tag
    Handler.port = args.port

    print("[test-server] tag=%s apk=%s (%d B)" % (args.tag, apk_name, len(apk_bytes)))
    print("[test-server] digest=sha256:%s" % hashlib.sha256(apk_bytes).hexdigest())
    print("[test-server] nasłuchuję na 127.0.0.1:%d" % args.port)
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
