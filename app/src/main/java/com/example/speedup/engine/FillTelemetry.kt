package com.example.speedup.engine

import android.content.Context
import android.content.SharedPreferences

/** On-device fill session stats (no network). */
object FillTelemetry {
    private const val PREFS = "speedup_fill_telemetry"

    fun recordSession(context: Context, filled: Int, failed: Int, unknownLabels: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getInt("sessions", 0) + 1
        prefs.edit()
            .putInt("sessions", total)
            .putInt("last_filled", filled)
            .putInt("last_failed", failed)
            .putStringSet("last_unknown_labels", unknownLabels.take(20).toSet())
            .apply()
    }
}
