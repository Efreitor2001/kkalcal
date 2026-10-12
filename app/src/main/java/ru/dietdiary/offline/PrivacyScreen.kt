package ru.dietdiary.offline

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val policy = listOf(
        tr(R.string.data_privacy_paragraph_1),
        tr(R.string.data_privacy_paragraph_2),
        tr(R.string.data_privacy_paragraph_3),
        tr(R.string.data_privacy_paragraph_4),
        tr(R.string.data_privacy_paragraph_5),
        tr(R.string.data_privacy_paragraph_6),
        tr(R.string.data_privacy_paragraph_7),
        tr(R.string.data_privacy_paragraph_8),
        tr(R.string.data_privacy_paragraph_9),
        tr(R.string.data_privacy_paragraph_10),
        tr(R.string.data_privacy_paragraph_11),
        tr(R.string.data_privacy_paragraph_12),
        tr(R.string.data_privacy_paragraph_13),
        tr(R.string.data_privacy_paragraph_14),
        tr(R.string.data_privacy_paragraph_15),
        tr(R.string.data_privacy_paragraph_16),
        tr(R.string.data_privacy_paragraph_17),
        tr(R.string.data_privacy_paragraph_18),
        tr(R.string.data_privacy_paragraph_19),
        tr(R.string.data_privacy_paragraph_20),
    )
    var notice by remember { mutableStateOf<String?>(null) }
    fun open(intent: Intent) {
        try { DietAds.suppressNextEntry(); context.startActivity(intent) }
        catch (_: Exception) { notice = tr(R.string.data_page_open_failed) }
    }
    Column(Modifier.fillMaxSize().testTag("privacy_screen").verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) {
            BackNavigationLabel(tr(R.string.data_back))
        }
        PageTitle(tr(R.string.data_privacy_title), tr(R.string.data_privacy_subtitle))
        Panel {
            Text(tr(R.string.data_app_purpose), fontWeight = FontWeight.Bold)
            Text(tr(R.string.data_medical_disclaimer), color = Muted)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                policy.forEach { paragraph ->
                    Text(paragraph.trim(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Panel {
            Text(tr(R.string.data_manage_delete_data), fontWeight = FontWeight.Bold)
            Text(tr(R.string.data_delete_data_intro), color = Muted)
            OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/u/0/settings"))) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.data_manage_drive_data)) }
            OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/connections"))) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.data_google_account_access)) }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.data_android_app_storage)) }
            TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Efreitor2001/kkalcal/issues"))) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.data_contact_developer)) }
        }
    }
    if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text(tr(R.string.data_opening_page)) },
        text = { Text(notice!!) }, confirmButton = { TextButton(onClick = { notice = null }) { Text(tr(R.string.data_got_it)) } })
}
