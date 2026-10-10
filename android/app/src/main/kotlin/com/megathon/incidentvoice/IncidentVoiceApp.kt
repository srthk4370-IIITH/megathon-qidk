package com.megathon.incidentvoice

import android.app.Application

class IncidentVoiceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startEngine()
        container.housekeeping()
        container.traceConversation()
    }
}
