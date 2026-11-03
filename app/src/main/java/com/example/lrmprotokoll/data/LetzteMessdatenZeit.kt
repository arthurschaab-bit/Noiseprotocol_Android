package com.example.lrmprotokoll.data

/** Der Drive-Puffer kann nach dem Sync geloescht sein; Rohwerte bleiben die primaere Spur. */
suspend fun AppDatabase.letzteMessdatenAt(): Long? =
    listOfNotNull(
        measurementDao().letzterMesswertAt(),
        levelSampleDao().maxAt(),
    ).maxOrNull()
