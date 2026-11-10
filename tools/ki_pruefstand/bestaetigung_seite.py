"""Schritt C: baut die HTML-Seite zur Owner-Bestaetigung (WAVs als data:-URIs eingebettet).

Aufruf: python bestaetigung_seite.py <Kandidaten.csv> <WAV-Ordner> <Ausgabe.html>
Ausgabe gehoert nicht ins Repo. Der App-Label wird absichtlich NICHT angezeigt (blind).
"""
import base64
import csv
import json
import random
import sys

KLASSEN = [
    ("HYD_HAMMER", "Hydraulikhammer am CAT-Bagger"),
    ("DRUCKLUFT", "Manueller Drucklufthammer"),
    ("ANKERBOHR", "Wöhrl-Ankerbohrgerät"),
    ("DREHBOHR", "Großdrehbohrgerät"),
    ("VERLADUNG", "Betonbruch-Verladung"),
    ("BAU_SONST", "Baustelle, anderes"),
    ("KEINE_BAU", "Keine Baustelle"),
    ("UNKLAR", "Unklar"),
]

CSS = """
:root{--bg:#f3f4f2;--fg:#1c2321;--mut:#5d6965;--card:#fff;--line:#d5dbd8;--acc:#1f5f4a;--accfg:#fff;--ok:#2d7d4f;--sel:#e1efe8}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#121715;--fg:#e4eae7;--mut:#93a09b;--card:#1b2220;--line:#323d39;--acc:#5fc4a1;--accfg:#08231a;--ok:#6fd195;--sel:#22372f;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#121715;--fg:#e4eae7;--mut:#93a09b;--card:#1b2220;--line:#323d39;--acc:#5fc4a1;--accfg:#08231a;--ok:#6fd195;--sel:#22372f;color-scheme:dark}
body{background:var(--bg);color:var(--fg);font:15px/1.5 "IBM Plex Sans",system-ui,sans-serif;padding-block:20px 80px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
.wrap{max-width:760px;margin:0 auto}
h1{font:600 24px/1.2 "IBM Plex Sans",system-ui,sans-serif;margin:0 0 6px;text-wrap:balance}
p.lead{color:var(--mut);margin:0 0 16px}
.bar{position:sticky;top:env(safe-area-inset-top,0px);background:var(--bg);padding:8px 0;border-bottom:1px solid var(--line);z-index:5;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
.bar progress{flex:1;min-width:120px;height:8px}
.mono{font-family:"IBM Plex Mono",ui-monospace,monospace;font-variant-numeric:tabular-nums}
.clip{background:var(--card);border:1px solid var(--line);border-radius:6px;padding:12px;margin:12px 0;display:grid;gap:10px;min-width:0}
.clip.done{border-color:var(--ok)}
.head{display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap;font-size:13px;color:var(--mut)}
.head b{color:var(--fg)}
audio{width:100%}
.opts{display:flex;flex-wrap:wrap;gap:6px}
.opts label{border:1px solid var(--line);border-radius:4px;padding:6px 9px;cursor:pointer;font-size:13px;display:flex;flex-direction:column;min-width:0;position:relative}
.opts label small{color:var(--mut);font-size:11px}
.opts input{position:absolute;opacity:0}
.opts label:has(input:checked){background:var(--sel);border-color:var(--acc)}
.opts label:has(input:focus-visible){outline:2px solid var(--acc)}
input[type=text]{width:100%;box-sizing:border-box;padding:6px 8px;border:1px solid var(--line);border-radius:4px;background:var(--bg);color:var(--fg);font:inherit}
button{background:var(--acc);color:var(--accfg);border:0;border-radius:4px;padding:7px 12px;font:inherit;cursor:pointer}
textarea{width:100%;box-sizing:border-box;height:140px;background:var(--card);color:var(--fg);border:1px solid var(--line);font-size:12px}
#msg{color:var(--mut);font-size:13px}
"""

