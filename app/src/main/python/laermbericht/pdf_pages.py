"""Matplotlib-Seitenport aus gesamtbericht_lib_v3.py (646c7c7e7edfa87332d1e6783767f626f67741dd).

Figure/fig.text, Tabellen, Balken und Tageskurven bleiben PdfPages-Seiten wie im
Original. Ausgeschlossene Fallquellen entfallen. Langtexte und Listen werden auf
Folgeseiten fortgesetzt statt still abgeschnitten. Jede Seitenfunktion nennt ihre
Originalfundstelle; vollständige Zuordnung in docs/BERICHT_PDF_SEITEN_V1.md.
"""
from __future__ import annotations

import math
from datetime import datetime
from zoneinfo import ZoneInfo

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.font_manager import FontProperties
from matplotlib.patches import Rectangle
import numpy as np
import pandas as pd
from PIL import Image, ImageOps

TITLE = "#1F4E79"
ACCENT = "#2E75B6"
RED = "#C00000"
ORANGE = "#C05000"
TIER = {"valid": "hohe Abdeckung", "partial": "Teilerfassung", "window": "Messfenster"}
ENVIRONMENT = {"inside": "Innenraum", "outside": "Außenmessung", "unknown": "Innen-/Außenlage unbekannt"}


def number(value):
    return f"{value:.1f}" if value is not None and math.isfinite(value) else "—"


def _lines(fig, text, size=11, width=.88):
    """Breitenprüfung wie fig_text_wrap im Original, ohne stilles min_y-Abschneiden."""
    renderer = fig.canvas.get_renderer()
    font = FontProperties(size=size)
    limit = fig.get_figwidth() * fig.dpi * width
    for paragraph in str(text).splitlines() or [""]:
        line = ""
        for word in paragraph.split():
            candidate = (line + " " + word).strip()
            if renderer.get_text_width_height_descent(candidate, font, False)[0] <= limit:
                line = candidate
                continue
            if line:
                yield line
                line = ""
            # Auch ungetrennte Seriennummern, URLs und Freitexte vollständig erhalten.
            for char in word:
                if renderer.get_text_width_height_descent(line + char, font, False)[0] > limit:
                    yield line
                    line = ""
                line += char
        yield line


def new_page(title, override=False):
    fig = plt.figure(figsize=(14, 10.5))
    fig.patch.set_facecolor("white")
    ax = fig.add_axes([0, 0, 1, 1])
    ax.axis("off")
    ax.add_patch(Rectangle((0, .92), 1, .08, color=TITLE, transform=ax.transAxes))
    fig.text(.06, .95, title, fontsize=16, color="white", weight="bold", parse_math=False)
    if override:
        fig.text(.06, .888, "VORBEHALT: A-/Zeitbewertung nicht bestätigt - bewusster Override.",
                 fontsize=11, color=RED, weight="bold")
    return fig


def save_page(pdf, fig):
    """Z. 832–914: Rand-/Textprüfung vor PdfPages.savefig, ohne globale Review-Listen."""
    try:
        fig.canvas.draw()
        renderer = fig.canvas.get_renderer()
        width, height = fig.bbox.width, fig.bbox.height
        boxes = []
        for text in fig.texts:
            box = text.get_window_extent(renderer)
            if box.x0 < 8 or box.x1 > width - 8 or box.y0 < 8 or box.y1 > height - 8:
                raise ValueError("Ein Berichtstext passt nicht auf die PDF-Seite.")
            for previous in boxes:
                if box.overlaps(previous):
                    raise ValueError("Berichtstexte überlagern sich auf der PDF-Seite.")
            boxes.append(box)
        fig.text(.06, .025, f"Lärmprotokoll · High-End-Bericht V1 · Seite {pdf.get_pagecount() + 1}",
                 fontsize=8, color="#777777")
        pdf.savefig(fig)
    finally:
        plt.close(fig)


