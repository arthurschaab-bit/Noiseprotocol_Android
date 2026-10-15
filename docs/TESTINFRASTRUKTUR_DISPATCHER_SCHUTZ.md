# Testinfrastruktur: Schutz vor festgefahrenem `AndroidUiDispatcher`

Stand 15.10.2026 · Untersuchung und Evidenz: PR #280 (Branch `investigate/appnotidle-brainstorm`, Ordner
`docs/flakiness-evidenz/` dort) · Vorgänger-Bericht: `docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md`

## Das Problem in drei Sätzen

In der CI scheiterten sporadisch Robolectric-Compose-Tests mit `AppNotIdleException` (60 s) oder
`ComposeTimeoutException`, lokal nie. Wenn es auftrat, hing meist **nicht ein** Test, sondern eine Kaskade
(bis 15 Tests in 9 Klassen), und es traf immer Tests, die einen **synchronen globalen Schreibzugriff ohne
Coroutine** brauchen (z. B. `performSemanticsAction(OnClick)`). Ursache ist ein Dauerzustand der Robolectric-Sandbox,
kein Fehler im einzelnen Test.

## Mechanismus (gemessen und im Bytecode gelesen, Compose 1.6.7 / Robolectric 4.16.1)

1. Compose wendet globale State-Schreibzugriffe über den `GlobalSnapshotManager` an: Der Erzeuger setzt `sent` per
   `compareAndSet(false, true)` und sendet **einmal**; nur sein Konsument auf `AndroidUiDispatcher.Main` setzt `sent`
   zurück und ruft `Snapshot.sendApplyNotifications()`.
2. `AndroidUiDispatcher.dispatch` postet nur, wenn `scheduledTrampolineDispatch` noch `false` ist.
3. Robolectric verwirft die Looper-Nachrichten zwischen Tests (`ShadowPausedLooper.resetLoopers()`,
   `MessageQueue.reset()`), `AndroidUiDispatcher.Main` ist aber ein **statischer Singleton je Sandbox** und behält
   seine Flags. Steht beim Wechsel ein Dispatch an, bleibt die Nachricht weg, beide Flags bleiben `true`, der
   Dispatcher postet **nie wieder**, `sent` bleibt `true`, und jeder globale Schreibzugriff bleibt für immer
   unangewandt (`hadSnapshotChanges`).

Lokal nachgestellt: `DispatcherPluginTest` (ohne Plugin scheitert der zweite Test mit genau dieser Signatur) und
`DispatcherReparaturTest` (ohne Reparatur läuft `waitForIdle` in `AppNotIdleException`, nur `hadSnapshotChanges`).

**Nicht geklärt:** was den Dispatch genau an der Testgrenze verliert. Gestützt, nicht belegt: späte Schreibzugriffe
von Room-Threads (`arch_disk_io_*`) in Compose-State, z. B. aus `LiveCockpitCard`. Laut Verlauf von Robolectric #9043
(per Abruf gelesen, nicht Zeile für Zeile gegengelesen) wurde die verwandte Ursache „statische `Choreographer`-Referenz“
gefixt; der Rest, die Flags des Dispatchers, ist
damit nicht abgedeckt (Schluss aus dem Bytecode, nicht gegen Upstream-Code geprüft).

## Was dieser Code tut

| Teil | Aufgabe |
|---|---|
| `DispatcherPlugin` (`TestEnvironmentLifecyclePlugin`, geladen über `META-INF/services` in `src/test/resources`) | Läuft **vor jedem Robolectric-Test** in derselben Sandbox-Classloader wie die Compose-Singletons und heilt einen verlorenen oder anstehenden Dispatch. Schützt auch Klassen ohne Regel. |
| `ZeitwaechterRegel` (`MethodRule`, in Klassen mit bekanntem Hänger) | Zweite Verteidigungslinie (gleiche Reparatur zu Beginn des Tests), dazu Wächter: nach 50 s Stacks, Stichproben und Idling-Gründe in die Diagnose. |
| `DispatcherReparatur` | Erkennung und Heilung (`dispatchCallback.run()` und `doFrame(0)`, je einzeln abgesichert). |
| `IdlingGruende`, `LaufzeitZustand`, `GlobaleSchreiber` | Diagnose: warum `waitForIdle` wartet (fünf Gründe), Zustand der Test-JVM, Schreibzugriffe aus Fremd-Threads (höchstens 20 Stacks je 100 ms). |
| `Diagnoseausgabe` | Ein Ausgabeweg: `System.err` und `app/build/zeitwaechter-diagnose.txt` (landet im CI-Artefakt). |

**Nichts geschieht still.** Jede Reparatur steht mit Zustand vorher/nachher und den letzten Fremd-Thread-Schreibern in
der Diagnosedatei (`=== DISPATCHER-REPARATUR: … ===`); am JVM-Ende folgt je Sandbox eine Zeile
`=== DISPATCHER-PLUGIN-ZUSAMMENFASSUNG: Aufrufe=… ruhigAbgearbeitet=… verlorenGefunden=… nichtRepariert=… ===`.
Ein grünes CI heißt deshalb nicht „nichts passiert“. Die Compose-Tests laufen (Schluss aus Aufrufzahlen und
Testkonfiguration, nicht gesondert nachgewiesen) in der Sandbox mit `GraphicsMode.NATIVE` (ca. 165 Aufrufe); die vier
Funde dort in jedem Lauf stammen von den absichtlichen Positivtests, jeder weitere `verlorenGefunden`-Wert ist ein
**natürlicher Fund**.

## Grenzen und Preis

- Reflexion auf Compose-Interna (`AndroidUiDispatcher`, `GlobalSnapshotManager`): gebunden an Compose 1.6.7 /
  Robolectric 4.16.1. Ändert sich ein Feldname, wird die Diagnose schlechter, der Test nicht rot (jede Stufe ist
  abgesichert). **Bei Updates prüfen**, ob `DispatcherReparaturTest` und `DispatcherPluginTest` grün bleiben.
- Das Plugin wirkt auf **alle** Robolectric-Tests. `DispatcherVerlustTest`, `DispatcherReparaturTest` und
  `DispatcherPluginTest` erzeugen den Defekt absichtlich in der geteilten Sandbox, heilen in `finally`
  und überspringen sich, wenn der Dispatcher schon vorher nicht sauber ist.
- Es findet den Auslöser nicht und behebt nicht den Fehler selbst, es verhindert seine Folgen.

## Wieder entfernen

Wenn Compose oder Robolectric das Verhalten beheben: `DispatcherPlugin.kt`, die Service-Datei
`META-INF/services/org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin` und den Aufruf in `ZeitwaechterRegel`
löschen; die Zähler zeigen vorher in der CI, ob noch natürliche Funde auftreten (`verlorenGefunden`).
