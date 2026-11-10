"""Kleine Pruefseite: gezielte Clips einzelner Tage zu bestimmten Uhrzeiten anhoeren (Tiefbohrer oder Bagger?).

Aufruf:
  python pruefseite_tage.py <Protokollordner (alt)> <gruppen_neu.csv> <WAV-Zielordner> <Ausgabe.html> <Tag1,Tag2> [--zeiten 07:30,09:30,...]

Waehlt je Tag und Uhrzeit den zeitlich naechsten Clip, den das Protokoll als Tiefbohrer (B01) oder Bagger (B05) fuehrt, holt die WAV
aus dem Quell-Zip und baut eine Seite mit eingebetteten Playern. Die Einordnung des Protokolls ist eingeklappt (blind hoeren).
Ausgabe gehoert nicht ins Repo.
"""
import argparse
import base64
import csv
import json
import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gruppen_seiten as gs  # noqa: E402
import tagesprotokoll as tp  # noqa: E402

OPTIONEN = [("TIEFBOHRER", "Tiefbohrer"), ("BAGGER_BAUSCHUTT", "Bagger beim Bauschutt-Abholen"), ("BAGGER_SONST", "Bagger, anderes"),
            ("PRESSLUFTHAMMER", "Presslufthammer / Hämmern"), ("ANDERES", "Anderes"), ("UNKLAR", "Unklar")]

SEITE = """<title>Prüfung Tiefbohrer oder Bagger</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>%CSS%</style>
<div class="wrap">
<h1>Tiefbohrer oder Bagger?</h1>
<p class="lead">Je Clip anhören und wählen, was zu hören ist. Die Einordnung des Protokolls ist eingeklappt. Alles wird automatisch gespeichert.</p>
<div class="bar"><span class="mono" id="zaehler"></span><progress id="fort" value="0" max="%N%"></progress><span id="msg"></span></div>
<div id="liste"></div>
<h2 style="margin-top:28px">Export</h2>
<textarea id="export" readonly></textarea><p><button id="kopie" type="button">Export kopieren</button></p>
</div>
<script>
const OPT=%OPT%, CLIPS=%CLIPS%; const urteil={}; let db=null; const $=s=>document.querySelector(s);
function render(){
  const root=$('#liste'); root.textContent=''; let tag='';
  CLIPS.forEach((c,i)=>{
    if(c.tag!==tag){ tag=c.tag; const h=document.createElement('h2'); h.textContent=c.tag; h.style.margin='18px 0 6px'; root.append(h); }
    const d=document.createElement('section'); d.className='grp'+((urteil[c.id]||{}).klasse?' done':'');
    const t=document.createElement('div'); t.className='head'; t.innerHTML=''; const a1=document.createElement('span'); a1.textContent='Gegen '+c.zeit+' Uhr'; t.append(a1);
    const a=document.createElement('audio'); a.controls=true; a.preload='none'; a.src=c.audio;
    const o=document.createElement('div'); o.className='opts';
    OPT.forEach(k=>{ const lb=document.createElement('label'); const r=document.createElement('input'); r.type='radio'; r.name='c'+i; r.id='c'+i+'_'+k[0]; r.value=k[0]; r.checked=(urteil[c.id]||{}).klasse===k[0];
      r.onchange=()=>speichern(c.id,k[0]); lb.append(r,document.createTextNode(k[1])); o.append(lb); });
    const det=document.createElement('details'); const sm=document.createElement('summary'); sm.textContent='Einordnung des Protokolls (erst nach dem Anhören)'; const p=document.createElement('p'); p.textContent=c.protokoll; det.append(sm,p);
    d.append(t,a,o,det); root.append(d);
  });
  const n=CLIPS.filter(c=>(urteil[c.id]||{}).klasse).length; $('#zaehler').textContent=n+' / '+CLIPS.length; $('#fort').value=n;
  $('#export').value=JSON.stringify(urteil,null,1);
}
function neu(){ const y=window.scrollY; render(); window.scrollTo(0,y); }
async function speichern(id,k){ urteil[id]={klasse:k}; neu(); if(db){ try{ await db.collection('clips').doc(id).set({klasse:k}); $('#msg').textContent='Gespeichert.'; }catch(e){ $('#msg').textContent='Speichern fehlgeschlagen, bitte Export kopieren.'; } } }
render();
(async()=>{ try{ db=await claude.use('db'); }catch(e){ db=null; } if(!db){ $('#msg').textContent='Kein Speicher: bitte unten Export kopieren.'; return; }
  db.collection('clips').onSnapshot(s=>{ s.docs.forEach(x=>{ const v=x.data(); if(v) urteil[x.id]=v; }); neu(); }, ()=>{}); })();
$('#kopie').onclick=async()=>{ const t=$('#export'); try{ await navigator.clipboard.writeText(t.value); $('#msg').textContent='Kopiert.'; }catch(e){ t.select(); $('#msg').textContent='Markiert, bitte kopieren.'; } };
</script>
"""


