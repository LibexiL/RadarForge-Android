#!/usr/bin/env python3
"""Screenshots tools/glcheck/harness.html for an exported scene (needs Playwright + Chromium)."""
import sys, json, subprocess, time, os
from playwright.sync_api import sync_playwright
root, scene, out = sys.argv[1], sys.argv[2], sys.argv[3]
params = sys.argv[4] if len(sys.argv) > 4 else ""
srv = subprocess.Popen([sys.executable, "-m", "http.server", "8123", "--bind", "127.0.0.1"], cwd=root,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    time.sleep(0.8)
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"])
        pg = b.new_page(viewport={"width": 1200, "height": 1200})
        logs = []
        pg.on("console", lambda m: logs.append(m.text))
        pg.goto(f"http://127.0.0.1:8123/tools/glcheck/harness.html?scene=/{scene}/&{params}")
        pg.wait_for_function("window.result !== null", timeout=120000)
        res = pg.evaluate("window.result")
        pg.screenshot(path=out)
        print(json.dumps(res), *logs, sep="\n")
        b.close()
finally:
    srv.terminate()
