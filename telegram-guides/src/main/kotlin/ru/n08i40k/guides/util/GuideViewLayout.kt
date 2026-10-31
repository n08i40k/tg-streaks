package ru.n08i40k.guides.util

import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun awaitGuideLayout(view: View) =
    suspendCancellableCoroutine { continuation ->
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive)
                    observer.removeOnPreDrawListener(this)

                if (continuation.isActive)
                    continuation.resume(Unit)

                return true
            }
        }

        observer.addOnPreDrawListener(listener)
        view.requestLayout()
        view.invalidate()

        continuation.invokeOnCancellation {
            if (observer.isAlive)
                observer.removeOnPreDrawListener(listener)
        }
    }
