package ru.n08i40k.streaks.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

class AlphaAwareItemAnimator : DefaultItemAnimator() {
    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        fromLeft: Int,
        fromTop: Int,
        toLeft: Int,
        toTop: Int,
    ): Boolean {
        if (oldHolder !== newHolder)
            return super.animateChange(oldHolder, newHolder, fromLeft, fromTop, toLeft, toTop)

        val view = newHolder.itemView
        val targetAlpha = view.alpha

        view.animate().cancel()
        view.alpha = 0f
        view.animate()
            .alpha(targetAlpha)
            .setDuration(changeDuration)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    dispatchChangeStarting(newHolder, false)
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    view.alpha = targetAlpha
                    dispatchChangeFinished(newHolder, false)
                }
            })
            .start()

        return true
    }
}
