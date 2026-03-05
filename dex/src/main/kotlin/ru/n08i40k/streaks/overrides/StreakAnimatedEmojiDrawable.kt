@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.overrides

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import org.telegram.ui.Stars.StarsReactionsSheet
import ru.n08i40k.streaks.CloneFieldDirection
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.cloneFields
import ru.n08i40k.streaks.getField
import ru.n08i40k.streaks.getFieldValue
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

            StreakParticles.restore(
                getFieldValue<StreakParticles>(
                    SwapAnimatedEmojiDrawable::class.java,
                    drawable,
                    "particles"
                )!!,
                drawable
            )

            val targetObject = this.targetObject.get() ?: return

            val pseudoOriginal = SwapAnimatedEmojiDrawable(null, 0)

            cloneFields(
                drawable as Object,
                pseudoOriginal as Object,
                CloneFieldDirection.FROM_DESTINATION
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
                    drawable.userId = userId
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
                drawable.userId = userId
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

    private var _userId: Long
    private var _cachedStreakData: Pair<Int, Color>?

    private var _particles: StreakParticles

    var userId: Long
        get() = _userId
        set(value) {
            _userId = value
            _cachedStreakData =
                Plugin.getInstance()!!.resolveStreakData(value)?.let { Pair(it.first, it.second) }

            val isExists = _cachedStreakData != null
            super.setParticles(isExists, isExists)

            _particles.userId = value
        }


    constructor(base: SwapAnimatedEmojiDrawable, userId: Long) : super(null, 0) {
        cloneFields(base as Object, this as Object)
        super.setParticles(true, true)

        val particlesField = getField(SwapAnimatedEmojiDrawable::class.java, "particles")

        this._userId = userId
        this._particles =
            StreakParticles(particlesField.get(this)!! as StarsReactionsSheet.Particles, userId)

        this._cachedStreakData =
            Plugin.getInstance()!!.resolveStreakData(userId)?.let { Pair(it.first, it.second) }

        particlesField.set(this, this._particles)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        _cachedStreakData?.let {
            paint.setColor(it.second.toArgb())

            canvas.drawText(
                it.first.toString(),
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

    override fun setParticles(p0: Boolean, p1: Boolean) {}
}
