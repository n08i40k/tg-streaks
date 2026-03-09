@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.overrides

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import com.exteragram.messenger.badges.BadgesController
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import org.telegram.ui.Components.Premium.PremiumGradient
import org.telegram.ui.Stars.StarsReactionsSheet
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.cloneFields
import ru.n08i40k.streaks.data.StreakData
import ru.n08i40k.streaks.getField
import ru.n08i40k.streaks.getFieldValue
import java.lang.ref.WeakReference
import java.lang.reflect.Field

class StreakAnimatedEmojiDrawable : SwapAnimatedEmojiDrawable {
    data class EjectData(
        val drawable: WeakReference<StreakAnimatedEmojiDrawable>,
        val targetObject: WeakReference<Any>,
        val targetField: Field,
        val arrayIndex: Int?,
    ) {
        fun restore() {
            val drawable = this.drawable.get() ?: return
            drawable.resetCache(true)

            val targetObject = this.targetObject.get() ?: return

            val pseudoOriginal = SwapAnimatedEmojiDrawable(null, 0)

            cloneFields(
                drawable as Object,
                pseudoOriginal as Object,
                SwapAnimatedEmojiDrawable::class.java
            )

            if (arrayIndex == null) {
                targetField.set(targetObject, pseudoOriginal)
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
            userId: Long,
            canDrawBadge: Boolean = false,
            hideParticlesOnCollectibles: Boolean = false,
        ) {
            if (arrayIndex == null) {
                val drawable = field.get(obj) as? SwapAnimatedEmojiDrawable
                    ?: throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable")

                if (drawable as? StreakAnimatedEmojiDrawable != null) {
                    drawable.setUserId(userId)
                    return
                }

                val newDrawable = StreakAnimatedEmojiDrawable(
                    drawable,
                    userId,
                    canDrawBadge,
                    hideParticlesOnCollectibles
                )

                field.set(obj, newDrawable)
                newDrawable.detach()

                Plugin.getInstance()!!.addStreakDrawableEjectData(
                    EjectData(
                        WeakReference(newDrawable),
                        WeakReference(obj),
                        field,
                        arrayIndex
                    )
                )
                return
            }

            val unknownArray = field.get(obj) ?: throw NullPointerException("Field is null")

            if (!unknownArray::class.java.isArray)
                throw TypeCastException("Field value type isn't array")

            if (unknownArray::class.java.componentType != SwapAnimatedEmojiDrawable::class.java)
                throw TypeCastException("Field value type isn't SwapAnimatedEmojiDrawable[]")

            @Suppress("UNCHECKED_CAST")
            val array = unknownArray as Array<SwapAnimatedEmojiDrawable>

            if (array.size <= arrayIndex)
                throw IndexOutOfBoundsException("SwapAnimatedEmojiDrawable[] size is below $arrayIndex")

            val drawable = array[arrayIndex]

            if (drawable as? StreakAnimatedEmojiDrawable != null) {
                drawable.setUserId(userId)
                return
            }

            val newDrawable = StreakAnimatedEmojiDrawable(
                drawable,
                userId,
                canDrawBadge,
                hideParticlesOnCollectibles
            )
            array[arrayIndex] = newDrawable

            Plugin.getInstance()!!.addStreakDrawableEjectData(
                EjectData(
                    WeakReference(newDrawable),
                    WeakReference(obj),
                    field,
                    arrayIndex
                )
            )
        }

        private val paint by lazy {
            val paint = Paint()
            paint.textSize = 22f
            paint
        }
    }

    private var userId: Long = 0
    private var cachedStreakData: StreakData? = null

    private val canDrawBadge: Boolean
    private var badgeView: SwapAnimatedEmojiDrawable? = null

    private var hideParticlesOnCollectibles: Boolean

    private val size: Int

    private var hasCustomParticles: Boolean = false

    fun setBadge(user: TLRPC.User?) {
        if (!canDrawBadge) return

        if (user == null) {
            badgeView = null
            return
        }

        val badge = BadgesController.INSTANCE.getBadge(user)

        if (badge == null) {
            if (cachedStreakData == null && !user.premium) {
                super.set(null as Drawable?, false)
                super.setParticles(false, false)
            }

            badgeView = null
        } else {
            if (cachedStreakData == null && !user.premium) {
                super.set(badge.documentId, false)
                super.setParticles(true, false)
            } else {
                val parentView =
                    getFieldValue<View>(SwapAnimatedEmojiDrawable::class.java, this, "parentView")!!

                badgeView = SwapAnimatedEmojiDrawable(parentView, size)

                badgeView!!.set(badge.documentId, false)
                badgeView!!.setParticles(true, false)
                badgeView!!.color = Theme.getColor(Theme.key_chats_verifiedBackground)
            }
        }
    }

    fun setUserId(userId: Long, clearStreak: Boolean = false) {
        this.userId = userId
        this.cachedStreakData = Plugin.getInstance()!!.resolveStreakData(userId)

        if (cachedStreakData != null && !clearStreak) {
            super.set(cachedStreakData!!.documentId, 7, true)
            super.setParticles(true, true)

            getField(SwapAnimatedEmojiDrawable::class.java, "particles").let { field ->
                val parent = field.get(this) as? StarsReactionsSheet.Particles
                val child =
                    parent?.let { base ->
                        StreakParticles(
                            base,
                            cachedStreakData!!.accentColor.toArgb()
                        )
                    }

                field.set(this, child)
            }

            hasCustomParticles = true

            MessagesController
                .getInstance(UserConfig.selectedAccount)
                .getUser(userId)
                ?.let {
                    setBadge(it)
                }
        } else {
            if (hasCustomParticles) {
                // restore original particles class without custom color or etc.
                super.setParticles(false, false)
                hasCustomParticles = false
            }

            val dialog =
                MessagesController.getInstance(UserConfig.selectedAccount).getUserOrChat(userId)

            when (dialog) {
                is TLRPC.User -> {
                    val documentId = UserObject.getEmojiStatusDocumentId(dialog.emoji_status)

                    if (documentId != null) {
                        super.set(documentId, false)
                        super.setParticles(
                            !hideParticlesOnCollectibles && DialogObject.isEmojiStatusCollectible(
                                dialog.emoji_status
                            ),
                            false
                        )
                    } else if (dialog.premium) {
                        super.set(PremiumGradient.getInstance().premiumStarDrawableMini, false)
                        super.setParticles(false, false)
                    } else {
                        super.set(null as Drawable?, false)
                        super.setParticles(false, false)
                    }

                    setBadge(dialog)
                }

                is TLRPC.Chat -> {
                    val documentId = DialogObject.getEmojiStatusDocumentId(dialog.emoji_status)

                    if (documentId != 0L) {
                        super.set(documentId, false)
                        super.setParticles(
                            DialogObject.isEmojiStatusCollectible(dialog.emoji_status),
                            false
                        )
                    } else {
                        val badge = BadgesController.INSTANCE.getBadge(dialog)

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
            }
        }

        this.invalidateSelf()
    }

    fun resetCache(clearStreak: Boolean = false) {
        setUserId(userId, clearStreak)
    }

    constructor(
        base: SwapAnimatedEmojiDrawable,
        userId: Long,
        canDrawBadge: Boolean,
        hideParticlesOnCollectibles: Boolean
    ) : super(
        null,
        0
    ) {
        cloneFields(base as Object, this as Object, SwapAnimatedEmojiDrawable::class.java)
        this.canDrawBadge = canDrawBadge
        this.hideParticlesOnCollectibles = hideParticlesOnCollectibles
        this.size = getFieldValue<Int>(SwapAnimatedEmojiDrawable::class.java, this, "size")!!

        setUserId(userId)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        cachedStreakData?.let {
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

        badgeView?.setBounds(
            left + size + getTextWidth(),
            top,
            left + size * 2 + getTextWidth(),
            bottom
        )
    }

    override fun setParticles(p0: Boolean, p1: Boolean) {
        if (cachedStreakData != null)
            return

        super.setParticles(p0, p1)
    }

    override fun set(p0: Drawable?, p1: Boolean) {
        if (cachedStreakData != null)
            return

        super.set(p0, p1)
    }

    override fun set(p0: Long, p1: Boolean): Boolean {
        if (cachedStreakData != null)
            return true

        return super.set(p0, p1)
    }

    override fun set(p0: Long, p1: Int, p2: Boolean): Boolean {
        if (cachedStreakData != null)
            return true

        return super.set(p0, p1, p2)
    }

    override fun set(p0: TLRPC.Document?, p1: Boolean) {
        if (cachedStreakData != null)
            return

        super.set(p0, p1)
    }

    override fun set(p0: TLRPC.Document?, p1: Int, p2: Boolean) {
        if (cachedStreakData != null)
            return

        super.set(p0, p1, p2)
    }

    private fun getTextWidth(): Int {
        val length = cachedStreakData?.length ?: return 0
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

    fun getAdditionalWidth(): Int {
        return if (canDrawBadge)
            getTextWidth() + 1
        else
            getTextWidth()
    }
}
