package ru.ivan.jarvis.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.ivan.jarvis.settings.KeyImport
import ru.ivan.jarvis.voice.YandexTts

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val voices by vm.speaker.voices.collectAsState()

    var yandexVoiceOn by remember { mutableStateOf(prefs.yandexVoiceOn) }
    var yandexVoice by remember { mutableStateOf(prefs.yandexVoice) }
    var systemVoice by remember { mutableStateOf(prefs.systemVoice) }
    var rate by remember { mutableFloatStateOf(prefs.speechRate) }
    var pitch by remember { mutableFloatStateOf(prefs.pitch) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var folderId by remember { mutableStateOf(prefs.folderId) }
    var aiOn by remember { mutableStateOf(prefs.aiOn) }
    var limit by remember { mutableStateOf(prefs.dailyTokenLimit.toString()) }
    var nag by remember { mutableStateOf(prefs.nag) }
    var breakMin by remember { mutableIntStateOf(prefs.breakMinutes) }

    fun open(intent: Intent) = runCatching { context.startActivity(intent) }

    var importMsg by remember { mutableStateOf<String?>(null) }
    fun importFromClipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val keys = KeyImport.parse(text)
        keys.apiKey?.let { apiKey = it; prefs.apiKey = it }
        keys.folderId?.let { folderId = it; prefs.folderId = it }
        importMsg = when {
            keys.apiKey != null && keys.folderId != null -> "Ключи вставлены, ИИ готов"
            keys.apiKey != null || keys.folderId != null -> "Вставлено частично, проверьте поля"
            else -> "В буфере нет ключей. Скопируйте сообщение с YANDEX_API_KEY и YANDEX_FOLDER_ID"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Section("ИИ — YandexGPT Lite") {
            Text(
                "Включается только для вопросов и сложных просьб. Обычные команды работают бесплатно и без интернета.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { importFromClipboard() }, modifier = Modifier.fillMaxWidth()) {
                Text("Вставить ключи из буфера")
            }
            importMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it; prefs.apiKey = it },
                label = { Text("API-ключ Yandex Cloud") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = folderId, onValueChange = { folderId = it; prefs.folderId = it },
                label = { Text("ID каталога (b1g…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Использовать ИИ", aiOn) { aiOn = it; prefs.aiOn = it }
            OutlinedTextField(
                value = limit,
                onValueChange = { v -> limit = v.filter { it.isDigit() }; limit.toIntOrNull()?.let { prefs.dailyTokenLimit = it } },
                label = { Text("Лимит токенов в день") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Сегодня: ${prefs.tokensToday} токенов · всего: ${prefs.tokensTotal} · озвучено Яндексом: ${prefs.ttsCharsTotal} симв.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Голос") {
            SwitchRow("Голос Яндекса (SpeechKit)", yandexVoiceOn) { yandexVoiceOn = it; prefs.yandexVoiceOn = it }
            if (yandexVoiceOn) {
                if (!prefs.yandexReady) {
                    Text("Нужны API-ключ и ID каталога выше", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YandexTts.VOICES.forEach { (id, name) ->
                        FilterChip(selected = yandexVoice == id, onClick = { yandexVoice = id; prefs.yandexVoice = id }, label = { Text(name) })
                    }
                }
            } else {
                Text(
                    "Системный синтезатор: ${vm.speaker.engineName.ifBlank { "не найден" }}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (voices.isEmpty()) {
                    Text("Русских голосов не найдено — установите «Синтезатор речи Google»", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    voices.forEachIndexed { i, v ->
                        val label = "Голос ${i + 1}" + if (v.isNetworkConnectionRequired) " · онлайн" else ""
                        FilterChip(
                            selected = systemVoice == v.name || (systemVoice.isEmpty() && i == 0),
                            onClick = { systemVoice = v.name; prefs.systemVoice = v.name; vm.speaker.applySettings(); vm.speaker.say("Привет, я Джарвис") },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Text("Скорость: %.1f".format(rate), style = MaterialTheme.typography.bodyMedium)
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.8f,
                onValueChangeFinished = { prefs.speechRate = rate; vm.speaker.applySettings() })
            if (!yandexVoiceOn) {
                Text("Тон: %.1f".format(pitch), style = MaterialTheme.typography.bodyMedium)
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.6f..1.4f,
                    onValueChangeFinished = { prefs.pitch = pitch; vm.speaker.applySettings() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.speaker.say("Добрый вечер. Я Джарвис, чем могу помочь?") }) { Text("Прослушать") }
                if (!yandexVoiceOn) {
                    OutlinedButton(onClick = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")))
                    }) { Text("Голоса Google") }
                }
            }
            if (!yandexVoiceOn) {
                OutlinedButton(onClick = { open(Intent("com.android.settings.TTS_SETTINGS")) }) { Text("Настройки синтеза речи") }
            }
        }

        Section("Помощь с вниманием") {
            SwitchRow("Настойчивые напоминания (повтор каждые 10 мин до «Готово»)", nag) { nag = it; prefs.nag = it }
            Text("Перерыв после фокуса", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { m ->
                    FilterChip(selected = breakMin == m, onClick = { breakMin = m; prefs.breakMinutes = m }, label = { Text("$m мин") })
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
