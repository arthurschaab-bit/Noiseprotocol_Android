"""Schritt B: die ausgewaehlten WAVs aus den Stundenzips entpacken (Drive nur lesend).

Aufruf: python wavs_holen.py <Drive-Wurzel> <Kandidaten.csv> <Zielordner>
Zielordner muss ausserhalb des Repos liegen. WAVs werden byteweise kopiert, nie veraendert.
"""
import csv, glob, os, sys, wave, zipfile


def main(wurzel, kandidaten, ziel):
    os.makedirs(ziel, exist_ok=True)
    fehlt, falsch = [], []
    with open(kandidaten, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f, delimiter=";"))
    for r in rows:
        tag = r["zeit"][:10]
        basis = os.path.splitext(r["zip"])[0]
        zips = sorted(glob.glob(os.path.join(glob.escape(wurzel), tag, "WAV", glob.escape(basis) + "*.zip")))
        gefunden = False
        for zp in zips:
            with zipfile.ZipFile(zp) as z:
                for n in z.namelist():
                    if os.path.basename(n) == r["wav"]:
                        out = os.path.join(ziel, r["wav"])
                        with z.open(n) as src, open(out, "wb") as dst:
                            dst.write(src.read())
                        gefunden = True
                        break
            if gefunden:
                break
        if not gefunden:
            fehlt.append(r["wav"]); continue
        with wave.open(out, "rb") as w:
            fmt = (w.getframerate(), w.getnchannels(), w.getsampwidth() * 8, w.getnframes() / w.getframerate())
        if fmt[:3] != (16000, 1, 16):
            falsch.append((r["wav"], fmt))
    print(f"{len(rows)} Kandidaten, {len(rows) - len(fehlt)} gefunden, fehlen: {fehlt}")
    print(f"Format != 16kHz/mono/PCM16: {falsch}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
