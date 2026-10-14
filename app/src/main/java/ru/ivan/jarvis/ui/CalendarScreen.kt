package ru.ivan.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.occursOn
import ru.ivan.jarvis.data.toLocalDateTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

@Composable
fun CalendarScreen(
    items: List<Item>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onEdit: (Item) -> Unit,
    onToggleDone: (Item) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val today = LocalDate.now()
    val reminders = items.filter { it.type == ItemType.REMINDER && it.dueAt != null }
    val dayItems = reminders.filter { it.occursOn(selected) }
        .sortedWith(compareBy<Item>({ !it.allDay }, { it.dueAt!!.toLocalDateTime().toLocalTime() }))

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, "Предыдущий месяц") }
            Text(
                month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU).replaceFirstChar { it.uppercase() } + " " + month.year,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (month != YearMonth.from(today)) {
                TextButton(onClick = { month = YearMonth.from(today); onSelect(today) }) { Text("Сегодня") }
            }
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.ChevronRight, "Следующий месяц") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { i, d ->
                Text(
                    d,
                    Modifier.weight(1f).padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i >= 5) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - 1
        val cells = offset + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c - offset
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (idx in 0 until month.lengthOfMonth()) {
                            val date = month.atDay(idx + 1)
                            val count = reminders.count { !it.done && it.occursOn(date) }
                            DayCell(date, date == selected, date == today, count) { onSelect(date) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            dayLabel(selected).replaceFirstChar { it.uppercase() } + ", " + weekdayName(selected.dayOfWeek),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        if (dayItems.isEmpty()) {
            Text(
                "Ничего не запланировано. Нажмите «+» или скажите голосом.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dayItems, key = { it.id }) { item ->
                ItemCard(item, onClick = { onEdit(item) }, onToggleDone = { onToggleDone(item) }, subtitle = timeOf(item))
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, count: Int, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize()
            .clip(CircleShape)
            .background(if (selected) accent else Color.Transparent)
            .then(if (today && !selected) Modifier.border(1.5.dp, accent, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
        )
        if (count > 0) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(5.dp)
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary, CircleShape)
            )
        }
    }
}
