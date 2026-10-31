package ru.n08i40k.streaks.ui.upgrade

import kotlin.math.PI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

internal class Span(val start: Float, val duration: Float) {
    val end get() = start + duration

    fun at(time: Float) = ((time - start) / duration).coerceIn(0f, 1f)

    fun isActive(time: Float) = time > start && time < end
}

internal object UpgradeTimeline {
    const val SWAP = 1.75f
    const val DURATION = SWAP + 3.25f

    val PLAYBACK: Duration = DURATION.toDouble().seconds

    val INTRO = Span(0f, .4f)
    val AVATARS_IN = Span(.05f, .5f)
    val OLD_TEXT_IN = Span(.1f, .3f)
    val PARTICLES_CHARGE = Span(.45f, SWAP - .45f)
    val OLD_CHARGE = Span(.55f, SWAP - .55f)
    val COUNTER = Span(.55f, SWAP - .55f)
    val OLD_TEXT_OUT = Span(SWAP - .5f, .4f)
    val COLOR_SHIFT = Span(SWAP - .3f, .6f)
    val OLD_OUT = Span(SWAP - .1f, .18f)

    const val FLASH_HALF_WIDTH = .16f
    val SHAKE = Span(SWAP, .6f)
    val NUMBER_RESTYLE = Span(SWAP - .05f, .3f)

    val NEW_IN = Span(SWAP, .15f)
    val NEW_SPRING = Span(SWAP, .85f)
    val BURST = Span(SWAP, 1.1f)
    val ECHO_RING = Span(SWAP + .12f, 1f)
    val AVATARS_BOUNCE = Span(SWAP, .6f)
    val AVATARS_WAVE = Span(SWAP + .05f, .7f)
    val NEW_TEXT_IN = Span(SWAP + .03f, .2f)
    val NEW_TEXT_ARRIVAL = Span(SWAP + .03f, .65f)
    val TITLE_LETTER = Span(SWAP + .05f, .4f)
    const val TITLE_LETTER_STAGGER = .025f
    val TITLE_SPREAD = Span(SWAP + .1f, .65f)
    val BAR_GROWTH = Span(SWAP + .1f, .95f)
    val SHEEN = Span(SWAP + .45f, .8f)
    val FLOAT_IN = Span(SWAP + .6f, .8f)
    val BAR_PULSE = Span(SWAP + 1.05f, .7f)

    fun isUpgraded(time: Float) = time >= SWAP
}

internal object Easing {
    fun smooth(x: Float) = x * x * (3 - 2 * x)

    fun outQuad(x: Float) = 1 - (1 - x) * (1 - x)

    fun hump(x: Float) = sin(x * PI).toFloat()

    fun noise(x: Float) = (sin(x) + .6f * sin(x * 1.73f + 1.1f) + .3f * sin(x * 2.91f + 2.3f)) / 1.9f

    fun spring(x: Float, damping: Float, frequency: Float) =
        if (x <= 0f) 0f else 1 - exp(-damping * x) * cos(frequency * x)
}
