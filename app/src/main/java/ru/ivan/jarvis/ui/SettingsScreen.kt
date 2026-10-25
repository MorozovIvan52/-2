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
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
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
import ru.ivan.jarvis.briefing.Briefing
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
    var dialog by remember { mutableStateOf(prefs.dialog) }
    var calSync by remember { mutableStateOf(prefs.calendarSync) }
    var askWhen by remember { mutableStateOf(prefs.askWhen) }
    var speakRem by remember { mutableStateOf(prefs.speakReminders) }
    var alarmOwn by remember { mutableStateOf(prefs.alarmOwn) }
    val calGranted = ru.ivan.jarvis.calendar.CalendarSync.granted(context)
    var morningOn by remember { mutableStateOf(prefs.morningOn) }
    var morningHour by remember { mutableIntStateOf(prefs.morningHour) }
    var eveningOn by remember { mutableStateOf(prefs.eveningOn) }
    var eveningHour by remember { mutableIntStateOf(prefs.eveningHour) }

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

        Section("Без рук и сообщения") {
            HandsFreeBlock(vm)
        }

        Section("Журнал команд") {
            JournalBlock(vm)
        }

        Section("Резервная копия") {
            BackupBlock()
        }

        Section("Проверка: что разрешено") {
            DiagnosticsBlock()
        }

        Section("Календарь и будильник") {
            SwitchRow("Дублировать напоминания в календарь телефона", calSync) {
                calSync = it; prefs.calendarSync = it
                if (it) vm.requestCalendar()
            }
            if (calSync && !calGranted) {
                OutlinedButton(onClick = { vm.requestCalendar() }, modifier = Modifier.fillMaxWidth()) { Text("Дать доступ к календарю") }
            } else if (calSync) {
                OutlinedButton(onClick = { vm.syncAllToCalendar() }, modifier = Modifier.fillMaxWidth()) { Text("Перенести все дела в календарь") }
            }
            SwitchRow("Свой будильник Джарвиса (вместо приложения «Часы»)", alarmOwn) { alarmOwn = it; prefs.alarmOwn = it }
            Text(
                "Будильник на конкретную дату («разбуди в пятницу в 8») всегда ставится свой — часы такое не умеют.",
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
            Text(
                "Сделано сегодня: ${prefs.doneToday} · всего: ${prefs.doneTotal}" +
                    (if (prefs.streak > 1) " · серия ${prefs.streak} дн." else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Режим диалога (после ответа снова слушаю)", dialog) { dialog = it; prefs.dialog = it }
            SwitchRow("Спрашивать «Когда напомнить?» для задач без времени", askWhen) { askWhen = it; prefs.askWhen = it }
            SwitchRow("Проговаривать напоминания вслух", speakRem) { speakRem = it; prefs.speakReminders = it }
            SwitchRow("Утренний план дня", morningOn) { morningOn = it; prefs.morningOn = it; Briefing.scheduleAll(context) }
            if (morningOn) HourChips(listOf(7, 8, 9, 10), morningHour) { morningHour = it; prefs.morningHour = it; Briefing.scheduleAll(context) }
            SwitchRow("Вечерние итоги", eveningOn) { eveningOn = it; prefs.eveningOn = it; Briefing.scheduleAll(context) }
            if (eveningOn) HourChips(listOf(19, 20, 21, 22), eveningHour) { eveningHour = it; prefs.eveningHour = it; Briefing.scheduleAll(context) }
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
private fun HandsFreeBlock(vm: MainViewModel) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(vm.prefs.handsFree) }
    var tick by remember { mutableIntStateOf(0) }
    val mic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { vm.prefs.handsFree = true; on = true; ru.ivan.jarvis.voice.HandsFreeService.start(context) } }
    val overlay = android.provider.Settings.canDrawOverlays(context)
    SwitchRow("Слушать всегда: «Джарвис, …» без касаний", on) { v ->
        if (v) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                mic.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                vm.prefs.handsFree = true; on = true
                ru.ivan.jarvis.voice.HandsFreeService.start(context)
            }
        } else {
            vm.prefs.handsFree = false; on = false
            ru.ivan.jarvis.voice.HandsFreeService.stop(context)
        }
    }
    Text(
        "Работает, пока висит уведомление «Джарвис слушает». Расходует батарею; на ночь лучше выключать. Некоторые телефоны тихо пикают при каждом прослушивании.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    key(tick) {
        if (!overlay) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)))
                }
                tick++
            }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить «Поверх других окон» (чтобы отвечать из фона)") }
        }
        val listener = ru.ivan.jarvis.messages.MessageListener.enabled(context)
        OutlinedButton(onClick = { runCatching { context.startActivity(ru.ivan.jarvis.messages.MessageListener.settingsIntent()) }; tick++ },
            modifier = Modifier.fillMaxWidth()) {
            Text(if (listener) "✅ Доступ к уведомлениям есть (читаю сообщения)" else "Разрешить читать сообщения (доступ к уведомлениям)")
        }
    }
}

