package ru.dietdiary.offline

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/** Existing Russian UI scenarios choose their language explicitly, independently of the emulator. */
internal fun installRussianTestLocale(context: Context): AutoCloseable {
    val original = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList(appLocale()))
    }
    val originalDefault = Locale.getDefault()
    val russian = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag("ru-RU")))
    }
    Localization.install(context, russian)
    return AutoCloseable {
        Localization.install(context, original)
        Locale.setDefault(originalDefault)
    }
}

/** Platform strings and date pickers in these fixtures also receive Russian resources. */
@Composable internal fun RussianTestContent(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val currentConfiguration = LocalConfiguration.current
    val configuration = remember(currentConfiguration) {
        Configuration(currentConfiguration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("ru-RU")))
            setLayoutDirection(Locale.forLanguageTag("ru-RU"))
        }
    }
    val localized = remember(context, configuration) {
        // Keep the Activity in the wrapper chain for native ad hosts and platform dialogs.
        ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(configuration) }
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides LayoutDirection.Ltr,
        content = content,
    )
}
