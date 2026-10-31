package ru.n08i40k.guides.scene

import android.view.View
import ru.n08i40k.guides.model.GuideAction
import ru.n08i40k.guides.util.animateGuide
import org.telegram.ui.Components.CubicBezierInterpolator

interface GuideScene {
    val layer: GuideLayer
        get() = GuideLayer.PAGE

    fun onShow() = Unit

    fun updateTheme()

    suspend fun perform(action: GuideAction)

    suspend fun transition(opening: Boolean) {
        val view = getView()

        animateGuide(240, CubicBezierInterpolator.EASE_OUT_QUINT) { progress ->
            view.alpha = if (opening) progress else 1f - progress
        }
    }

    fun dispose()

    fun getView(): View
}
