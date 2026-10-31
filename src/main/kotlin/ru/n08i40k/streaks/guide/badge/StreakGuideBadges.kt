package ru.n08i40k.streaks.guide.badge

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.view.View
import android.view.ViewTreeObserver
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import ru.n08i40k.badges.api.BadgesSdk
import ru.n08i40k.badges.api.ViewFactory
import ru.n08i40k.guides.api.GuideBadgeSession
import ru.n08i40k.guides.api.GuideBadge
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.emoji.StreakEmoji
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal class StreakGuideBadges(private val root: View, private val account: Int) : GuideBadgeSession {
    companion object {
        private val sessions = mutableSetOf<StreakGuideBadges>()

        fun contains(view: View): Boolean =
            sessions.any { it.owns(view) }

        fun point(view: View): PointF {
            val session = checkNotNull(sessions.firstOrNull { it.owns(view) }) { "The view is outside guide previews" }
            val bounds = checkNotNull(session.sdk.getBadgeBounds(session.factory, view)) { "Guide badge is not drawn on this view" }

            return PointF(bounds.left + bounds.height() / 2f, bounds.centerY())
        }
    }

    private val sdk = checkNotNull(Plugin.getInstance().badgesSdk) { "Badge SDK is unavailable" }
    private var state = emptyMap<Long, GuideBadge?>()
    private var closed = false
    private val views = WeakHashMap<BadgeView, Boolean>()
    private val factory = object : ViewFactory {
        override fun getPluginId() =
            Plugin.ID

        override fun create(params: ViewFactory.CreateParams): View =
            BadgeView(params.parent, params.heightInPx).also { views[it] = owns(params.parent) }

        override fun bind(params: ViewFactory.BindParams): Boolean =
            (params.view as BadgeView).bind(params.userId)

        override fun destroy(view: View) {
            (view as BadgeView).destroy()
            views.remove(view)
        }
    }

    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        var changed = false
        for (view in views.keys.toList()) {
            val inside = view.owner.get()?.let(::owns) == true

            if (views.put(view, inside) != inside)
                changed = true
        }
        if (changed && !closed) {
            Plugin.getInstance().rebindStreakBadges()
            refresh()
        }
        true
    }

    init {
        sessions.add(this)
        root.viewTreeObserver.addOnPreDrawListener(beforeDraw)
        try {
            sdk.installViewFactory(factory)
        } catch (error: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== error)
                    error.addSuppressed(cleanup)
            }

            throw error
        }
    }

    private fun owns(view: View): Boolean =
        !closed && generateSequence(view) { it.parent as? View }.any { it === root }

    override fun update(badges: Map<Long, GuideBadge?>) {
        check(!closed)
        state = badges.toMap()
        refresh()
    }

    private fun refresh() =
        sdk.rebindViews(BadgesSdk.RebindViewsParams.builder(factory).build())

    override fun close() {
        if (closed)
            return

        closed = true
        sessions.remove(this)

        if (root.viewTreeObserver.isAlive)
            root.viewTreeObserver.removeOnPreDrawListener(beforeDraw)

        state = emptyMap()

        try {
            sdk.uninstallViewFactory(factory)
        } finally {
            views.clear()
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class BadgeView(parent: View, private val size: Int) : View(parent.context) {
        val owner = WeakReference(parent)
        private var emoji: StreakEmoji? = null
        private var boundData: StreakViewData? = null

        fun bind(userId: Long): Boolean {
            val parent = owner.get() ?: return false
            val badge = (state[userId] as StreakGuideBadge?)?.takeIf {
                owns(parent) && account == UserConfig.selectedAccount
            }
            if (badge == null) {
                if (boundData != null)
                    emoji?.setPreviewData(null)

                boundData = null

                return false
            }
            val level =
                if (badge.cold) StreakLevel.Cold
                else StreakLevel.findByLengthApproximate(badge.length)
            val config = Plugin.getInstance().streakEmojiPacksController.getCurrent().getEmoji(level)
            val drawable = emoji ?: StreakEmoji(parent, size).also {
                emoji = it
                it.attach()
            }
            val data = StreakViewData(
                badge.length,
                config.documentId,
                config.accentColor ?: Color.valueOf(Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon)),
                false
            )

            if (boundData != data) {
                val previousWidth = drawable.getTotalWidth()

                drawable.setPreviewData(data)
                boundData = data

                if (previousWidth != drawable.getTotalWidth()) {
                    requestLayout()
                    parent.requestLayout()
                }
            }

            return true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(
                resolveSize(emoji?.getTotalWidth() ?: 0, widthMeasureSpec),
                resolveSize(size, heightMeasureSpec)
            )

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val y = (height - size) / 2

            emoji?.setBounds(0, y, size, y + size)
        }

        override fun onDraw(canvas: Canvas) {
            emoji?.draw(canvas)
        }

        fun destroy() {
            emoji?.detach()
            emoji = null
        }
    }
}