def text_pages(pdf, title, blocks, override=False):
    fig = new_page(title, override)
    y = .84 if override else .875
    for heading, text in blocks:
        for content, size, color, bold in ((heading, 12, TITLE, True), (text, 10.5, "#333333", False)):
            if not content:
                continue
            for line in _lines(fig, content, size):
                if y < .085:
                    save_page(pdf, fig)
                    fig = new_page(title + " (Fortsetzung)", override)
                    y = .84 if override else .875
                fig.text(.06, y, line, fontsize=size, color=color,
                         weight="bold" if bold else "normal", va="top", parse_math=False)
                y -= .026 if bold else .023
        y -= .015
    save_page(pdf, fig)


def table_pages(pdf, title, columns, rows, note, override=False, widths=None):
    for offset in range(0, max(1, len(rows)), 18):
        fig = new_page(title, override)
        chunk = rows[offset:offset + 18] or [["—"] * len(columns)]
        ax = fig.add_axes([.06, .20, .88, .61])
        ax.axis("off")
        height = min(1, .057 * (len(chunk) + 1))
        table = ax.table(cellText=chunk, colLabels=columns, cellLoc="center",
                         colWidths=widths, bbox=[0, 1 - height, 1, height])
        table.auto_set_font_size(False)
        table.set_fontsize(9)
        for (r, c), cell in table.get_celld().items():
            cell.set_edgecolor("#DDDDDD")
            if r == 0:
                cell.set_facecolor(TITLE)
                cell.set_text_props(color="white", weight="bold")
            else:
                cell.set_facecolor("#EAF1FB" if r % 2 else "#FFFFFF")
        y = .145
        for line in _lines(fig, note, 9):
            fig.text(.06, y, line, fontsize=9, va="top", color="#555555", parse_math=False)
            y -= .019
        save_page(pdf, fig)


def page_cover(pdf, days, config, texts, override):
    """Z. 917–1113: Banner, Rahmendaten, nach Tier getrennte Ergebnisse; ohne Fallbehauptungen."""
    measured = [d for d in days if d["metrics"] is not None]
    blocks = [("Messzeitraum", f"{days[0]['source']['date']} bis {days[-1]['source']['date']} · "
               f"{len(measured)} Tage mit Rohdaten, {len(days)-len(measured)} Tage ohne Rohdaten · Zeitzone {config.time_zone}"),
              ("Gebietseinstufung", texts["heading"]), ("Örtliche Einordnung", texts["qualification"])]
    for tier, label in TIER.items():
        values = [d["metrics"].laeq_day_db for d in measured
                  if d["environment"] == "outside" and d["metrics"].tier == tier
                  and math.isfinite(d["metrics"].laeq_day_db)]
        blocks.append((f"Außenmessungen: {label}", f"{len(values)} Tage · höchster gemessener LAeq: "
                       f"{number(max(values) if values else None)} dB(A). Abdeckungsstufen sind nicht gleichrangig."))
    blocks += [("Inhalt", "Berechnungsgrundlagen, Gebietsrichtwerte, Tagesübersicht, Belastungsdauer, lauteste Stunde, "
                "Tagesdiagramme, Messaufbau und gespeicherte Prüfsummen. Innenraumdaten werden getrennt ausgewiesen."),
               ("Geltungsbereich", "V1 enthält keine Quellenzuordnung, Dauerlärm-Phasen, Bautagebuch- oder Vergleichstagsauswertung. "
                "Aus Pegeln allein wird keine Baustellenursache oder rechtliche Überschreitung festgestellt."),
               ("Dokumentationslücken", "Fehlende Stammdaten und Prüfsummen sind sichtbar bezeichnet. Bei unbekannter "
                "Innen-/Außenlage entfallen Außenrichtwertvergleich und Hochrechnungen.")]
    text_pages(pdf, "SCHALLMESSUNG - DOKUMENTATION", blocks, override)


