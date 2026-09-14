# Prompt: High-End-Bericht Schritt 4b — Gebietseinstufung generalisieren

Dritter Baustein von Schritt 4. Owner-Entscheidung (Klärung 13.09.2026, siehe
`docs/DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 6): die Gebietseinstufung soll **echt
generisch** werden, nicht nur ein Textfeld, das nirgends ausgewertet wird.
`ReportConfigEntity.gebietseinstufung` existiert bereits (freier Text, siehe
`docs/PROMPT_BERICHT_KONFIGURATION_UND_ERSTELLUNG.md`), aber die zugehörige Rechenlogik im
Original ist komplett auf einen einzigen Gebietstyp hart verdrahtet.

**Dieser Auftrag hat eine echte rechtliche Recherche-Komponente, keine reine Coding-Aufgabe** —
siehe Abschnitt 2. Unterschätze das nicht als "einfach die Zahl konfigurierbar machen".

---

## 0 · Arbeitsregeln

- Neuer Branch von `main`: `feature/bericht-gebietseinstufung`. **Nie auf `main` pushen.**
- Läuft im selben Python-Modul wie `docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md` (reine Logik, mit
  pytest testbar, keine Chaquopy-Anbindung nötig) — **baut auf dessen Ergebnis auf**, sinnvoll
  erst danach zu beginnen.
- Commit-Nachrichten deutsch.
- **Erfinde keine Rechtswerte.** Jede Richtwert-Zahl, die du einträgst, muss aus einer konkreten,
  nennbaren Quelle stammen (siehe Abschnitt 2) — nicht aus Erinnerung/Training. Wenn du dir bei
  einer Zahl nicht zu 100 % sicher bist: **halte an, frag den Owner**, statt zu raten. Das ist ein
  Dokument, das in einem Rechtsverfahren verwendet werden soll — eine falsche Zahl hier ist kein
  Bug wie jeder andere.

---

## 1 · Ist-Zustand — was heute hart codiert ist

Alle Fundstellen: `gesamtbericht_lib_v3.py`, Repo `arthurschaab-bit/Baul-rm`.

### Konstanten (Zeile 44-51)

```python
COVERAGE_VALID  = 0.90
COVERAGE_WINDOW = 0.70
TAG_REF_SEC     = 13 * 3600
RW_TAG_WA       = 55.0   # Richtwert Tag, Allgemeines Wohngebiet
EINGREIF_TAG    = 60.0   # Eingreifschwelle Tag (Richtwert + 5 dB)
RW_NACHT_WA     = 40.0   # Richtwert Nacht
EINGREIF_NACHT  = 45.0   # Eingreifschwelle Nacht
NACHT_SPITZE    = 60.0   # Spitzenpegel-Kriterium Nacht
```

`RW_TAG_WA` und `RW_NACHT_WA` sind **nur für Allgemeine Wohngebiete (§ 4 BauNVO)** gültig — die
Namensgebung selbst sagt das schon (`_WA`).

### Fließtext, der den Gebietstyp als gegeben behandelt

- Zeile 1163: "Für das allgemeine Wohngebiet werden tags 55 dB(A) und nachts 40 dB(A) als
  Richtwerte angesetzt; die Eingreifschwelle liegt jeweils 5 dB höher."
- Zeile 1240: "Allgemeines Wohngebiet (§ 4 BauNVO): Tagzeit 07–20 Uhr: 55 dB(A) | Nachtzeit
  20–07 Uhr: 40 dB(A)"
- Zeile 1254-1261 (`_legal_p2`): "WA-Charakter (§4 BauNVO) für {STANDORT_KURZ} behördlich
  bestätigt" … "WA-Qualifikation ist Voraussetzung für diese Bewertung — kein anderer Gebietstyp
  (GI, GE, MI) trifft zu."
- Weitere Vorkommen von "Wohngebiet"/"WA"/"55 dB(A)"/"40 dB(A)" als feste Zahl statt Parameter:
  durchsuche die Datei selbst nach diesen Strings, die obige Liste ist nicht vollständig — such
  systematisch (`grep -n "WA\|55 dB\|40 dB\|Wohngebiet" gesamtbericht_lib_v3.py`), bevor du
  anfängst, sonst bleibt an einer Stelle die Alt-Zahl stehen.

---

## 2 · Recherche: welche Gebietstypen, welche Richtwerte

**Die Rechtsgrundlage ist die AVV Baulärm** (Allgemeine Verwaltungsvorschrift zum Schutz gegen
Baulärm), Nummer 3.1.1, die den Immissionsrichtwerttabellen-Bezug zu den Baugebietstypen aus
§§ 2-9 BauNVO herstellt. **Besorge dir den tatsächlichen Text der AVV Baulärm** (offizielle
Fundstelle, z. B. über die Bundesanzeiger-Verkündung oder eine zitierfähige Rechtsdatenbank) statt
die Tabelle aus einer Zusammenfassung oder aus dem Gedächtnis zu übernehmen. Trage danach für
jeden im BauNVO benannten Gebietstyp (mindestens: WR – Reines Wohngebiet, WA – Allgemeines
Wohngebiet, WB – Besonderes Wohngebiet, MD – Dorfgebiet, MI – Mischgebiet, MU – Urbanes Gebiet,
MK – Kerngebiet, GE – Gewerbegebiet, GI – Industriegebiet) den jeweiligen Tag-/Nachtrichtwert und
die Eingreifschwelle ein.

**Prüfpunkt, den du im PR ausdrücklich beantworten musst:** Ist die Eingreifschwelle für jeden
Gebietstyp tatsächlich "Richtwert + 5 dB" (wie im Original für WA angenommen, Zeile 47-50), oder
gilt das nur für WA? Nimm das nicht ungeprüft aus dem Original für alle anderen Typen an.

**Wenn du eine belastbare Quelle nicht sicher beschaffen kannst:** Baue die Struktur (Enum/
Konfigurationstabelle) mit **nur dem bereits verifizierten WA-Eintrag** befüllt, alle anderen
Gebietstypen als vorbereitete, aber leere/`TODO`-markierte Einträge, und sag im PR explizit,
welche Werte der Owner selbst eintragen oder gegenprüfen muss. **Eine unvollständige, aber
korrekte Tabelle ist besser als eine vollständige, aber geratene.**

---

## 3 · Umsetzung

**3.1** Neuer Typ (Vorschlag, im selben Modul wie `PROMPT_BERICHT_PYTHON_KERNLOGIK.md`):

```python
@dataclass
class Gebietstyp:
    kuerzel: str            # "WA", "WR", ...
    bezeichnung: str        # "Allgemeines Wohngebiet"
    bauNVO_paragraph: str   # "§ 4 BauNVO"
    richtwert_tag_db: float
    richtwert_nacht_db: float
    eingreifschwelle_tag_db: float
    eingreifschwelle_nacht_db: float
```

Eine Tabelle/Registry dieser Typen, mit dem WA-Eintrag aus dem Original als erstem, verifiziertem
Fall (`RW_TAG_WA=55.0`, `RW_NACHT_WA=40.0`, `EINGREIF_TAG=60.0`, `EINGREIF_NACHT=45.0` — diese
vier Zahlen sind bereits durch das Referenzskript belegt, die brauchst du nicht neu zu
recherchieren).

**3.2** `ReportConfigEntity.gebietseinstufung` (Kotlin-Seite) auf das `kuerzel` dieser Tabelle
abbilden. **Offene Entscheidung, nicht selbst treffen:** Soll das Kotlin-Feld weiterhin freier
Text bleiben (Risiko: Tippfehler, kein Bezug zu einer gültigen Zeile der Tabelle) oder auf eine
feste Auswahl (Dropdown/`ExposedDropdownMenu`) umgestellt werden, sobald die Gebietstypen-Tabelle
feststeht? Das war in `docs/PROMPT_BERICHT_KONFIGURATION_UND_ERSTELLUNG.md` bewusst offen
gelassen. Jetzt, wo du die tatsächliche Zahl an Gebietstypen kennst, kannst du das mit dem Owner
konkret klären statt abstrakt.

**3.3** Fließtext generalisieren. Jede der in Abschnitt 1 gefundenen Stellen muss den Gebietstyp
und seine Werte aus der Tabelle ziehen, nicht mehr "WA"/"55"/"40" fest im String haben. Bei den
juristischen Begründungssätzen (z. B. "WA-Qualifikation ist Voraussetzung … kein anderer
Gebietstyp trifft zu", Zeile 1261): **das wird bei anderen Gebietstypen ein anderer Satz, nicht
nur andere Zahlen** — z. B. bei einem Mischgebiet ist die Argumentationsstruktur eine andere
(andere Schutzwürdigkeit, andere Referenznorm-Auslegung). Formuliere für jeden Gebietstyp einen
eigenen, fachlich korrekten Begründungssatz, oder — falls dir das rechtlich zu heikel ist, um es
allein zu formulieren — markiere diese Textstellen als `TODO(Owner)` mit Platzhaltertext und sag
im PR ausdrücklich, dass die Rechtsprosa für Nicht-WA-Gebietstypen noch der Freigabe durch den
Owner (oder eine rechtskundige Person) bedarf. **Das ist eine der beiden Stellen in diesem
gesamten Bericht-Umbau, bei der eine falsche Automatisierung mehr schadet als eine ehrliche
Lücke.**

**3.4** `konservativAnnahmeDb`-Parameter aus `docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md` Abschnitt
2.2 mit `richtwert_tag_db` des gewählten Gebietstyps befüllen (im Original fest `RW_TAG_WA`).

---

## 4 · Tests

- Reine Funktionstests: Tabellen-Lookup für jeden eingetragenen Gebietstyp, inklusive
  Fehlerverhalten bei unbekanntem `kuerzel` (klare Fehlermeldung, kein stiller Fallback auf WA).
- Falls du Fließtext-Generierung baust (3.3): ein Test pro Gebietstyp, der prüft, dass der
  erzeugte Text tatsächlich dessen `kuerzel`/Zahlenwerte enthält, nicht mehr fest "WA"/"55"/"40".
- Kein Test kann die rechtliche Korrektheit der Prosa selbst prüfen — das ist Aufgabe der
  menschlichen Prüfung (Abschnitt 3.3), nicht automatisierbar.

---

## 5 · Doku

- `docs/DATENMAPPING_BERICHT_SCHRITT4.md`: Abschnitt 6, Punkt 4 ("Gebietseinstufungs-
  Generalisierung … eigener Klärungspunkt") durch das tatsächliche Ergebnis ersetzen — welche
  Gebietstypen sind vollständig, welche als `TODO(Owner)` markiert.
- Neue Quellenangabe im Modul-Docstring: die genaue Fundstelle der AVV Baulärm, die du für die
  Tabelle verwendet hast (Datum/Fassung, damit eine spätere Novellierung nachvollziehbar bleibt).

---

## 6 · Definition of Done

1. `python -m pytest` grün, Ausgabe im PR.
2. Für jeden eingetragenen Gebietstyp ist die Quelle der Richtwerte im PR **explizit genannt**
   (nicht nur "aus AVV Baulärm", sondern welche Fassung/Fundstelle).
3. Jede Textstelle aus Abschnitt 1 entweder generalisiert oder als `TODO(Owner)` markiert — keine
   stillschweigend WA-spezifisch gebliebene Stelle.
4. Abschnitt 3.2 (Freitext vs. Dropdown) mit dem Owner geklärt oder als offene Frage im PR
   benannt.
5. Draft-PR gegen `main`: welche Gebietstypen sind einsatzbereit, welche nicht, und warum.
