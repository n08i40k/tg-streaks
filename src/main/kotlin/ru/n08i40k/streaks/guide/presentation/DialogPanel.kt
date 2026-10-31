package ru.n08i40k.streaks.guide.presentation

import android.content.Context
import android.content.DialogInterface
import android.view.ViewGroup
import org.telegram.ui.ActionBar.AlertDialog
import ru.n08i40k.guides.scene.GuideLayer
import ru.n08i40k.guides.scene.GuideOverlayScene
import ru.n08i40k.guides.scene.GuideScene

internal fun dialogPanel(context: Context, create: (Context) -> AlertDialog): GuideScene {
    lateinit var dialog: AlertDialog

    return GuideOverlayScene(
        context,
        GuideLayer.DIALOG,
        content = {
            dialog = create(context)
            dialog.create()
            dialog.containerView.also { (it.parent as? ViewGroup)?.removeView(it) }
        },
        target = { target ->
            check(target == StreakGuideTargets.CONFIRM_ACCEPT) { "Unknown dialog target: $target" }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        },
        cleanup = { dialog.dismiss() },
    )
}
