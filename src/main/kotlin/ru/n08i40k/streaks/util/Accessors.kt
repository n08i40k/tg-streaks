@file:Suppress("ObjectPropertyName", "HasPlatformType")

package ru.n08i40k.streaks.util

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
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.FireworksOverlay
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet

@JvmField
val `ChatActionCell$currentMessageObject` =
    getFieldGetter(ChatActionCell::class.java, "currentMessageObject")

@JvmField
val `ChatActionCell$imageReceiver` =
    getFieldGetter(ChatActionCell::class.java, "imageReceiver")

@JvmField
val `ChatActionCell$setStarsPaused` =
    getMethodHandle(ChatActionCell::class.java, "setStarsPaused", Boolean::class.javaPrimitiveType!!)

@JvmField
val `TLRPC$Message$$fields` =
    getAccessibleFields(TLRPC.Message::class.java)

@JvmField
val `BaseController$currentAccount` =
    getFieldGetter(BaseController::class.java, "currentAccount")

@JvmField
val `CalendarActivity$MonthView` =
    Class.forName($$"org.telegram.ui.CalendarActivity$MonthView")

@JvmField
val GestureDetectorCompat =
    Class.forName("androidx.core.view.GestureDetectorCompat")

@JvmField
val `CalendarActivity$PeriodDay` =
    Class.forName($$"org.telegram.ui.CalendarActivity$PeriodDay")

// MonthView недоступен статически, поэтому его поля принимают View
@JvmField
val `CalendarActivity$MonthView$currentYear` =
    getFieldGetter(`CalendarActivity$MonthView`, "currentYear")
        .retype(Int::class.java, View::class.java)

@JvmField
val `CalendarActivity$MonthView$currentMonthInYear` =
    getFieldGetter(`CalendarActivity$MonthView`, "currentMonthInYear")
        .retype(Int::class.java, View::class.java)

@JvmField
val `CalendarActivity$MonthView$daysInMonth` =
    getFieldGetter(`CalendarActivity$MonthView`, "daysInMonth")
        .retype(Int::class.java, View::class.java)

@JvmField
val `CalendarActivity$MonthView$startDayOfWeek` =
    getFieldGetter(`CalendarActivity$MonthView`, "startDayOfWeek")
        .retype(Int::class.java, View::class.java)

@JvmField
val `CalendarActivity$MonthView$gestureDetector$$setter` =
    getFieldSetter(`CalendarActivity$MonthView`, "gestureDetector")
        .retype(Void.TYPE, View::class.java, Any::class.java)

@JvmField
val `CalendarActivity$PeriodDay$hasImage$$setter` =
    getFieldSetter(`CalendarActivity$PeriodDay`, "hasImage")
        .retype(Void.TYPE, Any::class.java, Boolean::class.java)

@JvmField
val `CalendarActivity$listView` =
    getFieldGetter(CalendarActivity::class.java, "listView")

@JvmField
val `CalendarActivity$bottomBar` =
    getFieldGetter(CalendarActivity::class.java, "bottomBar")
        .retype(View::class.java, CalendarActivity::class.java)

@JvmField
val `CalendarActivity$selectDaysButton` =
    getFieldGetter(CalendarActivity::class.java, "selectDaysButton")
        .retype(View::class.java, CalendarActivity::class.java)

@JvmField
val `CalendarActivity$removeDaysButton` =
    getFieldGetter(CalendarActivity::class.java, "removeDaysButton")
        .retype(View::class.java, CalendarActivity::class.java)

@JvmField
val `CalendarActivity$inSelectionMode$$setter` =
    getFieldSetter(CalendarActivity::class.java, "inSelectionMode")

@JvmField
val `CalendarActivity$dateSelectedStart$$setter` =
    getFieldSetter(CalendarActivity::class.java, "dateSelectedStart")

@JvmField
val `CalendarActivity$dateSelectedEnd$$setter` =
    getFieldSetter(CalendarActivity::class.java, "dateSelectedEnd")

@JvmField
val `CalendarActivity$messagesByYearMounth` =
    getFieldGetter(CalendarActivity::class.java, "messagesByYearMounth")

@JvmField
val `CalendarActivity$messagesByYearMounth$$setter` =
    getFieldSetter(CalendarActivity::class.java, "messagesByYearMounth")

@JvmField
val BadgesController =
    getClassIfExists("com.exteragram.messenger.badges.BadgesController")

// BadgesController — Kotlin object, поэтому его свойства хранятся в статических полях
private val `BadgesController$apiBadgeSource$$field` =
    getFieldGetterIfExists(BadgesController, "apiBadgeSource")

@JvmField
val `BadgesController$apiBadgeSource` =
    `BadgesController$apiBadgeSource$$field`?.retype(Any::class.java)

@JvmField
val `ApiBadgeSource$cache` =
    getFieldGetterIfExists(`BadgesController$apiBadgeSource$$field`?.type()?.returnType(), "cache")
        ?.retype(Any::class.java, Any::class.java)

@JvmField
val BadgeDTO =
    getClassIfExists("com.exteragram.messenger.api.dto.BadgeDTO")

@JvmField
val `BadgeDTO$$init` =
    getConstructorIfExists(BadgeDTO, Long::class.java, String::class.java)

@JvmField
val ProfileStatus =
    getClassIfExists("com.exteragram.messenger.api.model.ProfileStatus")

@JvmField
val BadgeInfo =
    getClassIfExists("com.exteragram.messenger.badges.source.BadgeInfo")

