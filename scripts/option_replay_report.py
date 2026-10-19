#!/usr/bin/env python3
"""Render a saved option replay response using the existing HTML report styling.

No app startup, broker requests, trading or notifications are performed by this renderer.
"""
import argparse
import csv
import hashlib
import html
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo


def esc(value):
    return html.escape(str(value if value is not None else "Not returned"), quote=True)


def show(value):
    if isinstance(value, bool):
        return "ON" if value else "OFF"
    if isinstance(value, dict):
        return "; ".join(f"{key}={val}" for key, val in value.items())
    return str(value) if value is not None else "Not returned by API"


def table(headers, rows):
    return '<div class="table-wrap"><table><thead><tr>' + ''.join(
        f'<th>{esc(h)}</th>' for h in headers) + '</tr></thead><tbody>' + ''.join(
        '<tr>' + ''.join(f'<td>{esc(c)}</td>' for c in row) + '</tr>' for row in rows
    ) + '</tbody></table></div>'


def render(source, output, metadata=None, template=None, replace=False):
    source, output = Path(source), Path(output)
    data = json.loads(source.read_text())
    if not isinstance(data, dict) or not isinstance(data.get('results'), list):
        raise ValueError('Input must be a successful option replay response containing results[]')
    if output.exists() and not replace:
        raise FileExistsError(f'Preserve existing snapshots: {output} already exists')
    metadata = json.loads(Path(metadata).read_text()) if metadata else {}
    generated = datetime.now(ZoneInfo('America/New_York')).isoformat(timespec='seconds')
    rows = data['results']
    contracts = [(row['underlying'], c) for row in rows for c in row.get('contracts', [])]
    signals = [{**{key: c.get(key) for key in ('occSymbol', 'type', 'strike', 'moneyness')},
                **s, 'underlying': u} for u, c in contracts for s in c.get('signals', [])]
    signals.sort(key=lambda s: (s.get('firedAtEt', ''), s.get('occSymbol', '')))
    grouped = data.get('signalCounting') == 'UNDERLYING_SIDE_GROUP'
    groups = sorted([g for row in rows for g in row.get('groupedSignals', [])],
                    key=lambda g: (g['evaluatedAtEt'], g['underlying'], g['type']))
    events = groups if grouped else signals
    hours, reasons = Counter(), Counter()
    for s in events:
        match = re.search(r'T(\d{2}):', s.get('evaluatedAtEt', s.get('firedAtEt', '')))
        if match:
            hours[match[1] + ':00'] += 1
    for row in rows:
        reasons.update(row.get('dataQualityRejections', {}))
    atr_rejected = sum(row.get('largeCandleRejections', 0) for row in rows)
    coverage = sum(row.get('contractsWithSessionData', 0) for row in rows)
    covered_symbols = sum(row.get('contractsWithSessionData', 0) > 0 for row in rows)
    sides = Counter(s.get('type', 'UNKNOWN') for s in events)
    warnings = []
    for key in ('haDirectionEnabled', 'haTimeframe', 'largeCandleFilterEnabled', 'maxCandleAtr',
                'barQuality', 'threeMinStEnabled'):
        if key not in data:
            warnings.append(f'{key} is absent: the response cannot verify this feature’s runtime setting.')
    if len(signals) != data.get('totalContractSignals', data.get('totalSignals')):
        warnings.append('API contract signal count differs from the number of returned strike rows.')
    if data.get('signalCounting') == 'UNDERLYING_SIDE_GROUP':
        warnings.append(f"Grouped alerts: {data.get('totalSignals', 0)}; retained contract candidates: {len(signals)}. Overview, side counts and hourly chart count grouped alerts; contract detail tables count retained candidates. Candidates are unranked.")
    if not data.get('date'):
        warnings.append('No opening session was resolved from the available stock history.')
    missing = [row['underlying'] for row in rows if row.get('contractsWithSessionData', 0) == 0]
    if missing:
        warnings.append('No usable option-session coverage for: ' + ', '.join(missing) + '.')

    # Retain the established report's visual language rather than introducing another template.
    css = ''
    if template and Path(template).exists():
        match = re.search(r'<style>(.*?)</style>', Path(template).read_text(), re.S)
        css = match.group(1) if match else ''
    css += '''
body{background:#f5f7fb}.wrap{max-width:1240px;background:white;min-height:100vh}
h1{font-size:2rem;letter-spacing:-.04em}.eyebrow{color:#0969da;font-size:.75rem;font-weight:700;letter-spacing:.12em;text-transform:uppercase}
.lead{font-size:1.05rem;color:#475569}.table-wrap{overflow-x:auto}th,td{vertical-align:top}
th{white-space:nowrap}.good{color:#1a7f37}.warn{color:#9a6700}.muted{color:#64748b}
.status{border-left:4px solid #0969da;padding:1rem;background:#eff6ff;margin:1rem 0;border-radius:5px}
.status.warning{border-color:#bf8700;background:#fff8e1}.badge{background:#eaf1ff;color:#0969da;border-radius:20px;padding:4px 10px;font-size:.75rem}
.toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:1rem 0}
input{padding:10px;border:1px solid #cbd5e1;border-radius:6px;font:inherit;min-width:240px}
.bar-track{flex:1;background:#eef2f6;border-radius:3px}.hrow{gap:12px;margin:.6rem 0}
.hh{width:60px}.hn{width:45px}.bar{display:block;height:20px}.config td:first-child{min-width:200px}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:.78rem;background:#f6f8fa;padding:14px;border-radius:7px}
details{margin:.7rem 0}summary{cursor:pointer;font-weight:600}.card{margin:1rem 0;padding:1rem}
nav{display:flex;gap:16px;flex-wrap:wrap;margin:1rem 0}a{color:#0969da}.tiny{font-size:.78rem}
@media(max-width:650px){.wrap{padding:1rem}h1{font-size:1.5rem}.grid{grid-template-columns:repeat(2,1fr)}}
@media print{body{background:white}.toolbar,nav{display:none}.wrap{max-width:none;padding:0}.card{break-inside:avoid}details>*{display:block}}
'''
    session = data.get('date') or 'No session resolved'
    title = f'Option strategy analysis · {session}'
    parts = [f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{esc(title)}</title><style>{css}</style></head><body><main class="wrap">',
             '<div class="eyebrow">Trading research / option replay</div>',
             f'<h1>{esc(title)}</h1>',
             '<p class="lead">Completed-candle replay with underlying Heikin Ashi direction, option ATR spike rejection, and data-quality diagnostics.</p>',
             f'<p class="meta">Request date {esc(data.get("requestDate"))} · API snapshot {esc(data.get("timestamp"))} · Generated {esc(generated)} · Broker mode {esc(metadata.get("mode"))}</p>',
             '<nav><a href="#overview">Overview</a><a href="#rules">Active rules</a><a href="#coverage">Coverage</a><a href="#diagnostics">Rejections</a><a href="#signals">Signals</a><a href="#provenance">Provenance</a></nav>']
    if grouped and groups:
        conclusion = f'{len(groups)} stock/direction alert groups containing {len(signals)} option candidates across {len(contracts)} contracts. Shared cooldown: {data.get("cooldownMinutes")} minutes; no ranking.'
    elif signals:
        conclusion = f'{len(signals)} qualifying signals across {len(contracts)} contracts; {covered_symbols}/{len(rows)} underlying symbols have option-session data.'
    elif coverage:
        conclusion = f'No qualifying signals returned. {coverage} contracts have session data; inspect quality and filter rejections before drawing conclusions.'
    else:
        conclusion = 'No usable option-session data: signal performance cannot be evaluated from this snapshot.'
    parts.append(f'<div class="status {"warning" if not signals else ""}"><b>{esc(conclusion)}</b><br>{esc(data.get("session"))} · Previous-session fallback: {esc(show(data.get("previousSession")))}</div>')
    if warnings:
        parts.append('<div class="caveat"><b>Coverage and verification notices</b><ul>' + ''.join(f'<li>{esc(w)}</li>' for w in dict.fromkeys(warnings)) + '</ul></div>')
    parts.append('<h2 id="overview">Replay at a glance</h2><div class="grid">')
    stats = [(len(events), 'Grouped alerts' if grouped else 'Returned signals'), (len(signals), 'Contract candidate signals'), (len(contracts), 'Contracts with signals'),
             (f'{covered_symbols}/{len(rows)}', 'Underlyings with session data'),
             (coverage, 'Contracts with session data'), (sides['CALL'], 'CALL groups' if grouped else 'CALL signals'),
             (sides['PUT'], 'PUT groups' if grouped else 'PUT signals'), (atr_rejected, 'Large-candle rejections'),
             (sum(reasons.values()), 'Data-quality rejections')]
    for value, label in stats:
        parts.append(f'<div class="stat"><div class="stat-v">{esc(value)}</div><div class="stat-l">{esc(label)}</div></div>')
    if signals:
        average = sum(s.get('stRisingStreak', 0) for s in signals) / len(signals)
        busiest = max(contracts, key=lambda pair: len(pair[1].get('signals', [])))[1]
        parts.append(f'</div><p class="meta">First candidate candle {esc(signals[0]["firedAtEt"])} · Last {esc(signals[-1]["firedAtEt"])} · Average candidate ST streak {average:.2f} · Busiest contract {esc(busiest["occSymbol"])} ({len(busiest["signals"])} signals)</p>')
    else:
        parts.append('</div>')
    if signals:
        active = max(rows, key=lambda row: row.get('signalCount', 0))
        peak_hour = max(hours, key=hours.get) if hours else 'Not available'
        parts.append('<h2>Observed patterns</h2><ul>')
        parts.append(f'<li>PUTs account for {sides["PUT"]}/{len(events)} returned alert events ({100*sides["PUT"]/len(events):.1f}%). This describes this replay, not a forward directional forecast.</li>')
        parts.append(f'<li>{esc(active["underlying"])} has the most returned signals ({active.get("signalCount", 0)}). Peak signal hour starts at {esc(peak_hour)} ET ({hours.get(peak_hour, 0)} signals).</li>')
        parts.append(f'<li>{sum(reasons.values())} historical evaluations failed data-quality checks and {atr_rejected} were rejected by the ATR cap. Session-data coverage means bars were present, not that every contract has complete uninterrupted coverage.</li></ul>')
    parts.append('<h2 id="rules">Runtime rules and new filters</h2>')
    rules = [('Option timeframe', 'timeframe'), ('ST rising bars', 'stRisingCandles'),
             ('Close-above EMA period', 'emaPeriod'), ('Above-EMA bars', 'emaAboveCandles'),
             ('EMA stack enabled', 'emaStackEnabled'), ('Fast EMA', 'emaStackFast'), ('Slow EMA', 'emaStackSlow'),
             ('Volume gate', 'volumeEnabled'), ('M3 / higher-timeframe ST gate', 'threeMinStEnabled'),
             ('Higher ST timeframe', 'threeMinStTimeframe'), ('Underlying stock HA gate', 'haDirectionEnabled'),
             ('Stock HA timeframe', 'haTimeframe'), ('Option large-candle gate', 'largeCandleFilterEnabled'),
             ('Maximum option range / ATR', 'maxCandleAtr'), ('Shared stock/side cooldown (minutes)' if grouped else 'Replay cooldown (minutes)', 'cooldownMinutes'),
             ('Data-quality policy', 'barQuality')]
    parts.append(table(['Runtime setting', 'Replay response value'], [(label, show(data.get(key))) for label, key in rules]))
    parts.append('<p class="meta">HA uses underlying stock candles: green permits CALL, red permits PUT; it checks color, not strength. The ATR cap uses real option candles and the current completed candle’s Wilder ATR, matching Swift Trend. The signal candle contributes to ATR. Settings above are returned by the running app, not inferred from the older report.</p>')
    if metadata.get('sourceConfigSupplement'):
        parts.append('<details><summary>Additional repository configuration</summary><p class="meta">Source YAML values for context. These fields are not returned by the replay API and are not independently verified against runtime overrides.</p>')
        parts.append(table(['Source configuration key', 'Repository value'], metadata['sourceConfigSupplement'].items()))
        parts.append('</details>')
    parts.append('<h2 id="coverage">Per-underlying coverage</h2>')
    coverage_rows = []
    for row in rows:
        counts = Counter(g['type'] for g in row.get('groupedSignals', [])) if grouped else Counter(c.get('type') for c in row.get('contracts', []) for s in c.get('signals', []))
        coverage_rows.append([row['underlying'], row.get('stockOpen'), row.get('contractsWithSessionData', 0),
                              row.get('contractsWithSignals', 0), row.get('signalCount', 0), counts['CALL'], counts['PUT'],
                              row.get('largeCandleRejections', 0), sum(row.get('dataQualityRejections', {}).values()), row.get('note', '—')])
    parts.append(table(['Underlying', 'Stock open', 'Contracts with data', 'Contracts fired', 'Signals', 'CALL', 'PUT', 'ATR rejects', 'Quality rejects', 'Coverage note'], coverage_rows))
    parts.append('<h2 id="diagnostics">Why evaluations were rejected</h2>')
    parts.append(table(['Quality reason', 'Rejected evaluations'], reasons.most_common()) if reasons else '<p>No data-quality rejection counts were returned.</p>')
    parts.append('<p class="meta">These are sequential evaluation counts, not unique contracts or a complete funnel. Cooldown and HA side checks can prevent later gates from running. ATR rejections are recorded before the remaining option conditions; they do not prove an otherwise qualifying trade was avoided. Full EMA/ST/volume/HA side-rejection counts are not exposed by this endpoint.</p>')
    parts.append('<h2>Signals by hour (ET)</h2>')
    peak = max(hours.values(), default=1)
    if hours:
        for hour, count in sorted(hours.items()):
            parts.append(f'<div class="hrow"><span class="hh">{esc(hour)}</span><div class="bar-track"><span class="bar" style="width:{100*count/peak:.2f}%"></span></div><span class="hn">{count}</span></div>')
    else:
        parts.append('<p>No signal timestamps to chart.</p>')
    if grouped:
        parts.append('<h2 id="groups">Stock/direction alert groups</h2><p class="meta">One row per alert event. All qualifying strikes are retained in numeric order; no preferred contract is selected. Times are historical evaluation times in ET.</p>')
        parts.append(table(['Evaluation time (ET)', 'Stock', 'Side', 'Candidates', 'Strike @ option close (unranked)'],
                           [[g['evaluatedAtEt'], g['underlying'], g['type'], len(g['candidates']),
                             '; '.join(f"{c['strike']} @ {c['optionClose']}" for c in g['candidates'])] for g in groups]))
        parts.append(f'<p class="meta">Raw qualifying contract evaluations: {sum(r.get("rawQualifyingContractSignals", 0) for r in rows)} · Stock/side evaluation groups suppressed by cooldown: {sum(r.get("cooldownSuppressedGroups", 0) for r in rows)}. These count repeated evaluations, not trades.</p>')
    parts.append('<h2 id="signals">Contract signal details</h2><p class="meta">Times are completed signal candles’ start times in ET, not delivery times or fill timestamps.</p>')
    parts.append('<div class="toolbar"><label for="search">Filter contracts</label><input id="search" placeholder="Underlying, OCC, CALL or PUT"><span id="visible" class="muted"></span></div>')
    for underlying, c in contracts:
        side = c.get('type', '')
        side_class = 'call' if side == 'CALL' else 'put' if side == 'PUT' else ''
        parts.append(f'<section class="card contract" data-search="{esc(underlying + " " + c.get("occSymbol", "") + " " + side)}"><div class="card-h"><span class="pill {side_class}">{esc(side)}</span><b>{esc(underlying)}</b><span class="occ">{esc(c.get("occSymbol"))}</span></div><p class="meta">Strike {esc(c.get("strike"))} · {esc(c.get("moneyness"))} · {esc(c.get("rthBars"))} replay evaluation bars · {len(c.get("signals", []))} signals</p>')
        parts.append(table(['Signal candle (ET)', 'Option close', 'ST rising streak', 'Above EMA', 'EMA stack', 'Volume'], [[s.get('firedAtEt'), s.get('optionClose'), s.get('stRisingStreak'), show(s.get('aboveEma')), show(s.get('emaStackUp')), show(s.get('volumeOk'))] for s in c.get('signals', [])]))
        parts.append('</section>')
    if not contracts:
        parts.append('<p>No qualifying contracts in this response. Coverage and rejection tables above distinguish unavailable data from filtered signals.</p>')
    parts.append('<div class="caveat"><b>Interpretation limits.</b> This is detection analysis, not a live alert log, execution backtest or P&amp;L report. Replay fixes strikes at the session open; live selection follows current spot. New grouped replay and live alerts use the configured stock/side cooldown; live state resets on restart. Older snapshots used per-contract reporting. Data are most-recent-N bars and may be incomplete, particularly for expired contracts. Per-signal HA values and ATR ratios are not included in this replay response, so they are not fabricated here. Recovery applies to live/inspection data; replay checks historical data against historical time.</div>')
    parts.append('<h2 id="provenance">Provenance and reproducibility</h2>')
    parts.append(table(['Item', 'Captured value'], [('Source endpoint', metadata.get('endpoint')),
        ('Application restart', metadata.get('restartVerified')), ('Runtime overrides', show(metadata.get('runtimeOverrides'))),
        ('Git HEAD', metadata.get('gitHead')), ('Working tree', metadata.get('workingTree')),
        ('Source fingerprint', metadata.get('sourceSha256')), ('Build / tests', metadata.get('buildValidation')),
        ('Replay response SHA-256', hashlib.sha256(source.read_bytes()).hexdigest())]))
    parts.append('<p><a href="' + esc(output.with_suffix('.json').name) + '">Replay JSON</a> · <a href="' + esc(output.with_suffix('.csv').name) + '">Signal CSV</a> · <a href="' + esc(output.with_suffix('.metadata.json').name) + '">Provenance JSON</a> · <a href="OPTION-VOL-ST-REPORT-GUIDE.md">Report guide</a> · <a href="KNOWLEDGE-GRAPH.md">Knowledge graph</a></p>')
    parts.append('<details><summary>Source replay response</summary><pre>' + esc(json.dumps(data, indent=2)) + '</pre></details>')
    parts.append('''<script>const input=document.getElementById('search');function filter(){let n=0;document.querySelectorAll('.contract').forEach(c=>{const keep=c.dataset.search.toLowerCase().includes(input.value.toLowerCase());c.hidden=!keep;n+=keep});document.getElementById('visible').textContent=n+' contract(s) shown'}input.addEventListener('input',filter);filter();</script></main></body></html>''')
    output.parent.mkdir(parents=True, exist_ok=True)
    for suffix in ('.json', '.csv', '.metadata.json'):
        if output.with_suffix(suffix).exists() and not replace:
            raise FileExistsError(f'Preserve existing snapshot: {output.with_suffix(suffix)}')
    output.write_text('\n'.join(parts))
    output.with_suffix('.json').write_bytes(source.read_bytes())
    output.with_suffix('.metadata.json').write_text(json.dumps(dict(metadata, reportGeneratedAt=generated), indent=2) + '\n')
    fields = ['underlying', 'occSymbol', 'type', 'strike', 'moneyness', 'evaluatedAtEt', 'firedAtEt', 'optionClose', 'stRisingStreak', 'aboveEma', 'emaStackUp', 'volumeOk']
    with output.with_suffix('.csv').open('w', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(signals)
    return {'report': str(output), 'session': data.get('date'), 'signals': len(events), 'contractCandidates': len(signals),
            'contractsWithSignals': len(contracts), 'contractsWithSessionData': coverage,
            'largeCandleRejections': atr_rejected, 'dataQualityRejections': dict(reasons), 'warnings': warnings}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--metadata')
    parser.add_argument('--replace', action='store_true', help='Explicitly replace a draft report and its sidecars')
    parser.add_argument('--template', default='reports/option-vol-st-replay-2026-10-16.html')
    args = parser.parse_args()
    print(json.dumps(render(args.input, args.out, args.metadata, args.template, args.replace), indent=2))
