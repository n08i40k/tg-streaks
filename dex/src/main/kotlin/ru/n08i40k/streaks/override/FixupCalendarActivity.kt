package ru.n08i40k.streaks.override

import android.content.Context
import android.graphics.Paint
import android.os.Bundle
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.CalendarActivity
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.RecyclerListView
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.controller.StreaksController
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.extension.next
import ru.n08i40k.streaks.extension.toLocalDate
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.setFieldValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import java.util.concurrent.atomic.AtomicReference

class FixupCalendarActivity : CalendarActivity {
    companion object {
        private const val BOTH_ACTIVITY_COLOR = 0xFF34C759.toInt()
        private const val PRE_REVIVE_ACTIVITY_COLOR = 0xFFFFCC00.toInt()
        private const val REVIVE_ACTIVITY_COLOR = 0xFF5AC8FA.toInt()
        private const val ACTIVITY_COLOR_ALPHA = 170

        private val monthViewClass: Class<*> by lazy {
            Class.forName($$"org.telegram.ui.CalendarActivity$MonthView")
        }

        private val currentYearField by lazy {
            monthViewClass.getDeclaredField("currentYear").apply { isAccessible = true }
        }

        private val currentMonthInYearField by lazy {
            monthViewClass.getDeclaredField("currentMonthInYear").apply { isAccessible = true }
        }

        private val daysInMonthField by lazy {
            monthViewClass.getDeclaredField("daysInMonth").apply { isAccessible = true }
        }

        private val startDayOfWeekField by lazy {
            monthViewClass.getDeclaredField("startDayOfWeek").apply { isAccessible = true }
        }

        private val gestureDetectorField by lazy {
            monthViewClass.getDeclaredField("gestureDetector").apply { isAccessible = true }
        }

        private val gestureDetectorCompatClass: Class<*> by lazy {
            Class.forName("androidx.core.view.GestureDetectorCompat")
        }

        private val thisClass = CalendarActivity::class.java

        fun create(peerUserId: Long, chatActivity: ChatActivity): FixupCalendarActivity {
            val bundle = Bundle()
            bundle.putLong("dialog_id", peerUserId)
            bundle.putLong("topic_id", 0)
            bundle.putInt("type", 0)

            return FixupCalendarActivity(bundle, 0, 0)
                .apply { setChatActivity(chatActivity) }
        }
    }

    private val peerUserId: Long
    private val activeDaysByMonthKey = SparseArray<SparseBooleanArray>()
    private val cachedRevivesByMonthKey = SparseArray<SparseBooleanArray>()
    private val decoratedListViewRef = AtomicReference<RecyclerListView?>()
    private val interceptedListViewRef = AtomicReference<RecyclerListView?>()
    private val activityPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private var messagesByYearMonthProxy: MessagesByYearMonthProxy? = null
    private var cachedSnapshotLoaded = false
    private var manualRevivesUsed = 0
    private val activityStatusDecoration =
        object : RecyclerView.ItemDecoration() {
            override fun onDraw(
                canvas: android.graphics.Canvas,
                parent: RecyclerView,
                state: RecyclerView.State,
            ) {
                super.onDraw(canvas, parent, state)

                val rowHeight = AndroidUtilities.dp(52f).toFloat()
                val dayCircleSize = AndroidUtilities.dp(44f).toFloat()
                val dayCircleRadius = dayCircleSize / 2f

                for (childIndex in 0 until parent.childCount) {
                    val monthView = parent.getChildAt(childIndex)
                    if (!monthViewClass.isInstance(monthView)) {
                        continue
                    }

                    val year = currentYearField.getInt(monthView)
                    val monthIndex = currentMonthInYearField.getInt(monthView)
                    val daysInMonth = daysInMonthField.getInt(monthView)
                    var column = startDayOfWeekField.getInt(monthView)
                    var row = 0

                    for (dayIndex in 0 until daysInMonth) {
                        val color = resolveActivityColor(year, monthIndex, dayIndex)

                        if (color != null) {
                            val cellWidth = monthView.measuredWidth / 7f
                            val centerX = monthView.left + column * cellWidth + cellWidth / 2f
                            val centerY =
                                monthView.top + row * rowHeight + rowHeight / 2f + dayCircleSize

                            activityPaint.color = color
                            activityPaint.alpha = ACTIVITY_COLOR_ALPHA
                            canvas.drawCircle(centerX, centerY, dayCircleRadius, activityPaint)
                        }

                        column += 1
                        if (column >= 7) {
                            column = 0
                            row += 1
                        }
                    }
                }
            }
        }

