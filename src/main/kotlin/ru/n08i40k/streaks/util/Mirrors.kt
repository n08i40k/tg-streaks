@file:Suppress("unused")

package ru.n08i40k.streaks.util

import android.content.Context
import android.view.GestureDetector
import android.view.View
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.BaseController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessageSuggestionParams
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.CalendarActivity
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.FireworksOverlay
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import org.telegram.ui.Components.RecyclerListView
import ru.n08i40k.reflection.generator.Before
import ru.n08i40k.reflection.generator.Fields
import ru.n08i40k.reflection.generator.Getter
import ru.n08i40k.reflection.generator.Mirror
import ru.n08i40k.reflection.generator.Constructor
import ru.n08i40k.reflection.generator.Optional
import ru.n08i40k.reflection.generator.Retype
import ru.n08i40k.reflection.generator.RuntimeMirror
import ru.n08i40k.reflection.generator.Setter
import ru.n08i40k.reflection.generator.Since
import ru.n08i40k.reflection.generator.Type

@Mirror(ChatActionCell::class)
interface ChatActionCellMirror {
    @Getter
    val currentMessageObject: Any?

    @Getter
    val imageReceiver: Any?

    fun setStarsPaused(paused: Boolean)
}

@Mirror(TLRPC.Message::class)
interface MessageMirror {
    @Fields
    val fields: Any?
}

@Mirror(BaseController::class)
interface BaseControllerMirror {
    @Getter
    val currentAccount: Any?
}

@RuntimeMirror("androidx.core.view.GestureDetectorCompat")
interface GestureDetectorCompatMirror {
    @Constructor
    @Retype(Any::class, Context::class, GestureDetector.OnGestureListener::class)
    fun create(context: Context, listener: GestureDetector.OnGestureListener)

    @Suppress("SpellCheckingInspection")
    fun setIsLongpressEnabled(enabled: Boolean)
}

/** MonthView недоступен статически, поэтому его поля принимают View */
@RuntimeMirror($$"org.telegram.ui.CalendarActivity$MonthView")
interface MonthViewMirror {
    @Getter(retype = [Int::class, View::class])
    val currentYear: Any?

    @Getter(retype = [Int::class, View::class])
    val currentMonthInYear: Any?

    @Getter(retype = [Int::class, View::class])
    val daysInMonth: Any?

    @Getter(retype = [Int::class, View::class])
    val startDayOfWeek: Any?

    @Setter(retype = [Unit::class, View::class, Any::class])
    val gestureDetector: Any?
}

@RuntimeMirror($$"org.telegram.ui.CalendarActivity$PeriodDay")
interface PeriodDayMirror {
    @Setter(retype = [Unit::class, Any::class, Boolean::class])
    val hasImage: Any?
}

@Mirror(CalendarActivity::class)
interface CalendarActivityMirror {
    @Getter
    val listView: Any?

    @Getter(retype = [View::class, CalendarActivity::class])
    val bottomBar: Any?

    @Getter(retype = [View::class, CalendarActivity::class])
    val selectDaysButton: Any?

    @Getter(retype = [View::class, CalendarActivity::class])
    val removeDaysButton: Any?

    @Setter
    val inSelectionMode: Any?

    @Setter
    val dateSelectedStart: Any?

    @Setter
    val dateSelectedEnd: Any?

    @Getter
    @Setter
    val messagesByYearMounth: Any?

    @Setter
    val endReached: Any?
}

/** BadgesController — Kotlin object, поэтому его свойства хранятся в статических полях */
@Optional
@RuntimeMirror("com.exteragram.messenger.badges.BadgesController")
interface BadgesControllerMirror {
    @Getter(retype = [Any::class])
    val apiBadgeSource: Any?
}

/** кэш — j$.util.concurrent.ConcurrentHashMap или java.util.concurrent.ConcurrentHashMap, оба реализуют Map */
@Optional
@RuntimeMirror("com.exteragram.messenger.badges.source.ApiBadgeSource")
interface ApiBadgeSourceMirror {
    @Getter(retype = [Any::class, Any::class])
    val cache: Any?
}

@Optional
@RuntimeMirror("com.exteragram.messenger.api.dto.BadgeDTO")
interface BadgeDTOMirror {
    @Constructor
    fun create(id: Long, title: String)
}

@Optional
@RuntimeMirror("com.exteragram.messenger.api.model.ProfileStatus")
interface ProfileStatusMirror

@Optional
@RuntimeMirror("com.exteragram.messenger.badges.source.BadgeInfo")
interface BadgeInfoMirror {
    @Constructor
    @Since("13.1.1")
    fun create(
        @Type("com.exteragram.messenger.api.dto.BadgeDTO") badge: Any?,
        @Type("com.exteragram.messenger.api.model.ProfileStatus") status: Any?,
        flag: Boolean,
        number: Int?,
    )

    @Constructor
    @Since("12.2.10")
    @Before("13.1.1")
    fun create(
        @Type("com.exteragram.messenger.api.dto.BadgeDTO") badge: Any?,
        @Type("com.exteragram.messenger.api.model.ProfileStatus") status: Any?,
        flag: Boolean,
    )

