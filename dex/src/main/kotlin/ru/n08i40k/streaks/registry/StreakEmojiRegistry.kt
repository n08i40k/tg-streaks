package ru.n08i40k.streaks.registry

import android.view.View
import androidx.annotation.UiThread
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.override.StreakEmojiTouchHandler
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getField
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class StreakEmojiRegistry {
    companion object Reflection {
        // DialogsActivity
        val VIEW_PAGES = getField(DialogsActivity::class.java, "viewPages")

        // LaunchActivity
        val ACTION_BAR_LAYOUT = getField(LaunchActivity::class.java, "actionBarLayout")
        val RIGHT_ACTION_BAR_LAYOUT = getField(LaunchActivity::class.java, "rightActionBarLayout")
        val LAYERS_ACTION_BAR_LAYOUT = getField(LaunchActivity::class.java, "layersActionBarLayout")

        // org.telegram.ui.MainTabsActivity, отсутствует в части клиентов
        val GET_DIALOGS_ACTIVITY: Method by lazy {
            Class.forName("org.telegram.ui.MainTabsActivity")
                .getDeclaredMethod("getDialogsActivity")
        }
    }

    private val elements = ConcurrentHashMap.newKeySet<StreakEmoji.EjectData>(128)

    private val touchHandlers = WeakHashMap<View, StreakEmojiTouchHandler>()

    fun add(data: StreakEmoji.EjectData) = elements.add(data)

    fun attachTouchHandler(view: View, drawable: StreakEmoji) {
        val handler = synchronized(touchHandlers) {
            touchHandlers.getOrPut(view) { StreakEmojiTouchHandler.install(view) }
        }

        handler.register(drawable)
    }

    @UiThread
    fun restoreAll() {
        elements.forEach {
            Logger.tryOrFatal("restore original streak emoji") {
                it.restore()
            }
        }

        elements.clear()

        synchronized(touchHandlers) {
            touchHandlers.forEach { (view, handler) ->
                Logger.tryOrFatal("restore original touch listener") {
                    handler.restore(view)
                }
            }

            touchHandlers.clear()
        }
    }

    fun refreshAll() {
        val it = elements.iterator()

        while (it.hasNext()) {
            val streakEmoji = it.next().drawable.get() ?: run {
                it.remove()
                continue
            }

            streakEmoji.refresh()
        }
    }

    fun refreshByPeerUserId(peerUserId: Long) {
        val it = elements.iterator()

        while (it.hasNext()) {
            val streakEmoji = it.next().drawable.get() ?: run {
                it.remove()
                continue
            }

            if (streakEmoji.getPeerUserId() != peerUserId)
                continue

            streakEmoji.refresh()
        }
    }

    fun refreshDialogCells() {
        val launchActivity = LaunchActivity.instance
        val dialogsActivities = mutableSetOf<DialogsActivity>()

        fun populateSet(layout: INavigationLayout) {
            val stack = layout.fragmentStack

            for (i in 0..<stack.size) {
                val fragment = stack[i] ?: continue

                if (fragment is DialogsActivity)
                    dialogsActivities.add(fragment)
                else if (fragment.javaClass.name == "org.telegram.ui.MainTabsActivity") {
                    (GET_DIALOGS_ACTIVITY.invoke(fragment) as? DialogsActivity)
                        ?.let(dialogsActivities::add)
                }
            }
        }

        // Удивительно, что баг проявился только после обновления jar до версии 12.8.0
        // Как это вообще работало?
        ACTION_BAR_LAYOUT.getAs<ActionBarLayout>(launchActivity)?.let(::populateSet)
        RIGHT_ACTION_BAR_LAYOUT.getAs<ActionBarLayout>(launchActivity)?.let(::populateSet)
        LAYERS_ACTION_BAR_LAYOUT.getAs<ActionBarLayout>(launchActivity)?.let(::populateSet)

        @Suppress("UNCHECKED_CAST")
        val viewPages = dialogsActivities
            .mapNotNull { VIEW_PAGES.getAs<Array<View?>>(it) }
            .flatMap { it.toSet() }

        for (page in viewPages) {
            val listView = (page as? DialogsActivity.ViewPage)?.listView ?: continue
            val adapter = listView.adapter
            listView.adapter = null
            listView.adapter = adapter
        }
    }
}