    constructor(bundle: Bundle, p1: Int, p2: Int) : super(bundle, p1, p2) {
        peerUserId = bundle.getLong("dialog_id")
    }

    override fun onFragmentCreate(): Boolean {
        val created = super.onFragmentCreate()
        if (created) {
            installMessagesByYearMonthProxy()
        }
        return created
    }

    override fun createView(context: Context): View {
        val view = super.createView(context)

        val listView = getFieldValue<RecyclerListView>(thisClass, this, "listView")
            ?: return view

        disableBuiltInSelectionUi()
        installActivityStatusDecoration(listView)
        installMonthViewInterceptors(listView)
        loadCachedSnapshot(listView)
        return view
    }

    private fun disableBuiltInSelectionUi() {
        getFieldValue<View>(thisClass, this, "bottomBar")
            ?.apply {
                visibility = View.GONE
                isEnabled = false
            }
        getFieldValue<View>(thisClass, this, "selectDaysButton")
            ?.apply {
                visibility = View.GONE
                isEnabled = false
            }
        getFieldValue<View>(thisClass, this, "removeDaysButton")
            ?.apply {
                visibility = View.GONE
                isEnabled = false
            }

        setFieldValue(thisClass, this, "inSelectionMode", false)
        setFieldValue(thisClass, this, "dateSelectedStart", 0)
        setFieldValue(thisClass, this, "dateSelectedEnd", 0)
    }

    private fun installActivityStatusDecoration(listView: RecyclerListView) {
        if (decoratedListViewRef.get() === listView) {
            return
        }

        decoratedListViewRef.set(listView)
        listView.post { listView.addItemDecoration(activityStatusDecoration) }
    }

    private fun installMonthViewInterceptors(listView: RecyclerListView) {
        if (interceptedListViewRef.get() === listView) {
            return
        }

        interceptedListViewRef.set(listView)
        listView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    patchMonthViewInteraction(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )

        for (index in 0 until listView.childCount) {
            patchMonthViewInteraction(listView.getChildAt(index))
        }
    }

