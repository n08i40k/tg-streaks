@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.overrides

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import org.telegram.ui.Stars.StarsReactionsSheet
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.cloneFields
import ru.n08i40k.streaks.data.StreakData
import ru.n08i40k.streaks.getField
import java.lang.ref.WeakReference
import java.lang.reflect.Field

class StreakAnimatedEmojiDrawable : SwapAnimatedEmojiDrawable {
    data class EjectData(
        val drawable: WeakReference<StreakAnimatedEmojiDrawable>,
        val targetObject: WeakReference<Any>,
        val targetField: Field,
        val arrayIndex: Int?,
    ) {
        fun restore() {
            val drawable = this.drawable.get() ?: return

            drawable.setUserId(0)
            drawable.setParticles(false, false)

            val targetObject = this.targetObject.get() ?: return

            val pseudoOriginal = SwapAnimatedEmojiDrawable(null, 0)

            cloneFields(
                drawable as Object,
                pseudoOriginal as Object,
                SwapAnimatedEmojiDrawable::class.java
            )

            if (arrayIndex == null) {
                targetField.set(targetObject, pseudoOriginal)
                return
            }

            @Suppress("UNCHECKED_CAST")
            val array = targetField.get(targetObject)!! as Array<SwapAnimatedEmojiDrawable>

            array[arrayIndex] = pseudoOriginal
        }
    }

    companion object {
        fun encapsulate(
            obj: Any,
            field: Field,
            arrayIndex: Int?,
            userId: Long
        ) {
            if (arrayIndex == null) {
                val drawable = field.get(obj) as? SwapAnimatedEmojiDrawable
                    ?: throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable")

                if (drawable as? StreakAnimatedEmojiDrawable != null) {
                    drawable.setUserId(userId)
                    return
                }

                val newDrawable = StreakAnimatedEmojiDrawable(drawable, userId)

                field.set(obj, newDrawable)
                newDrawable.detach()

                Plugin.getInstance()!!.addStreakDrawableEjectData(
                    EjectData(
                        WeakReference(newDrawable),
                        WeakReference(obj),
                        field,
                        arrayIndex
                    )
                )
                return
            }

            val unknownArray = field.get(obj) ?: throw NullPointerException("Field is null")

            if (!unknownArray::class.java.isArray)
                throw TypeCastException("Field value type isn't array")

            if (unknownArray::class.java.componentType != SwapAnimatedEmojiDrawable::class.java)
                throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable[]")

            @Suppress("UNCHECKED_CAST")
            val array = unknownArray as Array<SwapAnimatedEmojiDrawable>

            if (array.size <= arrayIndex)
                throw IndexOutOfBoundsException("SwapAnimatedEmojiDrawable[] size is below $arrayIndex")

            val drawable = array[arrayIndex]

            if (drawable as? StreakAnimatedEmojiDrawable != null) {
                drawable.setUserId(userId)
                return
            }

            val newDrawable = StreakAnimatedEmojiDrawable(drawable, userId)
            array[arrayIndex] = newDrawable

            Plugin.getInstance()!!.addStreakDrawableEjectData(
                EjectData(
                    WeakReference(newDrawable),
                    WeakReference(obj),
                    field,
                    arrayIndex
                )
            )
        }

        private val paint by lazy {
            val paint = Paint()
            paint.textSize = 22f
            paint
        }
    }

    private var userId: Long
    private var cachedStreakData: StreakData?

    fun setUserId(userId: Long) {
        this.userId = userId
        this.cachedStreakData = Plugin.getInstance()!!.resolveStreakData(userId)

        val isExists = cachedStreakData != null
        super.setParticles(isExists, isExists)

        if (isExists) {
            super.set(cachedStreakData!!.documentId, 7, true)

            getField(SwapAnimatedEmojiDrawable::class.java, "particles").let { field ->
                val parent = field.get(this) as? StarsReactionsSheet.Particles
                val child = parent?.let { base -> StreakParticles(base, userId) }

                field.set(this, child)
            }
        }

        this.invalidateSelf()
    }

    fun resetCache() {
        setUserId(userId)
    }

    constructor(base: SwapAnimatedEmojiDrawable, userId: Long) : super(null, 0) {
        cloneFields(base as Object, this as Object, SwapAnimatedEmojiDrawable::class.java)

        this.userId = userId
        this.cachedStreakData = Plugin.getInstance()!!.resolveStreakData(userId)

        this.cachedStreakData?.let {
            super.setParticles(true, true)
            super.set(it.documentId, 7, true)

            getField(SwapAnimatedEmojiDrawable::class.java, "particles").let { field ->
                val parent = field.get(this) as? StarsReactionsSheet.Particles
                val child = parent?.let { base -> StreakParticles(base, userId) }

                field.set(this, child)

            }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        cachedStreakData?.let {
            paint.setColor(it.textColor.toArgb())

            canvas.drawText(
                it.length.toString(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat() - AndroidUtilities.dp(5f),
                paint
            )
        }
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        if ((bottom - top) < (right - left))
            super.setBounds(left, top, left + (right - left) / 3, bottom)
        else
            super.setBounds(left, top, right, bottom)
    }

    override fun setParticles(p0: Boolean, p1: Boolean) {
        if (cachedStreakData != null)
            return

        super.setParticles(p0, p1)
    }

    override fun set(p0: Drawable?, p1: Boolean) {
        if (cachedStreakData != null)
            return

        super.set(p0, p1)
    }

    override fun set(p0: Long, p1: Boolean): Boolean {
        if (cachedStreakData != null)
            return true

        return super.set(p0, p1)
    }

    override fun set(p0: Long, p1: Int, p2: Boolean): Boolean {
        if (cachedStreakData != null)
            return true

        return super.set(p0, p1, p2)
    }

    override fun set(p0: TLRPC.Document?, p1: Boolean) {
        if (cachedStreakData != null)
            return

        super.set(p0, p1)
    }

    override fun set(p0: TLRPC.Document?, p1: Int, p2: Boolean) {
        if (cachedStreakData != null)
            return

        super.set(p0, p1, p2)
    }
}