def page_legal(pdf, texts, area, override):
    """Z. 1211–1330 (_legal_p2/_legal_p3): geprüfte 4b-Texte statt örtlicher Rechtsbehauptungen."""
    text_pages(pdf, "GEBIETSEINSTUFUNG UND BEWERTUNGSGRENZEN", [
        (texts["heading"], texts["guidelines"]), ("Vergleichsgrenzen", texts["intervention"]),
        ("Nacht", texts["night_peak"]), ("Offene rechtliche Begründung", texts["qualification"]),
        ("Quellen", "AVV Baulärm vom 19.08.1970, Beilage zum BAnz. 160 vom 01.09.1970, Nr. 3.1.1–3.2 und 4.1.\n"
         + area.limits.source + "\nZuordnung der Gebietskürzel: Stadt Jena, Merkblatt Baustellenbetrieb, Februar 2021, S. 1.\n"
         + area.limits.mapping_source),
    ], override)


def page_berechnung_kennwerte(pdf, config, texts, override):
    """Z. 1336–1424: portable Formeln, parametrisierte Stufen/Schätzungen, keine Phase-/Quellenregeln."""
    text_pages(pdf, "BERECHNUNG DER KENNWERTE", [
        ("LAeq und 1-Hz-Raster", "LAeq = 10 · log10(Mittelwert(10^(L/10))). Die etwa 2-Hz-Rohwerte werden pro Sekunde "
         "energetisch gemittelt. Gültige Sekunden zählen gleich; GAP-Frames und fehlende Sekunden gehen weder als 0 dB "
         "noch als Messzeit ein. Rollierender Minuten-LAeq: 60 Sekunden, mindestens 20 gemessene Sekunden."),
        ("Spitzen und L1", "Maximum und 99.-Perzentil verwenden die Sekundenmaxima. Das Maximum umfasst alle Messzeiten "
         "des Kalendertags, L1 nur 07–20 Uhr. Kurze Spitzen werden dadurch nicht mit dem Sekundenmittel abgesenkt. "
         "Die gespeicherte Zeitbewertung wird je Tag genannt; unbestätigte Werte werden nicht als FAST behauptet."),
        ("Abdeckung", f"Gemessene Tagessekunden / 46800 (07–20 Uhr). Hohe Abdeckung ab {config.coverage_valid*100:g} %, "
         f"Teilerfassung ab {config.coverage_partial*100:g} %, darunter Messfenster. Der LAeq umfasst stets nur tatsächlich "
         "gemessene Sekunden. Bei Teilerfassung und Messfenstern ist er kein vollständiger Tagesdurchschnitt."),
        ("Lauteste Stunde", f"Energetisches Mittel im gleitenden 3600-Sekunden-Fenster, mindestens "
         f"{config.loudest_hour_min_seconds} gemessene Sekunden. Teilfenster werden gekennzeichnet. Die Suche umfasst "
         "alle Messzeiten des Kalendertags, einschließlich Nachtsegmenten."),
        ("Taktmaximum (informativ)", "Sekundenmaxima werden nach Entfernen fehlender Werte in Fünfergruppen zusammengefasst "
         "und deren Maxima energetisch gemittelt. Dies entspricht der portierten Original-Gruppierung; Gruppen können "
         "Messlücken überspannen. Die Differenz zum LAeq ist der rechnerische Impulszuschlag. Kein automatisch ermittelter "
         "AVV-Beurteilungspegel nach Nr. 6."),
        ("Messunsicherheit", f"Konfigurierter Gerätewert ±{config.device_uncertainty_db:g} dB; nicht vom Pegel abgezogen. "
         "Geräteklasse und Kalibrierung stammen ausschließlich aus den gewählten Stammdaten."),
        ("Zusätzliche Schätzung bei Messfenster", f"Nur bei dokumentierter Außenmessung und Messende zwischen "
         f"{config.conservative_window_start_hour:02}:00 und {config.conservative_window_end_hour:02}:00 Uhr: "
         + texts["window_estimate"] + " Überbrückt wird nur der Rest ab tatsächlichem Messende bis 20 Uhr. "
         "Frühere Lücken bleiben offen; gemessener LAeq und reale Abdeckung bleiben separat erhalten."),
        ("Zusätzliche Schätzung bei Teilerfassung", texts["partial_estimate"] + " Nur bei dokumentierter Außenmessung. "
         "LAeq* = 10 · log10((T_gemessen · 10^(LAeq/10) + T_fehlend · 10^(Annahme/10)) / 46800). "
         "Beide Hochrechnungen sind Hilfsannahmen für § 287 ZPO, keine Messungen und keine automatische rechtliche Würdigung."),
        ("Wetter und Aufbau", "Wetterangaben sind Schnappschüsse aus den gewählten Stammdaten, keine Tageszeitreihe. "
         "Bei mehreren Stammdaten desselben Tages gilt die ausdrücklich ausgewählte Zeile. Aufbauwechsel werden nicht erfunden."),
    ], override)


