package ru.ivan.jarvis.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.voice.VoiceState

private val examples = listOf(
    "Напомни завтра в 9 позвонить врачу",
    "Что мне сейчас делать?",
    "Сделал позвонить врачу",
    "Перенеси отчёт на завтра в 10",
    "Итоги дня",
    "Фокус 25 минут",
    "Открой Телеграм",
    "Разбуди меня в 7",
    "Будильник на 6:30 по будням",
    "Запиши в календарь встречу в пятницу в 15",
    "Поставь таймер на 10 минут",
    "Включи фонарик",
    "Какой момент затяжки ГБЦ на Логан 1.6?",
    "Что значит ошибка P0300?",
    "Напиши маме что я задержусь",
    "Что ты умеешь?",
)

@Composable
fun HomeScreen(
    items: List<Item>,
    voice: VoiceState,
    feedback: Feedback?,
    speakEnabled: Boolean,
    onMic: () -> Unit,
    onText: (String) -> Unit,
    onUndo: () -> Unit,
    onToggleSpeak: () -> Unit,
    onEdit: (Item) -> Unit,
    onToggleDone: (Item) -> Unit,
    focus: FocusState = FocusState(),
    onStartFocus: (Int) -> Unit = {},
    onStopFocus: () -> Unit = {},
    onRefreshFocus: () -> Unit = {},
) {
    val now = System.currentTimeMillis()
    val upcoming = items
        .filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null }
        .sortedBy { it.dueAt }
        .filter { it.dueAt!! >= now - 12 * 3600_000L }
        .take(8)
    val recentNotes = items.filter { it.type == ItemType.NOTE }.take(3)
    var typed by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Джарвис",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleSpeak) {
                    Icon(
                        if (speakEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = if (speakEnabled) "Выключить голосовые ответы" else "Включить голосовые ответы",
                    )
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MicButton(listening = voice.listening, level = voice.level, onClick = onMic)
                Spacer(Modifier.height(16.dp))
                Text(
                    when {
                        voice.listening && voice.partial.isNotBlank() -> voice.partial
                        voice.listening -> "Слушаю…"
                        voice.error != null -> voice.error
                        else -> "Нажмите и скажите, что записать или напомнить"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = if (voice.error != null && !voice.listening) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        item {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("…или напишите команду") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onText(typed); typed = "" }),
                trailingIcon = {
                    IconButton(onClick = { onText(typed); typed = "" }, enabled = typed.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                },
            )
        }
        item { FocusCard(focus, onStartFocus, onStopFocus, onRefreshFocus, onAsk = onText) }
        if (feedback != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(feedback.text, Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (feedback.itemId != null) TextButton(onClick = onUndo) { Text("Отменить") }
                }
                if (feedback.links.isNotEmpty()) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        feedback.links.forEach { (domain, url) ->
                            SuggestionChip(onClick = {
                                runCatching {
                                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                                }
                            }, label = { Text("🔗 $domain") })
                        }
                    }
                }
                if (feedback.quick.isNotEmpty()) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        feedback.quick.forEach { q -> SuggestionChip(onClick = { onText(q) }, label = { Text(q) }) }
                    }
                }
            }
        }
        item { SectionTitle("Ближайшие напоминания") }
        if (upcoming.isEmpty()) {
            item {
                Text(
                    "Пока пусто. Попробуйте сказать:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(examples) { ex ->
                SuggestionChip(onClick = { onText(ex) }, label = { Text("«$ex»") })
            }
        } else {
            items(upcoming, key = { it.id }) { item ->
                ItemCard(item, onClick = { onEdit(item) }, onToggleDone = { onToggleDone(item) })
            }
        }
        if (recentNotes.isNotEmpty()) {
            item { SectionTitle("Последние заметки") }
            items(recentNotes, key = { "n${it.id}" }) { item ->
                ItemCard(item, onClick = { onEdit(item) }, onToggleDone = {})
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun MicButton(listening: Boolean, level: Float, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val idle by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "idle",
    )
    val voiceScale by animateFloatAsState(if (listening) 1f + level * 0.35f else idle, label = "level")
    val accent = MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
        Box(
            Modifier.size(150.dp).scale(voiceScale)
                .background(accent.copy(alpha = if (listening) 0.22f else 0.10f), CircleShape)
        )
        Box(
            Modifier.size(124.dp).border(2.dp, accent.copy(alpha = 0.6f), CircleShape)
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(104.dp)
                .clip(CircleShape)
                .background(if (listening) accent else MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .clickable(onClick = onClick),
        ) {
            Icon(
                if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (listening) "Остановить" else "Говорить",
                tint = if (listening) MaterialTheme.colorScheme.onPrimary else accent,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun FocusCard(
    focus: FocusState,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onAsk: (String) -> Unit,
) {
    if (focus.phase.isEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onStart(25) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Timer, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Фокус 25 мин")
            }
            OutlinedButton(onClick = { onAsk("что мне сейчас делать") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Lightbulb, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Что делать?")
            }
        }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(focus) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= focus.endAt) {
                delay(1500)
                onRefresh()
            }
            delay(1000)
        }
    }
    val left = ((focus.endAt - now) / 1000).coerceAtLeast(0)
    val isWork = focus.phase == "work"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isWork) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (isWork) "Фокус" else "Перерыв", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%d:%02d".format(left / 60, left % 60),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (isWork) "Одна задача. Телефон — только для неё." else "Встаньте, разомнитесь, попейте воды.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onStop) { Text("Стоп") }
        }
    }
}
