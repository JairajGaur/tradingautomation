#!/usr/bin/env python3
"""
LLM Alert-Review Report generator.

Joins the deterministic alerts the engine fired (ALERT_FIRED) with the parallel LLM
review verdicts (ALERT_LLM_REVIEW + JSON sidecars) and, when the app is running,
the ACTUAL price outcome of each alert — then emits a self-contained dark-theme
HTML dashboard comparing WHERE THE CODE'S ALERTS FAILED vs WHERE THE LLM
AGREED/DISAGREED, broken down per stock and per strategy.

The headline is the 2x2 confusion matrix:

                  move happened (WIN)        move failed (LOSS/FLAT)
  LLM AGREE       true positive (both right) false positive (LLM backed a loser)
  LLM DISAGREE    missed winner              TRUE NEGATIVE (LLM caught a bad alert) <-- the point

Usage:
  python3 scripts/llm_review_report.py [--date YYYY-MM-DD] [--no-verify]
                                       [--log logs/tradingautomation.log]
                                       [--reviews logs/alert-reviews]
                                       [--out reports]
                                       [--base-url http://localhost:8080]
                                       [--target 0.5] [--stop 0.3]

Data sources (all already produced by the app):
  - JSON sidecars under logs/alert-reviews/*.json  (primary — full verdict + context)
  - [ALERT_LLM_REVIEW] lines in the log            (fallback / cross-check)
  - [ALERT_FIRED] lines in the log                 (the deterministic alerts)
  - GET /api/swift-scan/backtest/{ticker}?date=&timeframe=&debug=true  (per-bar closes
    to verify the move; skipped with --no-verify or if the app is not reachable)

Move verification is a close-based proxy (no fills/slippage/intrabar) — for COMPARING
alert behaviour vs the LLM filter, not a P&L backtest. Mirrors reports/ALERT-REPORT-GUIDE.md.
"""

import argparse
import datetime as dt
import glob
import html
import json
import os
import re
import sys
import urllib.request
import urllib.error

ET_TODAY = None  # resolved in main()

# ----------------------------------------------------------------------------
# Parsing
# ----------------------------------------------------------------------------

FIRED_RE = re.compile(r"\[ALERT_FIRED\]\s+(.*)")
REVIEW_RE = re.compile(r"\[ALERT_LLM_REVIEW\]\s+(.*)")
# key=value with optional "quoted value"
KV_RE = re.compile(r'(\w+)=("([^"]*)"|\S+)')
# log line timestamp like 2026-10-12T00:58:31.138-04:00
TS_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})")


def parse_kvs(blob):
    out = {}
    for m in KV_RE.finditer(blob):
        key = m.group(1)
        out[key] = m.group(3) if m.group(3) is not None else m.group(2)
    return out


def line_ts(line):
    m = TS_RE.match(line)
    return (m.group(1), m.group(2)) if m else (None, None)


def parse_log(path, want_date):
    """Returns (fired[], reviews[]) parsed from the log, filtered to want_date if given."""
    fired, reviews = [], []
    if not os.path.exists(path):
        return fired, reviews
    with open(path, "r", errors="replace") as f:
        for line in f:
            d, t = line_ts(line)
            if want_date and d and d != want_date:
                continue
            mf = FIRED_RE.search(line)
            if mf:
                kv = parse_kvs(mf.group(1))
                kv["_time"] = t
                kv["_date"] = d
                fired.append(kv)
                continue
            mr = REVIEW_RE.search(line)
            if mr:
                kv = parse_kvs(mr.group(1))
                kv["_time"] = t
                kv["_date"] = d
                reviews.append(kv)
    return fired, reviews


