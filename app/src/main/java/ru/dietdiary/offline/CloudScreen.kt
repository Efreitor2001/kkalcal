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
        TextButton(onClick = onBack) { Text("‹  Назад") }
        PageTitle("Облачная синхронизация", "Ваш дневник на ваших устройствах")
        Panel {
            Text(status.email ?: "Без аккаунта", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Вход необязателен. Продукты, еда, измерения, цели и достижения хранятся на телефоне. После подключения копия будет в скрытой папке вашего Google Drive.", color = Muted)
            Text("При первом подключении записи объединяются. Другие файлы на вашем Диске приложению недоступны.", color = Muted)
            if (status.email == null) {
                Button(modifier = Modifier.fillMaxWidth().testTag("connect_google"), enabled = !status.busy, onClick = {
                    if (!cloud.configured) notice = "Подключение Google ещё не настроено для этой сборки. Дневник и резервные копии работают без аккаунта."
                    else confirmConnect = true
                }) { Text("Подключить Google") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Автоматическая синхронизация", fontWeight = FontWeight.SemiBold)
                        Text("При изменениях и в фоне, когда разрешает Android", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Switch(checked = status.automatic, onCheckedChange = {
                        try { cloud.setAutomatic(it) } catch (_: Exception) { notice = "Не удалось изменить расписание. Данные сохранены на устройстве." }
                    }, enabled = !status.busy)
                }
                Button(onClick = {
                    try { cloud.syncNow() } catch (_: Exception) { notice = "Не удалось запустить синхронизацию. Данные сохранены на устройстве." }
                }, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) { Text("Синхронизировать сейчас") }
                OutlinedButton(onClick = { confirmConnect = true }, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) { Text("Повторный вход в Google") }
                TextButton(onClick = { confirmDisconnect = true }, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) { Text("Отключить аккаунт") }
            }
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status.message, color = if (status.message.startsWith("Не ")) MaterialTheme.colorScheme.error else Green,
                modifier = Modifier.testTag("cloud_status"))
            Text(if (status.lastSuccess > 0) "Последняя синхронизация: " +
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(status.lastSuccess)) else "Синхронизаций пока не было", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Panel {
            Text("Без интернета всё сохраняется", fontWeight = FontWeight.Bold)
            Text("С включённой автосинхронизацией изменения отправятся после появления сети. Если один и тот же показатель изменён на двух устройствах, применяется версия с более поздней отметкой изменения. Независимые записи и поля объединяются.", color = Muted)
            Text("Пароль вводится только на странице Google в браузере. Токены доступа защищены хранилищем ключей Android и не включаются в резервные копии.", color = Muted)
        }
        AdCard(AdPlacement.CLOUD)
    }
    if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text("Подключение Google") },
        text = { Text(notice!!) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("Понятно") } })
    if (confirmConnect) AlertDialog(onDismissRequest = { confirmConnect = false }, title = { Text("Подключить Google Drive?") },
        text = { Text("Текущий дневник будет объединён с данными выбранного Google-аккаунта. Используйте один личный аккаунт на своих устройствах. Вход откроется в системном браузере.") },
        confirmButton = { TextButton(onClick = {
            confirmConnect = false
            try { DietAds.suppressNextEntry(); authorization.launch(cloud.authorizationIntent()) }
            catch (_: Exception) { notice = "Не удалось открыть вход. Установите или включите системный браузер и повторите попытку." }
        }) { Text("Продолжить") } }, dismissButton = { TextButton(onClick = { confirmConnect = false }) { Text("Отмена") } })
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text("Отключить аккаунт?") },
        text = { Text("Синхронизация на этом устройстве остановится. Локальный дневник и копии в Google Drive сохранятся. Управлять разрешением приложения можно в настройках Google-аккаунта.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; scope.launch {
            try { cloud.disconnect() } catch (_: Exception) { notice = "Не удалось отключить аккаунт. Повторите попытку." }
        } }) { Text("Отключить") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") } })
}
