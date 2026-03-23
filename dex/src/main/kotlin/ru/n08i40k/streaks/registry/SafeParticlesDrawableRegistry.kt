@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN"
)

package ru.n08i40k.streaks.registry

import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import ru.n08i40k.streaks.override.SafeParticlesDrawable
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getField
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

class SafeParticlesDrawableRegistry {
    data class EjectData(
        val drawable: WeakReference<SafeParticlesDrawable>,
        val targetObject: WeakReference<Any>,
        val targetField: Field,
        val nameTextView: WeakReference<SimpleTextView>,
    ) {
        fun restore() {
            val drawable = this.drawable.get() ?: return
            val targetObject = this.targetObject.get() ?: return
            val nameTextView = this.nameTextView.get()

            val pseudoOriginal = SwapAnimatedEmojiDrawable(null, 0)
            cloneFields(
                drawable as Object,
                pseudoOriginal as Object,
                SwapAnimatedEmojiDrawable::class.java
            )

            targetField.set(targetObject, pseudoOriginal)

            if (nameTextView != null) {
                val rightDrawableField = getField(SimpleTextView::class.java, "rightDrawable")
                val rightDrawable2Field = getField(SimpleTextView::class.java, "rightDrawable2")

                if (rightDrawableField.get(nameTextView) === drawable)
                    nameTextView.rightDrawable = pseudoOriginal

                if (rightDrawable2Field.get(nameTextView) === drawable)
                    nameTextView.rightDrawable2 = pseudoOriginal
            }
        }
    }

    private val elements = ConcurrentHashMap.newKeySet<EjectData>(64)

    fun add(data: EjectData) = elements.add(data)

    fun restoreAll() {
        elements.forEach { it.restore() }
        elements.clear()
    }
}