def page_summary(pdf, days, override):
    """Z. 1429–1554: Tagesübersicht, ohne Quellen/Phasen/Vergleichstag und ohne Ersetzen des Mess-LAeq."""
    rows = []
    for d in reversed(days):
        m = d["metrics"]
        if m is None:
            rows.append([d["source"]["date"], "keine Rohdaten", "—", "—", "—", "—", "—"])
            continue
        estimate = m.high_coverage_laeq_day_db if m.high_coverage_estimate_active else m.conservative_laeq_day_db
        rows.append([str(m.day), TIER[m.tier], f"{m.coverage_day*100:.1f}%", number(m.laeq_day_db),
                     number(m.highest_db), number(m.l1_day_db), number(estimate)])
    table_pages(pdf, "TAGESÜBERSICHT", ["Datum", "Abdeckungsstufe", "Abdeckung", "LAeq Tag", "Maximum", "L1 Tag", "Schätzung*"],
                rows, "Pegel in dB(A). LAeq nur für gemessene Sekunden. * Zusätzliche Hochrechnung, keine Messung; "
                "Annahme und Umfang stehen auf der jeweiligen Tagesseite. Maximum über alle Messzeiten des Tages.", override)


def page_belastungsdauer(pdf, days, config, texts, override):
    """Z. 1999–2036: zwei Schwellen-Balken; ausgeschlossener Dauerlärm-Balken entfernt."""
    outdoor = [d["metrics"] for d in days if d["environment"] == "outside" and d["metrics"] is not None]
    for offset in range(0, max(1, len(outdoor)), 18):
        chunk = outdoor[offset:offset + 18]
        fig = new_page("BELASTUNGSDAUER - GEMESSENE TAGZEIT", override)
        ax = fig.add_axes([.08, .25, .86, .57])
        x = np.arange(len(chunk))
        bars1 = ax.bar(x - .18, [m.seconds_above_guideline/60 for m in chunk], .36, color=ORANGE,
                       label=texts["guideline_duration_label"])
        bars2 = ax.bar(x + .18, [m.seconds_above_intervention/60 for m in chunk], .36, color=RED,
                       label=texts["intervention_duration_label"])
        for bars in (bars1, bars2):
            for bar, m in zip(bars, chunk):
                if m.tier == "window":
                    bar.set_hatch("//")
                    bar.set_edgecolor("#555555")
        ax.set_xticks(x)
        ax.set_xticklabels([str(m.day) for m in chunk], rotation=45, ha="right", fontsize=8)
        ax.set_ylim(0, 800)
        ax.set_ylabel("Minuten (07–20 Uhr)")
        ax.grid(axis="y", linestyle=":", alpha=.4)
        ax.legend(fontsize=9)
        if not chunk:
            ax.text(.5, .5, "Keine dokumentierten Außenmessungen", transform=ax.transAxes, ha="center")
        fig.text(.06, .12, "Orange/Rot: Sekundenmittel strikt über der jeweiligen Schwelle; keine Dauerlärm-Phasen.", fontsize=10)
        fig.text(.06, .09, "Schraffiert: Messfenster. Nur Außenmessungen; Schwellenzeiten sind kein AVV-Beurteilungspegel.", fontsize=10)
        save_page(pdf, fig)


