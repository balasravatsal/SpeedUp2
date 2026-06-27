package com.example.speedup

import android.app.Application
import com.example.speedup.engine.FieldAliasRepository
import com.example.speedup.engine.FieldMapper
import com.example.speedup.engine.SemanticMatcher

class SpeedUpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FieldMapper.ensureInitialized(this)
        FieldAliasRepository.initialize(this)
        Thread {
            SemanticMatcher.initialize(applicationContext)
        }.start()
    }
}
