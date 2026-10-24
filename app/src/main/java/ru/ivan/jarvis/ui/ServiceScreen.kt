package ru.ivan.jarvis.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.ivan.jarvis.parse.OrderStatus
import ru.ivan.jarvis.service.MoneyEntry
import ru.ivan.jarvis.service.Order
import ru.ivan.jarvis.service.ServiceAssistant

/** Вкладка «Сервис»: машины в работе по статусам и касса. */
@Composable
fun ServiceScreen(
    orders: List<Order>,
    money: List<MoneyEntry>,
    onEdit: (Order) -> Unit,
    onStatus: (Order, OrderStatus) -> Unit,
    onAsk: (String) -> Unit,
) {
    var showDone by remember { mutableStateOf(false) }
    val active = orders.filter { it.status != OrderStatus.DONE }
    val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val today = money.filter { it.at >= dayStart }
    val inc = today.filter { it.income }.sumOf { it.amount }
    val out = today.filter { !it.income }.sumOf { it.amount }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Автосервис", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Касса сегодня", style = MaterialTheme.typography.labelLarge)
                    Text("+${ServiceAssistant.rub(inc)}   −${ServiceAssistant.rub(out)}",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onAsk("касса за неделю") }) { Text("За неделю") }
                        TextButton(onClick = { onAsk("касса за месяц") }) { Text("За месяц") }
                    }
                }
            }
        }
        item {
            Text(
                "В сервисе: ${active.size}. Скажите: «новый клиент Петров, Солярис, замена колодок», «Солярис Петрова готов».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OrderStatus.entries.filter { it != OrderStatus.DONE }.forEach { st ->
            val group = active.filter { it.status == st }
            if (group.isNotEmpty()) {
                item { Text("${st.label} · ${group.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(group, key = { it.id }) { o -> OrderCard(o, onEdit, onStatus) }
            }
        }
        item {
            TextButton(onClick = { showDone = !showDone }) { Text(if (showDone) "Скрыть выданные" else "Показать выданные") }
        }
        if (showDone) items(orders.filter { it.status == OrderStatus.DONE }.take(30), key = { "d${it.id}" }) { o -> OrderCard(o, onEdit, onStatus) }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun OrderCard(o: Order, onEdit: (Order) -> Unit, onStatus: (Order, OrderStatus) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { onEdit(o) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(o.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (o.work.isNotBlank()) Text(o.work, style = MaterialTheme.typography.bodyMedium)
            if (o.parts.isNotBlank()) Text("Запчасти: ${o.parts}", style = MaterialTheme.typography.bodySmall)
            if (o.price > 0) Text("Оплачено: ${ServiceAssistant.rub(o.price)}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OrderStatus.entries.forEach { st ->
                    FilterChip(selected = o.status == st, onClick = { onStatus(o, st) }, label = { Text(st.label) })
                }
            }
            if (o.phone.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + o.phone))) } }) { Text("Позвонить") }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + o.phone))) }
                }) { Text("SMS") }
            }
        }
    }
}

/** Карточка заказа: всё редактируется руками, если голос понял не так. */
@Composable
fun EditOrderDialog(initial: Order, onDismiss: () -> Unit, onSave: (Order) -> Unit, onDelete: ((Order) -> Unit)?) {
    var client by remember { mutableStateOf(initial.client) }
    var phone by remember { mutableStateOf(initial.phone) }
    var car by remember { mutableStateOf(initial.car) }
    var plate by remember { mutableStateOf(initial.plate) }
    var work by remember { mutableStateOf(initial.work) }
    var parts by remember { mutableStateOf(initial.parts) }
    var price by remember { mutableStateOf(if (initial.price > 0) initial.price.toString() else "") }
    var status by remember { mutableStateOf(initial.status) }
    LaunchedEffect(initial.id) {}
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый заказ" else "Заказ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(client, { client = it }, label = { Text("Клиент") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Телефон") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(car, { car = it }, label = { Text("Машина") }, singleLine = true)
                OutlinedTextField(plate, { plate = it.uppercase() }, label = { Text("Госномер") }, singleLine = true)
                OutlinedTextField(work, { work = it }, label = { Text("Работа") })
                OutlinedTextField(parts, { parts = it }, label = { Text("Запчасти") })
                OutlinedTextField(price, { v -> price = v.filter { it.isDigit() } }, label = { Text("Оплачено, ₽") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OrderStatus.entries.forEach { st -> FilterChip(selected = status == st, onClick = { status = st }, label = { Text(st.label) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(client = client.trim(), phone = phone.trim(), car = car.trim(), plate = plate.trim(),
                    work = work.trim(), parts = parts.trim(), price = price.toLongOrNull() ?: 0, status = status))
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { onDelete(initial) }) { Text("Удалить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
