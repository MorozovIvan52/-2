package ru.ivan.jarvis.actions

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import ru.ivan.jarvis.parse.AppMatcher
import ru.ivan.jarvis.parse.MediaKey
import ru.ivan.jarvis.parse.PhoneAction
import ru.ivan.jarvis.parse.SettingsPanel
import ru.ivan.jarvis.parse.VolumeChange

/** Выполняет команды управления телефоном через стандартные интенты Android. */
class PhoneController(private val context: Context) {

    /** message — что сказать пользователю; missingPermissions — что нужно запросить и повторить. */
    data class Result(
        val message: String,
        val missingPermissions: List<String> = emptyList(),
        val speak: Boolean = true,
    )

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** canAsk = false после того, как разрешения уже запрашивали: тогда работаем с тем, что есть. */
    fun perform(action: PhoneAction, canAsk: Boolean): Result = when (action) {
        is PhoneAction.OpenApp -> openApp(action.name)
        is PhoneAction.Call -> call(action.target, canAsk)
        is PhoneAction.Sms -> sms(action.target, action.text, canAsk)
        is PhoneAction.Alarm -> alarm(action)
        is PhoneAction.Timer -> timer(action.seconds)
        is PhoneAction.Flashlight -> flashlight(action.on)
        is PhoneAction.Volume -> volume(action.change)
        is PhoneAction.Media -> media(action.key)
        is PhoneAction.PlayMusic -> playMusic(action.query)
        is PhoneAction.Navigate -> navigate(action.destination)
        is PhoneAction.WebSearch -> webSearch(action.query)
        is PhoneAction.Settings -> settings(action.panel)
        PhoneAction.Camera -> if (start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) Result("Открываю камеру")
        else Result("Не нашёл приложение камеры")
        // Остальное обрабатывает MainViewModel
        PhoneAction.Help, PhoneAction.WhatNow, PhoneAction.FocusStop, is PhoneAction.Focus -> Result("")
    }

