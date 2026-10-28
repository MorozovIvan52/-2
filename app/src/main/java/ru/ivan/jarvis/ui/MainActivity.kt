package ru.ivan.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.toMillis
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Растёт при каждом запуске через ярлык «Голосовая команда». */
    private var listenRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            JarvisTheme {
                JarvisRoot(vm, listenRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshFocus()
        // Режим «Слушать всегда» был включён — поднимаем после перезапуска телефона/приложения
        if (vm.prefs.handsFree && !ru.ivan.jarvis.voice.HandsFreeService.running &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) runCatching { ru.ivan.jarvis.voice.HandsFreeService.start(this) }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_LISTEN, Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> listenRequests++
            ACTION_WHAT_NOW -> vm.sayWhatNow()
            ACTION_COMMAND -> intent.getStringExtra(EXTRA_TEXT)?.let(vm::handleExternalVoice)
        }
    }

    companion object {
        const val ACTION_LISTEN = "ru.ivan.jarvis.LISTEN"
        const val ACTION_WHAT_NOW = "ru.ivan.jarvis.WHAT_NOW"
        const val ACTION_COMMAND = "ru.ivan.jarvis.COMMAND"
        const val EXTRA_TEXT = "text"
    }
}

private enum class Tab(val label: String) { HOME("Джарвис"), SERVICE("Сервис"), CALENDAR("Календарь"), NOTES("Заметки"), SETTINGS("Настройки") }

@Composable
private fun JarvisRoot(vm: MainViewModel, listenRequests: Int) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val voice by vm.voice.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var editingOrder by remember { mutableStateOf<ru.ivan.jarvis.service.Order?>(null) }
    val orders by vm.orders.collectAsStateWithLifecycle()
    val money by vm.money.collectAsStateWithLifecycle()

    // Запасной путь, если на телефоне нет встроенного SpeechRecognizer — системное окно распознавания.
    val recognizeActivity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::handle)
        }
    }
    fun startListening() {
        if (vm.voice.available) vm.voice.start()
        else runCatching { recognizeActivity.launch(ru.ivan.jarvis.voice.VoiceInput.recognizerIntent()) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
    }
    fun onMic() {
        tab = Tab.HOME
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Разрешения для звонков/контактов запрашиваются в момент первой такой команды
    val actionPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionsResult()
    }
    val ask = vm.permissionAsk
    LaunchedEffect(ask) {
        if (ask != null) actionPermissions.launch(ask.permissions.toTypedArray())
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(listenRequests) {
        if (listenRequests > 0) onMic()
    }
    // Режим диалога: после голосового ответа снова слушаем
    LaunchedEffect(vm.listenAgain) {
        if (vm.listenAgain > 0 && vm.voice.available &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) { tab = Tab.HOME; vm.voice.start() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Filled.Mic
                                    Tab.SERVICE -> Icons.Filled.Build
                                    Tab.CALENDAR -> Icons.Filled.CalendarMonth
                                    Tab.NOTES -> Icons.Filled.EditNote
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.SERVICE) {
                FloatingActionButton(onClick = { editingOrder = ru.ivan.jarvis.service.Order() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Новый заказ")
                }
            }
            if (tab == Tab.CALENDAR || tab == Tab.NOTES) {
                FloatingActionButton(onClick = {
                    editing = if (tab == Tab.CALENDAR) {
                        Item(type = ItemType.REMINDER, title = "", dueAt = selectedDay.atTime(9, 0).toMillis())
                    } else {
                        Item(type = ItemType.NOTE, title = "")
                    }
                }) { Icon(Icons.Filled.Add, contentDescription = "Добавить") }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    items = items,
                    voice = voice,
                    feedback = vm.feedback,
                    speakEnabled = vm.speakEnabled,
                    onMic = ::onMic,
                    onText = vm::handle,
                    onUndo = vm::undo,
                    onWrong = vm::markWrong,
                    onToggleSpeak = vm::toggleSpeak,
                    onEdit = { editing = it },
                    onToggleDone = vm::toggleDone,
                    focus = vm.focus,
                    onStartFocus = vm::startFocus,
                    onStopFocus = vm::stopFocus,
                    onRefreshFocus = vm::refreshFocus,
                    aiProblem = vm.aiProblem(),
                    aiLabel = vm.aiLabel,
                    onOpenSettings = { tab = Tab.SETTINGS },
                )
                Tab.CALENDAR -> CalendarScreen(
                    items = items,
                    selected = selectedDay,
                    onSelect = { selectedDay = it },
                    onEdit = { editing = it },
                    onToggleDone = vm::toggleDone,
                )
                Tab.NOTES -> NotesScreen(items = items, onEdit = { editing = it }, onToggleDone = vm::toggleDone)
                Tab.SETTINGS -> SettingsScreen(vm)
                Tab.SERVICE -> ServiceScreen(
                    orders = orders,
                    money = money,
                    onEdit = { editingOrder = it },
                    onStatus = { o, st ->
                        vm.setOrderStatus(o, st)
                        // «Готов» с телефоном — Джарвис предложит написать клиенту на главном экране
                        if (st == ru.ivan.jarvis.parse.OrderStatus.READY && o.phone.isNotBlank()) tab = Tab.HOME
                    },
                    onAsk = { tab = Tab.HOME; vm.handle(it) },
                )
            }
        }
    }

    editingOrder?.let { o ->
        EditOrderDialog(
            initial = o,
            onDismiss = { editingOrder = null },
            onSave = { vm.saveOrder(it); editingOrder = null },
            onDelete = if (o.id != 0L) ({ x: ru.ivan.jarvis.service.Order -> vm.deleteOrder(x); editingOrder = null }) else null,
        )
    }

    editing?.let { item ->
        EditItemDialog(
            initial = item,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = if (item.id != 0L) ({ i: Item -> vm.delete(i); editing = null }) else null,
        )
    }
}
