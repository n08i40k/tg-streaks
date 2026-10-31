package ru.n08i40k.guides.util

import android.view.View
import org.telegram.messenger.AndroidUtilities

internal fun measureGuideViewport(screen: View, width: Int, height: Int) {
    val virtualWidth = maxOf(width, AndroidUtilities.displaySize.x, 1)
    val scale = if (width > 0) width.toFloat() / virtualWidth else 1f
    screen.measure(
        View.MeasureSpec.makeMeasureSpec(virtualWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec((height / scale).toInt(), View.MeasureSpec.EXACTLY),
    )
    screen.pivotX = 0f
    screen.pivotY = 0f
    screen.scaleX = scale
    screen.scaleY = scale
}
