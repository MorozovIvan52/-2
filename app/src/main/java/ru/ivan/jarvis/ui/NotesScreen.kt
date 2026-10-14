package ru.ivan.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType

private enum class Filter(val label: String) { NOTES("Заметки"), REMINDERS("Напоминания"), DONE("Выполнено") }

@Composable
fun NotesScreen(items: List<Item>, onEdit: (Item) -> Unit, onToggleDone: (Item) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.NOTES) }

    val shown = items.filter {
        when (filter) {
            Filter.NOTES -> it.type == ItemType.NOTE
            Filter.REMINDERS -> it.type == ItemType.REMINDER && !it.done
            Filter.DONE -> it.type == ItemType.REMINDER && it.done
        }
    }.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .let { list -> if (filter == Filter.REMINDERS) list.sortedBy { it.dueAt } else list }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { Text("Поиск") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (filter == Filter.NOTES) "Заметок пока нет. Скажите «Запиши…»" else "Здесь пока пусто",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { item ->
                ItemCard(item, onClick = { onEdit(item) }, onToggleDone = { onToggleDone(item) })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
