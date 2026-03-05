@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package ru.n08i40k.streaks.overrides

import android.graphics.Canvas
import android.graphics.Color
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import org.telegram.ui.Stars.StarsReactionsSheet.Particles
import ru.n08i40k.streaks.CloneFieldDirection
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.cloneFields
import ru.n08i40k.streaks.setFieldValue

class StreakParticles : Particles {
    companion object {
        fun restore(
            thisObject: StreakParticles,
            parentObject: StreakAnimatedEmojiDrawable
        ) {
            val pseudoOriginal = Particles(0, 0)

            cloneFields(
                thisObject as Object,
                pseudoOriginal as Object,
                CloneFieldDirection.FROM_DESTINATION
            )

            setFieldValue(
                SwapAnimatedEmojiDrawable::class.java,
                parentObject,
                "particles",
                pseudoOriginal
            )
        }
    }

    private var _userId: Long
    private var _cachedStreakData: Pair<Int, Color>?

    var userId: Long
        get() = _userId
        set(value) {
            _userId = value
            _cachedStreakData =
                Plugin.getInstance()!!.resolveStreakData(value)?.let { Pair(it.first, it.second) }
        }

    constructor(base: Particles, userId: Long) : super(0, 0) {
        cloneFields(
            base as Object,
            this as Object,
            CloneFieldDirection.FROM_SOURCE
        )

        this._userId = userId
        this._cachedStreakData =
            Plugin.getInstance()!!.resolveStreakData(userId)?.let { Pair(it.first, it.second) }
    }

    override fun draw(canvas: Canvas, color: Int, p2: Float) {
        super.draw(canvas, this._cachedStreakData?.second?.toArgb() ?: color, p2)
    }
}
