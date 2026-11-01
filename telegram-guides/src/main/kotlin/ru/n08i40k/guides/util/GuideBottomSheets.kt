package ru.n08i40k.guides.util

import android.view.View
import android.view.ViewGroup
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet

fun BottomSheet.takeEmbeddedContent(): View =
    createDetached().getContainerView().detachFromParent()

fun PremiumPreviewBottomSheet.takeEmbeddedContent(): View =
    createDetached().container.apply { visibility = View.VISIBLE }.detachFromParent()

private fun <T : BottomSheet> T.createDetached(): T = apply {
    create()

    invokeCancelSheetAnimation()
    getContainerView().apply {
        visibility = View.VISIBLE
        alpha = 1f
        translationY = 0f
    }
}

private fun View.detachFromParent(): View = apply {
    (parent as? ViewGroup)?.removeView(this)
}
