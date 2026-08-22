# UX Design Plan: Noise Protocol (Lärmprotokoll)

Basierend auf dem vollständigen UX-Design-Brief (26 Kernfragen) für die Weiterentwicklung und
Gestaltung von **Noise Protocol**.

---

## 1. Nutzerprofil & Gestaltungsprinzipien

### 1.1 Primäre Zielgruppe & Kernbedürfnis
* **Zielgruppe:** Mieter bei Nachbarschafts- oder Baulärm, die verlässliche, beweissichere und
  nachvollziehbare Dokumentationen für Vermieter, Behörden oder Gerichte benötigen.
* **Kernaufgabe:** In 80 % der Fälle nach dem Öffnen: **Laufende Langzeitmessung sofort kontrollieren**.
* **Nutzungssituation:** Das Smartphone liegt oft stunden- oder tagelang unberührt am Messort (Dauermessung).

### 1.2 Die 6 UX-Leitlinien
1. **Zwei Ebenen (Hybrid):** Im Alltag extrem einfach und selbsterklärend; für Techniker und Akustiker
   stehen alle Details, Rohwerte und Parameter über intuitive Detail-Ansichten bereit.
2. **1-Sekunden-Verständlichkeit:** Innerhalb einer Sekunde muss erfassbar sein:
   - Aktueller dB-Wert
   - Live-Verlauf als Kurve
   - Verbindungsstatus & Datenquelle
   - Durchschnitt / Leq
3. **Verlauf vor Einzelwert:** Der aktuelle dB-Wert ist nicht überdimensional groß, sondern die
   zeitliche Kurve und Tendenz stehen im visuellen Fokus.
