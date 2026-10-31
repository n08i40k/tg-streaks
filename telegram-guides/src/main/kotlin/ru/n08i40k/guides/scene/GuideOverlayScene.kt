package ru.n08i40k.guides.scene

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Context
import android.view.Gravity
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.CubicBezierInterpolator
import ru.n08i40k.guides.api.GuideExtension
import ru.n08i40k.guides.model.GuideAction
import ru.n08i40k.guides.util.animateGuide

open class GuideOverlayScene(
    context: Context,
    override val layer: GuideLayer,
    private val content: () -> View,
    private val target: suspend (String) -> View = { error("No tap targets in this overlay: $it") },
    private val command: suspend (GuideExtension) -> Unit = { error("Unsupported command: $it") },
    private val refreshTheme: () -> Unit = {},
    private val cleanup: () -> Unit = {},
    private val contentHeight: Int = LayoutParams.WRAP_CONTENT,
    private val scrimColor: Int = if (layer == GuideLayer.SHEET) SHEET_SCRIM else DIALOG_SCRIM,
    private val sheetHeight: (content: View) -> Int = View::getHeight,
) : GuideViewportScene(context) {
    private val scrim = ColorDrawable(scrimColor)
    private var contentView: View? = null

    init {
        require(layer != GuideLayer.PAGE) { "Use GuideFragmentScene for pages" }
        background = scrim
    }

    override fun onFirstShow() {
        val view = content()

        contentView = view
        addContent(
            view,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                contentHeight,
                if (layer == GuideLayer.SHEET) Gravity.BOTTOM else Gravity.CENTER
            )
        )
    }

    override suspend fun perform(action: GuideAction) {
        when (action) {
            is GuideAction.TapTarget -> tap(target(action.target), press = true)

            is GuideAction.Extension -> command(action.command)

            else -> error("Unsupported overlay action: $action")
        }
    }

    override suspend fun transition(opening: Boolean) {
        val view = checkNotNull(contentView)
        val scrimAlpha = Color.alpha(scrimColor)

        alpha = 1f

        if (layer == GuideLayer.SHEET) {
            val hidden = sheetHeight(view) + AndroidUtilities.dp(10f).toFloat()

            animateGuide(
                if (opening) 400 else 250,
                if (opening) CubicBezierInterpolator.EASE_OUT_QUINT else CubicBezierInterpolator.EASE_OUT
            ) { progress ->
                val shown = if (opening) progress else 1f - progress

                view.translationY = hidden * (1f - shown)
                scrim.alpha = (scrimAlpha * shown).toInt()
            }
        } else {
            animateGuide(
                if (opening) 220 else 150,
                if (opening) CubicBezierInterpolator.EASE_OUT_QUINT else CubicBezierInterpolator.EASE_OUT
            ) { progress ->
                val shown = if (opening) progress else 1f - progress

                view.alpha = shown
                view.scaleX = .9f + .1f * shown
                view.scaleY = view.scaleX
                scrim.alpha = (scrimAlpha * shown).toInt()
            }
        }
    }

    override fun updateTheme() {
        refreshTheme()
        screen.invalidate()
    }

    override fun onDispose() =
        cleanup()

    private companion object {
        const val SHEET_SCRIM = 0x33000000

        const val DIALOG_SCRIM = 0x99000000.toInt()
    }
}
