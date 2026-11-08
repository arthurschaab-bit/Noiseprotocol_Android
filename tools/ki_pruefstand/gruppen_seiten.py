"""Seiten zum Anhoeren der Klanggruppen (Owner-Urteil je Gruppe) bauen.

Aufruf:
  python gruppen_seiten.py <Drive-Wurzel> <detail.csv> <WAV-Zielordner> <Ausgabeordner>
         [--je-gruppe 5] [--gruppen-je-seite 6] [--zuerst 3,24,27,0,22,9,15,11,14,6]

Je Gruppe (Spalte `cluster` >= 0 in detail.csv) werden `je-gruppe` typische Clips gewaehlt: nahe am
Gruppenzentrum, moeglichst von verschiedenen Tagen. Die WAVs werden aus den Stundenzips des Drive gelesen
(nur lesen), nach <WAV-Zielordner> kopiert und als data:-URI in die HTML-Seiten eingebettet.
Ausgabe: <Ausgabeordner>/seite1.html ... (je unter 16 MB). Nichts davon gehoert ins Repo.
"""
import argparse
import base64
import csv
import glob
import json
import os
import time
import zipfile
from collections import Counter, defaultdict
from statistics import median

TITEL = "Klanggruppen"
MENGEN = {
    "AL": "Ältere Aufnahmen (Juni–August), ab 75 dB · ",
    "NQ": "Neue Aufnahmen (ab 14.09.), 60–75 dB · ",
    "AQ": "Ältere Aufnahmen (Juni–August), 60–75 dB · ",
}
KLASSEN = [
    ("MOTOR", "Motorgeräusch (dauerhaft, Leerlauf, Fahren)"),
    ("HAEMMERND", "Wiederholte Schläge (Hämmern)"),
    ("SCHLAG", "Einzelner Schlag, Aufprall, Poltern"),
    ("BOHREND", "Bohren"),
    ("SAEGEND_SCHLEIFEND", "Sägen, Schleifen, Trennen"),
    ("KLIRREN", "Klirren, Metall auf Metall"),
    ("SIGNAL", "Warnsignal, Piepen"),
    ("SPRACHE_MUSIK", "Sprache, Musik"),
    ("VERKEHR_EXTERN", "Straße, Zug, Flugzeug"),
    ("SIRENE", "Sirene"),
    ("WIND_RAUSCHEN", "Wind, Rauschen, Hintergrund"),
    ("STILLE", "Stille, Grundpegel"),
    ("MESSGERAET", "Messgerät, Handhabung"),
    ("UNKLAR", "Unklar"),
    ("GEMISCHT", "Gruppe ist gemischt"),
]
QUELLEN = ["", "Bagger", "Tiefbohrer / Drehbohrgerät", "Ankerbohrgerät", "Drucklufthammer", "Hydraulikhammer",
           "Handhammer", "Betonbruch-Verladung", "Baustellenfahrzeug (Lkw, Radlader)", "Kran", "Pumpe / Aggregat", "Sonstiges"]


def zahl(s):
    return float(s.replace(",", "."))