@Composable
private fun JournalBlock(vm: MainViewModel) {
    val context = LocalContext.current
    val entries = remember { vm.journal.all().take(15) }
    if (entries.isEmpty()) Text("Пока пусто", style = MaterialTheme.typography.bodySmall)
    entries.forEach { e ->
        Text((if (e.wrong) "❌ " else "• ") + "«${e.said}» → ${e.reply.take(90)}", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Журнал Джарвиса", vm.journal.export()))
        android.widget.Toast.makeText(context, "Журнал скопирован — вставьте его в чат разработчику", android.widget.Toast.LENGTH_LONG).show()
    }, modifier = Modifier.fillMaxWidth()) { Text("Скопировать журнал") }
}

@Composable
private fun BackupBlock() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val create = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ru.ivan.jarvis.data.Backup.export(context, uri) }.getOrDefault(-1) }
            msg = if (n >= 0) "Сохранено записей: $n" else "Не удалось сохранить"
        }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ru.ivan.jarvis.data.Backup.import(context, uri) }.getOrDefault(-1) }
            msg = if (n >= 0) "Восстановлено записей: $n" else "Это не файл копии Джарвиса"
        }
    }
    Text("Все заметки, напоминания, заказы и касса в одном файле. Сохраните на Google Диск или в Telegram «Избранное».",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { create.launch("jarvis-${java.time.LocalDate.now()}.json") }) { Text("Сохранить копию") }
        OutlinedButton(onClick = { open.launch(arrayOf("application/json", "*/*")) }) { Text("Восстановить") }
    }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun DiagnosticsBlock() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    val apps = remember(tick) {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).size
    }
    val am = context.getSystemService(android.app.AlarmManager::class.java)
    val nm = context.getSystemService(android.app.NotificationManager::class.java)
    val rows = listOf(
        Triple("Микрофон", ok(android.Manifest.permission.RECORD_AUDIO), android.Manifest.permission.RECORD_AUDIO),
        Triple("Контакты (звонки и SMS по имени)", ok(android.Manifest.permission.READ_CONTACTS), android.Manifest.permission.READ_CONTACTS),
        Triple("Отправка SMS", ok(android.Manifest.permission.SEND_SMS), android.Manifest.permission.SEND_SMS),
        Triple("Звонки", ok(android.Manifest.permission.CALL_PHONE), android.Manifest.permission.CALL_PHONE),
        Triple("Календарь", ok(android.Manifest.permission.WRITE_CALENDAR), android.Manifest.permission.WRITE_CALENDAR),
        Triple("Уведомления", android.os.Build.VERSION.SDK_INT < 33 || ok(android.Manifest.permission.POST_NOTIFICATIONS),
            if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.POST_NOTIFICATIONS else ""),
    )
    key(tick) {
        rows.forEach { (name, granted, _) -> Text((if (granted) "✅ " else "❌ ") + name, style = MaterialTheme.typography.bodyMedium) }
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        Text((if (exact) "✅ " else "❌ ") + "Точные напоминания", style = MaterialTheme.typography.bodyMedium)
        val full = android.os.Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        Text((if (full) "✅ " else "❌ ") + "Будильник поверх экрана", style = MaterialTheme.typography.bodyMedium)
        Text((if (apps > 3) "✅ " else "❌ ") + "Видно приложений: $apps", style = MaterialTheme.typography.bodyMedium)
        val speech = android.speech.SpeechRecognizer.isRecognitionAvailable(context)
        Text((if (speech) "✅ " else "❌ ") + "Распознавание речи", style = MaterialTheme.typography.bodyMedium)
    }
    val missing = rows.filter { !it.second && it.third.isNotEmpty() }.map { it.third }
    if (missing.isNotEmpty()) {
        androidx.compose.material3.Button(onClick = { launcher.launch(missing.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Разрешить всё")
        }
    }
    OutlinedButton(onClick = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Настройки приложения") }
    Text(
        "На Xiaomi/Redmi/POCO в «Настройки приложения → Другие разрешения» включите «Отображать всплывающие окна, когда запущено в фоне» и «Автозапуск» — иначе телефон не даёт открывать приложения и вовремя напоминать.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HourChips(hours: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hours.forEach { h -> FilterChip(selected = selected == h, onClick = { onPick(h) }, label = { Text("$h:00") }) }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
