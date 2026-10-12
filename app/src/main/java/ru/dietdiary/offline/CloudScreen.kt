package ru.dietdiary.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle

@Composable
fun CloudScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cloud = remember { CloudSync.get(context) }
    val status = cloud.status
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmConnect by remember { mutableStateOf(false) }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch { cloud.completeAuthorization(result.data) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) {
            BackNavigationLabel(tr(R.string.data_back))
        }
        PageTitle(tr(R.string.data_cloud_title), tr(R.string.data_cloud_subtitle))
        Panel {
            Text(status.email ?: tr(R.string.data_no_account), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(tr(R.string.data_cloud_intro), color = Muted)
            Text(tr(R.string.data_cloud_first_connection), color = Muted)
            if (status.email == null) {
                Button(modifier = Modifier.fillMaxWidth().testTag("connect_google"), enabled = !status.busy, onClick = {
                    if (!cloud.configured) notice = tr(R.string.data_cloud_not_configured_notice)
                    else confirmConnect = true
                }) { Text(tr(R.string.data_connect_google)) }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.data_automatic_sync), fontWeight = FontWeight.SemiBold)
                        Text(tr(R.string.data_automatic_sync_description), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Switch(checked = status.automatic, onCheckedChange = {
                        try { cloud.setAutomatic(it) } catch (_: Exception) { notice = tr(R.string.data_sync_schedule_failed) }
                    }, enabled = !status.busy)
                }
                Button(onClick = {
                    try { cloud.syncNow() } catch (_: Exception) { notice = tr(R.string.data_sync_start_failed) }
                }, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.data_sync_now)) }
                OutlinedButton(onClick = { confirmConnect = true }, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.data_google_sign_in_again)) }
                TextButton(onClick = { confirmDisconnect = true }, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.data_disconnect_account)) }
            }
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status.message, color = if (status.failed) MaterialTheme.colorScheme.error else Green,
                modifier = Modifier.testTag("cloud_status"))
            Text(if (status.lastSuccess > 0) tr(R.string.data_last_sync,
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(appLocale())
                    .withDecimalStyle(DecimalStyle.of(appLocale())).withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(status.lastSuccess))) else tr(R.string.data_sync_never), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Panel {
            Text(tr(R.string.data_offline_saved_title), fontWeight = FontWeight.Bold)
            Text(tr(R.string.data_cloud_merge_description), color = Muted)
            Text(tr(R.string.data_cloud_password_description), color = Muted)
        }
        AdCard(AdPlacement.CLOUD)
    }
    if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text(tr(R.string.data_google_connection_title)) },
        text = { Text(notice!!) }, confirmButton = { TextButton(onClick = { notice = null }) { Text(tr(R.string.data_got_it)) } })
    if (confirmConnect) AlertDialog(onDismissRequest = { confirmConnect = false }, title = { Text(tr(R.string.data_connect_drive_title)) },
        text = { Text(tr(R.string.data_connect_drive_description)) },
        confirmButton = { TextButton(onClick = {
            confirmConnect = false
            try { DietAds.suppressNextEntry(); authorization.launch(cloud.authorizationIntent()) }
            catch (_: Exception) { notice = tr(R.string.data_sign_in_open_failed) }
        }) { Text(tr(R.string.data_continue)) } }, dismissButton = { TextButton(onClick = { confirmConnect = false }) { Text(tr(R.string.data_cancel)) } })
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text(tr(R.string.data_disconnect_title)) },
        text = { Text(tr(R.string.data_disconnect_description)) },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; scope.launch {
            try { cloud.disconnect() } catch (_: Exception) { notice = tr(R.string.data_disconnect_failed) }
        } }) { Text(tr(R.string.data_disconnect)) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(tr(R.string.data_cancel)) } })
}