def lade_gruppen(detail):
    gr = defaultdict(list)
    with open(detail, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            c = r["cluster"]
            if c not in ("", "-1"):
                gr[c].append(r)
    return gr


def waehle(mitglieder, n):
    kand = sorted(mitglieder, key=lambda r: zahl(r["cluster_abstand"]))[:80]
    gewaehlt, tage = [], set()
    for r in kand:
        if r["tag"] not in tage:
            gewaehlt.append(r)
            tage.add(r["tag"])
        if len(gewaehlt) == n:
            return gewaehlt
    for r in kand:  # zu wenige verschiedene Tage: mit den naechsten auffuellen
        if r not in gewaehlt:
            gewaehlt.append(r)
        if len(gewaehlt) == n:
            break
    return gewaehlt


def hole_wav(wurzel, zielordner, r):
    ziel = os.path.join(zielordner, r["clip"] + ".wav")
    if os.path.exists(ziel):
        return ziel
    name = r["clip"] + ".wav"
    quelle = r.get("quelle_pfad", "")
    if quelle:  # aeltere Aufnahmen: Zip oder Ordner im Bautagebuch
        for versuch in range(4):
            try:
                if quelle.lower().endswith(".zip"):
                    with zipfile.ZipFile(quelle) as z:
                        vorh = {os.path.basename(n): n for n in z.namelist()}
                        if name in vorh:
                            with open(ziel, "wb") as f:
                                f.write(z.read(vorh[name]))
                            return ziel
                else:
                    p = os.path.join(quelle, name)
                    if os.path.exists(p):
                        with open(p, "rb") as src, open(ziel, "wb") as dst:
                            dst.write(src.read())
                        return ziel
                return None
            except (OSError, zipfile.BadZipFile):
                time.sleep(5 * (versuch + 1))
        return None
    muster = os.path.join(glob.escape(wurzel), r["tag"], "WAV", f"audio_{r['tag']}_{int(r['stunde']):02d}-00*.zip")
    for versuch in range(4):
        try:
            for zp in sorted(glob.glob(muster)):
                with zipfile.ZipFile(zp) as z:
                    for n in z.namelist():
                        if os.path.basename(n) == name:
                            with open(ziel, "wb") as f:
                                f.write(z.read(n))
                            return ziel
            return None
        except (OSError, zipfile.BadZipFile):
            time.sleep(5 * (versuch + 1))
    return None


CSS = """
:root{--bg:#f3f4f2;--fg:#1c2321;--mut:#5d6965;--card:#fff;--line:#d5dbd8;--acc:#1f5f4a;--accfg:#fff;--ok:#2d7d4f;--sel:#e1efe8}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#121715;--fg:#e4eae7;--mut:#93a09b;--card:#1b2220;--line:#323d39;--acc:#5fc4a1;--accfg:#08231a;--ok:#6fd195;--sel:#22372f;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#121715;--fg:#e4eae7;--mut:#93a09b;--card:#1b2220;--line:#323d39;--acc:#5fc4a1;--accfg:#08231a;--ok:#6fd195;--sel:#22372f;color-scheme:dark}
body{background:var(--bg);color:var(--fg);font:15px/1.5 "IBM Plex Sans",system-ui,sans-serif;padding-block:20px 80px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
.wrap{max-width:820px;margin:0 auto}
h1{font:600 24px/1.2 "IBM Plex Sans",system-ui,sans-serif;margin:0 0 6px;text-wrap:balance}
h2{font:600 18px/1.3 "IBM Plex Sans",system-ui,sans-serif;margin:0}
p.lead{color:var(--mut);margin:0 0 12px}
nav{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 12px;font-size:13px}
nav a{color:var(--acc)}
.bar{position:sticky;top:env(safe-area-inset-top,0px);background:var(--bg);padding:8px 0;border-bottom:1px solid var(--line);z-index:5;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
.bar progress{flex:1;min-width:120px;height:8px}
.mono{font-family:"IBM Plex Mono",ui-monospace,monospace;font-variant-numeric:tabular-nums}
.grp{background:var(--card);border:1px solid var(--line);border-radius:6px;padding:14px;margin:16px 0;display:grid;gap:12px;min-width:0}
.grp.done{border-color:var(--ok)}
.fact{color:var(--mut);font-size:13px}
details{font-size:13px;color:var(--mut)}
details summary{cursor:pointer}
.clips{display:grid;gap:10px;grid-template-columns:repeat(auto-fill,minmax(230px,1fr))}
.clip{display:grid;gap:4px;min-width:0;font-size:12px;color:var(--mut)}
audio{width:100%}
select,input[type=text]{box-sizing:border-box;padding:5px 7px;border:1px solid var(--line);border-radius:4px;background:var(--bg);color:var(--fg);font:inherit;max-width:100%}
input[type=text]{width:100%}
.zeile{display:flex;gap:18px;flex-wrap:wrap;align-items:center;font-size:13px}
.opts{display:flex;flex-wrap:wrap;gap:6px}
.opts label{border:1px solid var(--line);border-radius:4px;padding:6px 9px;cursor:pointer;font-size:13px;display:flex;flex-direction:column;min-width:0;position:relative}
.opts label small{color:var(--mut);font-size:11px}
.opts input{position:absolute;opacity:0}
.opts label:has(input:checked){background:var(--sel);border-color:var(--acc)}
.opts label:has(input:focus-visible){outline:2px solid var(--acc)}
button{background:var(--acc);color:var(--accfg);border:0;border-radius:4px;padding:7px 12px;font:inherit;cursor:pointer}
textarea{width:100%;box-sizing:border-box;height:120px;background:var(--card);color:var(--fg);border:1px solid var(--line);font-size:12px}
#msg{color:var(--mut);font-size:13px}
"""

JS = r"""
const KLASSEN=%KLASSEN%, QUELLEN=%QUELLEN%, GRUPPEN=%GRUPPEN%;
const KEINE=['SPRACHE_MUSIK','VERKEHR_EXTERN','SIRENE','WIND_RAUSCHEN','STILLE','MESSGERAET'];
const urteil={}, clipUrteil={}; let db=null;
const $=s=>document.querySelector(s);
function render(){
  const root=$('#liste'); root.textContent='';
  GRUPPEN.forEach(g=>{
    const u=urteil['g'+g.id]||{};
    const d=document.createElement('section'); d.className='grp'+(u.klasse?' done':'');
    const h=document.createElement('h2'); h.textContent='Gruppe '+g.id+' · '+g.anzahl+' Clips · Median '+g.median+' dB';
    const f=document.createElement('div'); f.className='fact'; f.textContent=g.fakten;
    const det=document.createElement('details'); const sm=document.createElement('summary'); sm.textContent='Hinweise von App und YAMNet (erst nach dem Anhören öffnen)';
    const hp=document.createElement('p'); hp.textContent=g.hinweis; det.append(sm,hp);
    const cl=document.createElement('div'); cl.className='clips';
    g.clips.forEach((c,i)=>{
      const w=document.createElement('div'); w.className='clip';
      const t=document.createElement('span'); t.className='mono'; t.textContent=c.zeit+' · '+c.pegel+' dB';
      const a=document.createElement('audio'); a.controls=true; a.preload='none'; a.src=c.audio;
      const s=document.createElement('select'); s.id='s'+g.id+'_'+i;
      const o0=document.createElement('option'); o0.value=''; o0.textContent='wie Gruppe'; s.append(o0);
      KLASSEN.forEach(k=>{ const o=document.createElement('option'); o.value=k[0]; o.textContent=k[0]; s.append(o); });
      s.value=(clipUrteil[c.id]||{}).klasse||'';
      s.onchange=()=>speichernClip(c.id,s.value);
      w.append(t,a,s); cl.append(w);
    });
    const gid='g'+g.id;
    const o=document.createElement('div'); o.className='opts';
    KLASSEN.forEach(k=>{
      const lb=document.createElement('label'); const r=document.createElement('input');
      r.type='radio'; r.name=gid; r.id='r'+g.id+'_'+k[0]; r.value=k[0]; r.checked=u.klasse===k[0];
      r.onchange=()=>upd(gid,{klasse:k[0],baustelle:(urteil[gid]||{}).baustelle||(KEINE.includes(k[0])?'nein':'ja')});
      lb.append(r,document.createTextNode(k[0])); const sm2=document.createElement('small'); sm2.textContent=k[1]; lb.append(sm2); o.append(lb);
    });
    const zeile=document.createElement('div'); zeile.className='zeile';
    const ql=document.createElement('label'); ql.append(document.createTextNode('Quelle (optional) '));
    const qs=document.createElement('select'); qs.id='q'+g.id;
    QUELLEN.forEach(q=>{ const op=document.createElement('option'); op.value=q; op.textContent=q||'unbekannt / keine Angabe'; qs.append(op); });
    qs.value=u.quelle||''; qs.onchange=()=>upd(gid,{quelle:qs.value}); ql.append(qs);
    const bl=document.createElement('span'); bl.append(document.createTextNode('Baustelle: '));
    ['ja','nein','unklar'].forEach(b=>{
      const l2=document.createElement('label'); const r2=document.createElement('input');
      r2.type='radio'; r2.name='b'+g.id; r2.id='b'+g.id+'_'+b; r2.value=b; r2.checked=u.baustelle===b;
      r2.onchange=()=>upd(gid,{baustelle:b}); l2.append(r2,document.createTextNode(' '+b+'  ')); bl.append(l2);
    });
    zeile.append(ql,bl);
    const n=document.createElement('input'); n.type='text'; n.id='n'+g.id; n.placeholder='Notiz zur Gruppe (optional)'; n.value=u.notiz||'';
    n.onchange=()=>upd(gid,{notiz:n.value});
    d.append(h,f,cl,det,o,zeile,n); root.append(d);
  });
  const fertig=GRUPPEN.filter(g=>(urteil['g'+g.id]||{}).klasse).length;
  $('#zaehler').textContent=fertig+' / '+GRUPPEN.length+' Gruppen'; $('#fort').value=fertig;
  $('#export').value=JSON.stringify({gruppen:urteil,clips:clipUrteil},null,1);
}
function neu(){ const y=window.scrollY; render(); window.scrollTo(0,y); }
function upd(id,patch){ const alt=urteil[id]||{klasse:'',quelle:'',baustelle:'',notiz:''}; speichern(id,Object.assign({},alt,patch)); }
async function speichern(id,data){
  urteil[id]=data; neu();
  if(db){ try{ await db.collection('gruppen').doc(id).set(data); $('#msg').textContent='Gespeichert.'; }catch(e){ $('#msg').textContent='Speichern fehlgeschlagen, bitte unten den Export kopieren.'; } }
}
async function speichernClip(id,klasse){
  clipUrteil[id]={klasse}; neu();
  if(db){ try{ await db.collection('clips').doc(id).set({klasse}); $('#msg').textContent='Gespeichert.'; }catch(e){ $('#msg').textContent='Speichern fehlgeschlagen, bitte unten den Export kopieren.'; } }
}
render();
(async()=>{
  try{ db=await claude.use('db'); }catch(e){ db=null; }
  if(!db){ $('#msg').textContent='Kein gemeinsamer Speicher: Urteile gelten nur in dieser Ansicht. Unten Export kopieren.'; return; }
  const aktiv=()=>{ const ae=document.activeElement; return ae&&ae.tagName==='INPUT'&&ae.type==='text'; };
  db.collection('gruppen').onSnapshot(s=>{ s.docs.forEach(x=>{ const v=x.data(); if(v) urteil[x.id]=v; }); if(!aktiv()) neu(); },
    e=>{ $('#msg').textContent='Speicher nicht lesbar: '+((e&&e.code)||''); });
  db.collection('clips').onSnapshot(s=>{ s.docs.forEach(x=>{ const v=x.data(); if(v) clipUrteil[x.id]=v; }); if(!aktiv()) neu(); }, ()=>{});
})();
$('#kopie').onclick=async()=>{ const t=$('#export'); try{ await navigator.clipboard.writeText(t.value); $('#msg').textContent='Kopiert.'; }catch(e){ t.select(); $('#msg').textContent='Markiert, bitte kopieren.'; } };
"""


def seite_html(nr, gesamt, gruppen, navigation):
    js = JS.replace("%KLASSEN%", json.dumps(KLASSEN)).replace("%QUELLEN%", json.dumps(QUELLEN)).replace("%GRUPPEN%", json.dumps(gruppen))
    nav = " · ".join(navigation)
    return f"""<title>{TITEL} Teil {nr}</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono&family=IBM+Plex+Sans:wght@400;600&display=swap">
<style>{CSS}</style>
<div class="wrap">
<h1>{TITEL} Teil {nr} von {gesamt}</h1>
<p class="lead">Je Gruppe 5 typische Clips anhören und die Geräuschart der ganzen Gruppe wählen. Die Quelle (Gerät) ist optional, „Baustelle“ ist vorbelegt und änderbar. Klingt ein Clip anders, wähle unter dem Player eine eigene Klasse. Die Hinweise von App und YAMNet sind eingeklappt, damit sie dein Urteil nicht lenken. Alles wird automatisch gespeichert.</p>
<nav>{nav}</nav>
<div class="bar"><span class="mono" id="zaehler"></span><progress id="fort" value="0" max="{len(gruppen)}"></progress><span id="msg"></span></div>
<div id="liste"></div>
<h2 style="margin-top:28px">Export</h2>
<p class="lead">Falls das Speichern nicht klappt: Inhalt kopieren und zurückgeben.</p>
<textarea id="export" readonly></textarea><p><button id="kopie" type="button">Export kopieren</button></p>
</div>
<script>{js}</script>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wurzel")
    ap.add_argument("detail")
    ap.add_argument("wavordner")
    ap.add_argument("ausgabe")
    ap.add_argument("--je-gruppe", type=int, default=5)
    ap.add_argument("--gruppen-je-seite", type=int, default=6)
    ap.add_argument("--zuerst", default="3,24,27,0,22,9,15,11,14,6")
    ap.add_argument("--titel", default="Klanggruppen")
    ap.add_argument("--nav", default="", help="Zeilen 'Teil N=URL' getrennt durch |, werden als Navigation eingebaut")
    a = ap.parse_args()
    os.makedirs(a.wavordner, exist_ok=True)
    os.makedirs(a.ausgabe, exist_ok=True)

    gr = lade_gruppen(a.detail)
    global TITEL
    TITEL = a.titel
    zuerst = [x for x in a.zuerst.split(",") if x]
    if all("rang" in v[0] for v in gr.values()):  # Reihenfolge steht in der Eingabe
        reihenfolge = sorted(gr, key=lambda g: int(gr[g][0]["rang"]))
    else:
        reihenfolge = [g for g in zuerst if g in gr] + sorted((g for g in gr if g not in zuerst), key=lambda g: -len(gr[g]))
    nav_links = dict(p.split("=", 1) for p in a.nav.split("|") if "=" in p)

    daten = []
    for g in reihenfolge:
        m = gr[g]
        clips = []
        for r in waehle(m, a.je_gruppe):
            pfad = hole_wav(a.wurzel, a.wavordner, r)
            if not pfad:
                print("WAV nicht gefunden:", r["clip"])
                continue
            with open(pfad, "rb") as f:
                b = base64.b64encode(f.read()).decode()
            t = r["zeit"]
            clips.append(dict(id=r["clip"], zeit=f"{t[8:10]}.{t[5:7]}. {t[11:16]}", pegel=r["pegel_db"],
                              audio="data:audio/wav;base64," + b))
        pegel = [zahl(r["pegel_db"]) for r in m]
        app = Counter(r["app_baulaerm"] for r in m)
        app_text = ("Keine App-Klassifikation (ältere Aufnahmen)" if app.get("-")
                    else f"App-Label: Baulärm {app['ja']}, möglich {app['möglich']}, nein {app['nein']}")
        top = Counter(r["yamnet_top1"] for r in m).most_common(3)
        spitzen = Counter(r["app_spitze"] for r in m if r["app_spitze"]).most_common(3)
        tage = len({r["tag"] for r in m})
        std = Counter(int(r["stunde"]) for r in m).most_common(3)
        daten.append(dict(
            id=g, anzahl=len(m), median=f"{median(pegel):.1f}".replace(".", ","),
            fakten=MENGEN.get(m[0].get("menge", ""), "") + f"Pegel {min(pegel):.0f}–{max(pegel):.0f} dB · {tage} Tage · häufigste Stunden: " + ", ".join(f"{h} Uhr" for h, _ in std),
            hinweis=(app_text
                     + (f"; App-Spitzen: {', '.join(f'{k} ({v})' for k, v in spitzen)}" if spitzen else "")
                     + f". Häufigste YAMNet-Klasse: {', '.join(f'{k} ({v})' for k, v in top)}."),
            clips=clips))
        print(f"Gruppe {g}: {len(clips)} Clips")

    je = a.gruppen_je_seite
    seiten = [daten[i:i + je] for i in range(0, len(daten), je)]
    for i, gruppen in enumerate(seiten, 1):
        navi = [(f'<a href="{nav_links[str(k)]}">Teil {k}</a>' if str(k) in nav_links and k != i else f"<b>Teil {k}</b>" if k == i else f"Teil {k}")
                for k in range(1, len(seiten) + 1)]
        html = seite_html(i, len(seiten), gruppen, navi)
        pfad = os.path.join(a.ausgabe, f"seite{i}.html")
        with open(pfad, "w", encoding="utf-8") as f:
            f.write(html)
        print(f"{pfad}: {len(gruppen)} Gruppen, {len(html) / 1e6:.1f} MB")
    with open(os.path.join(a.ausgabe, "zuordnung.json"), "w", encoding="utf-8") as f:
        json.dump({str(d["id"]): [c["id"] for c in d["clips"]] for d in daten}, f)


if __name__ == "__main__":
    main()