def page_lauteste_stunde(pdf, days, override):
    """Z. 2041–2103: Pegelbalken links, Zeitfenster rechts, ohne feste 60/70-dB-Klassen."""
    measured = [d for d in days if d["metrics"] is not None]
    for offset in range(0, max(1, len(measured)), 16):
        chunk = measured[offset:offset + 16]
        fig = new_page("LAUTESTE STUNDE - PEGEL UND ZEITFENSTER", override)
        ax = fig.add_axes([.12, .25, .44, .56])
        detail = fig.add_axes([.60, .25, .34, .56])
        y = np.arange(len(chunk))
        values = [d["metrics"].loudest_hour_db for d in chunk]
        bars = ax.barh(y, [v if math.isfinite(v) else 0 for v in values], color=TITLE, height=.55)
        for i, (bar, d) in enumerate(zip(bars, chunk)):
            m = d["metrics"]
            if m.loudest_hour_coverage_seconds < 3600:
                bar.set_hatch("//")
                bar.set_edgecolor("#555555")
            ax.text((m.loudest_hour_db if math.isfinite(m.loudest_hour_db) else 0)+1, i,
                    number(m.loudest_hour_db), va="center", fontsize=8)
            detail.text(0, i, f"{m.loudest_hour_text} · {m.loudest_hour_coverage_seconds}/3600 s",
                        va="center", fontsize=10)
        ax.set_xlim(0, max([100] + [v+15 for v in values if math.isfinite(v)]))
        ax.set_yticks(y)
        ax.set_yticklabels([d["source"]["date"] for d in chunk], fontsize=9)
        ax.set_ylim(-.6, max(.6, len(chunk)-.4))
        detail.set_ylim(ax.get_ylim())
        detail.axis("off")
        ax.set_xlabel("LAeq des lautesten Fensters [dB(A)]")
        ax.grid(axis="x", linestyle=":", alpha=.4)
        ax.set_title("Pegel", color=TITLE, fontsize=11)
        detail.set_title("Uhrzeit und tatsächlich gemessene Sekunden", color=TITLE, fontsize=10, loc="left")
        fig.text(.06, .14, "Blau: lautestes 60-Minuten-Fenster je Tag. Schraffiert: weniger als 3600 gemessene Sekunden.", fontsize=10)
        fig.text(.06, .105, "Mindestabdeckung 1800 s. — = nicht erreicht. Alle Messzeiten einschließlich Nacht; keine Interpolation.", fontsize=10)
        fig.text(.06, .07, "Innen-/Außenlage und Vorbehalte siehe Tagesseite; keine Tagesrichtwertlinie für ein Stundenmittel.", fontsize=10)
        save_page(pdf, fig)


def page_innenraum(pdf, days, override):
    """Z. 2109–2163: nur die portable Messwerttabelle; feste Mai-Werte/Videos und Ausweichbehauptung entfallen."""
    indoor = [d["metrics"] for d in days if d["environment"] == "inside" and d["metrics"] is not None]
    if indoor:
        table_pages(pdf, "INNENRAUM - ORIENTIERENDE MESSWERTE", ["Datum", "LAeq Tag", "Maximum", "Abdeckung", "Abdeckungsstufe"],
                    [[str(m.day), number(m.laeq_day_db), number(m.highest_db), f"{m.coverage_day*100:.1f}%", TIER[m.tier]] for m in indoor],
                    "Pegel in dB(A), nur gemessene Zeit. Keine AVV-Außenrichtwerte oder Außenhochrechnungen. "
                    "Orientierende Dokumentation für § 287 ZPO; keine Aussage über andere Räume oder Ausweichmöglichkeiten.", override)