def parse_sidecars(folder, want_date):
    """Returns review dicts from the JSON sidecars (richer than the log line)."""
    out = []
    for fp in sorted(glob.glob(os.path.join(folder, "*.json"))):
        try:
            with open(fp) as f:
                j = json.load(f)
        except Exception:
            continue
        if want_date and j.get("day") and j["day"] != want_date:
            continue
        alert = j.get("alert", {})
        v = j.get("verdict", {})
        ts = j.get("timestamp", "")  # "2026-10-12T00:58:31 EDT"
        tmatch = re.search(r"T(\d{2}:\d{2}:\d{2})", ts)
        out.append({
            "id": alert.get("id", "?"),
            "ticker": (alert.get("ticker") or "?").upper(),
            "alertDir": alert.get("direction"),
            "tf": alert.get("timeframe"),
            "price": alert.get("price"),
            "state": alert.get("state"),
            "verdict": (v.get("verdict") or "NEUTRAL").upper(),
            "conviction": v.get("conviction"),
            "reason": v.get("reason", ""),
            "watchFor": v.get("watchFor", ""),
            "ok": v.get("ok", False),
            "time": tmatch.group(1) if tmatch else "",
            "day": j.get("day"),
            "source": "sidecar",
        })
    return out


# ----------------------------------------------------------------------------
# Move verification (optional — needs the running app)
# ----------------------------------------------------------------------------

def fetch_closes(base_url, ticker, date, tf):
    """Per-bar [(timeEt, close)] from the swift-scan debug trace, or None on failure."""
    url = (f"{base_url}/api/swift-scan/backtest/{ticker}"
           f"?date={date}&timeframe={tf or 'M5'}&debug=true")
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            j = json.load(r)
    except Exception:
        return None
    trace = j.get("debugTrace") or j.get("debug_trace") or []
    series = []
    for b in trace:
        c = b.get("close")
        t = b.get("timeEt") or b.get("time")
        if c is not None:
            series.append((t, float(c)))
    return series or None


def classify(series, alert_time, entry, direction, target_pct, stop_pct):
    """WIN/LOSS/FLAT + MFE/MAE/end, using bar closes strictly after alert_time."""
    if not series or entry in (None, 0):
        return None
    after = [(t, c) for (t, c) in series if t and alert_time and t >= alert_time]
    if not after:
        return None
    up = (direction or "").upper() in ("UP", "BULLISH")
    mfe = mae = 0.0
    hit_t = hit_s = None
    for i, (t, c) in enumerate(after):
        fav = (c - entry) / entry * 100 if up else (entry - c) / entry * 100
        adv = -fav
        mfe = max(mfe, fav)
        mae = max(mae, adv)
        if hit_t is None and fav >= target_pct:
            hit_t = i
        if hit_s is None and adv >= stop_pct:
            hit_s = i
    end = (after[-1][1] - entry) / entry * 100
    if not up:
        end = -end
    if hit_t is not None and (hit_s is None or hit_t <= hit_s):
        cls = "WIN"
    elif hit_s is not None:
        cls = "LOSS"
    else:
        cls = "FLAT"
    return {"cls": cls, "mfe": round(mfe, 2), "mae": round(mae, 2), "end": round(end, 2)}


# ----------------------------------------------------------------------------
# Join + comparison
# ----------------------------------------------------------------------------

def correlate(fired, reviews):
    """
    Join each review to a fired alert by ticker (+ tf when present). The review is
    the unit of analysis — the engine publishes an event per delivered hit, so one
    review ~ one fired alert. When no ALERT_FIRED exists (e.g. manual /review tests),
    the review stands alone with source noted.
    """
    rows = []
    used = set()
    for r in reviews:
        match = None
        for i, fa in enumerate(fired):
            if i in used:
                continue
            if fa.get("ticker", "").upper() == r["ticker"] and \
               (not r.get("tf") or fa.get("tf") == r.get("tf")):
                match = fa
                used.add(i)
                break
        rows.append({**r, "firedMatched": match is not None,
                     "strategy": (match or {}).get("id") or r.get("id") or "unknown"})
    return rows


def outcome_bucket(cls):
    if cls == "WIN":
        return "WIN"
    if cls in ("LOSS", "FLAT"):
        return "FAIL"
    return None  # unverified


def confusion(rows):
    m = {("AGREE", "WIN"): 0, ("AGREE", "FAIL"): 0,
         ("DISAGREE", "WIN"): 0, ("DISAGREE", "FAIL"): 0,
         ("NEUTRAL", "WIN"): 0, ("NEUTRAL", "FAIL"): 0}
    for r in rows:
        b = outcome_bucket(r.get("cls"))
        if b is None:
            continue
        v = r["verdict"] if r["verdict"] in ("AGREE", "DISAGREE") else "NEUTRAL"
        m[(v, b)] += 1
    return m


