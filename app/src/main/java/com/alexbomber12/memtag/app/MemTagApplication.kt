package com.alexbomber12.memtag.app

import android.app.Application

class MemTagApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
