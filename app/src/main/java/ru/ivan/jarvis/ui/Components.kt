package ru.ivan.jarvis.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.Repeat
import ru.ivan.jarvis.data.toLocalDateTime
import ru.ivan.jarvis.data.toMillis
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ItemCard(
    item: Item,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val isReminder = item.type == ItemType.REMINDER
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    !isReminder -> Icons.Filled.EditNote
                    item.repeat != Repeat.NONE -> Icons.Filled.Repeat
                    item.allDay -> Icons.Filled.Event
                    else -> Icons.Filled.Alarm
                },
                contentDescription = null,
                tint = if (isReminder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                )
                Text(
                    subtitle ?: if (isReminder) describeDue(item) else createdLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isReminder) Checkbox(checked = item.done, onCheckedChange = { onToggleDone() })
        }
    }
}

/** Создание/редактирование записи вручную. */
@Composable
fun EditItemDialog(
    initial: Item,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit,
    onDelete: ((Item) -> Unit)?,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var withReminder by remember { mutableStateOf(initial.type == ItemType.REMINDER) }
    var due by remember {
        mutableStateOf(initial.dueAt?.toLocalDateTime() ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0))
    }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var repeat by remember { mutableStateOf(initial.repeat) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новая запись" else "Изменить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Текст") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Напомнить", Modifier.weight(1f))
                    Switch(checked = withReminder, onCheckedChange = { withReminder = it })
                }
                if (withReminder) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            DatePickerDialog(context, { _, y, m, d ->
                                due = due.withYear(y).withMonth(m + 1).withDayOfMonth(d)
                            }, due.year, due.monthValue - 1, due.dayOfMonth).show()
                        }) { Text(due.format(DateTimeFormatter.ofPattern("d MMM yyyy", RU))) }
                        OutlinedButton(onClick = {
                            TimePickerDialog(context, { _, h, min ->
                                due = due.withHour(h).withMinute(min)
                                allDay = false
                            }, due.hour, due.minute, true).show()
                        }) { Text(if (allDay) "весь день" else due.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Repeat.NONE to "Один раз", Repeat.DAILY to "Ежедневно", Repeat.WEEKLY to "Еженедельно")
                            .forEach { (r, label) ->
                                FilterChip(selected = repeat == r, onClick = { repeat = r }, label = { Text(label) })
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            type = if (withReminder) ItemType.REMINDER else ItemType.NOTE,
                            dueAt = if (withReminder) due.toMillis() else null,
                            allDay = withReminder && allDay,
                            repeat = if (withReminder) repeat else Repeat.NONE,
                            done = if (withReminder && initial.dueAt != due.toMillis()) false else initial.done,
                        )
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
