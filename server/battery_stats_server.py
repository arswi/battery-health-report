#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
电池健康报告 · 群体数据统计服务（单文件，零第三方依赖）
====================================================

只用 Python 标准库（http.server + sqlite3 + statistics），有 Python 3.8+ 就能跑。
数据量在"几百台设备、几万条采样"以内完全够用；再大就换 FastAPI + PostgreSQL。

启动：
    python battery_stats_server.py            # 监听 0.0.0.0:8300
    PORT=9000 python battery_stats_server.py  # 自定义端口
    API_KEY=某密钥 python battery_stats_server.py  # 给上传/统计加一道口令（可选）

数据库文件 data.db 自动建在脚本同目录；claims.json 是官方寿命表（在线核对数据源），
改它不用重启服务。

接口（App 端 v4.2 已能直接消费第 1 个；其余给网页/后续 App 版本用）：
  GET  /                    健康检查
  GET  /api/baseline        群体基准曲线 + 官方寿命表（App「联机数据地址」填这个）
  POST /api/reports         上传一条匿名采样记录（需要 device_id，见下）
  GET  /api/stats           全部机型概览（每机型样本数/设备数/到 80% 循环中位数）
  GET  /api/stats/<model>   单机型聚合：健康度-循环群体曲线 + 到 80% 循环分布

隐私红线（写死在代码里）：
  1. 只收白名单字段，收到 IMEI/序列号字段直接丢弃并记警告；
  2. device_id 由用户自己生成的随机串（App 端未来会上报设置项），服务端无法还原设备；
  3. 不记上传来源 IP 到数据库。