JS = r"""
const KLASSEN=%KLASSEN%, CLIPS=%CLIPS%;
const labels={}; let db=null;
const $=s=>document.querySelector(s);
function render(){
  const root=$('#liste'); root.textContent='';
  CLIPS.forEach((c,i)=>{
    const l=labels[c.id]||{};
    const d=document.createElement('div'); d.className='clip'+(l.klasse?' done':'');
    const h=document.createElement('div'); h.className='head';
    const t1=document.createElement('span'); t1.textContent='Clip '+(i+1)+' · '+c.zeit;
    const t2=document.createElement('span'); t2.className='mono'; t2.textContent=c.pegel+' dB';
    h.append(t1,t2);
    const a=document.createElement('audio'); a.controls=true; a.preload='none'; a.src=c.audio;
    const o=document.createElement('div'); o.className='opts';
    KLASSEN.forEach(k=>{
      const lb=document.createElement('label'); const r=document.createElement('input');
      r.type='radio'; r.name='k'+i; r.id='k'+i+'_'+k[0]; r.value=k[0]; r.checked=l.klasse===k[0];
      r.onchange=()=>speichern(c.id,{klasse:k[0],notiz:(labels[c.id]||{}).notiz||''});
      lb.append(r,document.createTextNode(k[0])); const s=document.createElement('small'); s.textContent=k[1]; lb.append(s); o.append(lb);
    });
    const n=document.createElement('input'); n.type='text'; n.id='n'+i; n.placeholder='Notiz (optional)'; n.value=l.notiz||'';
    n.onchange=()=>speichern(c.id,{klasse:(labels[c.id]||{}).klasse||'',notiz:n.value});
    d.append(h,a,o,n); root.append(d);
  });
  const n=Object.values(labels).filter(x=>x.klasse).length;
  $('#zaehler').textContent=n+' / '+CLIPS.length; $('#fort').value=n;
  $('#export').value=JSON.stringify(labels,null,1);
}
async function speichern(id,data){
  labels[id]=data; const y=window.scrollY; render(); window.scrollTo(0,y);
  if(db){ try{ await db.collection('labels').doc(id).set(data); $('#msg').textContent='Gespeichert.'; }catch(e){ $('#msg').textContent='Speichern fehlgeschlagen, bitte unten den Export kopieren.'; } }
}
render();
(async()=>{
  try{ db=await claude.use('db'); }catch(e){ db=null; }
  if(!db){ $('#msg').textContent='Kein gemeinsamer Speicher: Antworten gelten nur in dieser Ansicht. Unten Export kopieren.'; return; }
  db.collection('labels').onSnapshot(s=>{
    s.docs.forEach(x=>{ const v=x.data(); if(v) labels[x.id]=v; });
    const ae=document.activeElement; if(!ae||ae.tagName!=='INPUT'||ae.type!=='text'){ const y=window.scrollY; render(); window.scrollTo(0,y); }
  }, e=>{ $('#msg').textContent='Speicher nicht lesbar: '+((e&&e.code)||''); });
})();
$('#kopie').onclick=async()=>{ const t=$('#export'); try{ await navigator.clipboard.writeText(t.value); $('#msg').textContent='Kopiert.'; }catch(e){ t.select(); $('#msg').textContent='Markiert, bitte kopieren.'; } };
"""


def main(kand, wavdir, out):
    with open(kand, encoding="utf-8") as f:
        rows = list(csv.DictReader(f, delimiter=";"))
    random.Random(7).shuffle(rows)
    clips = []
    for r in rows:
        with open(f"{wavdir}/{r['wav']}", "rb") as f:
            b = base64.b64encode(f.read()).decode()
        t = r["zeit"]
        clips.append(dict(id=r["wav"].replace(".wav", ""), zeit=f"{t[8:10]}.{t[5:7]}. {t[11:16]}",
                          pegel=r["pegel_db"].replace(".", ","), audio="data:audio/wav;base64," + b))
    js = JS.replace("%KLASSEN%", json.dumps(KLASSEN)).replace("%CLIPS%", json.dumps(clips))
    html = f"""<title>Lärmquellen Bestätigung</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono&family=IBM+Plex+Sans:wght@400;600&display=swap">
<style>{CSS}</style>
<div class="wrap">
<h1>Lärmquellen bestätigen</h1>
<p class="lead">{len(clips)} Aufnahmen à 5 s in zufälliger Reihenfolge. Pro Clip abspielen und eine Klasse wählen. Das heutige App-Label ist ausgeblendet. Antworten werden automatisch gespeichert.</p>
<div class="bar"><span class="mono" id="zaehler"></span><progress id="fort" value="0" max="{len(clips)}"></progress><span id="msg"></span></div>
<div id="liste"></div>
<h2 style="font-size:16px;margin-top:28px">Export</h2>
<p class="lead">Falls das Speichern nicht klappt: Inhalt kopieren und zurückgeben.</p>
<textarea id="export" readonly></textarea><p><button id="kopie" type="button">Export kopieren</button></p>
</div>
<script>{js}</script>
"""
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"{len(clips)} Clips, {len(html)/1e6:.1f} MB")


main(*sys.argv[1:4])