def page_day(pdf, day, config, texts, override):
    """Z. 2507–3022: Tageskurve + Kennwerte; Quellenflächen, Videos, Falltage vollständig entfernt."""
    m = day["metrics"]
    if m is None:
        return
    fig = new_page(f"{m.day:%d.%m.%Y} - {ENVIRONMENT[day['environment']]}", override)
    levels = m.energy_level_1hz_db
    # Wandzeit-Achse, damit Android/Host unabhängig von Matplotlibs Default-Zeitzone sind.
    start = pd.Timestamp(m.day).tz_localize(config.time_zone).replace(hour=7)
    end = pd.Timestamp(m.day).tz_localize(config.time_zone).replace(hour=20)
    left = min(start, levels.index[0]).tz_localize(None)
    right = max(end, levels.index[-1] + pd.Timedelta(seconds=1)).tz_localize(None)
    ax = fig.add_axes([.07, .39, .87, .43])
    ax.set_xlim(left, right)
    ax.set_ylim(min(25, float(levels.min())-3) if levels.notna().any() else 25,
                max(105, float(m.peak_level_1hz_db.max())+3) if levels.notna().any() else 105)
    grid = pd.date_range(left, right, freq="s", inclusive="left")
    visible = levels.copy()
    visible.index = visible.index.tz_localize(None)
    # Bei Zeitrückstellung gleiche Wandzeiten: zum Plotten energetisch zusammenfassen;
    # die eigentliche Berechnung bleibt bei den absoluten Sekunden des Kerns.
    if visible.index.has_duplicates:
        visible = visible.groupby(level=0).first()
    missing = visible.reindex(grid).isna().to_numpy()
    transitions = np.diff(np.r_[False, missing, False].astype(int))
    for i, (a, b) in enumerate(zip(np.flatnonzero(transitions == 1), np.flatnonzero(transitions == -1))):
        ax.axvspan(grid[a], grid[min(b, len(grid)-1)] + pd.Timedelta(seconds=int(b == len(grid))),
                   color="#DDDDDD", alpha=.5, hatch="//", label="nicht gemessen" if i == 0 else None)
    ax.plot(levels.index.tz_localize(None), levels.values, color="#BBBBBB", lw=.5, label="Sekunden-Energiemittel")
    ax.plot(m.laeq_1min_db.index.tz_localize(None), m.laeq_1min_db.values, color=TITLE, lw=1.2, label="LAeq 1 min gleitend")
    if day["environment"] == "outside":
        ax.hlines(config.day_guideline_db, start.tz_localize(None), end.tz_localize(None), color=RED,
                  label=texts["day_line_label"])
        ax.hlines(config.day_intervention_db, start.tz_localize(None), end.tz_localize(None), color=ORANGE,
                  linestyle="--", label=f"{config.day_intervention_db:g} dB(A) Vergleichsgrenze Tag")
    ax.xaxis.set_major_locator(mdates.HourLocator())
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M"))
    ax.set_ylabel("Pegel dB(A)")
    ax.tick_params(labelsize=8)
    ax.grid(linestyle=":", alpha=.4)
    ax.legend(loc="upper right", fontsize=8, ncol=2)
    fig.text(.06, .315, f"{TIER[m.tier]} · reale Abdeckung {m.coverage_day*100:.1f}% · gemessener LAeq Tag {number(m.laeq_day_db)} dB(A)",
             fontsize=12, color=TITLE, weight="bold")
    fig.text(.06, .275, f"Maximum (ganzer Tag): {number(m.highest_db)} dB(A) · L1 (Tag): {number(m.l1_day_db)} dB(A)", fontsize=11)
    fig.text(.06, .235, f"Lauteste Stunde: {number(m.loudest_hour_db)} dB(A), {m.loudest_hour_text} ({m.loudest_hour_coverage_seconds}/3600 s)", fontsize=11)
    fig.text(.06, .18, "Grau: Sekundenmittel; Blau: gleitender LAeq; schraffierte Bereiche: fehlende Sekunden.", fontsize=10)
    fig.text(.06, .145, "Schwellenlinien nur bei dokumentierter Außenlage und nur 07–20 Uhr; keine automatische Rechtsbewertung.", fontsize=10)
    save_page(pdf, fig)
    details = [
        ("Messzeiten / Datenqualität", f"{m.measurement_period_text}\n{m.laeq_day_label}\n"
         "Abdeckung wird anhand gültiger Sekunden bestimmt. Interne GAP-Lücken können auch innerhalb einer Session liegen."),
        ("Bewertung", f"Gespeicherte Zeitbewertung: {', '.join(day['modes']) or 'keine gültigen Werte'}. "
         f"Konfigurierte Geräteunsicherheit ±{config.device_uncertainty_db:g} dB."),
        ("Nachtsegmente (ohne Quellenzuordnung)", f"Vor 07 Uhr: {m.before_7_seconds} s, LAeq {number(m.laeq_before_7_db)}, "
         f"Maximum {number(m.lmax_before_7_db)} dB(A). Ab 20 Uhr: {m.after_20_seconds} s, "
         f"LAeq {number(m.laeq_after_20_db)}, Maximum {number(m.lmax_after_20_db)} dB(A)."),
        ("Taktkennwerte (informativ)", f"Taktmaximalpegel {number(m.rating_level_day_db)} dB(A), "
         f"Differenz zum LAeq {number(m.impulse_adjustment_day_db)} dB. Methodische Einschränkung siehe Berechnungsgrundlagen."),
    ]
    if day["environment"] == "outside":
        details.append(("Gemessene Schwellenzeiten (07–20 Uhr)", f"{texts['guideline_duration_label']}: {m.seconds_above_guideline} s "
                        f"({m.percent_above_guideline:.1f}% der gemessenen Tagzeit); "
                        f"{texts['intervention_duration_label']}: {m.seconds_above_intervention} s."))
    else:
        details.append(("Bewertungsgrenze", "Keine Außenrichtwertvergleiche und keine Außenhochrechnung: " + ENVIRONMENT[day["environment"]]))
    if m.conservative_active:
        details.append(("Zusätzliche Schätzung (§ 287 ZPO), keine Messung", texts["window_estimate"] +
                        f" Ab Messende {m.conservative_measurement_end_text} bis 20 Uhr: "
                        f"{number(m.conservative_laeq_day_db)} dB(A); gemessener plus ergänzter Anteil "
                        f"{m.conservative_coverage_day*100:.1f}%. Frühere Lücken bleiben offen."))
    if m.high_coverage_estimate_active:
        details.append(("Zusätzliche Schätzung (§ 287 ZPO), keine Messung", texts["partial_estimate"] +
                        f" Ergebnis {number(m.high_coverage_laeq_day_db)} dB(A). Reale Abdeckung unverändert {m.coverage_day*100:.1f}%."))
    text_pages(pdf, f"{m.day:%d.%m.%Y} - KENNWERTE UND VORBEHALTE", details, override)


