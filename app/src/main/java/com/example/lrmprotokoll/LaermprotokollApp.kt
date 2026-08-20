package com.example.lrmprotokoll

import android.app.Application

class LaermprotokollApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