# ----------------------------------------------------------------------------
# HTML
# ----------------------------------------------------------------------------

def build_html(rows, date, params):
    data = []
    for r in rows:
        data.append({
            "time": r.get("time", ""),
            "sym": r["ticker"],
            "strategy": r["strategy"],
            "alertDir": r.get("alertDir") or "",
            "verdict": r["verdict"],
            "conv": r.get("conviction"),
            "cls": r.get("cls") or "N/A",
            "mfe": r.get("mfe"),
            "mae": r.get("mae"),
            "end": r.get("end"),
            "reason": r.get("reason", ""),
            "watch": r.get("watchFor", ""),
        })
    data_json = json.dumps(data)
    verified = sum(1 for r in rows if r.get("cls"))
    note = (f"{len(rows)} reviews · {verified} with verified outcome · "
            f"target {params['target']}% / stop {params['stop']}% · close-based proxy")

    return TEMPLATE.replace("__DATE__", html.escape(date)) \
                   .replace("__NOTE__", html.escape(note)) \
                   .replace("__DATA__", data_json)


TEMPLATE = r"""<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>LLM Alert Review — __DATE__</title>
<style>
  :root{--bg:#0d1117;--panel:#161b22;--panel2:#1c2330;--border:#30363d;--text:#e6edf3;
    --muted:#8b949e;--up:#2ea043;--down:#f85149;--accent:#58a6ff;--warn:#d29922;--flat:#6e7681;}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;line-height:1.5;padding:0 0 60px}
  header{padding:28px 32px;border-bottom:1px solid var(--border);background:linear-gradient(180deg,#161b22,#0d1117)}
  header h1{margin:0 0 6px;font-size:24px}header p{margin:0;color:var(--muted);font-size:14px}
  .wrap{max-width:1200px;margin:0 auto;padding:0 32px}
  section{margin-top:34px}
  h2{font-size:18px;border-left:3px solid var(--accent);padding-left:10px;margin-bottom:14px}
  .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:14px}
  .kpi{background:var(--panel);border:1px solid var(--border);border-radius:10px;padding:16px}
  .kpi .num{font-size:28px;font-weight:700}
  .kpi .lbl{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.5px;margin-top:4px}
  .kpi.up .num{color:var(--up)}.kpi.down .num{color:var(--down)}.kpi.good .num{color:var(--accent)}
  table{width:100%;border-collapse:collapse;font-size:13px;background:var(--panel);border:1px solid var(--border);border-radius:10px;overflow:hidden}
  th,td{text-align:left;padding:8px 12px;border-top:1px solid var(--border);vertical-align:top}
  th{color:var(--muted);font-weight:600;font-size:11px;text-transform:uppercase;cursor:pointer}
  thead tr{border-top:none}
  td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}
  .tag{font-size:11px;padding:2px 8px;border-radius:20px;font-weight:600;white-space:nowrap}
  .tag.WIN{background:rgba(46,160,67,.18);color:var(--up)}
  .tag.LOSS{background:rgba(248,81,73,.18);color:var(--down)}
  .tag.FLAT{background:rgba(110,118,129,.25);color:var(--muted)}
  .tag.AGREE{background:rgba(46,160,67,.18);color:var(--up)}
  .tag.DISAGREE{background:rgba(248,81,73,.18);color:var(--down)}
  .tag.NEUTRAL{background:rgba(210,153,34,.18);color:var(--warn)}
  .d.UP,.d.BULLISH{color:var(--up);font-weight:600}.d.DOWN,.d.BEARISH{color:var(--down);font-weight:600}
  .matrix{display:grid;grid-template-columns:140px 1fr 1fr;gap:10px;max-width:720px}
  .cell{background:var(--panel);border:1px solid var(--border);border-radius:10px;padding:16px;text-align:center}
  .cell .n{font-size:30px;font-weight:700}.cell .c{font-size:11px;color:var(--muted);margin-top:4px}
  .cell.hl{border-color:var(--up);box-shadow:0 0 0 1px var(--up) inset}
  .cell.bad{border-color:var(--down)}
  .rowlab{display:flex;align-items:center;justify-content:center;font-weight:600;color:var(--muted)}
  .controls{margin:12px 0;display:flex;gap:8px;flex-wrap:wrap}
  .btn{background:var(--panel);border:1px solid var(--border);color:var(--text);padding:6px 14px;border-radius:20px;cursor:pointer;font-size:13px}
  .btn.active{background:var(--accent);color:#fff;border-color:var(--accent)}
  .reason{color:var(--muted);font-size:12px;max-width:380px}
  .caveat{background:rgba(210,153,34,.1);border:1px solid rgba(210,153,34,.3);border-radius:8px;padding:12px 14px;font-size:13px;color:#e8d6a8;margin-top:14px}
  footer{color:var(--muted);font-size:12px;margin-top:40px;text-align:center}
  .empty{background:var(--panel);border:1px dashed var(--border);border-radius:10px;padding:40px;text-align:center;color:var(--muted)}
</style></head><body>
<header><div class="wrap">
  <h1>🤖 LLM Alert Review vs Code</h1>
  <p>__DATE__ · __NOTE__</p>
</div></header>
<div class="wrap">

  <section><h2>Overview</h2>
    <div class="kpis">
      <div class="kpi"><div class="num" id="k-total">0</div><div class="lbl">Reviews</div></div>
      <div class="kpi up"><div class="num" id="k-agree">0</div><div class="lbl">LLM Agree</div></div>
      <div class="kpi down"><div class="num" id="k-disagree">0</div><div class="lbl">LLM Disagree</div></div>
      <div class="kpi"><div class="num" id="k-neutral">0</div><div class="lbl">Neutral</div></div>
      <div class="kpi good"><div class="num" id="k-avgconv">0</div><div class="lbl">Avg conviction</div></div>
    </div>
  </section>

  <section><h2>LLM vs Outcome — confusion matrix</h2>
    <div id="matrix-wrap"></div>
    <div class="note" id="matrix-note" style="color:var(--muted);font-size:12px;margin-top:10px"></div>
  </section>

  <section><h2>Per-strategy breakdown</h2>
    <table id="strat"><thead><tr>
      <th>Strategy</th><th class="num">Reviews</th><th class="num">Agree</th>
      <th class="num">Disagree</th><th class="num">Neutral</th><th class="num">Avg conv</th>
      <th class="num">LLM caught (DISAGREE+fail)</th>
    </tr></thead><tbody id="strat-body"></tbody></table>
  </section>

  <section><h2>Per-stock detail (grouped by strategy)</h2>
    <div class="controls">
      <button class="btn active" data-f="all">All</button>
      <button class="btn" data-f="AGREE">Agree</button>
      <button class="btn" data-f="DISAGREE">Disagree</button>
      <button class="btn" data-f="NEUTRAL">Neutral</button>
    </div>
    <table id="tbl"><thead><tr>
      <th data-k="time">Time</th><th data-k="sym">Ticker</th><th data-k="strategy">Strategy</th>
      <th data-k="alertDir">Alert</th><th data-k="verdict">LLM</th><th class="num" data-k="conv">Conv</th>
      <th data-k="cls">Outcome</th><th class="num" data-k="mfe">MFE%</th><th class="num" data-k="mae">MAE%</th>
      <th>Reason</th>
    </tr></thead><tbody id="tbody"></tbody></table>
    <div id="empty"></div>
  </section>

  <div class="caveat">⚠️ Advisory analysis. Outcome is a close-based proxy (no fills/slippage/intrabar);
    "N/A" = outcome not verified (app not running at generation time or no bars). The LLM review is
    advisory — it never placed or blocked any order. For comparing the LLM filter vs the code's
    alerts, not P&L.</div>
  <footer>Generated by scripts/llm_review_report.py · data embedded inline.</footer>
</div>
<script>
const D=__DATA__;
const g=id=>document.getElementById(id);
const V=v=>["AGREE","DISAGREE","NEUTRAL"].includes(v)?v:"NEUTRAL";
const isFail=c=>c==="LOSS"||c==="FLAT";

// KPIs
g("k-total").textContent=D.length;
g("k-agree").textContent=D.filter(d=>V(d.verdict)==="AGREE").length;
g("k-disagree").textContent=D.filter(d=>V(d.verdict)==="DISAGREE").length;
g("k-neutral").textContent=D.filter(d=>V(d.verdict)==="NEUTRAL").length;
const convs=D.map(d=>d.conv).filter(x=>typeof x==="number");
g("k-avgconv").textContent=convs.length?(convs.reduce((a,b)=>a+b,0)/convs.length).toFixed(1):"—";

// confusion matrix (only verified rows)
const ver=D.filter(d=>d.cls==="WIN"||d.cls==="LOSS"||d.cls==="FLAT");
function cnt(v,win){return ver.filter(d=>V(d.verdict)===v && (win? d.cls==="WIN" : isFail(d.cls))).length;}
if(ver.length){
  g("matrix-wrap").innerHTML=
   `<div class="matrix">
      <div></div><div class="rowlab">Move happened (WIN)</div><div class="rowlab">Move failed (LOSS/FLAT)</div>
      <div class="rowlab">LLM AGREE</div>
      <div class="cell"><div class="n">${cnt("AGREE",1)}</div><div class="c">true positive</div></div>
      <div class="cell bad"><div class="n">${cnt("AGREE",0)}</div><div class="c">false positive — backed a loser</div></div>
      <div class="rowlab">LLM DISAGREE</div>
      <div class="cell"><div class="n">${cnt("DISAGREE",1)}</div><div class="c">missed a winner</div></div>
      <div class="cell hl"><div class="n">${cnt("DISAGREE",0)}</div><div class="c">✓ caught a bad alert</div></div>
    </div>`;
  const caught=cnt("DISAGREE",0), backed=cnt("AGREE",0);
  g("matrix-note").textContent=`The highlighted cell = alerts the code fired that the LLM flagged, which then failed (${caught}). `+
    `Watch the "backed a loser" cell (${backed}) — where the LLM was fooled.`;
}else{
  g("matrix-wrap").innerHTML=`<div class="empty">No verified outcomes yet. Run with the app up (so moves can be checked), or on a day that has fired alerts + bars.</div>`;
}

// per-strategy
const strat={};
D.forEach(d=>{const s=d.strategy||"unknown";(strat[s]=strat[s]||[]).push(d);});
g("strat-body").innerHTML=Object.entries(strat).sort((a,b)=>b[1].length-a[1].length).map(([s,rows])=>{
  const a=rows.filter(d=>V(d.verdict)==="AGREE").length;
  const di=rows.filter(d=>V(d.verdict)==="DISAGREE").length;
  const ne=rows.filter(d=>V(d.verdict)==="NEUTRAL").length;
  const cv=rows.map(d=>d.conv).filter(x=>typeof x==="number");
  const avg=cv.length?(cv.reduce((x,y)=>x+y,0)/cv.length).toFixed(1):"—";
  const caught=rows.filter(d=>V(d.verdict)==="DISAGREE"&&isFail(d.cls)).length;
  return `<tr><td>${s}</td><td class="num">${rows.length}</td><td class="num">${a}</td>`+
    `<td class="num">${di}</td><td class="num">${ne}</td><td class="num">${avg}</td><td class="num">${caught}</td></tr>`;
}).join("");

// detail table
let filter="all", sortK="time", sortAsc=true;
function render(){
  let rows=D.filter(d=> filter==="all" || V(d.verdict)===filter);
  rows.sort((a,b)=>{let x=a[sortK],y=b[sortK];if(x==null)x=-1;if(y==null)y=-1;
    if(typeof x==="string")return sortAsc?String(x).localeCompare(y):String(y).localeCompare(x);return sortAsc?x-y:y-x;});
  g("tbody").innerHTML=rows.map(d=>{
    const cls=d.cls&&d.cls!=="N/A"?`<span class="tag ${d.cls}">${d.cls}</span>`:'<span style="color:var(--muted)">N/A</span>';
    const conv=typeof d.conv==="number"?d.conv:"—";
    return `<tr><td>${d.time||"—"}</td><td><b>${d.sym}</b></td><td>${d.strategy}</td>`+
      `<td class="d ${d.alertDir}">${d.alertDir||"—"}</td>`+
      `<td><span class="tag ${V(d.verdict)}">${V(d.verdict)}</span></td>`+
      `<td class="num">${conv}</td><td>${cls}</td>`+
      `<td class="num">${d.mfe??"—"}</td><td class="num">${d.mae??"—"}</td>`+
      `<td class="reason">${(d.reason||"").replace(/</g,"&lt;")}</td></tr>`;
  }).join("");
  g("empty").innerHTML=rows.length?"":'<div class="empty">No reviews match this filter.</div>';
}
document.querySelectorAll(".btn").forEach(b=>b.onclick=()=>{
  document.querySelectorAll(".btn").forEach(x=>x.classList.remove("active"));
  b.classList.add("active");filter=b.dataset.f;render();});
document.querySelectorAll("#tbl th[data-k]").forEach(th=>th.onclick=()=>{
  const k=th.dataset.k;if(sortK===k)sortAsc=!sortAsc;else{sortK=k;sortAsc=true;}render();});
render();
</script></body></html>
"""


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Generate the LLM alert-review HTML report.")
    ap.add_argument("--date", help="ET date YYYY-MM-DD (default: today)")
    ap.add_argument("--log", default="logs/tradingautomation.log")
    ap.add_argument("--reviews", default="logs/alert-reviews")
    ap.add_argument("--out", default="reports")
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--no-verify", action="store_true", help="skip move verification (no app call)")
    ap.add_argument("--target", type=float, default=0.5)
    ap.add_argument("--stop", type=float, default=0.3)
    args = ap.parse_args()

    date = args.date or dt.date.today().isoformat()

    fired, review_lines = parse_log(args.log, date)
    reviews = parse_sidecars(args.reviews, date)

    # Prefer sidecars; if none for the date, fall back to the parsed log review lines.
    if not reviews and review_lines:
        for kv in review_lines:
            reviews.append({
                "id": kv.get("id", "?"), "ticker": (kv.get("ticker") or "?").upper(),
                "alertDir": kv.get("alertDir"), "tf": kv.get("tf"), "price": None,
                "state": kv.get("state"), "verdict": (kv.get("verdict") or "NEUTRAL").upper(),
                "conviction": int(kv["conviction"]) if kv.get("conviction", "").isdigit() else None,
                "reason": kv.get("reason", ""), "watchFor": kv.get("watch", ""),
                "ok": kv.get("ok") == "true", "time": kv.get("_time", ""), "day": date,
                "source": "log",
            })

    if not reviews:
        print(f"No LLM reviews found for {date} in {args.reviews} or {args.log}.")
        print("Run the pipeline through a market session first (app up, alerts + review enabled).")
        # Still emit an empty report so the file exists.
    rows = correlate(fired, reviews)

    # Move verification (optional)
    if not args.no_verify and rows:
        cache = {}
        for r in rows:
            if r.get("price") in (None, "", "null"):
                continue
            key = (r["ticker"], r.get("tf") or "M5")
            if key not in cache:
                cache[key] = fetch_closes(args.base_url, r["ticker"], date, r.get("tf") or "M5")
            series = cache[key]
            if series:
                try:
                    entry = float(r["price"])
                except (TypeError, ValueError):
                    continue
                res = classify(series, r.get("time"), entry,
                               r.get("alertDir"), args.target, args.stop)
                if res:
                    r.update(res)

    os.makedirs(args.out, exist_ok=True)
    html_out = build_html(rows, date, {"target": args.target, "stop": args.stop})
    out_path = os.path.join(args.out, f"llm-review-report-{date}.html")
    with open(out_path, "w") as f:
        f.write(html_out)

    verified = sum(1 for r in rows if r.get("cls"))
    print(f"Wrote {out_path}")
    print(f"  reviews={len(rows)}  fired={len(fired)}  verified_outcomes={verified}")
    if rows:
        m = confusion(rows)
        print(f"  confusion: AGREE/WIN={m[('AGREE','WIN')]} AGREE/FAIL={m[('AGREE','FAIL')]} "
              f"DISAGREE/WIN={m[('DISAGREE','WIN')]} DISAGREE/FAIL(caught)={m[('DISAGREE','FAIL')]}")


if __name__ == "__main__":
    main()