def page_messaufbau(pdf, day, config, override):
    """Z. 1562–1655: EXIF-korrigierte Fotos und reale Stammdaten statt festem Aufbau-Regime."""
    source = day["source"]
    metadata = source.get("stammdaten") or {}
    fields = [("Messort", "messort"), ("Gerätehersteller", "geraetHersteller"), ("Gerätetyp", "geraetTyp"),
              ("Genauigkeitsklasse", "geraetGenauigkeitsklasse"), ("Seriennummer", "geraetSeriennummer"),
              ("Kalibrierung", "geraetKalibrierung"), ("Mikrofonposition", "mikrofonposition"),
              ("Mikrofonhöhe", "mikrofonhoehe"), ("Entfernung zur Quelle", "entfernungZurQuelle"),
              ("Innen-/Außenlage", "innenAussen"), ("Fensterzustand", "fensterzustand"),
              ("Wetter (Schnappschuss)", "wetter"), ("Datenqualität", "datenqualitaetHinweis")]
    blocks = [("Gewählte Stammdaten", f"ID: {metadata.get('id', 'nicht vorhanden')} · "
               f"fehlende Pflichtangaben: {', '.join(source.get('missingStammdatenFields', [])) or 'keine laut Vorprüfung'}"),
              ("Messaufbau", "\n".join(f"{label}: {metadata.get(key) or 'nicht angegeben'}" for label, key in fields))]
    if not source.get("photos"):
        blocks.append(("Fotos", "Keine Dokumentationsfotos zu den zugeordneten Sessions vorhanden."))
    text_pages(pdf, source["date"] + " - MESSAUFBAU", blocks, override)
    for photo in source.get("photos", []):
        fig = new_page(source["date"] + " - DOKUMENTATIONSFOTO", override)
        ax = fig.add_axes([.06, .25, .88, .57])
        ax.axis("off")
        try:
            with Image.open(photo["path"]) as original:
                img = ImageOps.exif_transpose(original)
                img.thumbnail((1600, 1100))
                ax.imshow(np.asarray(img.convert("RGB")))
        except (OSError, ValueError):
            ax.text(.5, .5, "Dokumentationsfoto fehlt oder ist nicht lesbar", ha="center", transform=ax.transAxes, color=RED)
        captured = datetime.fromtimestamp(photo["capturedAt"]/1000, ZoneInfo(config.time_zone)).strftime("%d.%m.%Y %H:%M:%S")
        fig.text(.06, .19, f"Foto {photo['id']} · Session {photo['sessionId']} · {captured} · {photo['category']}", fontsize=10)
        fig.text(.06, .145, "Nachträglich aus Galerie hinzugefügt" if photo.get("imported") else "Während des Messvorgangs aufgenommen",
                 fontsize=11, color=ORANGE if photo.get("imported") else TITLE)
        save_page(pdf, fig)
        text_pages(pdf, source["date"] + " - FOTOANGABEN", [
            (f"Foto {photo['id']}: Geometrie", photo.get("geometry") or "nicht angegeben"),
            ("Notiz", photo.get("note") or "nicht angegeben"),
            ("Gespeicherte SHA-256-Prüfsumme", photo.get("sha256") or "nicht vorhanden"),
        ], override)


