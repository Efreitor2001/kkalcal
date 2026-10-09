package ru.dietdiary.offline

import android.app.Application
import android.util.Log

class DietDiaryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DietAds.install(this)
        // SDK/network/configuration failures must never prevent opening the local diary.
        try { CloudSync.get(this).start() }
        catch (_: Exception) { Log.w("DietDiaryCloud", "background_setup_failed") }
    }
}
