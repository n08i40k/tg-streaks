package ru.n08i40k.streaks.override

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.util.BadgesCompat
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.isClientVersionBelow
import java.lang.ref.WeakReference
import java.lang.reflect.Field

class StreakEmoji : SwapAnimatedEmojiDrawable {
    enum class Source {
        DIALOG_CELL,
        MESSAGE_CELL,
        OTHER
    }

    data class EjectData(
        val drawable: WeakReference<StreakEmoji>,
        val targetObject: WeakReference<Any>,
        val targetField: Field,
        val arrayIndex: Int?,
        val nameTextView: WeakReference<SimpleTextView>? = null,
    ) {
        fun restore() {
            val drawable = this.drawable.get() ?: return
            drawable.refresh(true)

            val targetObject = this.targetObject.get() ?: return

            val pseudoOriginal = SwapAnimatedEmojiDrawable(null, 0)

            cloneFields(
                drawable,
                pseudoOriginal,
                SwapAnimatedEmojiDrawable::class.java
            )

            if (arrayIndex == null) {
                targetField.set(targetObject, pseudoOriginal)
                nameTextView?.get()?.let { textView ->
                    val rightDrawableField = getField(SimpleTextView::class.java, "rightDrawable")
                    val rightDrawable2Field = getField(SimpleTextView::class.java, "rightDrawable2")

                    if (rightDrawableField.get(textView) === drawable)
                        textView.rightDrawable = pseudoOriginal

                    if (rightDrawable2Field.get(textView) === drawable)
                        textView.rightDrawable2 = pseudoOriginal
                }
                return
            }

            @Suppress("UNCHECKED_CAST")
            val array = targetField.get(targetObject)!! as Array<SwapAnimatedEmojiDrawable>

            array[arrayIndex] = pseudoOriginal
        }
    }

    companion object {
        fun encapsulate(
            obj: Any,
            field: Field,
            arrayIndex: Int?,
            peerUserId: Long,
            source: Source = Source.OTHER,
            canDrawBadge: Boolean = false,
            simpleTextView: SimpleTextView? = null,
        ): StreakEmoji? {
            if (arrayIndex == null) {
                val drawable = (field.get(obj) ?: return null) as? SwapAnimatedEmojiDrawable
                    ?: throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable")

                if (drawable as? StreakEmoji != null) {
                    drawable.setPeerUserId(peerUserId)
                    return drawable
                }

                val newDrawable = StreakEmoji(
                    drawable,
                    peerUserId,
                    source,
                    canDrawBadge,
                )

                field.set(obj, newDrawable)

                Plugin.getInstance().streakEmojiRegistry.add(
                    EjectData(
                        WeakReference(newDrawable),
                        WeakReference(obj),
                        field,
                        arrayIndex,
                        simpleTextView?.let(::WeakReference)
                    )
                )
                return newDrawable
            }

            val unknownArray = field.get(obj) ?: return null

            if (!unknownArray::class.java.isArray)
                throw TypeCastException("Field value type isn't array")

            if (unknownArray::class.java.componentType != SwapAnimatedEmojiDrawable::class.java)
                throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable[]")

            @Suppress("UNCHECKED_CAST")
            val array = unknownArray as Array<SwapAnimatedEmojiDrawable?>

            if (array.size <= arrayIndex)
                throw IndexOutOfBoundsException("SwapAnimatedEmojiDrawable[] size is below $arrayIndex")

            val drawable = array[arrayIndex] ?: return null

            if (drawable as? StreakEmoji != null) {
                drawable.setPeerUserId(peerUserId)
                return drawable
            }

            val newDrawable = StreakEmoji(
                drawable,
                peerUserId,
                source,
                canDrawBadge,
            )
            array[arrayIndex] = newDrawable

            Plugin.getInstance().streakEmojiRegistry.add(
                EjectData(
                    WeakReference(newDrawable),
                    WeakReference(obj),
                    field,
                    arrayIndex,
                    simpleTextView?.let(::WeakReference)
                )
            )

            return newDrawable
        }

        private val paint by lazy {
            val paint = Paint()
            paint.textSize = 22f
            paint
        }
    }

    private var peerUserId: Long = 0
    private var cachedStreakViewData: StreakViewData? = null

    private val source: Source
    private val canDrawBadge: Boolean

    private var streakView: SwapAnimatedEmojiDrawable? = null

    private var hasBadge = false
    private var badgeView: SwapAnimatedEmojiDrawable? = null

    private val size: Int

    private var hideOriginal: Boolean = false

    private fun clearStreakView() {
        streakView?.detach()
        streakView = null
    }

    private fun clearBadgeView() {
        badgeView?.detach()
        badgeView = null
    }

    private fun replaceStreakView(view: SwapAnimatedEmojiDrawable?) {
        if (streakView === view) {
            syncBounds()
            return
        }

        streakView?.detach()
        streakView = view
        streakView?.attach()
        syncBounds()
    }

    private fun replaceBadgeView(view: SwapAnimatedEmojiDrawable?) {
        if (badgeView === view) {
            syncBounds()
            return
        }

        badgeView?.detach()
        badgeView = view
        badgeView?.attach()
        syncBounds()
    }

    private fun syncBounds() {
        val streakOffset = bounds.left + if (hideOriginal) 0 else size
        val streakSize = size

        val badgeOffset = streakOffset + if (streakView == null) 0 else streakSize + getTextWidth()
        val badgeSize = size

        badgeView?.setBounds(
            badgeOffset,
            bounds.top,
            badgeOffset + badgeSize,
            bounds.bottom
        )

        streakView?.setBounds(
            streakOffset,
            bounds.top,
            streakOffset + streakSize,
            bounds.bottom
        )
    }

