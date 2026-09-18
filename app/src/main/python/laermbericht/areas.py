"""Gebietsabhängige Richtwerte und Textbausteine (Bericht, Schritt 4b).

Quellen, geprüft am 18.09.2026:
AVV Baulärm vom 19.08.1970, Beilage zum BAnz. Nr. 160 vom 01.09.1970,
Nr. 3.1.1–3.2 und 4.1, amtlicher Volltext:
https://www.verwaltungsvorschriften-im-internet.de/bsvwvbund_19081970_IGI7501331.htm
Zuordnung WR/WA/MI/GE/GI: Stadt Jena, Merkblatt Baustellenbetrieb, Februar
2021, Seite 1: https://service.jena.de/system/files/2021-11/Merkblatt_Baustelle_02_2021.pdf

Die Gebietswahl ist keine Feststellung der tatsächlichen örtlichen Nutzung.
Nicht eindeutig belegte Zuordnungen bleiben ohne Zahlen und werden abgelehnt.
Die Rechtsprosa des Einzelfalls bleibt ausdrücklich TODO(Owner), auch bei WA.
Siehe docs/BERICHT_GEBIETSEINSTUFUNG_QUELLEN.md und
docs/PROMPT_BERICHT_GEBIETSEINSTUFUNG.md. Keine Datei-/Android-Zugriffe.
"""

from dataclasses import dataclass, replace
from types import MappingProxyType

from .day_metrics import DayConfig

AVV_SOURCE = "https://www.verwaltungsvorschriften-im-internet.de/bsvwvbund_19081970_IGI7501331.htm"
MAPPING_SOURCE = "https://service.jena.de/system/files/2021-11/Merkblatt_Baustelle_02_2021.pdf"


@dataclass(frozen=True)
class AreaLimits:
    """Außenrichtwerte, keine Innenraumwerte; AVV Nr. 3.1.1, 3.1.3, 4.1."""

    day_db: float
    night_db: float
    avv_category: str
    source: str = AVV_SOURCE
    mapping_source: str = MAPPING_SOURCE

    @property
    def day_intervention_db(self) -> float:
        """Vergleichsgrenze; erst strikt darüber greift Nr. 4.1."""
        return self.day_db + 5.0

    @property
    def night_intervention_db(self) -> float:
        """Nr. 4.1 gilt gebietsübergreifend, auch nachts."""
        return self.night_db + 5.0

    @property
    def night_peak_db(self) -> float:
        """Strikte Grenze für Messwerte nach Nr. 6.5; AVV Nr. 3.1.3."""
        return self.night_db + 20.0


@dataclass(frozen=True)
class AreaType:
    code: str
    name: str
    baunvo_section: str
    limits: AreaLimits | None
    qualification_todo: str


def _area(code: str, name: str, section: str, limits: AreaLimits | None) -> AreaType:
    return AreaType(
        code, name, section, limits,
        f"TODO(Owner): Für {name} ({code}, {section}) die örtliche Zuordnung "
        "nach AVV Nr. 3.2 belegen und die gebietsspezifische rechtliche Begründung "
        "freigeben. Eine behördliche Bestätigung wird nicht vorausgesetzt.",
    )


# Keine geratene Gleichsetzung neuer/anderer BauNVO-Typen mit AVV-Kategorien.
AREA_TYPES = MappingProxyType({area.code: area for area in (
    _area("WA", "Allgemeines Wohngebiet", "§ 4 BauNVO", AreaLimits(55.0, 40.0, "d")),
    _area("WR", "Reines Wohngebiet", "§ 3 BauNVO", AreaLimits(50.0, 35.0, "e")),
    _area("MI", "Mischgebiet", "§ 6 BauNVO", AreaLimits(60.0, 45.0, "c")),
    _area("GE", "Gewerbegebiet", "§ 8 BauNVO", AreaLimits(65.0, 50.0, "b")),
    _area("GI", "Industriegebiet", "§ 9 BauNVO", AreaLimits(70.0, 70.0, "a")),
    _area("WS", "Kleinsiedlungsgebiet", "§ 2 BauNVO", None),
    _area("WB", "Besonderes Wohngebiet", "§ 4a BauNVO", None),
    _area("MD", "Dorfgebiet", "§ 5 BauNVO", None),
    _area("MDW", "Dörfliches Wohngebiet", "§ 5a BauNVO", None),
    _area("MU", "Urbanes Gebiet", "§ 6a BauNVO", None),
    _area("MK", "Kerngebiet", "§ 7 BauNVO", None),
)})


