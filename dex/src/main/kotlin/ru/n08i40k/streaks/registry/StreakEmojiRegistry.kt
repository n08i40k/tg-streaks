@file:Suppress("MISSING_DEPENDENCY_SUPERCLASS", "MISSING_DEPENDENCY_SUPERCLASS_WARNING")

package ru.n08i40k.streaks.registry

import android.view.View
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import java.util.concurrent.ConcurrentHashMap

class StreakEmojiRegistry {
    private val elements = ConcurrentHashMap.newKeySet<StreakEmoji.EjectData>(128)

    fun add(data: StreakEmoji.EjectData) = elements.add(data)

    fun restoreByPeerUserId(peerUserId: Long) {
        val it = elements.iterator()

        while (it.hasNext()) {
            val el = it.next()

            val currentPeerUserId = el.drawable.get()?.getPeerUserId() ?: run {
                it.remove()
                continue
            }

            if (currentPeerUserId == peerUserId) {
                it.remove()
                el.restore()
            }
        }
    }

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

        val dialogsActivities = mutableSetOf<DialogsActivity>()

        fun populateSet(layout: INavigationLayout) {
            val stack = layout.fragmentStack

            for (i in 0..<stack.size) {
                dialogsActivities.add(stack[i] as? DialogsActivity ?: continue)
            }
        }

        launchActivity.actionBarLayout?.let { populateSet(it) }
        launchActivity.rightActionBarLayout?.let { populateSet(it) }
        launchActivity.layersActionBarLayout?.let { populateSet(it) }

        val viewPagesField = getField(DialogsActivity::class.java, "viewPages")
        val currentDialogIdField = getField(DialogCell::class.java, "currentDialogId")
        val emojiStatusField = getField(DialogCell::class.java, "emojiStatus")
        val emojiStatusViewField = getField(DialogCell::class.java, "emojiStatusView")

        @Suppress("UNCHECKED_CAST")
        val viewPages = dialogsActivities
            .mapNotNull { viewPagesField.get(it) as? Array<View?> }
            .flatMap { it.toSet() }

        var totalDialogCells = 0
        var refreshedDialogCells = 0

        for (page in viewPages) {
            val page = page as? DialogsActivity.ViewPage ?: continue
            val listView = page.listView

            for (i in 0..<listView.childCount) {
                val child = listView.getChildAt(i) as? DialogCell ?: continue
                totalDialogCells++
                val currentDialogId = currentDialogIdField.getLong(child)

                if (currentDialogId == 0L) {
                    continue
                }

                StreakEmoji.encapsulate(
                    obj = child,
                    field = emojiStatusField,
                    arrayIndex = null,
                    peerUserId = currentDialogId,
                )
                refreshedDialogCells++

                (emojiStatusViewField.get(child) as? View)?.invalidate()
                child.requestLayout()
                child.invalidate()
            }

            listView.invalidate()
        }
    }
}
