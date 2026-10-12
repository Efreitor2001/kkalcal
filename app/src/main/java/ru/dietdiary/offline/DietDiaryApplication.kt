package ru.dietdiary.offline

import android.app.Application
import android.content.res.Configuration
import android.util.Log

class DietDiaryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Localization.install(this)
        DietAds.install(this)
        // SDK/network/configuration failures must never prevent opening the local diary.
        try { CloudSync.get(this).start() }
        catch (_: Exception) { Log.w("DietDiaryCloud", "background_setup_failed") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Localization.install(this, Localization.deviceConfiguration(this, newConfig))
    }
}