    @Constructor
    @Since("12.1.1")
    @Before("12.2.10")
    fun create(
        @Type("com.exteragram.messenger.api.dto.BadgeDTO") badge: Any?,
        @Type("com.exteragram.messenger.api.model.ProfileStatus") status: Any?,
    )
}

@Mirror(PremiumPreviewBottomSheet::class)
interface PremiumPreviewBottomSheetMirror {
    @Setter
    val fireworksOverlay: Any?

    @Getter
    val buttonContainer: Any?

    @Getter
    @Setter
    val starParticlesView: Any?

    @Getter
    val iconTextureView: Any?
}

@Mirror(FireworksOverlay::class)
interface FireworksOverlayMirror {
    @Getter
    val paint: Any?
}

@Since("12.8.0")
@RuntimeMirror($$"org.telegram.tgnet.tl.TL_update$TL_updateNewMessage")
interface UpdateNewMessageMirror

@Since("12.1.1")
@Before("12.8.0")
@RuntimeMirror($$"org.telegram.tgnet.TLRPC$TL_updateNewMessage")
interface LegacyUpdateNewMessageMirror

@Optional
@RuntimeMirror("org.telegram.messenger.SendMessageChatArguments")
interface SendMessageChatArgumentsMirror

@Mirror(SendMessagesHelper::class)
interface SendMessagesHelperMirror {
    @Since("12.7.0")
    fun prepareSendingText(
        accountInstance: AccountInstance,
        text: CharSequence,
        dialogId: Long,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        monoForumPeerId: Long,
    )

    @Since("12.2.0")
    @Before("12.7.0")
    fun prepareSendingText(
        accountInstance: AccountInstance,
        text: String,
        dialogId: Long,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        monoForumPeerId: Long,
    )

    @Since("12.1.1")
    @Before("12.2.0")
    fun prepareSendingText(
        accountInstance: AccountInstance,
        text: String,
        dialogId: Long,
        notify: Boolean,
        scheduleDate: Int,
        monoForumPeerId: Long,
    )

    @Since("12.10.0")
    fun prepareSendingDocuments(
        accountInstance: AccountInstance,
        paths: ArrayList<*>,
        originalPaths: ArrayList<*>,
        uris: ArrayList<*>,
        caption: String,
        entities: ArrayList<*>,
        mime: String,
        dialogId: Long,
        replyToMsg: MessageObject,
        replyToTopMsg: MessageObject,
        storyItem: TL_stories.StoryItem,
        quote: ChatActivity.ReplyQuote,
        editingMessageObject: MessageObject,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        inputContent: InputContentInfoCompat,
        @Type("org.telegram.messenger.SendMessageChatArguments") sendMessageChatArguments: Any?,
        effectId: Long,
        invertMedia: Boolean,
        payStars: Long,
        monoForumPeerId: Long,
        suggestionParams: MessageSuggestionParams,
    )

    @Since("12.2.0")
    @Before("12.10.0")
    fun prepareSendingDocuments(
        accountInstance: AccountInstance,
        paths: ArrayList<*>,
        originalPaths: ArrayList<*>,
        uris: ArrayList<*>,
        caption: String,
        entities: ArrayList<*>,
        mime: String,
        dialogId: Long,
        replyToMsg: MessageObject,
        replyToTopMsg: MessageObject,
        storyItem: TL_stories.StoryItem,
        quote: ChatActivity.ReplyQuote,
        editingMessageObject: MessageObject,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        inputContent: InputContentInfoCompat,
        quickReplyShortcut: String,
        quickReplyShortcutId: Int,
        effectId: Long,
        invertMedia: Boolean,
        payStars: Long,
        monoForumPeerId: Long,
        suggestionParams: MessageSuggestionParams,
    )

    @Since("12.1.1")
    @Before("12.2.0")
    fun prepareSendingDocuments(
        accountInstance: AccountInstance,
        paths: ArrayList<*>,
        originalPaths: ArrayList<*>,
        uris: ArrayList<*>,
        caption: String,
        entities: ArrayList<*>,
        mime: String,
        dialogId: Long,
        replyToMsg: MessageObject,
        replyToTopMsg: MessageObject,
        storyItem: TL_stories.StoryItem,
        quote: ChatActivity.ReplyQuote,
        editingMessageObject: MessageObject,
        notify: Boolean,
        scheduleDate: Int,
        inputContent: InputContentInfoCompat,
        quickReplyShortcut: String,
        quickReplyShortcutId: Int,
        effectId: Long,
        invertMedia: Boolean,
        payStars: Long,
        monoForumPeerId: Long,
        suggestionParams: MessageSuggestionParams,
    )
}

@Mirror(TextCheckCell::class)
interface TextCheckCellMirror {
    @Since("12.5.1")
    fun setTextAndValueAndCheck(
        text: CharSequence,
        value: String,
        checked: Boolean,
        multiline: Boolean,
        divider: Boolean
    )

    @Since("12.1.1")
    @Before("12.5.1")
    fun setTextAndValueAndCheck(
        text: String,
        value: String,
        checked: Boolean,
        multiline: Boolean,
        divider: Boolean
    )
}

@Mirror(RecyclerListView::class)
interface RecyclerListViewMirror {
    @Since("12.5.1")
    fun setSections()
}