    fun setStreak(user: TLRPC.User?, streakViewData: StreakViewData?) {
        if (user == null || streakViewData == null) {
            clearStreakView()
            invalidateSelf()
            return
        }

        val parentView =
            getFieldValue<View>(SwapAnimatedEmojiDrawable::class.java, this, "parentView")
                ?: return

        replaceStreakView(
            object : SwapAnimatedEmojiDrawable(parentView, size) {
                val viewData: StreakViewData = streakViewData

                init {
                    set(viewData.documentId, false)
                    setParticles(true, false)
                    color = viewData.accentColor.toArgb()
                }

                override fun draw(canvas: Canvas) {
                    super.draw(canvas)

                    paint.setColor(viewData.accentColor.toArgb())

                    canvas.drawText(
                        viewData.length.toString(),
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat() - AndroidUtilities.dp(5f),
                        paint
                    )
                }
            }
        )

        invalidateSelf()
    }

    fun setBadge(user: TLRPC.User?, badgeDocumentId: Long?) {
        // do not draw badge, as it will be shown instead of an empty emoji status
        if (!canDrawBadge || badgeDocumentId == null || user?.emoji_status == null) {
            clearBadgeView()
            invalidateSelf()
            return
        }

        val parentView =
            getFieldValue<View>(SwapAnimatedEmojiDrawable::class.java, this, "parentView")
                ?: return

        replaceBadgeView(
            SwapAnimatedEmojiDrawable(parentView, size).apply {
                set(badgeDocumentId, false)
                setParticles(true, false)
                color = Theme.getColor(Theme.key_chats_verifiedBackground)
            }
        )

        invalidateSelf()
    }

    private fun refreshViews(streakViewData: StreakViewData?) {
        if (peerUserId == 0L) {
            setStreak(null, null)
            setBadge(null, null)

            return
        }

        if (streakViewData == null) {
            val dialog =
                MessagesController.getInstance(UserConfig.selectedAccount)
                    .getUserOrChat(peerUserId)

            when (dialog) {
                is TLRPC.User -> {
                    val badge = getBadgeDocumentId(dialog)

                    this.hasBadge = badge != null

                    setStreak(dialog, null)
                    setBadge(dialog, badge)
                }

                is TLRPC.Chat -> {
                    this.hasBadge = false

                    setStreak(null, null)
                    setBadge(null, null)
                }
            }

            return
        }

        MessagesController
            .getInstance(UserConfig.selectedAccount)
            .getUser(this.peerUserId)
            ?.let {
                hideOriginal = it.premium && it.emoji_status == null

                val badge = getBadgeDocumentId(it)

                this.hasBadge = badge != null

                setStreak(it, streakViewData)
                setBadge(it, badge)
            }
    }

    private fun getBadgeDocumentId(user: TLRPC.User): Long? =
        BadgesCompat.getDocumentId(user).let { documentId ->
            if (documentId == null
                || isClientVersionBelow("12.8.1")
                || (source != Source.DIALOG_CELL && source != Source.MESSAGE_CELL)
                || !BadgesCompat.hasSecondaryBadge(user)
            )
                return@let documentId

            return@let null
        }

    fun getPeerUserId(): Long = peerUserId

    fun setPeerUserId(peerUserId: Long, clearStreak: Boolean = false) {
        this.peerUserId = peerUserId

        val plugin = Plugin.getInstance()

        cachedStreakViewData =
            if (!clearStreak && peerUserId != 0L)
                plugin.streaksController.getViewDataBlocking(UserConfig.selectedAccount, peerUserId)
            else
                null

        refreshViews(cachedStreakViewData)

        AndroidUtilities.runOnUIThread { this@StreakEmoji.invalidateSelf() }
    }

    fun refresh(clearStreak: Boolean = false) =
        setPeerUserId(peerUserId, clearStreak)

    constructor(
        base: SwapAnimatedEmojiDrawable,
        peerUserId: Long,
        source: Source,
        canDrawBadge: Boolean,
    ) : super(
        null,
        0
    ) {
        cloneFields(base, this, SwapAnimatedEmojiDrawable::class.java)
        this.source = source
        this.canDrawBadge = canDrawBadge
        this.size = getFieldValue<Int>(SwapAnimatedEmojiDrawable::class.java, this, "size")!!

        setPeerUserId(peerUserId)
    }

    override fun draw(canvas: Canvas) {
        if (!hideOriginal)
            super.draw(canvas)

        streakView?.draw(canvas)
        badgeView?.draw(canvas)
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, left + size, bottom)
        syncBounds()
    }

    private fun getTextWidth(): Int {
        val length = cachedStreakViewData?.length ?: return 0
        val padding = if (canDrawBadge) size / 5 else 0

        if (length < 10)
            return (size * 0.3f).toInt() + padding

        if (length < 100)
            return (size * 0.6f).toInt() + padding

        if (length < 1000)
            return (size * 0.9f).toInt() + padding

        return (size * 1.2f).toInt() + padding
    }

    fun getAdditionalWidth(): Int {
        var width = 0

        if (cachedStreakViewData != null) {
            if (!hideOriginal)
                width += size

            width += getTextWidth()
        }

        if (canDrawBadge && hasBadge)
            width += size

        return width
    }

    override fun invalidate() {
        super.invalidate()
        streakView?.invalidate()
        badgeView?.invalidate()
    }

    override fun invalidateSelf() {
        super.invalidateSelf()
        streakView?.invalidateSelf()
        badgeView?.invalidateSelf()
    }

    override fun getMinimumWidth(): Int =
        super.getMinimumWidth() + getAdditionalWidth()

    override fun getIntrinsicWidth(): Int =
        super.getIntrinsicWidth() + getAdditionalWidth()

    override fun setAlpha(alpha: Int) {
        badgeView?.alpha = alpha
        super.setAlpha(alpha)
    }
}
