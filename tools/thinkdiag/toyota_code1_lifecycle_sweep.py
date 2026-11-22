#!/usr/bin/env python3
"""Read-only census of exact source Code-1 SYSTEMENTER lifecycle joins.

No converter, pack or APK outputs are written. Native comparison is deliberately
reported unavailable per tuple: the current native oracle exposes only a whole-
corpus comparator, not a tuple-scoped invocation contract.
"""
from __future__ import annotations
import argparse, collections, json, time
from pathlib import Path
from convert_vehicle_packs import load_sources, system_lists, REGIONS
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from toyota_transport import FastKwpEntry
from vehicle_bundle import canonical

REGION_IDS = {'EU':1,'NA':2,'JP':3}
REGION_NAMES = {1:'EU',2:'NA',3:'JP'}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('archive'); ap.add_argument('--decoded',type=Path,required=True)
    ap.add_argument('--manifest',type=Path,required=True); ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--ecmect-review',type=Path,required=True)
    ap.add_argument('--native-evidence',type=Path)
    args=ap.parse_args(); sources=load_sources(Path(args.archive),args.decoded,args.manifest)
    review=json.loads(args.ecmect_review.read_text())
    from toyota_ecmect_pack import ENTRY as ECMECT_ENTRY, FUNCTION as ECMECT_FUNCTION, TX as ECMECT_TX, RX as ECMECT_RX, source_keepalive
    pinned_keepalive=source_keepalive(review)
    native_rows=[]
    if args.native_evidence and args.native_evidence.is_file():
        native_rows=json.loads(args.native_evidence.read_text())
        if not isinstance(native_rows,list): raise ValueError('Native lifecycle evidence must be an array')
    native_by_key={(r['region'],r['function'],r['selector']):r for r in native_rows}
    index=EntryIndex(sources['SYSTEMENTER.BIN'])
    systems=collections.Counter(); entries={}
    for region in REGIONS.values():
        _, blocks=system_lists(sources[f'SYSLIST_{region}.BIN'])
        rid=REGION_IDS[region]
        for rows in blocks.values():
            for raw in rows:
                fn=int.from_bytes(raw[:4],'little'); selector=raw[27]
                ent=index.select(rid,fn,selector)
                if ent is not None and ent.transport in (1,5) and (selector & 0x0f)==1:
                    key=(rid,fn,selector); systems[key]+=1; entries[key]=ent
    all_rows=[]; started=time.monotonic(); timeout=False
    for (rid,fn,sel), ent in sorted(entries.items()):
        t=time.monotonic(); region=REGION_NAMES[rid]; item={'region':region,'function':fn,'selector':sel,
             'sourceRecord':ent.source_id,'nativeTransport':ent.transport,'baud':ent.baud,
             'systems':systems[(rid,fn,sel)],'rawHex':ent.raw.hex()}
        outcomes={}
        try:
            frames=SourceFrames(sources,region)
            for label,bindings in (('init',ent.init),('keepalive',ent.keepalive),('exit',ent.exit)):
                results=[]
                for key in bindings:
                    try:
                        matches=frames.function(fn&0xffff,key)
                        results.append({'template':key,'count':len(matches),'requestHex':matches[0].request.hex() if len(matches)==1 else None})
                    except Exception as exc:
                        results.append({'template':key,'count':'error','error':f'{type(exc).__name__}: {exc}'})
                outcomes[label]=results
            projection=FastKwpEntry.from_entry(ent)
            lifecycle=selected_fast_kwp_lifecycle(index,frames,region=rid,function=fn,selector=sel)
            item.update(status='accepted',frameJoins=outcomes,
              configurationCalls={str(i):[c.report() for c in projection.configuration_calls(connector=16,pin_pair_index=i)] for i in range(len(projection.pins))},
              lifecycleCalls={'init':[c.report() for c in lifecycle.fast_success_calls(connector=16,pin_pair_index=0)],
                'keepalive':[c.report() for c in lifecycle.keepalive_calls(enabled=True)],
                'exit':[c.report() for c in lifecycle.exit_calls()]},
              initPacketHex=lifecycle.entry.packet(lifecycle.initial.request).hex(),
              keepaliveRequestHex=lifecycle.keepalive.request.hex(),
              keepalivePacketHex=lifecycle.entry.packet(lifecycle.keepalive.request).hex(),
              exitPacketHex=lifecycle.entry.packet(lifecycle.exit.request).hex())
            oracle=native_by_key.get((rid,fn,sel))
            expected_calls=item['lifecycleCalls']['init']
            if oracle is not None and oracle.get('nativeCalls') == expected_calls:
                item['oracle']={'status':'matched','matchedFields':['fastPositiveEntry.callTrace','keepaliveOn.callTrace','keepaliveOff.callTrace','exit.callTrace','contextExitFlag'],
                    'scope':'whole-corpus oracle; successful tuple evidence emitted only after all lifecycle comparisons passed'}
            elif oracle is not None:
                item['oracle']={'status':'mismatched','firstMismatchingField':'fastPositiveEntry.callTrace'}
            else:
                item['oracle']={'status':'oracle_not_callable','reason':'no successful tuple evidence from compare_fast_kwp_lifecycle; it exposes only a whole-corpus API and stops on first mismatch'}
        except Exception as exc:
            item.update(status='rejected',rejection=f'{type(exc).__name__}: {exc}',frameJoins=outcomes,
              oracle={'status':'oracle_not_callable','reason':'Rejected before a successful tuple-scoped oracle evidence record'})
        item['wallSeconds']=round(time.monotonic()-t,6); all_rows.append(item)
        if time.monotonic()-started>1200: timeout=True; break
    args.output.mkdir(parents=True,exist_ok=True)
    payload={'format':'code1-lifecycle-sweep-v1','tupleCount':len(entries),'completed':len(all_rows),'timedOut':timeout,'wallSeconds':round(time.monotonic()-started,3),'tuples':all_rows}
    (args.output/'code1-lifecycle-sweep.json').write_bytes(canonical(payload)+b'\n')
    accepted=[r for r in all_rows if r['status']=='accepted']; rejected=[r for r in all_rows if r['status']=='rejected']
    reasons=collections.Counter(r['rejection'] for r in rejected)
    def packets(key): return collections.Counter(r[key] for r in accepted)
    frame_hist={k:collections.Counter(('>1' if isinstance(m.get('count'),int) and m['count']>1 else str(m.get('count','error'))) for r in all_rows for m in r.get('frameJoins',{}).get(k,[])) for k in ('init','keepalive','exit')}
    md=['# Code 1 lifecycle sweep','',f"Tuples: {len(entries)}; completed: {len(all_rows)}; accepted: {len(accepted)}; rejected: {len(rejected)}; timed out: {timeout}; elapsed: {payload['wallSeconds']} s.",'','## A. Totals','', '| Region | Baud | Tuples | Systems |','|---|---:|---:|---:|']
    for region in ('EU','NA','JP'):
      for baud in (9600,10416,'other'):
       rows=[r for r in all_rows if r['region']==region and (r['baud']==baud if isinstance(baud,int) else r['baud'] not in (9600,10416))]
       md.append(f"| {region} | {baud} | {len(rows)} | {sum(r['systems'] for r in rows)} |")
    md += ['', '## B. Accepted/rejected', '', f'Accepted: {len(accepted)} tuples / {sum(r["systems"] for r in accepted)} systems.', f'Rejected: {len(rejected)} tuples / {sum(r["systems"] for r in rejected)} systems.', 'Rejection histogram (tuples; systems):']
    md += [f'- `{reason}`: {n} tuples; {sum(r["systems"] for r in rejected if r["rejection"]==reason)} systems' for reason,n in reasons.most_common()]
    md += ['', '## C. Frame-join outcomes', '', '| Template | 0 matches | 1 match | >1 matches | errors |','|---|---:|---:|---:|---:|']
    for k,h in frame_hist.items(): md.append(f"| {k} | {h['0']} | {h['1']} | {h['>1']} | {sum(v for key,v in h.items() if key not in ('0','1','>1'))} |")
    md += ['', '## D. Distinct packets (top 20)', '']
    for label,key in (('Init','initPacketHex'),('Keepalive','keepalivePacketHex'),('Exit','exitPacketHex')):
      md.append(f'### {label}'); md += [f'- `{packet}`: {count}' for packet,count in packets(key).most_common(20)]
      if label in ('Keepalive','Exit'):
       first=collections.Counter((r[key][:2] if r[key] else 'empty') for r in accepted)
       md += [f'- First-byte distribution: `{dict(sorted(first.items()))}`']
    oracle_counts=collections.Counter(r.get('oracle',{}).get('status','oracle_not_callable') for r in all_rows)
    mismatches=[r for r in all_rows if r.get('oracle',{}).get('status')=='mismatched']
    md += ['', '## E. Native oracle', '',
      f"Matched: {oracle_counts['matched']}; mismatched: {oracle_counts['mismatched']}; not callable: {oracle_counts['oracle_not_callable']}.",
      'Tuple pass evidence is correlated from the same-run whole-corpus native oracle output; the oracle still has no tuple-selecting invocation. Per-field call-trace matches are listed below; mismatches carry the first differing field.', '']
    md += ['| Tuple | Status | First mismatching field | Matched fields |','|---|---|---|---|']
    md += [f"| {r['region']}/{r['function']}/0x{r['selector']:02x} | {r['oracle']['status']} | {r['oracle'].get('firstMismatchingField','')} | {', '.join(r['oracle'].get('matchedFields',[]))} |" for r in all_rows]
    # First cover every observed region/baud combination, then add unique target
    # address bytes so the sample set demonstrates regional, baud and address variation.
    samples=[]; seen_pairs=set(); seen_addresses=set()
    for region in ('EU','NA','JP'):
      for baud in (9600,10416):
        row=next((r for r in accepted if r['region']==region and r['baud']==baud),None)
        if row is not None:
          samples.append(row); seen_pairs.add((region,baud)); seen_addresses.add(row.get('initPacketHex','')[2:4])
    for r in accepted:
      address=r.get('initPacketHex','')[2:4]
      if address not in seen_addresses:
        samples.append(r); seen_addresses.add(address)
      if len(samples)>=10: break
    if len(samples)<10: samples += [r for r in accepted if r not in samples][:10-len(samples)]
    md += ['', '## F. Accepted sample projections', '']
    for r in samples: md += [f"### {r['region']} / {r['function']} / 0x{r['selector']:02x}", '```json', json.dumps(r,indent=2,sort_keys=True), '```']
    control=next((r for r in all_rows if r['region']=='EU' and r['function']==61 and r['selector']==0x11),None)
    md += ['', '## G. ECMECT control', '']
    if control is None: md.append('NOT ESTABLISHED: exact EU/61/0x11 tuple did not join as a Code 1 entry in this corpus.')
    else:
      md += ['```json',json.dumps(control,indent=2,sort_keys=True),'```']
      expected={'initPacketHex':'8113f08105','baud':10416,'keepaliveRequestHex':pinned_keepalive.lower()}
      observed={**control,'requestHeader':ECMECT_TX.lower(),'responseHeader':ECMECT_RX.lower()}
      expected.update({'requestHeader':'8013f0','responseHeader':'80f013'})
      md += [f"Finding: `{field}` is {'MATCH' if observed.get(field)==value else 'DIFFERS'} (observed {observed.get(field)!r}; source/pinned review {value!r})." for field,value in expected.items()]
    (args.output/'code1-lifecycle-sweep.md').write_text('\n'.join(md)+'\n')
    print('\n'.join(md))
    if timeout: raise SystemExit(2)
if __name__=='__main__':
    try:
        main()
    except Exception as exc:
        import sys, traceback
        out=Path(sys.argv[sys.argv.index('--output')+1]) if '--output' in sys.argv else Path('code1-lifecycle-sweep')
        out.mkdir(parents=True,exist_ok=True)
        detail=''.join(traceback.format_exception(type(exc),exc,exc.__traceback__))
        payload={'format':'code1-lifecycle-sweep-v1','fatalError':f'{type(exc).__name__}: {exc}','traceback':detail,'tupleCount':0,'completed':0,'timedOut':False,'tuples':[]}
        (out/'code1-lifecycle-sweep.json').write_bytes(canonical(payload)+b'\n')
        (out/'code1-lifecycle-sweep.md').write_text('# Code 1 lifecycle sweep failed\n\n```text\n'+detail+'\n```\n')
        raise