def main(prot, gruppen_csv, wavdir, ausgabe, tage, zeiten):
    G = json.load(open(os.path.join(prot, "gruppen_urteile.json"), encoding="utf-8"))["gruppen"]
    pfade = {}
    with open(gruppen_csv, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            pfade[r["clip"]] = r["quelle_pfad"]
    kandidaten = {}
    with open(os.path.join(prot, "detail.csv"), encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            if r["tag"] in tage and r["cluster"] in G and G[r["cluster"]]["art"] not in ("GEMISCHT", "OFFEN"):
                k = tp.abbilden(G[r["cluster"]]["art"], G[r["cluster"]].get("quelle", ""))
                if k in ("B01", "B05"):
                    kandidaten.setdefault(r["tag"], []).append((r, k))
    os.makedirs(wavdir, exist_ok=True)
    clips = []
    for tag in tage:
        benutzt = set()
        for z in zeiten:
            h, m = (int(x) for x in z.split(":"))
            ziel = h * 60 + m
            bes = sorted(kandidaten.get(tag, []), key=lambda rk: abs(datetime.fromisoformat(rk[0]["zeit"]).hour * 60 + datetime.fromisoformat(rk[0]["zeit"]).minute - ziel))
            for r, k in bes:
                if r["clip"] in benutzt:
                    continue
                zeile = {"clip": r["clip"], "tag": r["tag"], "stunde": datetime.fromisoformat(r["zeit"]).hour, "quelle_pfad": pfade.get(r["clip"], "")}
                pfad = gs.hole_wav("", wavdir, zeile)
                if not pfad:
                    continue
                benutzt.add(r["clip"])
                t = datetime.fromisoformat(r["zeit"])
                with open(pfad, "rb") as f:
                    b = base64.b64encode(f.read()).decode()
                clips.append({"id": r["clip"], "tag": f"{t:%d.%m.%Y}", "zeit": f"{t:%H:%M}", "audio": "data:audio/wav;base64," + b,
                              "protokoll": "Protokoll führt diesen Clip als " + ("Tiefbohrer" if k == "B01" else "Baggergeräusche") + f" (Gruppe {r['cluster']})."})
                break
    html = (SEITE.replace("%CSS%", gs.CSS).replace("%OPT%", json.dumps(OPTIONEN)).replace("%CLIPS%", json.dumps(clips))
            .replace("%N%", str(len(clips))))
    with open(ausgabe, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"{len(clips)} Clips, {len(html) / 1e6:.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("prot")
    ap.add_argument("gruppen_csv")
    ap.add_argument("wavdir")
    ap.add_argument("ausgabe")
    ap.add_argument("tage")
    ap.add_argument("--zeiten", default="07:30,09:30,11:00,13:00,14:30,16:00")
    a = ap.parse_args()
    main(a.prot, a.gruppen_csv, a.wavdir, a.ausgabe, a.tage.split(","), a.zeiten.split(","))