def get_area(code: str) -> AreaType:
    """Nur eindeutige Kürzel, kein unscharfer Freitext und kein WA-Fallback."""
    if not isinstance(code, str) or not code.strip():
        raise ValueError("Bitte in den Berichtsparametern eine Gebietseinstufung auswählen.")
    try:
        return AREA_TYPES[code.strip().upper()]
    except KeyError:
        raise ValueError(
            f"Unbekannte Gebietseinstufung: {code!r}. Bitte einen Gebietstyp auswählen."
        ) from None


def _verified_limits(area: AreaType) -> AreaLimits:
    if area.limits is None:
        raise ValueError(
            f"Für {area.name} ({area.code}) ist noch kein AVV-Richtwert hinterlegt. "
            "TODO(Owner): Zuordnung und Richtwerte anhand einer belastbaren Quelle prüfen."
        )
    return area.limits


def apply_area_to_config(config: DayConfig, code: str) -> DayConfig:
    """Ersetzt die WA-Festwerte aus Originalzeilen 47–54 und 763–780.

    Der Messfenster-Schätzpegel ist gemäß Auftrag 4b/3.4 der Tagesrichtwert.
    Die konfigurierbare Teilerfassungs-Annahme bleibt davon unabhängig.
    """
    limits = _verified_limits(get_area(code))
    return replace(
        config,
        day_guideline_db=limits.day_db,
        day_intervention_db=limits.day_intervention_db,
        window_estimate_db=limits.day_db,
    )


def area_report_texts(code: str, config: DayConfig) -> dict[str, str]:
    """Parameterisierte Bausteine für den späteren Seitenport in Schritt 4c.

    Ersetzt Originalzeilen 939–1026, 1163–1175, 1240–1267, 1372–1381,
    1440–1543, 1887–2070, 2520–2630 und 2769–2913. Keine fallspezifische
    Feststellung einer Rechtsverletzung oder behördlichen Bestätigung.
    """
    area = get_area(code)
    limits = _verified_limits(area)
    return {
        "heading": f"{area.name} ({area.code}, {area.baunvo_section})",
        "guidelines": (
            f"Für die ausgewählte Gebietseinstufung {area.code}: "
            f"Tag 07–20 Uhr {limits.day_db:g} dB(A), Nacht 20–07 Uhr "
            f"{limits.night_db:g} dB(A); Außenrichtwerte nach AVV Nr. 3.1.1 "
            f"Buchstabe {limits.avv_category}. Die örtliche Zuordnung ist gesondert zu belegen."
        ),
        "intervention": (
            f"AVV Nr. 4.1: Überschreitet der nach Nr. 6 ermittelte Beurteilungspegel "
            f"tags {limits.day_intervention_db:g} dB(A) oder nachts "
            f"{limits.night_intervention_db:g} dB(A), sollen Minderungsmaßnahmen "
            "angeordnet werden; die in Nr. 4.1 genannten Ausnahmen bleiben zu prüfen. "
            "Genau 5 dB über dem Richtwert reicht für diese Regel nicht aus. "
            "LAeq und Sekunden über einer Schwelle allein ersetzen diese Prüfung nicht."
        ),
        "night_peak": (
            f"Zusätzliches Nachtkriterium nach AVV Nr. 3.1.3: Messwerte nach Nr. 6.5 "
            f"über {limits.night_peak_db:g} dB(A)."
        ),
        "qualification": area.qualification_todo,
        "window_estimate": (
            f"Bei aktivierter Messfenster-Hochrechnung wird für die ergänzte Restzeit "
            f"der Tagesrichtwert {limits.day_db:g} dB(A) für {area.code} angenommen. "
            "Dies ist eine Schätzannahme, kein gemessener Pegel."
        ),
        "partial_estimate": (
            f"Bei aktivierter Teilerfassungs-Hochrechnung werden fehlende Tagessekunden "
            f"mit {config.partial_estimate_db:g} dB(A) ergänzt. Tagesrichtwert für "
            f"{area.code}: {limits.day_db:g} dB(A). Dies ist eine Schätzannahme."
        ),
        "guideline_duration_label": f"Zeit > {limits.day_db:g} dB(A)",
        "intervention_duration_label": f"Zeit > {limits.day_intervention_db:g} dB(A)",
        "day_line_label": f"{limits.day_db:g} dB(A) Richtwert Tag ({area.code})",
        "night_line_label": f"{limits.night_db:g} dB(A) Richtwert Nacht ({area.code})",
    }
