@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package ru.n08i40k.streaks.overrides

import android.graphics.Canvas
import org.telegram.ui.Stars.StarsReactionsSheet.Particles
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.cloneFields
import ru.n08i40k.streaks.data.StreakData

class StreakParticles : Particles {
    private val userId: Long
    private val cachedStreakData: StreakData?

    constructor(base: Particles, userId: Long) : super(0, 0) {
        cloneFields(
            base as Object,
            this as Object,
            Particles::class.java
        )

        this.userId = userId
        this.cachedStreakData = Plugin.getInstance()!!.resolveStreakData(userId)
    }

    override fun draw(canvas: Canvas, color: Int, p2: Float) {
        super.draw(canvas, this.cachedStreakData?.textColor?.toArgb() ?: color, p2)
    }
}
