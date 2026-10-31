package ru.n08i40k.streaks.ui.upgrade

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.dp

internal class StreakUpgradeDialog(context: Context, upgrade: StreakUpgrade) : Dialog(context) {
    private val palette = StreakUpgradePalette.fromTheme()
    private val root = FrameLayout(context).apply { setBackgroundColor(palette.scrim) }
    private val scene = StreakUpgradeSceneView(context, upgrade, palette)
    private val hint = createUpgradeHint(context, palette, Strings.upgrade_scene_dismiss_hint())

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))
        root.addView(hint, upgradeHintLayoutParams())
        scene.onFinished = { allowDismiss(UPGRADE_HINT_DELAY_MS) }
        scene.onFailed = { allowDismiss(0L) }
        setContentView(root)
        setCancelable(false)
        setUpFullscreenOverlay()
    }

    private fun allowDismiss(hintDelayMs: Long) {
        setCancelable(true)
        root.setOnClickListener { dismiss() }
        revealUpgradeHint(hint, hintDelayMs)
    }

    override fun dismiss() {
        hint.animate().cancel()
        scene.release()
        super.dismiss()
    }
}

internal const val UPGRADE_HINT_DELAY_MS = 600L

internal fun createUpgradeHint(context: Context, palette: StreakUpgradePalette, text: String = "") =
    TextView(context).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(palette.secondaryText)
        alpha = 0f
        visibility = View.INVISIBLE
    }

internal fun upgradeHintLayoutParams() =
    FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(32) }

internal fun revealUpgradeHint(hint: View, delayMs: Long) {
    hint.animate().cancel()
    hint.visibility = View.VISIBLE
    hint.animate().alpha(1f).setStartDelay(delayMs).setDuration(300).start()
}

internal fun Dialog.setUpFullscreenOverlay() {
    window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        decorView.setPadding(0, 0, 0, 0)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}
