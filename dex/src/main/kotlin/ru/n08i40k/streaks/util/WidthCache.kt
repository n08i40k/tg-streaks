@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
)

package ru.n08i40k.streaks.util

import org.telegram.ui.Cells.ChatMessageCell

class WidthCache : LinkedHashMap<Int, Pair<Int, Int>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<Int, Pair<Int, Int>>): Boolean {
        return size > 32
    }

    fun changeIfNeeded(cell: ChatMessageCell, additionalWidth: Int) {
        val hash = System.identityHashCode(cell)

        val (prevWidth, prevAdditionalWidth) = this[hash] ?: Pair(0, 0)

        if (prevWidth != cell.backgroundWidth || prevAdditionalWidth != additionalWidth) {
            val prevAdditionalWidth =
                if (prevWidth == cell.backgroundWidth)
                    prevAdditionalWidth
                else
                    0

            cell.backgroundWidth += additionalWidth - prevAdditionalWidth
            this[hash] = Pair(cell.backgroundWidth, additionalWidth)
        }
    }
}