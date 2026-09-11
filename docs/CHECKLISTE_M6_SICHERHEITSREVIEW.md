# Checkliste M6-Sicherheitsreview

Ersetzt die alte Formel "der Owner reviewt Crypto/BLE-Code persönlich" aus AGENTS.md §5
(Klarstellung 10.09.2026): Es gibt in diesem Repo keine selbstgeschriebene Kryptographie. Alle
Verschlüsselung läuft über `androidx.security.crypto` (Tink) mit Schlüsseln im Android Keystore;
BLE-Sicherheit ist App-Logik (Pinning, Stream-Plausibilisierung), keine Kryptographie. Review
heißt hier deshalb: korrekte Nutzung einer Standardbibliothek prüfen (Teil 1) und Plausibilität
einer Sicherheitslogik gegen das Bedrohungsmodell prüfen (Teil 2) — beides ohne
Kryptographie-Hintergrundwissen machbar.

Bei jeder Änderung an den betroffenen Dateien vor dem Merge durchgehen. Ein "Nein" bei einem
Punkt heißt nicht automatisch "Fehler" — aber: nachfragen, warum, bevor gemergt wird.

## Teil 1: Keystore/EncryptedSharedPreferences-Nutzung

Betroffene Datei: `app/src/main/java/com/example/lrmprotokoll/data/SettingsManager.kt`
(Referenz: Plan Abschnitt 6 "Auf Datenebene").

1. Liegen weiterhin **nur** `ntfyTopic`, `ntfyServer` und `heartbeatUrl` in `securePrefs`? Ist
   ein neues sicherheitsrelevantes Feld dazugekommen (z. B. ein neuer Zugangs-Token), muss es
   ebenfalls über `leseVerschluesselt`/`schreibeVerschluesselt` laufen, nicht in `prefs`
   (Klartext) landen.
2. `MasterKey.KeyScheme` ist weiterhin `AES256_GCM` — kein schwächeres oder
   selbstgewähltes Schema.
3. `PrefKeyEncryptionScheme`/`PrefValueEncryptionScheme` sind weiterhin `AES256_SIV`/
   `AES256_GCM` — das sind die von Google empfohlenen Standardwerte für
   `EncryptedSharedPreferences`, es gibt keinen Grund, hier etwas Eigenes einzusetzen.
4. Kein `Log.*`-Aufruf gibt einen der drei Werte selbst aus (nur Fehler/Exceptions dürfen
   geloggt werden, wie in `sicherePraefsOderNull` — dort wird die Exception geloggt, nicht der
   Wert).
5. Die verschlüsselte Ablage bleibt eine eigene Datei (`"noise_settings_secure"`), getrennt von
   `"noise_settings"` — keine Vermischung von verschlüsselten und Klartext-Werten in derselben
   Datei.
6. Der Klartext-Fallback (`securePrefs == null`) bleibt auf einen echten Keystore-Fehler
   beschränkt (dokumentierte Lücke, siehe Klassen-KDoc), wird nicht zum Standardpfad.
7. Bei der Migration alter Klartextwerte wird der Klartext-Eintrag danach wirklich gelöscht
   (`prefs.edit().remove(schluessel)`), nicht nur kopiert — sonst liegt der Wert doppelt vor.
8. `android:allowBackup="false"` und `android:dataExtractionRules` stehen weiterhin im
   Manifest — sonst könnte die verschlüsselte Datei über Auto-Backup abfließen (macht die
   Verschlüsselung selbst zwar nicht ungültig, aber sinnlos für dieses Bedrohungsmodell).

## Teil 2: Plausibilität der BLE-Sicherheitslogik

Betroffene Dateien: `app/src/main/java/com/example/lrmprotokoll/meter/GeraetePinning.kt`,
`ConnectionSupervisor.kt`, `ble/BleMeterTransport.kt` (Referenz: Plan Abschnitt 6, Ausgangslage:
das OEM-Modul bietet keine Authentifizierung, Sicherheit entsteht ausschließlich app-seitig).

1. Der Verbindungsaufbau (`BleMeterTransport.connect`) verbindet weiterhin **nur** über die
   persistierte Adresse, nie über den angezeigten Namen — Namensgleichheit allein darf nie zu
   einer automatischen Verbindung führen.
2. `PinningBefund.VERDAECHTIG_GLEICHER_NAME` wird in der UI (`MeterScreen`) weiterhin sichtbar
   gemacht, nicht nur geloggt — der Nutzer muss den Verdachtsfall sehen können, bevor er koppelt.
3. Die Kadenz-Toleranz (±20 %, siehe `ConnectionSupervisor`/`Pce323Profile`) und die Regel
   "zwei aufeinanderfolgende Abweichungen, nicht eine" sind nicht aufgeweicht worden — eine zu
   großzügige Toleranz macht die Spoofing-Erkennung wirkungslos.
4. Der `cadenceWatcher` ist weiterhin nur aktiv, wenn `expectedFramePeriod` (aus
   `Pce323Profile.EXPECTED_FRAME_PERIOD_MS`) gesetzt ist — prüfen, dass dieser Wert nicht
   versehentlich `null`/deaktiviert wurde.
5. Zustandsverändernde Kommandos (`POWER_OFF`, `MEMORY_CLEAR`, Bereichswechsel) lösen weiterhin
   **nur** auf explizite Nutzeraktion aus, nie automatisch (Ausnahme laut Plan: automatisches
   Zurückschalten auf A-Bewertung, mit Bestätigung).
6. Der Bonding-Status wird in der UI weiterhin ehrlich angezeigt (M6-A) — keine
   "verschlüsselt/gesichert"-Anzeige, wenn Bonding nicht unterstützt wird oder fehlschlägt.

## Wann diese Checkliste NICHT reicht

Sollte eine künftige Änderung tatsächlich eine neue kryptographische Primitive einführen (z. B.
ein eigenes Schlüsselaustauschverfahren, eigene Signaturprüfung, irgendetwas jenseits eines
Aufrufs von Android Keystore/`androidx.security.crypto`), ist das **kein Fall für diese
Checkliste**. Dann gilt AGENTS.md Abschnitt 8a: nicht selbst entscheiden, mit dem Owner klären,
ob dafür externe Expertise nötig ist.