    private fun openApp(name: String): Result {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }
        val pkg = AppMatcher.best(name, apps) ?: return Result("Не нашёл приложение «$name»")
        val label = apps.first { it.second == pkg }.first
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return Result("Не получается открыть $label")
        return if (start(intent)) Result("Открываю $label") else Result("Не получается открыть $label")
    }

    private fun isNumber(s: String) = s.count { it.isDigit() } >= 3 && s.all { it.isDigit() || it in "+-() " }

    /** Ищет контакт по имени с учётом падежей: «маме» → «Мама», «Ивану» → «Иван». */
    private fun findContact(name: String): Pair<String, String>? {
        val clean = name.trim().replaceFirstChar { it.uppercase() }
        val stems = listOf(clean, clean.dropLast(1), clean.dropLast(2))
            .filter { it.length >= 3 }.distinct()
        for (stem in stems) {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$stem%"),
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(0) to c.getString(1)
            }
        }
        return null
    }

    private fun call(target: String, canAsk: Boolean): Result {
        if (isNumber(target)) {
            val number = target.filter { it.isDigit() || it == '+' }
            return dial(number, target, canAsk)
        }
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            if (canAsk) return Result("", listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
            start(Intent(Intent.ACTION_DIAL))
            return Result("Нет доступа к контактам, открыл набор номера")
        }
        val (name, number) = findContact(target) ?: return Result("Не нашёл в контактах «$target»")
        return dial(number, name, canAsk)
    }

    private fun dial(number: String, who: String, canAsk: Boolean): Result {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (granted(Manifest.permission.CALL_PHONE)) {
            if (start(Intent(Intent.ACTION_CALL, uri))) return Result("Звоню: $who", speak = false)
        } else if (canAsk) {
            return Result("", listOf(Manifest.permission.CALL_PHONE))
        }
        start(Intent(Intent.ACTION_DIAL, uri))
        return Result("Набрал номер: $who. Нажмите вызов", speak = false)
    }

    private fun sms(target: String, text: String, canAsk: Boolean): Result {
        var number: String? = null
        var who = target
        if (isNumber(target)) {
            number = target.filter { it.isDigit() || it == '+' }
        } else if (granted(Manifest.permission.READ_CONTACTS)) {
            findContact(target)?.let { who = it.first; number = it.second }
        } else if (canAsk) {
            return Result("", listOf(Manifest.permission.READ_CONTACTS))
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (number?.let { Uri.encode(it) } ?: "")))
            .putExtra("sms_body", text)
        return if (start(intent)) {
            if (number != null) Result("Сообщение для $who готово, нажмите «Отправить»")
            else Result("Не нашёл контакт «$target», выберите получателя сами")
        } else Result("Не нашёл приложение для SMS")
    }

    private fun alarm(a: PhoneAction.Alarm): Result {
        if (a.hour == null) {
            return if (start(Intent(AlarmClock.ACTION_SHOW_ALARMS))) Result("Открываю будильники. Скажите время, например «будильник на 7».")
            else Result("Во сколько разбудить? Скажите, например, «будильник на 7».")
        }
        val time = "%d:%02d".format(a.hour, a.minute)
        val daysText = daysText(a.days)
        // Конкретная дата системным часам не передаётся — тогда ставим свой будильник
        val own = a.date != null || ru.ivan.jarvis.settings.Prefs(context).alarmOwn
        if (!own) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (a.label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
            if (a.days.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(a.days.map { it.value % 7 + 1 }))
            if (start(intent)) return Result("Будильник на $time$daysText поставлен в часах")
        }
        val at = ru.ivan.jarvis.alarm.JarvisAlarm.set(context, a.hour, a.minute, a.date, a.days, a.label)
            ?: return Result("Это время уже прошло. Скажите другое.")
        val day = when (at.toLocalDate()) {
            java.time.LocalDate.now() -> "сегодня"
            java.time.LocalDate.now().plusDays(1) -> "завтра"
            else -> at.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ru")))
        }
        return Result("Будильник Джарвиса на $time$daysText, ближайший $day")
    }

    private fun daysText(days: List<java.time.DayOfWeek>): String = when {
        days.isEmpty() -> ""
        days.size == 7 -> " каждый день"
        days == java.time.DayOfWeek.entries.take(5) -> " по будням"
        days == java.time.DayOfWeek.entries.takeLast(2) -> " по выходным"
        else -> ""
    }

    private fun timer(seconds: Int): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(intent)) Result("Таймер на ${durationText(seconds)}") else Result("Не получилось поставить таймер")
    }

    private fun flashlight(on: Boolean): Result {
        val cm = context.getSystemService(CameraManager::class.java)
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result("На телефоне нет фонарика")
            cm.setTorchMode(id, on)
            Result(if (on) "Фонарик включён" else "Фонарик выключен", speak = false)
        } catch (e: Exception) {
            Result("Не получилось управлять фонариком")
        }
    }

    private fun volume(change: VolumeChange): Result {
        val am = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        return try {
            when (change) {
                VolumeChange.UP -> { repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags) }; Result("Громче", speak = false) }
                VolumeChange.DOWN -> { repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags) }; Result("Тише", speak = false) }
                VolumeChange.MAX -> { am.setStreamVolume(stream, am.getStreamMaxVolume(stream), flags); Result("Громкость на максимуме", speak = false) }
                VolumeChange.MUTE -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                    am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                    Result("Звук выключен", speak = false)
                }
                VolumeChange.UNMUTE -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                    am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                    Result("Звук включён")
                }
            }
        } catch (e: SecurityException) {
            // Беззвучный режим без доступа «Не беспокоить» — открываем настройки
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Result("Разрешите Джарвису доступ к режиму «Не беспокоить»")
        }
    }

    private fun media(key: MediaKey): Result {
        val code = when (key) {
            MediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return Result(
            when (key) {
                MediaKey.PLAY -> "Продолжаю"
                MediaKey.PAUSE -> "Пауза"
                MediaKey.NEXT -> "Следующий трек"
                MediaKey.PREVIOUS -> "Предыдущий трек"
            },
            speak = false,
        )
    }

    private fun playMusic(query: String): Result {
        if (query.isBlank()) return media(MediaKey.PLAY).copy(message = "Включаю музыку")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (start(intent)) return Result("Включаю $query", speak = false)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.yandex.ru/search?text=" + Uri.encode(query)))
        return if (start(web)) Result("Ищу $query в Яндекс Музыке", speak = false) else Result("Не нашёл музыкальное приложение")
    }

    private fun navigate(dest: String): Result {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
        return if (start(intent)) Result("Строю маршрут: $dest") else Result("Не нашёл приложение карт")
    }

    private fun webSearch(query: String): Result {
        if (start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))) return Result("Ищу: $query", speak = false)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/search/?text=" + Uri.encode(query)))
        return if (start(web)) Result("Ищу: $query", speak = false) else Result("Не нашёл браузер")
    }

    private fun settings(panel: SettingsPanel): Result {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val (action, name) = when (panel) {
            SettingsPanel.WIFI -> (if (q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS) to "Wi‑Fi"
            SettingsPanel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            SettingsPanel.INTERNET -> (if (q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS) to "интернет"
            SettingsPanel.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS to "экран"
            SettingsPanel.SOUND -> (if (q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS) to "звук"
            SettingsPanel.BATTERY -> Intent.ACTION_POWER_USAGE_SUMMARY to "батарею"
            SettingsPanel.GENERAL -> Settings.ACTION_SETTINGS to "настройки"
        }
        // Android не даёт приложениям самим включать Wi‑Fi и Bluetooth — открываем переключатель
        return if (start(Intent(action)) || start(Intent(Settings.ACTION_SETTINGS))) Result("Открываю $name", speak = false)
        else Result("Не получилось открыть настройки")
    }

    companion object {
        fun durationText(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it ч" },
                m.takeIf { it > 0 }?.let { "$it мин" },
                s.takeIf { it > 0 }?.let { "$it сек" },
            ).joinToString(" ")
        }
    }
}
