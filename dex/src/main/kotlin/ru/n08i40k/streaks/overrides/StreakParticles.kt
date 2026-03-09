@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package ru.n08i40k.streaks.overrides

import android.graphics.Canvas
import org.telegram.ui.Stars.StarsReactionsSheet.Particles
import ru.n08i40k.streaks.cloneFields

class StreakParticles : Particles {
    private val color: Int

    constructor(base: Particles, color: Int) : super(0, 0) {
        cloneFields(
            base as Object,
            this as Object,
            Particles::class.java
        )

        this.color = color
    }

    override fun draw(canvas: Canvas, color: Int, p2: Float) =
        super.draw(canvas, this.color, p2)
}