4. **Sachlichkeit & Neutralität:** Keine moralisierende oder subjektive Farb-Interpretation
   (kein „rot = böse/zu laut"), sondern neutrale, präzise physikalische Messwerte.
5. **Kompletter Dark Mode:** Perfekt für nächtliche Dauermessungen am Bett oder Fenster,
   OLED-stromsparend und kontrastreich.
6. **Strukturierte Kartenästhetik (Inspiration DM-App):** Aufgeräumte Flächen, klare Hierarchien,
   hochwertige Abrundungen, reduzierte visuelle Unruhe.

---

## 2. Informationsarchitektur & Hauptscreens

```
┌────────────────────────────────────────────────────────────────────────┐
│                        NOISE PROTOCOL APP                              │
└────────────────────────────────────────────────────────────────────────┘
     │
     ├── 1. START / MESSMODUS (Haupt-Cockpit)
     │    ├── 1-Sekunden-Header: Live-dB · Leq · Datenquelle · Status-Badge
     │    ├── Zentraler Live-Pegelverlauf (Echtzeit-Kurve mit Schwellenwertlinie)
     │    ├── Prominenter Mess-Aktionsbutton („Messung starten" / „stoppen")
     │    ├── Quick-Action im Messmodus: „Lärmereignis markieren" (Schnell-Kategorisierung)
     │    └── Aufklappbare Messgeräte- & Sensorsteuerung (Einfach -> Details auf Klick)
     │
     ├── 2. PROTOKOLL (Tagesübersicht & Historie)
     │    ├── Tages- und Session-Karten mit Kompakt-Zusammenfassung (Dauer, Leq, Max, Events)
     │    └── Detailansicht je Session:
     │         ├── Interaktiver Zoom- & Pan-Chart für 24h-Messreihen
     │         ├── Sichtbare Disconnect-Lücken & Verbindungsunterbrechungen
     │         ├── Ereignisliste mit Audio-Player, Peak-dB und Kategorie
     │         └── Export-Aktionen (CSV, PDF-Protokollbericht, Support-Paket)
     │
     ├── 3. DIAGNOSE (System- & Verbindungsstatus)
     │    ├── Hardware- & Sensor-Gesundheit (Bluetooth-Verbindung, Pegelsensor, Mikrofon)
     │    ├── Google Drive Live-Synchronisationsstatus & Sofort-Sync
     │    ├── OEM- & Energie-Diagnose (Xiaomi/HyperOS, Akku-Optimierung, Autostart)
     │    └── Technische Ereignis-Historie
     │
     └── 4. EINSTELLUNGEN (Konfiguration)
          ├── Schwellenwerte & Ruhezeiten (Tag/Nacht)
          ├── Alarmierung bei Verbindungsabbruch (Akustischer Ton, ntfy, Totmannschaltung)
          ├── Google-Drive-Sync Konfiguration
          └── Datenspeicherung & Retention
```

---

## 3. Modularer Umsetzungs- & PR-Plan

### 📦 PR 1: Visuelles Fundament & OLED Dark Theme (DM-Designsprache)
- **Ziel:** Einheitliches, professionelles Dark Theme mit optimierten Kontrasten, DM-inspirierter
  Kartenarchitektur, klaren Typografie-Skalen und neutralen Akzentfarben.
- **Komponenten:**
  - `Theme.kt`: Modernes Dark-Color-Scheme (OLED Pure Dark Background `#121212` / Surface `#1E1E1E`).
  - Standardisierte UI-Card-Komponenten mit einheitlichen Paddings, Badges und dezenten Konturen.
- **Verifikation:** Unit- & Compose-Tests, CI-Builds grün.

---

### 📦 PR 2: Startscreen & Live-Messmodus (1-Sekunden-Header & Quick-Event-Tagger)
- **Ziel:** Perfektionierung des Startscreens nach den 1-Sekunden-Anforderungen des UX-Briefs.
- **Komponenten:**
  - **1-Sekunden-Header:** Kompakte Leiste mit Live-dB, Leq-Mittelwert, Messquellen-Badge
    (`PCE-323 Kalibriert` / `Smartphone Mikrofon`) und Verbindungsstatus.
  - **Live-Chart-Fokus:** Kurvenverlauf steht im Vordergrund.
  - **Prominenter Start/Stop-Button:** Großer, unübersehbarer Aktionsbutton.
  - **Lärmereignis markieren (Quick-Tagger):** Großer Button im aktiven Messmodus mit BottomSheet/Dialog
    zur 1-Klick-Kategorisierung (Bohren, Hämmern, Musik, Trittschall, Sprache, Sonstiges).
- **Verifikation:** Compose-Tests für Messmodus und Quick-Tagger, CI grün.

---

### 📦 PR 3: Interaktiver Zeitreihen-Chart (Zoom, Pan, Disconnect-Gaps, Event-Pins)
- **Ziel:** Leichtes Navigieren und Zoomen in langen 24-Stunden-Messreihen.
- **Komponenten:**
  - `ZoomableTimelineChart.kt`: Canvas-basierte Gestensteuerung (Pinch-to-Zoom, Drag-to-Pan).
  - Visuelle Kennzeichnung von Disconnect-Lücken (schraffierte/rot-transparente Intervalle).
  - Pins für erkannte/markierte Lärmereignisse mit Direkt-Sprung zum Audioausschnitt.
  - Horizontale Leq-Referenzlinie.
- **Verifikation:** Robuste JVM/Robolectric Tests & Gesten-Tests, CI grün.

---

### 📦 PR 4: Nachvollziehbare Protokoll-Zusammenfassung & Audit-Bericht
- **Ziel:** Transparente, revisionssichere Dokumentation für Mieter.
- **Komponenten:**
  - **Session-Zusammenfassungskarte:** Schneller Überblick (Start/Ende, Dauer, Leq, Max-dB, Event-Anzahl).
  - **Revisions-Metadaten:** Exakte Aufschlüsselung von Geräte-ID, Verbindungsunterbrechungen,
    Datenlücken, Frequenzbewertung und App-Version.
  - **Audio-Ereignisliste:** Kompakte Liste aller Vorfälle mit Sofort-Audio-Wiedergabe.
- **Verifikation:** Export- und Screen-Tests, CI grün.

---

## 4. Definition of Done je PR
1. `./gradlew test` (alle Unit- und Migrationstests grün).
2. `./gradlew assembleDebug assembleRelease` und `./gradlew lintDebug` (0 Fehler).
3. GitHub Actions CI-Workflows (`Android CI` und `Android Emulator Tests`) 100 % grün.
4. Squash-Merge in `main` mit deutscher Commit-Nachricht.
