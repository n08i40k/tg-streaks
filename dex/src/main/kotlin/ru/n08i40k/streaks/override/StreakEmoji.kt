@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.override

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import com.exteragram.messenger.badges.BadgesController
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import org.telegram.ui.Components.Premium.PremiumGradient
import org.telegram.ui.Stars.StarsReactionsSheet
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import java.lang.ref.WeakReference
import java.lang.reflect.Field

class StreakEmoji : SwapAnimatedEmojiDrawable {
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
                drawable as Object,
                pseudoOriginal as Object,
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
            canDrawBadge: Boolean = false,
            nameTextView: SimpleTextView? = null,
        ) {
            if (arrayIndex == null) {
                val drawable = (field.get(obj) ?: return) as? SwapAnimatedEmojiDrawable
                    ?: throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable")

                if (drawable as? StreakEmoji != null) {
                    drawable.setPeerUserId(peerUserId)
                    return
                }

                val newDrawable = StreakEmoji(
                    drawable,
                    peerUserId,
                    canDrawBadge,
                )

                field.set(obj, newDrawable)

                Plugin.getInstance().streakEmojiRegistry.add(
                    EjectData(
                        WeakReference(newDrawable),
                        WeakReference(obj),
                        field,
                        arrayIndex,
                        nameTextView?.let(::WeakReference)
                    )
                )
                return
            }

            val unknownArray = field.get(obj) ?: return

            if (!unknownArray::class.java.isArray)
                throw TypeCastException("Field value type isn't array")

            if (unknownArray::class.java.componentType != SwapAnimatedEmojiDrawable::class.java)
                throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable[]")

            @Suppress("UNCHECKED_CAST")
            val array = unknownArray as Array<SwapAnimatedEmojiDrawable?>

            if (array.size <= arrayIndex)
                throw IndexOutOfBoundsException("SwapAnimatedEmojiDrawable[] size is below $arrayIndex")

            val drawable = array[arrayIndex] ?: return

            if (drawable as? StreakEmoji != null) {
                drawable.setPeerUserId(peerUserId)
                return
            }

            val newDrawable = StreakEmoji(
                drawable,
                peerUserId,
                canDrawBadge,
            )
            array[arrayIndex] = newDrawable

            Plugin.getInstance().streakEmojiRegistry.add(
                EjectData(
                    WeakReference(newDrawable),
                    WeakReference(obj),
                    field,
                    arrayIndex,
                    nameTextView?.let(::WeakReference)
                )
            )
        }

        private val paint by lazy {
            val paint = Paint()
            paint.textSize = 22f
            paint
        }
    }

    private var peerUserId: Long = 0
    private var cachedStreakViewData: StreakViewData? = null

    private val canDrawBadge: Boolean
    private var badgeView: SwapAnimatedEmojiDrawable? = null

    private val size: Int

    private var hasCustomParticles: Boolean = false
    private var hasParticles: Boolean = false

    private fun clearBadgeView() {
        badgeView?.detach()
        badgeView = null
    }

    private fun replaceBadgeView(view: SwapAnimatedEmojiDrawable?) {
        if (badgeView === view) {
            syncBadgeBounds()
            return
        }

        badgeView?.detach()
        badgeView = view
        badgeView?.attach()
        syncBadgeBounds()
    }

    private fun syncBadgeBounds() {
        badgeView?.setBounds(
            bounds.left + size + getTextWidth(),
            bounds.top,
            bounds.left + size * 2 + getTextWidth(),
            bounds.bottom
        )
    }

    private fun applyBadgeDocument(user: TLRPC.User, document: TLRPC.Document) {
        if (this.peerUserId != user.id) {
            return
        }

        if (cachedStreakViewData == null && !user.premium) {
            super.set(document, false)
            super.setParticles(true, false)
            clearBadgeView()
        } else {
            val parentView =
                getFieldValue<View>(
                    SwapAnimatedEmojiDrawable::class.java,
                    this,
                    "parentView"
                ) ?: return

            replaceBadgeView(SwapAnimatedEmojiDrawable(parentView, size).apply {
                set(document, false)
                setParticles(true, false)
                color = Theme.getColor(Theme.key_chats_verifiedBackground)
            })
        }

        invalidateSelf()
    }

    private fun whenDocumentReady(documentId: Long, onReady: (TLRPC.Document) -> Unit) {
        if (documentId <= 0L) {
            return
        }

        val account = UserConfig.selectedAccount

        AnimatedEmojiDrawable.findDocument(account, documentId)
            ?.let {
                onReady(it)
                return
            }

        AnimatedEmojiDrawable
            .getDocumentFetcher(account)
            .fetchDocument(documentId) { document ->
                AndroidUtilities.runOnUIThread {
                    onReady(document)
                }
            }
    }

    private fun applyStreakState(streakViewData: StreakViewData) {
        val peerUserId = this.peerUserId

        whenDocumentReady(streakViewData.documentId) { document ->
            if (this.peerUserId != peerUserId || cachedStreakViewData?.documentId != streakViewData.documentId) {
                return@whenDocumentReady
            }

            super.set(document, 7, true)
            super.setParticles(true, false)

            invalidateSelf()
        }

        getField(SwapAnimatedEmojiDrawable::class.java, "particles").let { field ->
            val parent = field.get(this) as? StarsReactionsSheet.Particles
            val child =
                parent?.let { base ->
                    ColoredParticles(
                        base,
                        streakViewData.accentColor.toArgb()
                    )
                }

            field.set(this, child)
        }

        hasCustomParticles = true

        MessagesController
            .getInstance(UserConfig.selectedAccount)
            .getUser(peerUserId)
            ?.let(::setBadge)
    }

    private fun applyUserState(user: TLRPC.User) {
        val documentId = UserObject.getEmojiStatusDocumentId(user.emoji_status)

        when {
            documentId != null -> {
                super.set(documentId, true)
                super.setParticles(hasParticles, false)
            }

            user.premium -> {
                super.set(PremiumGradient.getInstance().premiumStarDrawableMini, false)
                super.setParticles(false, false)
            }

            else -> {
                super.set(null as Drawable?, false)
                super.setParticles(false, false)
            }
        }

        setBadge(user)
    }

    private fun applyChatState(chat: TLRPC.Chat) {
        val documentId = DialogObject.getEmojiStatusDocumentId(chat.emoji_status)

        if (documentId != 0L) {
            super.set(documentId, true)
            super.setParticles(hasParticles, false)
        } else {
            val badge = BadgesController.INSTANCE.getBadge(chat)

            if (badge == null) {
                super.set(null as Drawable?, false)
                super.setParticles(false, false)
            } else {
                super.set(badge.documentId, false)
                super.setParticles(true, false)
            }
        }

        setBadge(null)
    }

    fun setBadge(user: TLRPC.User?) {
        if (!canDrawBadge) return

        if (user == null) {
            clearBadgeView()
            invalidateSelf()
            return
        }

        if (!BadgesController.INSTANCE.hasBadge(user)) {
            if (cachedStreakViewData == null && !user.premium) {
                super.set(null as Drawable?, false)
                super.setParticles(false, false)
            }

            clearBadgeView()
            invalidateSelf()
            return
        }

        val badge = BadgesController.INSTANCE.getBadge(user)
            ?: throw IllegalStateException("User has badge, but it is null")

        AnimatedEmojiDrawable.findDocument(UserConfig.selectedAccount, badge.documentId)
            ?.let { cachedDocument ->
                applyBadgeDocument(user, cachedDocument)
                return
            }

        AnimatedEmojiDrawable
            .getDocumentFetcher(UserConfig.selectedAccount)
            .fetchDocument(badge.documentId) { document ->
                AndroidUtilities.runOnUIThread {
                    applyBadgeDocument(user, document)
                }
            }
    }

    fun getPeerUserId(): Long = peerUserId

    fun setPeerUserId(peerUserId: Long, clearStreak: Boolean = false) {
        this.peerUserId = peerUserId

        val plugin = Plugin.getInstance()

        runBlocking {
            cachedStreakViewData =
                if (clearStreak)
                    null
                else
                    plugin.streaksController.getViewData(UserConfig.selectedAccount, peerUserId)
        }

        plugin.backgroundScope.launch {
            AndroidUtilities.runOnUIThread {
                if (cachedStreakViewData != null)
                    applyStreakState(cachedStreakViewData!!)
                else {
                    if (hasCustomParticles) {
                        // restore original particles class without custom color or etc.
                        super.setParticles(false, false)
                        hasCustomParticles = false
                    }

                    val dialog =
                        MessagesController.getInstance(UserConfig.selectedAccount)
                            .getUserOrChat(peerUserId)

                    when (dialog) {
                        is TLRPC.User -> applyUserState(dialog)
                        is TLRPC.Chat -> applyChatState(dialog)
                    }
                }

                this@StreakEmoji.invalidateSelf()
            }
        }
    }

    fun refresh(clearStreak: Boolean = false) =
        setPeerUserId(peerUserId, clearStreak)

    constructor(
        base: SwapAnimatedEmojiDrawable,
        peerUserId: Long,
        canDrawBadge: Boolean,
    ) : super(
        null,
        0
    ) {
        cloneFields(base as Object, this as Object, SwapAnimatedEmojiDrawable::class.java)
        this.canDrawBadge = canDrawBadge
        this.size = getFieldValue<Int>(SwapAnimatedEmojiDrawable::class.java, this, "size")!!

        setPeerUserId(peerUserId)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        cachedStreakViewData?.let {
            paint.setColor(it.accentColor.toArgb())

            canvas.drawText(
                it.length.toString(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat() - AndroidUtilities.dp(5f),
                paint
            )
        }

        badgeView?.draw(canvas)
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, left + size, bottom)
        syncBadgeBounds()
    }

    override fun setParticles(p0: Boolean, p1: Boolean) {
        if (cachedStreakViewData != null) {
            hasParticles = p0
            return
        }

        super.setParticles(p0, p1)
    }

    override fun set(p0: Drawable?, p1: Boolean) {
        if (cachedStreakViewData != null)
            return

        super.set(p0, p1)
    }

    override fun set(p0: Long, p1: Boolean): Boolean {
        if (cachedStreakViewData != null)
            return true

        return super.set(p0, p1)
    }

    override fun set(p0: Long, p1: Int, p2: Boolean): Boolean {
        if (cachedStreakViewData != null)
            return true

        return super.set(p0, p1, p2)
    }

    override fun set(p0: TLRPC.Document?, p1: Boolean) {
        if (cachedStreakViewData != null)
            return

        super.set(p0, p1)
    }

    override fun set(p0: TLRPC.Document?, p1: Int, p2: Boolean) {
        if (cachedStreakViewData != null)
            return

        super.set(p0, p1, p2)
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

    override fun getMinimumWidth(): Int {
        val width = super.getMinimumWidth()

        return if (canDrawBadge)
            width + getTextWidth() + 1
        else
            width + getTextWidth()
    }

    override fun getIntrinsicWidth(): Int {
        val width = super.getIntrinsicWidth()

        return if (canDrawBadge)
            width + getTextWidth() + 1
        else
            width + getTextWidth()
    }

    fun getAdditionalWidth(): Int =
        if (canDrawBadge)
            getTextWidth() + 1
        else
            getTextWidth()

    override fun setAlpha(alpha: Int) {
        badgeView?.alpha = alpha
        super.setAlpha(alpha)
    }
}
