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
    val policy = remember(context) {
        context.assets.open("privacy-policy.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    fun open(intent: Intent) {
        try { DietAds.suppressNextEntry(); context.startActivity(intent) }
        catch (_: Exception) { notice = "Не удалось открыть страницу. Её адрес можно скопировать из текста ниже." }
    }
    Column(Modifier.fillMaxSize().testTag("privacy_screen").verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("‹  Назад") }
        PageTitle("Конфиденциальность", "Ваши записи и управление данными")
        Panel {
            Text("О назначении приложения", fontWeight = FontWeight.Bold)
            Text("Приложение не является медицинским изделием. Оно не диагностирует, не лечит, не излечивает и не предотвращает заболевания. За медицинскими рекомендациями, диагностикой и лечением обращайтесь к квалифицированному врачу.", color = Muted)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                policy.split(Regex("\\r?\\n\\s*\\r?\\n")).forEach { paragraph ->
                    Text(paragraph.trim(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Panel {
            Text("Управление и удаление данных", fontWeight = FontWeight.Bold)
            Text("Перед полной очисткой отключите синхронизацию на всех своих устройствах. Удаление приложения с телефона не удаляет копии в Google Drive и экспортированные файлы.", color = Muted)
            OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/u/0/settings"))) }, modifier = Modifier.fillMaxWidth()) { Text("Управление данными Google Drive") }
            OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/connections"))) }, modifier = Modifier.fillMaxWidth()) { Text("Доступ к Google-аккаунту") }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("Хранилище приложения в Android") }
            TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Efreitor2001/kkalcal/issues"))) }, modifier = Modifier.fillMaxWidth()) { Text("Связаться с разработчиком") }
        }
    }
    if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text("Открытие страницы") },
        text = { Text(notice!!) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("Понятно") } })
}
