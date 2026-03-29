@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.registry

import android.view.View
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import java.util.concurrent.ConcurrentHashMap

class StreakEmojiRegistry {
    private val elements = ConcurrentHashMap.newKeySet<StreakEmoji.EjectData>(128)

    fun add(data: StreakEmoji.EjectData) = elements.add(data)

    fun restoreAll() {
        elements.forEach { it.restore() }
        elements.clear()
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

    fun refreshAll() {
        val it = elements.iterator()

        while (it.hasNext()) {
            val streakEmoji = it.next().drawable.get() ?: run {
                it.remove()
                continue
            }

            streakEmoji.refresh()
        }

        refreshDialogCells()
    }

    fun refreshDialogCells() {
        val launchActivity = LaunchActivity.instance
        val viewPagesField = getField(DialogsActivity::class.java, "viewPages")
        val dialogsActivities = mutableSetOf<DialogsActivity>()

        fun populateSet(layout: INavigationLayout) {
            val stack = layout.fragmentStack

            for (i in 0..<stack.size) {
                val fragment = stack[i] ?: continue

                if (fragment is DialogsActivity)
                    dialogsActivities.add(fragment)
                else if (fragment.javaClass.name == "org.telegram.ui.MainTabsActivity") {
                    (fragment.javaClass.getDeclaredMethod("getDialogsActivity")
                        .invoke(fragment) as? DialogsActivity)
                        ?.let(dialogsActivities::add)
                }
            }
        }

        launchActivity.actionBarLayout?.let(::populateSet)
        launchActivity.rightActionBarLayout?.let(::populateSet)
        launchActivity.layersActionBarLayout?.let(::populateSet)

        @Suppress("UNCHECKED_CAST")
        val viewPages = dialogsActivities
            .mapNotNull { viewPagesField.get(it) as? Array<View?> }
            .flatMap { it.toSet() }

        for (page in viewPages) {
            val listView = (page as? DialogsActivity.ViewPage)?.listView ?: continue
            val adapter = listView.adapter
            listView.adapter = null
            listView.adapter = adapter
        }
    }
}