def page_manifest(pdf, days, override):
    """Z. 1875–1906: vollständige gespeicherte Session-/Fotohashes, ohne erneutes Hashen."""
    entries = {}
    for d in days:
        for session in d["source"].get("sessions", []):
            entries[("Session", session["id"])] = session.get("sha256")
        for photo in d["source"].get("photos", []):
            entries[("Foto", photo["id"])] = photo.get("sha256")
    blocks = [("Geltungsbereich", "Die Prüfsummen stammen aus der Datenbank. Session-Hashes beziehen sich auf die gesamte "
               "ursprüngliche Session, auch wenn der Bericht nur deren Tagesausschnitt enthält. Foto-Hashes sind die "
               "gespeicherten Prüfsummen der Originaldateien. Hier erfolgt keine erneute Integritätsprüfung und kein Hashen "
               "der temporären CSVs. Fehlende Hashes (Altbestand/laufende Session) werden nicht erfunden.")]
    blocks += [(f"{kind} {identifier}", checksum or "Prüfsumme nicht vorhanden") for (kind, identifier), checksum in entries.items()]
    text_pages(pdf, "ROHDATEN-MANIFEST - GESPEICHERTE SHA-256", blocks, override)


def render_report(path, days, config, area, texts, override):
    """PdfPages wie im Original; alle Figuren schließen auch im Fehlerfall."""
    with plt.rc_context({"font.family": "DejaVu Sans", "pdf.fonttype": 42}):
        try:
            with PdfPages(path) as pdf:
                pdf.infodict().update(Title="Schallmessung - High-End-Bericht V1", Creator="Lärmprotokoll / Matplotlib")
                page_cover(pdf, days, config, texts, override)
                page_berechnung_kennwerte(pdf, config, texts, override)
                page_legal(pdf, texts, area, override)
                page_summary(pdf, days, override)
                page_belastungsdauer(pdf, days, config, texts, override)
                page_lauteste_stunde(pdf, days, override)
                page_innenraum(pdf, days, override)
                for day in days:
                    page_day(pdf, day, config, texts, override)
                    page_messaufbau(pdf, day, config, override)
                page_manifest(pdf, days, override)
        finally:
            plt.close("all")