    private fun patchMonthViewInteraction(view: View) {
        if (!monthViewClass.isInstance(view)) {
            return
        }

        val gestureDetector =
            gestureDetectorCompatClass
                .getConstructor(Context::class.java, GestureDetector.OnGestureListener::class.java)
                .newInstance(
                    view.context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean = true

                        override fun onLongPress(e: MotionEvent) = Unit

                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            val day = findDayAtCoord(view, e.x, e.y) ?: return true
                            handleDayTap(day)
                            return true
                        }
                    }
                )

        gestureDetectorCompatClass
            .getMethod("setIsLongpressEnabled", Boolean::class.javaPrimitiveType)
            .invoke(gestureDetector, false)
        gestureDetectorField.set(view, gestureDetector)
    }

    private fun handleDayTap(day: LocalDate) {
        if (peerUserId <= 0L) {
            return
        }

        val accountId = UserConfig.selectedAccount
        Plugin.getInstance().backgroundScope.launch {
            val decision =
                Plugin.getInstance()
                    .streaksController
                    .analyzeCalendarTap(accountId, peerUserId, day)

            AndroidUtilities.runOnUIThread {
                when (decision) {
                    is StreaksController.CalendarTapDecision.Ignore -> Unit
                    is StreaksController.CalendarTapDecision.LimitReached ->
                        showInfoOnlyDialog(
                            Strings.dialog_calendar_fix_limit_reached_title(),
                            Strings.dialog_calendar_fix_limit_reached_message(),
                        )

                    is StreaksController.CalendarTapDecision.WarnTapNextDay ->
                        showInfoOnlyDialog(
                            Strings.dialog_calendar_fix_warning_next_day_title(),
                            Strings.dialog_calendar_fix_warning_next_day_message(),
                        )

                    is StreaksController.CalendarTapDecision.OfferManualRevive ->
                        showManualReviveOfferDialog(decision.reviveDay, decision.reason)
                }
            }
        }
    }

    private fun showManualReviveOfferDialog(
        day: LocalDate,
        reason: StreaksController.CalendarTapDecision.Reason,
    ) {
        val context = context ?: parentActivity ?: return
        val messageKey =
            when (reason) {
                StreaksController.CalendarTapDecision.Reason.FIRST_LIVE_DAY_AFTER_UNRESTORED_GAP ->
                    Strings.dialog_calendar_fix_manual_revive_message_gap

                StreaksController.CalendarTapDecision.Reason.DEAD_CHAIN_RESTORE ->
                    Strings.dialog_calendar_fix_manual_revive_message_dead_chain
            }

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(Strings.dialog_calendar_fix_manual_revive_title())
                .setMessage(messageKey())
                .setPositiveButton(Strings.dialog_calendar_fix_confirm()) { _, _ ->
                    persistManualRevive(day)
                }
                .setNegativeButton(Strings.dialog_calendar_fix_cancel(), null)
                .create()
        )
    }

    private fun persistManualRevive(day: LocalDate) {
        val accountId = UserConfig.selectedAccount

        Plugin.getInstance().backgroundScope.launch {
            val result =
                Plugin.getInstance()
                    .streaksController
                    .addManualCalendarRevive(accountId, peerUserId, day)

            AndroidUtilities.runOnUIThread {
                when (result) {
                    StreaksController.AddManualCalendarReviveResult.Added,
                    StreaksController.AddManualCalendarReviveResult.AlreadyExists -> {
                        reloadSnapshot()
                        showRebuildOfferDialog()
                    }

                    StreaksController.AddManualCalendarReviveResult.LimitReached ->
                        showInfoOnlyDialog(
                            Strings.dialog_calendar_fix_limit_reached_title(),
                            Strings.dialog_calendar_fix_limit_reached_message(),
                        )
                }
            }
        }
    }

    private fun showRebuildOfferDialog() {
        val context = context ?: parentActivity ?: return

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(Strings.dialog_calendar_fix_rebuild_title())
                .setMessage(Strings.dialog_calendar_fix_rebuild_message())
                .setPositiveButton(Strings.dialog_calendar_fix_rebuild_now()) { _, _ ->
                    Plugin.getInstance().enqueueRebuildForPeer(
                        UserConfig.selectedAccount,
                        peerUserId,
                    ) {
                        reloadSnapshot()
                    }
                }
                .setNegativeButton(Strings.dialog_calendar_fix_later(), null)
                .create()
        )
    }

    private fun showInfoOnlyDialog(
        title: String,
        message: String,
    ) {
        val context = context ?: parentActivity ?: return

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(Strings.dialog_calendar_fix_ok(), null)
                .create()
        )
    }

    private fun reloadSnapshot() {
        val listView = decoratedListViewRef.get() ?: return
        loadCachedSnapshot(listView, force = true)
    }

    private fun loadCachedSnapshot(
        listView: RecyclerListView,
        force: Boolean = false,
    ) {
        if ((!force && cachedSnapshotLoaded) || peerUserId <= 0L) {
            return
        }

        cachedSnapshotLoaded = true
        val accountId = UserConfig.selectedAccount

        Plugin.getInstance().backgroundScope.launch {
            val snapshot =
                Plugin.getInstance()
                    .streaksController
                    .getCalendarInteractionSnapshot(accountId, peerUserId)

            val newActiveByMonthKey = SparseArray<SparseBooleanArray>()
            val newRevivesByMonthKey = SparseArray<SparseBooleanArray>()

            fun monthKey(day: LocalDate): Int =
                day.year * 100 + (day.month.number - 1)

            fun markDay(map: SparseArray<SparseBooleanArray>, day: LocalDate) {
                val monthKey = monthKey(day)
                val daysForMonth =
                    map.get(monthKey) ?: SparseBooleanArray().also { map.put(monthKey, it) }

                daysForMonth.put(day.day - 1, true)
            }

            snapshot.streak?.let { streak ->
                val start = streak.createdAt.toLocalDate(snapshot.timeZone)
                val lastAliveDay = minOf(
                    streak.updateFromOwnerAt.toLocalDate(snapshot.timeZone),
                    streak.updateFromPeerAt.toLocalDate(snapshot.timeZone),
                )

                var day = start
                while (day <= lastAliveDay) {
                    markDay(newActiveByMonthKey, day)
                    day = day.next()
                }
            }

            for (revivedDay in snapshot.revivedDays) {
                markDay(newRevivesByMonthKey, revivedDay)
            }

            AndroidUtilities.runOnUIThread {
                activeDaysByMonthKey.clear()
                cachedRevivesByMonthKey.clear()
                manualRevivesUsed = snapshot.manualRevivesUsed

                for (index in 0 until newActiveByMonthKey.size()) {
                    val monthKey = newActiveByMonthKey.keyAt(index)
                    activeDaysByMonthKey.put(monthKey, newActiveByMonthKey.valueAt(index))
                }

                for (index in 0 until newRevivesByMonthKey.size()) {
                    val monthKey = newRevivesByMonthKey.keyAt(index)
                    cachedRevivesByMonthKey.put(monthKey, newRevivesByMonthKey.valueAt(index))
                }

                messagesByYearMonthProxy?.repatchAll()
                listView.invalidate()
                listView.post { listView.invalidateItemDecorations() }
            }
        }
    }

    private fun findDayAtCoord(
        monthView: View,
        x: Float,
        y: Float,
    ): LocalDate? {
        if (!monthViewClass.isInstance(monthView) || x < 0f || y < 0f) {
            return null
        }

        val cellWidth = monthView.measuredWidth / 7f
        val rowHeight = AndroidUtilities.dp(52f).toFloat()
        val firstRowOffset = AndroidUtilities.dp(44f).toFloat()
        val contentY = y - firstRowOffset

        if (cellWidth <= 0f || contentY < 0f || x >= monthView.measuredWidth) {
            return null
        }

        val column = (x / cellWidth).toInt()
        val row = (contentY / rowHeight).toInt()
        val startDayOfWeek = startDayOfWeekField.getInt(monthView)
        val dayIndex = row * 7 + column - startDayOfWeek
        val daysInMonth = daysInMonthField.getInt(monthView)

        if (column !in 0..6 || dayIndex !in 0 until daysInMonth) {
            return null
        }

        val year = currentYearField.getInt(monthView)
        val monthIndex = currentMonthInYearField.getInt(monthView)
        return LocalDate(year, monthIndex + 1, dayIndex + 1)
    }

    private fun isActiveDay(monthKey: Int, dayIndex: Int): Boolean =
        activeDaysByMonthKey.get(monthKey)?.get(dayIndex, false) ?: false

    private fun isCachedRevived(monthKey: Int, dayIndex: Int): Boolean =
        cachedRevivesByMonthKey.get(monthKey)?.get(dayIndex, false) ?: false

    private fun isNextDayRevived(year: Int, monthIndex: Int, dayIndex: Int): Boolean {
        val nextDay = LocalDate(year, monthIndex + 1, dayIndex + 1).next()
        val nextMonthKey = nextDay.year * 100 + (nextDay.month.number - 1)
        val nextDayIndex = nextDay.day - 1
        return isCachedRevived(nextMonthKey, nextDayIndex)
    }

    private fun shouldDecorateDay(year: Int, monthIndex: Int, dayIndex: Int): Boolean {
        val monthKey = year * 100 + monthIndex
        return isCachedRevived(monthKey, dayIndex) ||
                isNextDayRevived(year, monthIndex, dayIndex) ||
                isActiveDay(monthKey, dayIndex)
    }

    private fun resolveActivityColor(year: Int, monthIndex: Int, dayIndex: Int): Int? {
        val monthKey = year * 100 + monthIndex
        if (isCachedRevived(monthKey, dayIndex)) {
            return REVIVE_ACTIVITY_COLOR
        }
        if (isNextDayRevived(year, monthIndex, dayIndex)) {
            return PRE_REVIVE_ACTIVITY_COLOR
        }
        if (isActiveDay(monthKey, dayIndex)) {
            return BOTH_ACTIVITY_COLOR
        }

        return null
    }

    private fun installMessagesByYearMonthProxy() {
        if (messagesByYearMonthProxy != null) {
            return
        }

        val original = getFieldValue<SparseArray<Any>>(thisClass, this, "messagesByYearMounth")
            ?: return

        if (original is MessagesByYearMonthProxy) {
            messagesByYearMonthProxy = original
            return
        }

        val proxy = MessagesByYearMonthProxy().apply { copyFrom(original) }
        messagesByYearMonthProxy = proxy
        setFieldValue(thisClass, this, "messagesByYearMounth", proxy)
    }

    private fun patchPeriodDay(monthKey: Int, dayIndex: Int, value: Any?): Any? {
        if (value == null)
            return null

        val year = monthKey / 100
        val monthIndex = monthKey % 100

        if (shouldDecorateDay(year, monthIndex, dayIndex))
            setFieldValue(value, "hasImage", false)

        return value
    }

    private inner class DayMessagesSparseArray(
        private val monthKey: Int,
    ) : SparseArray<Any>() {
        override fun put(key: Int, value: Any?) {
            super.put(key, patchPeriodDay(monthKey, key, value))
        }

        fun copyFrom(source: SparseArray<Any>) {
            clear()

            for (index in 0 until source.size()) {
                put(source.keyAt(index), source.valueAt(index))
            }
        }

        fun repatchAll() {
            val keys = IntArray(size())
            val values = arrayOfNulls<Any>(size())

            for (index in 0 until size()) {
                keys[index] = keyAt(index)
                values[index] = valueAt(index)
            }

            clear()

            for (index in keys.indices) {
                put(keys[index], values[index])
            }
        }
    }

    private inner class MessagesByYearMonthProxy : SparseArray<Any>() {
        override fun put(key: Int, value: Any?) {
            super.put(key, wrapMonth(key, value))
        }

        private fun wrapMonth(monthKey: Int, value: Any?): Any? {
            if (value == null) {
                return null
            }

            if (value is DayMessagesSparseArray) {
                return value
            }

            @Suppress("UNCHECKED_CAST")
            val monthDays = value as? SparseArray<Any> ?: return value
            return DayMessagesSparseArray(monthKey).apply { copyFrom(monthDays) }
        }

        fun copyFrom(source: SparseArray<Any>) {
            clear()

            for (index in 0 until source.size()) {
                put(source.keyAt(index), source.valueAt(index))
            }
        }

        fun repatchAll() {
            for (index in 0 until size()) {
                val monthKey = keyAt(index)
                val value = valueAt(index)

                if (value is DayMessagesSparseArray) {
                    value.repatchAll()
                    continue
                }

                put(monthKey, value)
            }
        }
    }
}
