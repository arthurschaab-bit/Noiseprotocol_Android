package com.example.lrmprotokoll.report.pdf

/**
 * Die reine Umbruchlogik eines mehrseitigen Berichts - ohne `Canvas`, ohne `PdfDocument`.
 *
 * Warum getrennt: `PdfDocument()` wirft unter Robolectric bei jedem `startPage()`
 * ("IllegalStateException: document is closed!", siehe KDoc von
 * [com.example.lrmprotokoll.report.MessreiheExportTest]). Alles, was den Canvas anfasst, ist in
 * dieser Codebasis also grundsaetzlich nicht automatisiert pruefbar. Die Frage "auf welcher
 * Seite landet welcher Block" ist aber gar keine Zeichenfrage, sondern reine Arithmetik - und
 * damit als eigene Klasse unter plain JUnit vollstaendig testbar.
 *
 * Dasselbe Muster wie [com.example.lrmprotokoll.report.pegelEinheit] oder
 * [com.example.lrmprotokoll.report.BerichtDatei.mimeTypFuer]: die pruefbare Entscheidung von der
 * unpruefbaren Framework-Ausgabe trennen, statt beides zu vermengen und dann gar nichts pruefen
 * zu koennen.
 *
 * [Seitenlauf] wird zweimal durchlaufen: einmal mit `zeichnend = false`, um die Seitenzahl zu
 * ermitteln, und danach mit `zeichnend = true` und bekannter [gesamtSeiten] fuer die Ausgabe.
 * Der y-Verlauf ist in beiden Durchlaeufen identisch, weil Zeichnen den Cursor nicht bewegt -
 * nur [platziere] tut das. So steht "Seite 3 von 7" im Fuss, ohne die Gesamtzahl zu raten: In
 * einem Bericht, der als Beleg dient, ist die Angabe der einzige Hinweis darauf, ob dem Leser
 * eine Seite fehlt.
 */
class Seitenlauf(
    private val seitenHoehe: Float = SEITE_HOEHE,
    private val randOben: Float = RAND_OBEN,
    private val randUnten: Float = RAND_UNTEN,
    private val onNeueSeite: (seitenNummer: Int) -> Unit = {},
) {

    /** 1-basiert, wie im Fusszeilentext. */
    var seitenNummer: Int = 1
        private set

    /** Aktuelle Schreibposition von oben, in Punkt. */
    var y: Float = randOben
        private set

    /** Unterste noch beschreibbare y-Position. */
    val unterkante: Float get() = seitenHoehe - randUnten

    private var ersteSeiteGemeldet = false

    /**
     * Reserviert [hoehe] Punkt und liefert die y-Position, an der gezeichnet werden darf.
     * Passt der Block nicht mehr auf die Seite, wird vorher umgebrochen.
     *
     * Ein Block, der hoeher ist als eine ganze Seite, erzwingt KEINEN Endlos-Umbruch: Er beginnt
     * auf einer frischen Seite und laeuft ueber deren Rand hinaus. Das ist der ehrlichere
     * Ausgang als eine Endlosschleife - und der Aufrufer ist dafuer zustaendig, so grosse
     * Bloecke gar nicht erst zu bilden (Text wird zeilenweise platziert, nicht als ein Block).
     */
    fun platziere(hoehe: Float): Float {
        meldeErsteSeite()
        if (y + hoehe > unterkante && y > randOben) {
            seitenNummer++
            y = randOben
            onNeueSeite(seitenNummer)
        }
        val position = y
        y += hoehe
        return position
    }

    /**
     * Erzwingt einen Umbruch, sofern die aktuelle Seite bereits Inhalt traegt - fuer Abschnitte,
     * die auf einer eigenen Seite beginnen sollen. Auf einer noch leeren Seite ein No-Op, damit
     * kein Leerblatt entsteht.
     */
    fun neueSeite() {
        meldeErsteSeite()
        if (y <= randOben) return
        seitenNummer++
        y = randOben
        onNeueSeite(seitenNummer)
    }

    /** Fuegt [abstand] Punkt Leerraum ein, ohne einen Umbruch auszuloesen. */
    fun abstand(abstand: Float) {
        meldeErsteSeite()
        y += abstand
    }

    private fun meldeErsteSeite() {
        if (!ersteSeiteGemeldet) {
            ersteSeiteGemeldet = true
            onNeueSeite(1)
        }
    }

    companion object {
        /** DIN A4 bei 72 dpi - wie bisher in MessreiheExport und PeriodenBerichtExport. */
        const val SEITE_BREITE = 595f
        const val SEITE_HOEHE = 842f
        const val RAND_LINKS = 40f
        const val RAND_RECHTS = 40f
        const val RAND_OBEN = 48f

        /** Platz fuer die Fusszeile mit der Seitenangabe. */
        const val RAND_UNTEN = 56f

        val INHALT_BREITE = SEITE_BREITE - RAND_LINKS - RAND_RECHTS

        /**
         * Zaehlt die Seiten eines Berichts vor, indem [aufbau] gegen einen nicht zeichnenden
         * [Seitenlauf] laufen gelassen wird.
         */
        fun zaehleSeiten(aufbau: (Seitenlauf) -> Unit): Int {
            val lauf = Seitenlauf()
            aufbau(lauf)
            return lauf.seitenNummer
        }
    }
}
