package com.example.lrmprotokoll.alert

import com.example.lrmprotokoll.data.AlertEntity
import com.example.lrmprotokoll.data.DeliveryState
import com.example.lrmprotokoll.meter.ConnectionState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueft die Alarmlogik aus Plan 7.2 vollstaendig gegen Fakes - ohne BLE, ohne SMS, ohne Netz.
 *
 * Alle Zeiten laufen ueber [TestUhr] und [TestScheduler]: Die Karenzzeit betraegt 60 s, der
 * Cooldown 30 min und die Eskalation 60 min; in echter Zeit waere diese Datei eine Stunde
 * Laufzeit und wuerde binnen kurzem "vereinfacht", bis sie nichts mehr prueft.
 *
 * Robolectric statt reinem JUnit, weil der Fehlerpfad des Versands echtes android.util.Log
 * durchlaeuft - ohne Android-Laufzeit wirft der Protokollaufruf "not mocked" und der Test
 * scheitert an der Testumgebung statt an der Logik.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmCoordinatorTest {

    private val uhr = TestUhr()
    private val scheduler = TestScheduler()
    private val dao = TestAlertDao()
    private val ntfy = TestKanal(ChannelId.NTFY)
    private val lokal = TestKanal(ChannelId.LOCAL_NOTIFICATION)
    private val zustaende = MutableStateFlow(ConnectionState.IDLE)
    private val adapter = MutableStateFlow(true)

    private val karenz = Duration.ofSeconds(60)

    private fun baue(
        scope: kotlinx.coroutines.CoroutineScope,
        config: AlarmConfig = AlarmConfig(),
        kanaele: List<AlertChannel> = listOf(ntfy, lokal),
    ) = AlarmCoordinator(
        states = zustaende,
        adapterEnabled = adapter,
        channels = kanaele,
        dao = dao,
        scheduler = scheduler,
        scope = scope,
        now = uhr,
        config = config,
        zone = ZoneId.of("Europe/Berlin"),
    )

    /** Simuliert den Weckruf des AlarmManager zur geplanten Zeit. */
    private fun weckeZurGeplantenZeit(koordinator: AlarmCoordinator) {
        val faellig = requireNonNull(scheduler.geplant)
        uhr.setze(faellig)
        koordinator.onDeadline()
    }

    private fun <T> requireNonNull(wert: T?): T = requireNotNull(wert) { "Es war keine Weckzeit gesetzt" }

    // ------------------------------------------------------------------ Karenzzeit

    @Test
    fun rueckkehrInnerhalbDerKarenzzeitLoestKeinenAlarmAus() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.STREAMING
        zustaende.value = ConnectionState.DISCONNECTED
        uhr.vor(Duration.ofSeconds(20))
        zustaende.value = ConnectionState.STREAMING

        assertTrue("Kein Kanal darf etwas versendet haben", lokal.gesendet.isEmpty() && ntfy.gesendet.isEmpty())
        assertNull("Die Weckzeit muss zurueckgenommen sein", scheduler.geplant)
        assertEquals(1, dao.inhalt.size)
        assertNull("Der Ausfall darf nie ausgeloest worden sein", dao.inhalt.single().raisedAt)
        assertNotNull("Der Ausfall muss geschlossen sein", dao.inhalt.single().resolvedAt)
    }

    @Test
    fun ausfallUeberDieKarenzzeitHinausAlarmiertUeberAlleKanaele() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        val beginn = uhr.now()
        zustaende.value = ConnectionState.DISCONNECTED
        assertEquals("Die Karenzzeit muss ab Ausfallbeginn laufen", beginn.plus(karenz), scheduler.geplant)

        weckeZurGeplantenZeit(koordinator)

        assertEquals(listOf(AlertKind.RAISED), lokal.arten)
        assertEquals(listOf(AlertKind.RAISED), ntfy.arten)
        val zeile = dao.inhalt.single()
        assertEquals(beginn.plus(karenz).toEpochMilli(), zeile.raisedAt)
        assertEquals(DeliveryState.SENT, zeile.deliveryState)
        assertEquals(1, zeile.attempts)
    }

    @Test
    fun zehnKurzeAussetzerLoesenKeinenEinzigenAlarmAus() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        repeat(10) {
            zustaende.value = ConnectionState.DISCONNECTED
            uhr.vor(Duration.ofSeconds(5))
            zustaende.value = ConnectionState.RECONNECTING
            uhr.vor(Duration.ofSeconds(5))
            zustaende.value = ConnectionState.STREAMING
            uhr.vor(Duration.ofSeconds(5))
        }

        assertTrue(lokal.gesendet.isEmpty())
        assertTrue(ntfy.gesendet.isEmpty())
        assertEquals("Pro Aussetzer genau eine geschlossene Zeile, kein Wildwuchs", 10, dao.inhalt.size)
        assertTrue("Keine Zeile darf ausgeloest worden sein", dao.inhalt.all { it.raisedAt == null })
    }

    @Test
    fun zwischenzustaendeEinesReconnectsBeendenDenAusfallNicht() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        val beginn = uhr.now()
        zustaende.value = ConnectionState.DISCONNECTED
        // Der Supervisor durchlaeuft diese Zustaende bei jedem Reconnect-Versuch. Wuerden sie
        // als Entwarnung zaehlen, koennte ein Geraet, das sich verbindet aber nie Daten
        // liefert, den Alarm dauerhaft verhindern.
        zustaende.value = ConnectionState.RECONNECTING
        zustaende.value = ConnectionState.CONNECTING
        zustaende.value = ConnectionState.DISCOVERING
        zustaende.value = ConnectionState.SUBSCRIBING

        assertEquals("Die urspruengliche Weckzeit muss stehen bleiben", beginn.plus(karenz), scheduler.geplant)
        weckeZurGeplantenZeit(koordinator)
        assertEquals(listOf(AlertKind.RAISED), ntfy.arten)
    }

    // ------------------------------------------------------------------ Paralleler Versand

    @Test
    fun einWerfenderKanalVerhindertDenVersandUeberDieAnderenNicht() = runTest(UnconfinedTestDispatcher()) {
        lokal.wirftAb(RuntimeException("SMS-Stack kaputt"))
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)

        assertEquals("Der intakte Kanal muss versendet haben", listOf(AlertKind.RAISED), ntfy.arten)
        assertEquals("Der kaputte Kanal muss es versucht haben", 1, lokal.versuche)
        assertEquals(
            "Ein durchgekommener Kanal genuegt fuer SENT",
            DeliveryState.SENT,
            dao.inhalt.single().deliveryState,
        )
    }

    @Test
    fun scheiternAllerKanaeleWirdAlsFailedVermerkt() = runTest(UnconfinedTestDispatcher()) {
        lokal.scheitertAb(true)
        ntfy.scheitertAb(true)
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)

        assertEquals(DeliveryState.FAILED, dao.inhalt.single().deliveryState)
        assertEquals(1, lokal.versuche)
        assertEquals(1, ntfy.versuche)
    }

    @Test
    fun nichtVerfuegbareKanaeleWerdenUebersprungen() = runTest(UnconfinedTestDispatcher()) {
        lokal.isAvailable = false
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)

        assertEquals("Ohne Berechtigung darf gar nicht erst versucht werden", 0, lokal.versuche)
        assertEquals(listOf(AlertKind.RAISED), ntfy.arten)
    }

    // ------------------------------------------------------------------ Cooldown

    @Test
    fun zweiterAusfallImCooldownAlarmiertNichtSondernWirdVerschoben() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        // Erster Ausfall mit Alarm.
        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)
        val ersterAlarm = uhr.now()
        assertEquals(1, ntfy.gesendet.size)

        // Verbindung kehrt zurueck, faellt zehn Minuten spaeter erneut aus.
        zustaende.value = ConnectionState.STREAMING
        uhr.vor(Duration.ofMinutes(10))
        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)

        assertEquals(
            "Innerhalb des Cooldowns darf kein zweiter Alarm rausgehen",
            1,
            ntfy.gesendet.count { it.kind == AlertKind.RAISED },
        )
        assertEquals(
            "Stattdessen wird auf das Ende des Cooldowns verschoben",
            ersterAlarm.plus(Duration.ofMinutes(30)),
            scheduler.geplant,
        )
    }

    @Test
    fun nachAblaufDesCooldownsWirdDerVerschobeneAlarmNachgeholt() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)
        zustaende.value = ConnectionState.STREAMING
        uhr.vor(Duration.ofMinutes(10))
        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator) // faellt in den Cooldown, wird verschoben

        weckeZurGeplantenZeit(koordinator) // Cooldown ist jetzt abgelaufen

        assertEquals(
            "Ein Ausfall, der in einen Cooldown faellt, darf nicht dauerhaft stumm bleiben",
            2,
            ntfy.gesendet.count { it.kind == AlertKind.RAISED },
        )
    }

    // ------------------------------------------------------------------ Eskalation

    @Test
    fun eskalationWiederholtDreimalUndHoertDannAuf() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)
        val ersterAlarm = uhr.now()

        repeat(3) { runde ->
            assertEquals(
                "Wiederholung ${runde + 1} muss 60 min nach der vorigen liegen",
                ersterAlarm.plus(Duration.ofMinutes(60L * (runde + 1))),
                scheduler.geplant,
            )
            weckeZurGeplantenZeit(koordinator)
        }

        assertEquals(3, ntfy.gesendet.count { it.kind == AlertKind.ESCALATED })
        assertEquals(3, dao.inhalt.single().escalations)

        // Vierter Weckruf: keine weitere Wiederholung mehr.
        uhr.vor(Duration.ofMinutes(60))
        koordinator.onDeadline()
        assertEquals(
            "Nach der dritten Wiederholung ist Schluss",
            3,
            ntfy.gesendet.count { it.kind == AlertKind.ESCALATED },
        )
    }

    @Test
    fun rueckkehrDerVerbindungBeendetDieEskalation() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)
        weckeZurGeplantenZeit(koordinator) // erste Eskalation

        zustaende.value = ConnectionState.STREAMING
        assertNull(scheduler.geplant)

        koordinator.onDeadline() // verspaeteter Weckruf nach der Entwarnung
        assertEquals(1, ntfy.gesendet.count { it.kind == AlertKind.ESCALATED })
    }

    // ------------------------------------------------------------------ Entwarnung

    @Test
    fun entwarnungGehtNurAnDieDafuerEingeschaltetenKanaele() = runTest(UnconfinedTestDispatcher()) {
        // Nur der Push-Kanal bekommt eine Entwarnung. Die Einstellung ist je Kanal schaltbar,
        // weil ihr urspruenglicher Grund - ein Kanal, bei dem jede Nachricht spuerbar zaehlt -
        // mit jedem kuenftigen Kanal wiederkommen kann.
        val koordinator = baue(
            scope = backgroundScope,
            config = AlarmConfig(entwarnungUeber = setOf(ChannelId.NTFY)),
        )
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)
        zustaende.value = ConnectionState.STREAMING

        assertEquals(
            "Eingeschalteter Kanal: bekommt die Entwarnung",
            listOf(AlertKind.RAISED, AlertKind.RESOLVED),
            ntfy.arten,
        )
        assertEquals(
            "Ausgeschalteter Kanal: bekommt nur den Alarm, nicht die Entwarnung",
            listOf(AlertKind.RAISED),
            lokal.arten,
        )
        assertNotNull(dao.inhalt.single().resolvedAt)
    }

    @Test
    fun ohneVorherigenAlarmGibtEsAuchKeineEntwarnung() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        uhr.vor(Duration.ofSeconds(10))
        zustaende.value = ConnectionState.STREAMING

        assertTrue(
            "Eine Entwarnung waere hier die erste Nachricht ueber ein Problem, das es nicht mehr gibt",
            ntfy.gesendet.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ Ursachen

    @Test
    fun abgeschalteterAdapterWirdAlsSolcherGemeldetUndNichtAlsGeraeteausfall() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        adapter.value = false
        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)

        assertEquals(AlertReason.ADAPTER_OFF, ntfy.gesendet.single().reason)
        assertTrue(ntfy.gesendet.single().message.contains("Bluetooth aus"))
    }

    @Test
    fun ausErschoepftenVersuchenWirdDiePraezisereUrsache() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        zustaende.value = ConnectionState.RECONNECTING
        zustaende.value = ConnectionState.FAILED
        weckeZurGeplantenZeit(koordinator)

        assertEquals(AlertReason.RECONNECT_EXHAUSTED, ntfy.gesendet.single().reason)
    }

    // ------------------------------------------------------------------ Prozess-Tod

    @Test
    fun ausfallUeberlebtDenProzessTodUndWirdNachgeholt() = runTest(UnconfinedTestDispatcher()) {
        // Ausgangslage: Die App wurde waehrend der laufenden Karenzzeit abgeschossen. Genau das
        // tun Hersteller-ROMs im Dauerbetrieb - der Alarm darf dabei nicht verloren gehen.
        val ausfallBeginn = uhr.now().minus(Duration.ofMinutes(5))
        dao.lege(
            AlertEntity(
                outageSince = ausfallBeginn.toEpochMilli(),
                reason = AlertReason.DISCONNECTED.name,
            )
        )

        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        assertEquals(
            "Die Karenzzeit war laengst abgelaufen, aber der Verbindungsaufbau bekommt eine Nachfrist",
            uhr.now().plus(Duration.ofSeconds(15)),
            scheduler.geplant,
        )

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)

        assertEquals(listOf(AlertKind.RAISED), ntfy.arten)
        assertEquals(
            "Der Alarm nennt den urspruenglichen Ausfallbeginn, nicht den Neustart",
            ausfallBeginn,
            ntfy.gesendet.single().since,
        )
    }

    @Test
    fun nachDemProzessTodWiederhergestellteVerbindungAlarmiertNicht() = runTest(UnconfinedTestDispatcher()) {
        dao.lege(
            AlertEntity(
                outageSince = uhr.now().minus(Duration.ofMinutes(5)).toEpochMilli(),
                reason = AlertReason.DISCONNECTED.name,
            )
        )

        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        // Das Geraet ist beim Wiederanlauf in Reichweite - genau dafuer ist die Nachfrist da.
        zustaende.value = ConnectionState.STREAMING

        assertTrue(ntfy.gesendet.isEmpty())
        assertNull(scheduler.geplant)
        assertNotNull(dao.inhalt.single().resolvedAt)
    }

    @Test
    fun derCooldownUeberlebtEinenNeustart() = runTest(UnconfinedTestDispatcher()) {
        // Vor dem Neustart wurde bereits alarmiert; der Ausfall wurde geschlossen.
        val frueher = uhr.now().minus(Duration.ofMinutes(10))
        dao.lege(
            AlertEntity(
                outageSince = frueher.toEpochMilli(),
                raisedAt = frueher.toEpochMilli(),
                resolvedAt = frueher.plus(Duration.ofMinutes(1)).toEpochMilli(),
                reason = AlertReason.DISCONNECTED.name,
                deliveryState = DeliveryState.SENT,
            )
        )

        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        weckeZurGeplantenZeit(koordinator)

        assertTrue(
            "Laege der Cooldown nur im Speicher, gaebe es nach jedem Neustart sofort neue Alarme",
            ntfy.gesendet.isEmpty(),
        )
        assertEquals(frueher.plus(Duration.ofMinutes(30)), scheduler.geplant)
    }

    // ------------------------------------------------------------------ Ueberwachung beendet

    @Test
    fun beendeteUeberwachungSchliesstDenAusfallStill() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        zustaende.value = ConnectionState.DISCONNECTED
        zustaende.value = ConnectionState.IDLE

        assertNull("Kein Weckruf mehr, die Ueberwachung laeuft nicht", scheduler.geplant)
        assertNotNull(dao.inhalt.single().resolvedAt)
        assertTrue(ntfy.gesendet.isEmpty())
    }

    @Test
    fun testnachrichtLaeuftAnDerAlarmlogikVorbei() = runTest(UnconfinedTestDispatcher()) {
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        val ergebnis = koordinator.sendeTest(ChannelId.NTFY)

        assertTrue(ergebnis.isSuccess)
        assertEquals(listOf(AlertKind.TEST), ntfy.arten)
        assertTrue("Die Probe darf keinen Ausfall anlegen", dao.inhalt.isEmpty())
        assertNull(scheduler.geplant)
    }

    @Test
    fun testnachrichtUeberEinenNichtVerfuegbarenKanalSchlaegtFehl() = runTest(UnconfinedTestDispatcher()) {
        lokal.isAvailable = false
        val koordinator = baue(scope = backgroundScope)
        koordinator.start()

        val ergebnis = koordinator.sendeTest(ChannelId.LOCAL_NOTIFICATION)

        assertTrue("Der Nutzer muss erfahren, dass der Kanal nicht einsatzbereit ist", ergebnis.isFailure)
        assertEquals(0, lokal.versuche)
    }
}
