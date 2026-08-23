package ru.n08i40k.streaks.override

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
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
import ru.n08i40k.streaks.util.getAccessibleFields
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.runOnMainThread
import java.lang.ref.WeakReference
import java.lang.reflect.Field

class StreakEmoji : SwapAnimatedEmojiDrawable {
    // куда клиент кладёт бейдж без нашего вмешательства
    enum class BadgeSlot {
        // в отдельный view:
        // UserCell.emojiStatus2,
        // ChatAvatarContainer.emojiStatusDrawable2,
        // ProfileActivity.badgeDrawable;
        SEPARATE,

        // в тот же view, но только если тот не занят эмодзи:
        // ProfileSearchCell,
        // StatusBadgeComponent;
        STATUS,

        // то же, что STATUS, но при занятом статусе кастомный бейдж уезжает в слот перед именем (новые версии):
        // DialogCell.botVerification,
        // ChatMessageCell.currentNameEmojiStatusDrawable;
        STATUS_OR_NAME,
    }

    enum class Part {
        // оригинальный статус пользователя
        ORIGINAL,

        // эмодзи стрика
        STREAK,

        // бейдж
        BADGE
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

            cloneFields(drawable, pseudoOriginal, EMOJI_FIELDS)

            if (arrayIndex == null) {
                targetField.set(targetObject, pseudoOriginal)
                nameTextView?.get()?.let { textView ->
                    if (RIGHT_DRAWABLE.get(textView) === drawable)
                        textView.rightDrawable = pseudoOriginal

                    if (RIGHT_DRAWABLE_2.get(textView) === drawable)
                        textView.rightDrawable2 = pseudoOriginal
                }
                return
            }

            @Suppress("UNCHECKED_CAST")
            val array = targetField.get(targetObject)!! as Array<SwapAnimatedEmojiDrawable>

            array[arrayIndex] = pseudoOriginal
        }
    }

    companion object Reflection {
        private val CLASS = SwapAnimatedEmojiDrawable::class.java

        val PARENT_VIEW = getField(CLASS, "parentView")
        val SIZE = getField(CLASS, "size")

        // SimpleTextView
        val RIGHT_DRAWABLE = getField(SimpleTextView::class.java, "rightDrawable")
        val RIGHT_DRAWABLE_2 = getField(SimpleTextView::class.java, "rightDrawable2")

        val EMOJI_FIELDS = getAccessibleFields(SwapAnimatedEmojiDrawable::class.java)

        fun encapsulate(
            obj: Any,
            field: Field,
            arrayIndex: Int?,
            peerUserId: Long,
            badgeSlot: BadgeSlot,
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
                    badgeSlot,
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
                badgeSlot,
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

    private val badgeSlot: BadgeSlot

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

    fun hitTest(x: Int, y: Int): Part? {
        val padding = AndroidUtilities.dp(3f)

        if (y < bounds.top - padding || y > bounds.bottom + padding)
            return null

        val streakLeft = bounds.left + if (hideOriginal) 0 else size
        val streakWidth = size + getTextWidth()

        if (badgeView != null) {
            val badgeLeft = streakLeft + if (streakView == null) 0 else streakWidth

            if (x >= badgeLeft - padding && x <= badgeLeft + size + padding)
                return Part.BADGE
        }

        if (streakView != null
            && x >= streakLeft - padding
            && x <= streakLeft + streakWidth + padding
        )
            return Part.STREAK

        if (!hideOriginal && x >= bounds.left - padding && x <= bounds.right + padding)
            return Part.ORIGINAL

        return null
    }

    fun setStreak(user: TLRPC.User?, streakViewData: StreakViewData?) {
        if (user == null || streakViewData == null) {
            clearStreakView()
            invalidateSelf()
            return
        }

        val parentView = PARENT_VIEW.getAs<View>(this)
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

    fun setBadge(badgeDocumentId: Long?) {
        // do not draw badge, as it will be shown instead of an empty emoji status
        if (badgeDocumentId == null) {
            clearBadgeView()
            invalidateSelf()
            return
        }

        val parentView = PARENT_VIEW.getAs<View>(this)
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

    private fun refreshCachedViews() {
        val user = peerUserId
            .takeIf { it != 0L }
            ?.let { MessagesController.getInstance(UserConfig.selectedAccount).getUserOrChat(it) }
                as? TLRPC.User

        if (user == null) {
            hideOriginal = false
            hasBadge = false

            setStreak(null, null)
            setBadge(null)

            return
        }

        val documentId = BadgesCompat.getDocumentId(user)
        val hasStatus = hasEmojiStatus(user)

        // При пустом статусе в занятом нами слоте лежал бы бейдж или прем-эмодзи
        // а т.к. стрик должен быть перед бейджем, скрываем его и рисуем сами
        hideOriginal = !hasStatus && (cachedStreakViewData != null || documentId != null)

        val badge = getBadgeDocumentId(user, documentId, hasStatus)

        hasBadge = badge != null

        setStreak(user, cachedStreakViewData)
        setBadge(badge)
    }

    private fun hasEmojiStatus(user: TLRPC.User): Boolean =
        DialogObject.getEmojiStatusDocumentId(user.emoji_status) != 0L

    private fun getBadgeDocumentId(
        user: TLRPC.User,
        documentId: Long?,
        hasStatus: Boolean,
    ): Long? {
        if (documentId == null)
            return null

        // статус пуст, значит слот, в котором клиент нарисовал бы бейдж, занят нами (секс)
        if (!hasStatus)
            return documentId

        return when (badgeSlot) {
            // клиент рисует бейдж своим drawable рядом со статусом
            BadgeSlot.SEPARATE -> null

            // кастомный бейдж в слоте перед именем (новая версия)
            BadgeSlot.STATUS_OR_NAME -> documentId.takeIf { !BadgesCompat.hasSecondaryBadge(user) }

            // если есть прем эмодзи, клиент не рисует бейдж
            BadgeSlot.STATUS -> documentId
        }
    }

    fun getPeerUserId(): Long = peerUserId

    fun setPeerUserId(peerUserId: Long, clearStreak: Boolean = false) {
        this.peerUserId = peerUserId

        cachedStreakViewData = if (!clearStreak && peerUserId != 0L) {
            Plugin.getInstance()
                .streaksController
                .getViewData(UserConfig.selectedAccount, peerUserId)
        } else {
            null
        }

        refreshCachedViews()

        runOnMainThread(this@StreakEmoji::invalidateSelf)
    }

    fun refresh(clearStreak: Boolean = false) =
        setPeerUserId(peerUserId, clearStreak)

    constructor(
        base: SwapAnimatedEmojiDrawable,
        peerUserId: Long,
        badgeSlot: BadgeSlot,
    ) : super(
        null,
        0
    ) {
        cloneFields(base, this, EMOJI_FIELDS)
        this.badgeSlot = badgeSlot

        this.size = SIZE.getInt(this)

        PARENT_VIEW.getAs<View>(this)?.let {
            Plugin.getInstance().streakEmojiRegistry.attachTouchHandler(it, this)
        }

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
        val padding = if (hasBadge) size / 5 else 0

        if (length < 10)
            return (size * 0.3f).toInt() + padding

        if (length < 100)
            return (size * 0.6f).toInt() + padding

        if (length < 1000)
            return (size * 0.9f).toInt() + padding

        return (size * 1.2f).toInt() + padding
    }

    fun getAdditionalWidth(): Int {
        // ну типо уменьшаем размер на 1 эмодзи, если его нет
        var width = if (hideOriginal) -size else 0

        if (cachedStreakViewData != null)
            width += size + getTextWidth()

        if (hasBadge)
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

// жопа)