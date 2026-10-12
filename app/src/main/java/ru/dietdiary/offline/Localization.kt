package ru.dietdiary.offline

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import java.util.Locale

object Localization {
    @Volatile private var localizedResources: Resources? = null
    @Volatile var locale: Locale = Locale.US
        private set

    fun configuration(source: Configuration): Configuration = Configuration(source).apply {
        val requested = if (source.locales.isEmpty) Locale.US else source.locales[0]
        val selected = LocalePolicy.resolve(requested)
        setLocales(LocaleList(selected))
        setLayoutDirection(selected)
    }

    fun deviceConfiguration(context: Context, source: Configuration = context.resources.configuration): Configuration {
        // Resources may reorder locales to put an available translation first. Read the
        // user's actual preference before resolving our explicit English fallback.
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            manager.applicationLocales.takeUnless { it.isEmpty } ?: manager.systemLocales
        } else Resources.getSystem().configuration.locales
        return Configuration(source).apply { setLocales(requested) }
    }

    fun install(context: Context, source: Configuration = deviceConfiguration(context)) {
        val selected = configuration(source)
        locale = selected.locales[0]
        Locale.setDefault(locale)
        localizedResources = context.createConfigurationContext(selected).resources
    }

    fun text(@StringRes id: Int, vararg arguments: Any): String {
        val resources = localizedResources
        if (resources != null) return if (arguments.isEmpty()) resources.getString(id)
            else resources.getString(id, *arguments)
        // Plain JVM model tests have no Android Context. This is generated from the same English resources.
        val english = EnglishText.value(id)
        return if (arguments.isEmpty()) english else String.format(Locale.US, english, *arguments)
    }
}

fun appLocale(): Locale = Localization.locale
fun tr(@StringRes id: Int, vararg arguments: Any): String = Localization.text(id, *arguments)

/** Activity resources and platform dialogs use the same language as app-owned strings. */
open class LocalizedActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val configuration = Localization.configuration(Localization.deviceConfiguration(newBase))
        Localization.install(newBase, configuration)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
}
