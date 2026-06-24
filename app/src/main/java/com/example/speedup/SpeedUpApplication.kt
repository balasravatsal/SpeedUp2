package com.example.speedup

import android.app.Application
import com.example.speedup.engine.SemanticMatcher

class SpeedUpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            SemanticMatcher.initialize(applicationContext)
        }.start()
    }
}
