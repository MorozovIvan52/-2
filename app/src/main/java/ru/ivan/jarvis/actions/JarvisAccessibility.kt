package ru.ivan.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ru.ivan.jarvis.parse.ScreenGlobal

/**
 * Служба спецвозможностей: нажимает кнопки в чужих приложениях.
 * Включается вручную: Настройки → Спецвозможности → Установленные приложения → Джарвис.
 * На Android 13+ для APK не из Play сначала: О приложении → ⋮ → «Разрешить ограниченные настройки».
 * Текст экрана никуда не сохраняется и не отправляется.
 */
class JarvisAccessibility : AccessibilityService() {

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = armedPkg ?: return
        if (System.currentTimeMillis() > armedUntil) { armedPkg = null; return }
        if (event?.packageName?.toString() != pkg) return
        val root = rootInActiveWindow ?: return
        // ID кнопки WhatsApp «com.whatsapp:id/send» не документирован и может смениться — запасной вариант по подписи
        val btn = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
            ?: findByDescription(root, setOf("отправить", "send"))
            ?: return
        if (clickNode(btn)) armedPkg = null
    }

    companion object {
        @Volatile private var instance: JarvisAccessibility? = null
        @Volatile private var armedPkg: String? = null
        @Volatile private var armedUntil = 0L

        /** Служба включена в настройках (даже если Android её ещё не запустил). */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, JarvisAccessibility::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun running() = instance != null

        fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Один раз нажать «Отправить» в [pkg] в ближайшие [ms] мс.
         * Вызывается только после подтверждения «да» пользователем.
         */
        fun armSend(pkg: String, ms: Long = 15_000): Boolean {
            if (instance == null) return false
            armedPkg = pkg
            armedUntil = System.currentTimeMillis() + ms
            return true
        }

        fun global(action: ScreenGlobal): Boolean {
            val s = instance ?: return false
            val code = when (action) {
                ScreenGlobal.BACK -> GLOBAL_ACTION_BACK
                ScreenGlobal.HOME -> GLOBAL_ACTION_HOME
                ScreenGlobal.RECENTS -> GLOBAL_ACTION_RECENTS
                ScreenGlobal.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                ScreenGlobal.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                // Блокировка и скриншот — только с Android 9 (API 28)
                ScreenGlobal.LOCK -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
                ScreenGlobal.SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            }
            return s.performGlobalAction(code)
        }

        /** Нажать на элемент экрана с этим текстом или подписью. */
        fun click(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node = nodes.firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }
                ?: nodes.firstOrNull()
                ?: findByDescription(root, setOf(text.lowercase()))
                ?: return false
            return clickNode(node)
        }

        /** Видимый текст текущего экрана (для «что на экране»). Никуда не сохраняется. */
        fun screenText(maxChars: Int = 1500): String? {
            val root = instance?.rootInActiveWindow ?: return null
            val sb = StringBuilder()
            val seen = HashSet<String>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var n = 0
            while (queue.isNotEmpty() && n < 1500 && sb.length < maxChars) {
                val node = queue.removeFirst()
                n++
                if (node.isVisibleToUser) {
                    val t = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
                    if (t.length > 1 && seen.add(t)) sb.append(t).append('\n')
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            return sb.toString().take(maxChars).trim().ifBlank { null }
        }

        fun scroll(forward: Boolean): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val node = findFirst(root) { it.isScrollable } ?: return false
            return node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }

        /** Сам элемент часто не кликабелен — нажимаем ближайшего кликабельного родителя. */
        private fun clickNode(node: AccessibilityNodeInfo): Boolean {
            var n: AccessibilityNodeInfo? = node
            var depth = 0
            while (n != null && depth < 6) {
                if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                n = n.parent
                depth++
            }
            return false
        }

        private fun findByDescription(root: AccessibilityNodeInfo, words: Set<String>) =
            findFirst(root) { n -> n.contentDescription?.toString()?.lowercase()?.let { it in words } == true }

        private fun findFirst(root: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            while (queue.isNotEmpty() && seen < 800) {
                val n = queue.removeFirst()
                seen++
                if (test(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
            return null
        }
    }
}