"""
import json
import os
import re
import sqlite3
import statistics
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

PORT = int(os.environ.get("PORT", "8300"))
API_KEY = os.environ.get("API_KEY", "")          # 空 = 不校验
DB_PATH = Path(__file__).parent / "data.db"
CLAIMS_PATH = Path(__file__).parent / "claims.json"

# ---------------------------------------------------------------- 数据库

SCHEMA = """
CREATE TABLE IF NOT EXISTS reports (
    device_id TEXT NOT NULL,          -- 用户自选随机串，不是设备标识
    model     TEXT NOT NULL,          -- ro.product.model 小写，机型分组主键
    market    TEXT,
    ts        INTEGER NOT NULL,       -- 报告生成时间 epoch ms
    cycle     INTEGER,
    health    REAL,
    actual_mah REAL,
    design_mah REAL,
    level     INTEGER,
    temp_c    REAL,
    source    TEXT,
    PRIMARY KEY (device_id, ts)
);
CREATE INDEX IF NOT EXISTS idx_model ON reports (model, ts);
"""

_db_lock = threading.Lock()


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), timeout=15)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with _db_lock, db() as conn:
        conn.executescript(SCHEMA)


# ---------------------------------------------------------------- 上传字段白名单

ALLOWED_FIELDS = {
    "device_id", "model", "market", "ts", "cycle", "health",
    "actual_mah", "design_mah", "level", "temp_c", "source",
}
FORBIDDEN_KEYS = re.compile(r"imei|serial|meid|mac|phone|imsi", re.I)


def sanitize(payload: dict):
    """白名单过滤；带设备标识嫌疑的字段整条丢弃。返回 (干净数据, 警告列表)。"""
    warns = []
    for k in payload:
        if FORBIDDEN_KEYS.search(str(k)):
            warns.append(f"疑似设备标识字段 {k} 已拒绝")
    if warns:
        return None, warns
    out = {k: payload.get(k) for k in ALLOWED_FIELDS if k in payload}
    if not out.get("device_id") or not out.get("model") or "ts" not in out:
        return None, ["缺少 device_id / model / ts 必填字段"]
    out["model"] = str(out["model"]).strip().lower()
    try:
        out["ts"] = int(out["ts"])
    except (TypeError, ValueError):
        return None, ["ts 必须是整数（epoch ms）"]
    if not (1_000_000_000_000 <= out["ts"] <= 4_102_444_800_000):
        return None, ["ts 不像 epoch 毫秒"]
    for k in ("cycle", "level"):
        if out.get(k) is not None:
            out[k] = int(out[k])
    for k in ("health", "actual_mah", "design_mah", "temp_c"):
        if out.get(k) is not None:
            out[k] = float(out[k])
    return out, []


# ---------------------------------------------------------------- 统计

def fit_cycles_to_80(points):
    """一台设备的多点 (cycle, health) 线性拟合 → 外推健康度到 80% 的循环数。
    points 需 ≥2 个点、循环跨度 ≥10、斜率为负才可信。"""
    pts = sorted((c, h) for c, h in points if c is not None and h is not None)
    if len(pts) < 2 or pts[-1][0] - pts[0][0] < 10:
        return None
    n = len(pts)
    sx = sum(p[0] for p in pts)
    sy = sum(p[1] for p in pts)
    sxx = sum(p[0] * p[0] for p in pts)
    sxy = sum(p[0] * p[1] for p in pts)
    denom = n * sxx - sx * sx
    if denom == 0:
        return None
    slope = (n * sxy - sx * sy) / denom
    if slope >= 0:          # 没有下降趋势，推不出 80% 点
        return None
    intercept = (sy - slope * sx) / n
    x80 = (80.0 - intercept) / slope
    if 0 < x80 < 20000:
        return x80
    return None


def quantiles(vals):
    if not vals:
        return {}
    q = statistics.quantiles(vals, n=4) if len(vals) >= 2 else [vals[0]] * 3
    return {
        "median": round(statistics.median(vals), 1),
        "p25": round(q[0], 1),
        "p75": round(q[2], 1),
        "n": len(vals),
    }


def model_stats(model: str):
    with db() as conn:
        rows = conn.execute(
            "SELECT device_id, cycle, health, actual_mah, design_mah, ts "
            "FROM reports WHERE model = ? ORDER BY ts", (model,)
        ).fetchall()
    if not rows:
        return None

    by_dev = {}
    for r in rows:
        by_dev.setdefault(r["device_id"], []).append(r)

    # 每台设备各自拟合到 80% 的循环数
    fits = []
    for dev_rows in by_dev.values():
        x80 = fit_cycles_to_80([(r["cycle"], r["health"]) for r in dev_rows])
        if x80 is not None:
            fits.append(x80)

    # 群体曲线：按循环数分桶（每 100 次），桶内取健康度中位数
    bins = {}
    for r in rows:
        if r["cycle"] is None or r["health"] is None:
            continue
        bins.setdefault(r["cycle"] // 100, []).append(r["health"])
    curve = [[b * 100 + 50, round(statistics.median(hs), 2)]
             for b, hs in sorted(bins.items()) if len(hs) >= 2]

    return {
        "model": model,
        "devices": len(by_dev),
        "samples": len(rows),
        "curve": curve,
        "cycles_to_80": quantiles(fits),
    }


def all_models():
    with db() as conn:
        rows = conn.execute(
            "SELECT model, COUNT(*) AS n, COUNT(DISTINCT device_id) AS d "
            "FROM reports GROUP BY model ORDER BY n DESC"
        ).fetchall()
    out = []
    for r in rows:
        s = model_stats(r["model"]) or {}
        out.append({
            "model": r["model"],
            "market": s.get("market"),
            "devices": r["d"],
            "samples": r["n"],
            "cycles_to_80": s.get("cycles_to_80", {}),
        })
    return out


def population_curve():
    """全机型(锂电通用)健康度-循环群体曲线，给 App 做基准。样本不足时用内置典型值。"""
    with db() as conn:
        rows = conn.execute(
            "SELECT cycle, health FROM reports "
            "WHERE cycle IS NOT NULL AND health IS NOT NULL"
        ).fetchall()
    if len(rows) < 200:
        return [[0, 100], [100, 97.5], [300, 93], [500, 88], [800, 80], [1000, 74]]
    bins = {}
    for r in rows:
        bins.setdefault(r["cycle"] // 100, []).append(r["health"])
    curve = [[b * 100 + 50, round(statistics.median(hs), 2)]
             for b, hs in sorted(bins.items()) if len(hs) >= 5]
    return curve if len(curve) >= 4 else [[0, 100], [800, 80]]


def load_claims():
    """官方寿命表。claims.json 不存在时用内置核实表（与 App v4.2 内置一致）。"""
    if CLAIMS_PATH.exists():
        try:
            return json.loads(CLAIMS_PATH.read_text(encoding="utf-8"))
        except Exception:
            pass
    return [
        ["xiaomi\\s*14\\s*ultra|aurora", 1600, "小米官方发布 2024-02（金沙江电池首发）"],
        ["xiaomi\\s*15(?:$|\\s)|dada|haotian|odin|24129pn0", 1600, "小米官方规格页 2024-10（全系金沙江电池）"],
        ["xiaomi\\s*17(?:$|\\s)", 1600, "小米官网规格页（17 系列）"],
        ["xiaomi\\s*17t", 1600, "小米官方 2026-06（17T 系列发布）"],
        ["redmi\\s*note\\s*14\\s*pro", 1600, "Redmi 官网 · TÜV 南德长寿命认证"],
        ["redmi\\s*turbo\\s*4", 1600, "REDMI 官方发布 2025-01"],
        ["redmi\\s*k80(?:$|\\s)", 1600, "REDMI 官方发布 2024-11"],
    ]


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "BatteryStats/1.0"

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.address_string(), fmt % args), flush=True)

    # ---- 工具 ----
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _json(self, code: int, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        if not API_KEY:
            return True
        return self.headers.get("X-API-Key", "") == API_KEY

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > 64 * 1024:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return {}

    # ---- 路由 ----
    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        try:
            if path == "/":
                self._json(200, {"ok": True, "service": "battery-stats", "version": 1})
            elif path == "/api/baseline":
                self._json(200, {
                    "points": population_curve(),
                    "claims": load_claims(),
                    "note": "群体曲线样本不足时使用内置典型值；claims 为厂商官方宣称循环寿命",
                })
            elif path == "/api/stats":
                if not self._authorized():
                    self._json(403, {"error": "需要 X-API-Key"})
                    return
                self._json(200, {"models": all_models()})
            elif path.startswith("/api/stats/"):
                if not self._authorized():
                    self._json(403, {"error": "需要 X-API-Key"})
                    return
                model = path[len("/api/stats/"):].strip().lower()
                s = model_stats(model)
                self._json(200, s if s else {"error": "该机型还没有数据"})
            else:
                self._json(404, {"error": "not found"})
        except Exception as e:  # noqa: BLE001
            self._json(500, {"error": str(e)})

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        if path != "/api/reports":
            self._json(404, {"error": "not found"})
            return
        if not self._authorized():
            self._json(403, {"error": "需要 X-API-Key"})
            return
        data, warns = sanitize(self._body())
        if data is None:
            self._json(400, {"ok": False, "errors": warns})
            return
        try:
            with _db_lock, db() as conn:
                cur = conn.execute(
                    "INSERT OR IGNORE INTO reports "
                    "(device_id, model, market, ts, cycle, health, actual_mah, design_mah, "
                    " level, temp_c, source) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (data["device_id"], data["model"], data.get("market"), data["ts"],
                     data.get("cycle"), data.get("health"), data.get("actual_mah"),
                     data.get("design_mah"), data.get("level"), data.get("temp_c"),
                     data.get("source")))
                dup = cur.rowcount == 0
        except Exception as e:  # noqa: BLE001
            self._json(500, {"ok": False, "error": str(e)})
            return
        self._json(200, {"ok": True, "dup": dup, "warnings": warns})


def main():
    init_db()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"battery-stats server listening on 0.0.0.0:{PORT}")
    print(f"  db       = {DB_PATH}")
    print(f"  claims   = {CLAIMS_PATH}{'（不存在，用内置表）' if not CLAIMS_PATH.exists() else ''}")
    print(f"  api key  = {'已启用' if API_KEY else '未启用（任何人可上传/查询）'}")
    print("App 端「联机数据地址」填: http://<你的地址>:%d/api/baseline" % PORT)
    srv.serve_forever()


if __name__ == "__main__":
    main()
