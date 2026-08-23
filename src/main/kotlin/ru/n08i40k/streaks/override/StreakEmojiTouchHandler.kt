package ru.n08i40k.streaks.override

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.impl.PremiumPreviewBottomSheetHookBundle
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getLastFragment
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

class StreakEmojiTouchHandler private constructor(
    private val previous: View.OnTouchListener?,
) : View.OnTouchListener {
    companion object {
        // SimpleTextView
        private val RIGHT_DRAWABLE_ON_CLICK_LISTENER =
            getField(SimpleTextView::class.java, "rightDrawableOnClickListener")

        @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
        private val PREVIOUS_LISTENER: (View) -> View.OnTouchListener? = run {
            val listenerInfo = try {
                View::class.java.getDeclaredField("mListenerInfo")
                    .apply { isAccessible = true }
            } catch (_: Throwable) {
                Logger.info("View.mListenerInfo is inaccessible")
                return@run { null }
            }

            val onTouchListener = try {
                Class.forName($$"android.view.View$ListenerInfo")
                    .getDeclaredField("mOnTouchListener")
                    .apply { isAccessible = true }
            } catch (_: Throwable) {
                Logger.info("ListenerInfo.mOnTouchListener is inaccessible")
                return@run { null }
            }

            return@run { view ->
                try {
                    listenerInfo.get(view)?.let(onTouchListener::get) as? View.OnTouchListener
                } catch (_: Throwable) {
                    null
                }
            }
        }

        fun install(view: View): StreakEmojiTouchHandler {
            val previous = PREVIOUS_LISTENER(view)

            if (previous is StreakEmojiTouchHandler)
                return previous

            return StreakEmojiTouchHandler(previous)
                .also(view::setOnTouchListener)
        }
    }

    private val drawables = CopyOnWriteArrayList<WeakReference<StreakEmoji>>()

    private var pressedDrawable: StreakEmoji? = null
    private var pressedPart: StreakEmoji.Part? = null

    fun register(drawable: StreakEmoji) {
        val it = drawables.iterator()

        while (it.hasNext()) {
            val known = it.next().get()

            if (known === drawable)
                return
        }

        drawables.removeIf { it.get() == null }
        drawables.add(WeakReference(drawable))
    }

    fun restore(view: View) {
        drawables.clear()
        view.setOnTouchListener(previous)
    }

    private fun hitTest(x: Int, y: Int): Pair<StreakEmoji, StreakEmoji.Part>? {
        val it = drawables.iterator()

        while (it.hasNext()) {
            val drawable = it.next().get() ?: continue
            val part = drawable.hitTest(x, y) ?: continue

            return drawable to part
        }

        return null
    }

    private fun getOriginalClickListener(view: View): View.OnClickListener? {
        if (view !is SimpleTextView)
            return null

        return RIGHT_DRAWABLE_ON_CLICK_LISTENER.getAs<View.OnClickListener>(view)
    }

    private fun reset() {
        pressedDrawable = null
        pressedPart = null
    }

    private fun showStreakInfo(drawable: StreakEmoji) {
        val accountId = UserConfig.selectedAccount
        val peerUserId = drawable.getPeerUserId()

        if (Plugin.getInstance().streaksController.getViewData(accountId, peerUserId) == null)
            return

        val user = MessagesController.getInstance(accountId).getUser(peerUserId) ?: return
        val fragment = getLastFragment() ?: return

        fragment.showDialog(
            PremiumPreviewBottomSheet(
                fragment,
                accountId,
                user,
                fragment.resourceProvider
            )
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()

                val (drawable, part) = hitTest(x, y)
                    ?: return previous?.onTouch(view, event) ?: false

                if (part == StreakEmoji.Part.BADGE)
                    return previous?.onTouch(view, event) ?: false

                if (part == StreakEmoji.Part.ORIGINAL && getOriginalClickListener(view) == null)
                    return previous?.onTouch(view, event) ?: false

                pressedDrawable = drawable
                pressedPart = part

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val drawable = pressedDrawable
                    ?: return previous?.onTouch(view, event) ?: false

                if (drawable.hitTest(x, y) != pressedPart)
                    reset()

                return true
            }

            MotionEvent.ACTION_UP -> {
                val drawable = pressedDrawable
                val part = pressedPart

                if (drawable == null || part == null)
                    return previous?.onTouch(view, event) ?: false

                reset()

                Logger.tryOrFatal("handle streak emoji click") {
                    when (part) {
                        StreakEmoji.Part.STREAK -> showStreakInfo(drawable)
                        StreakEmoji.Part.ORIGINAL -> {
                            val listener = getOriginalClickListener(view)

                            if (listener != null)
                                PremiumPreviewBottomSheetHookBundle.bypass {
                                    listener.onClick(view)
                                }
                        }
                        StreakEmoji.Part.BADGE -> Unit
                    }
                }

                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (pressedDrawable == null)
                    return previous?.onTouch(view, event) ?: false

                reset()

                return true
            }
        }

        return previous?.onTouch(view, event) ?: false
    }
}