@JvmField
val `BadgeInfo$$init` =
    getConstructorIfExists(
        BadgeInfo,
        BadgeDTO,
        ProfileStatus,
        *if (isClientVersionBelow("12.2.10"))
            arrayOf()
        else
            arrayOf(Boolean::class.java)
    )

@JvmField
val `PremiumPreviewBottomSheet$fireworksOverlay$$setter` =
    getFieldSetter(PremiumPreviewBottomSheet::class.java, "fireworksOverlay")

@JvmField
val `PremiumPreviewBottomSheet$buttonContainer` =
    getFieldGetter(PremiumPreviewBottomSheet::class.java, "buttonContainer")

@JvmField
val `PremiumPreviewBottomSheet$starParticlesView` =
    getFieldGetter(PremiumPreviewBottomSheet::class.java, "starParticlesView")

@JvmField
val `PremiumPreviewBottomSheet$starParticlesView$$setter` =
    getFieldSetter(PremiumPreviewBottomSheet::class.java, "starParticlesView")

@JvmField
val `PremiumPreviewBottomSheet$iconTextureView` =
    getFieldGetter(PremiumPreviewBottomSheet::class.java, "iconTextureView")

@JvmField
val `FireworksOverlay$paint` =
    getFieldGetter(FireworksOverlay::class.java, "paint")

@JvmField
val TL_updateNewMessage =
    if (isClientVersionBelow("12.8.0"))
        Class.forName($$"org.telegram.tgnet.TLRPC$TL_updateNewMessage")
    else
        Class.forName($$"org.telegram.tgnet.tl.TL_update$TL_updateNewMessage")

@JvmField
val `TL_updateNewMessage$message` =
    getFieldGetter(TL_updateNewMessage, "message")
        .retype(TLRPC.Message::class.java, Any::class.java)

@JvmField
val SendMessageChatArguments =
    getClassIfExists("org.telegram.messenger.SendMessageChatArguments")

@JvmField
val `SendMessageChatArguments$EMPTY` =
    getFieldGetterIfExists(SendMessageChatArguments, "EMPTY")
        ?.retype(Any::class.java)
        ?.invokeExact()

@JvmField
val `SendMessagesHelper$prepareSendingText` =
    if (isClientVersionBelow("12.2.0"))
        SendMessagesHelper::class.java.getDeclaredMethod(
            "prepareSendingText",
            AccountInstance::class.java,
            String::class.java,
            Long::class.java,
            Boolean::class.java,
            Int::class.java,
            Long::class.java
        )
    else if (isClientVersionBelow("12.7.0"))
        SendMessagesHelper::class.java.getDeclaredMethod(
            "prepareSendingText",
            AccountInstance::class.java,
            String::class.java,
            Long::class.java,
            Boolean::class.java,
            Int::class.java,
            Int::class.java,
            Long::class.java
        )
    else
        SendMessagesHelper::class.java.getDeclaredMethod(
            "prepareSendingText",
            AccountInstance::class.java,
            CharSequence::class.java,
            Long::class.java,
            Boolean::class.java,
            Int::class.java,
            Int::class.java,
            Long::class.java
        )

@JvmField
val `SendMessagesHelper$prepareSendingDocuments` =
    if (isClientVersionBelow("12.2.0"))
        SendMessagesHelper::class.java.getDeclaredMethod(
            "prepareSendingDocuments",
            AccountInstance::class.java,
            ArrayList::class.java,
            ArrayList::class.java,
            ArrayList::class.java,
            String::class.java,
            ArrayList::class.java,
            String::class.java,
            Long::class.java,
            MessageObject::class.java,
            MessageObject::class.java,
            TL_stories.StoryItem::class.java,
            ChatActivity.ReplyQuote::class.java,
            MessageObject::class.java,
            Boolean::class.java,
            Int::class.java,
            InputContentInfoCompat::class.java,
            String::class.java,
            Int::class.java,
            Long::class.java,
            Boolean::class.java,
            Long::class.java,
            Long::class.java,
            MessageSuggestionParams::class.java
        )
    else if (isClientVersionBelow("12.10.0"))
        SendMessagesHelper::class.java.getDeclaredMethod(
            "prepareSendingDocuments",
            AccountInstance::class.java,
            ArrayList::class.java,
            ArrayList::class.java,
            ArrayList::class.java,
            String::class.java,
            ArrayList::class.java,
            String::class.java,
            Long::class.java,
            MessageObject::class.java,
            MessageObject::class.java,
            TL_stories.StoryItem::class.java,
            ChatActivity.ReplyQuote::class.java,
            MessageObject::class.java,
            Boolean::class.java,
            Int::class.java,
            Int::class.java,
            InputContentInfoCompat::class.java,
            String::class.java,
            Int::class.java,
            Long::class.java,
            Boolean::class.java,
            Long::class.java,
            Long::class.java,
            MessageSuggestionParams::class.java
        )
    else
        SendMessagesHelper::class.java.getDeclaredMethod(
            "prepareSendingDocuments",
            AccountInstance::class.java,
            ArrayList::class.java,
            ArrayList::class.java,
            ArrayList::class.java,
            String::class.java,
            ArrayList::class.java,
            String::class.java,
            Long::class.java,
            MessageObject::class.java,
            MessageObject::class.java,
            TL_stories.StoryItem::class.java,
            ChatActivity.ReplyQuote::class.java,
            MessageObject::class.java,
            Boolean::class.java,
            Int::class.java,
            Int::class.java,
            InputContentInfoCompat::class.java,
            SendMessageChatArguments,
            Long::class.java,
            Boolean::class.java,
            Long::class.java,
            Long::class.java,
            MessageSuggestionParams::class.java
        )
