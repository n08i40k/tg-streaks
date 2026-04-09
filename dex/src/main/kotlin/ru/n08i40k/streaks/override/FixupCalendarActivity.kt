@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.override

import android.content.Context
import android.graphics.Paint
import android.os.Bundle
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.util.SparseIntArray
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
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.controller.StreaksController
import ru.n08i40k.streaks.data.StreakActivityStatus
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.setFieldValue
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

class FixupCalendarActivity : CalendarActivity {
    companion object {
        private const val BOTH_ACTIVITY_COLOR = 0xFF34C759.toInt()
        private const val ONE_SIDED_OR_EMPTY_ACTIVITY_COLOR = 0xFFFF3B30.toInt()
        private const val PRE_REVIVE_ACTIVITY_COLOR = 0xFFFFCC00.toInt()
        private const val REVIVE_ACTIVITY_COLOR = 0xFF5AC8FA.toInt()
        private const val ACTIVITY_COLOR_ALPHA = 170

        private val monthViewClass: Class<*> by lazy {
            Class.forName("org.telegram.ui.CalendarActivity\$MonthView")
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

        private val periodDayClass: Class<*> by lazy {
            Class.forName("org.telegram.ui.CalendarActivity\$PeriodDay")
        }

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
    private val cachedStatusesByMonthKey = SparseArray<SparseIntArray>()
    private val cachedRevivesByMonthKey = SparseArray<SparseBooleanArray>()
    private val decoratedListViewRef = AtomicReference<RecyclerListView?>()
    private val interceptedListViewRef = AtomicReference<RecyclerListView?>()
    private val activityPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private var messagesByYearMonthProxy: MessagesByYearMonthProxy? = null
    private var cachedSnapshotLoaded = false
    private var streakStartDate: LocalDate? = null
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
        val listView =
            getFieldValue<RecyclerListView>(
                CalendarActivity::class.java,
                this,
                "listView"
            ) ?: return view

        disableBuiltInSelectionUi()
        installActivityStatusDecoration(listView)
        installMonthViewInterceptors(listView)
        loadCachedSnapshot(listView)
        return view
    }

    private fun disableBuiltInSelectionUi() {
        getFieldValue<View>(CalendarActivity::class.java, this, "bottomBar")?.apply {
            visibility = View.GONE
            isEnabled = false
        }
        getFieldValue<View>(CalendarActivity::class.java, this, "selectDaysButton")?.apply {
            visibility = View.GONE
            isEnabled = false
        }
        getFieldValue<View>(CalendarActivity::class.java, this, "removeDaysButton")?.apply {
            visibility = View.GONE
            isEnabled = false
        }

        setFieldValue(CalendarActivity::class.java, this, "inSelectionMode", false)
        setFieldValue(CalendarActivity::class.java, this, "dateSelectedStart", 0)
        setFieldValue(CalendarActivity::class.java, this, "dateSelectedEnd", 0)
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
        if (peerUserId <= 0L || streakStartDate?.let { day.isBefore(it) } == true) {
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
                            TranslationKey.Dialog.CalendarFix.LIMIT_REACHED_TITLE,
                            TranslationKey.Dialog.CalendarFix.LIMIT_REACHED_MESSAGE,
                        )

                    is StreaksController.CalendarTapDecision.WarnTapNextDay ->
                        showInfoOnlyDialog(
                            TranslationKey.Dialog.CalendarFix.WARNING_NEXT_DAY_TITLE,
                            TranslationKey.Dialog.CalendarFix.WARNING_NEXT_DAY_MESSAGE,
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
                    TranslationKey.Dialog.CalendarFix.MANUAL_REVIVE_MESSAGE_GAP

                StreaksController.CalendarTapDecision.Reason.DEAD_CHAIN_RESTORE ->
                    TranslationKey.Dialog.CalendarFix.MANUAL_REVIVE_MESSAGE_DEAD_CHAIN
            }

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(t(TranslationKey.Dialog.CalendarFix.MANUAL_REVIVE_TITLE))
                .setMessage(t(messageKey))
                .setPositiveButton(t(TranslationKey.Dialog.CalendarFix.CONFIRM)) { _, _ ->
                    persistManualRevive(day)
                }
                .setNegativeButton(t(TranslationKey.Dialog.CalendarFix.CANCEL), null)
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
                            TranslationKey.Dialog.CalendarFix.LIMIT_REACHED_TITLE,
                            TranslationKey.Dialog.CalendarFix.LIMIT_REACHED_MESSAGE,
                        )
                }
            }
        }
    }

    private fun showRebuildOfferDialog() {
        val context = context ?: parentActivity ?: return

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(t(TranslationKey.Dialog.CalendarFix.REBUILD_TITLE))
                .setMessage(t(TranslationKey.Dialog.CalendarFix.REBUILD_MESSAGE))
                .setPositiveButton(t(TranslationKey.Dialog.CalendarFix.REBUILD_NOW)) { _, _ ->
                    Plugin.getInstance().enqueueRebuildForPeer(
                        UserConfig.selectedAccount,
                        peerUserId,
                    ) {
                        reloadSnapshot()
                    }
                }
                .setNegativeButton(t(TranslationKey.Dialog.CalendarFix.LATER), null)
                .create()
        )
    }

    private fun showInfoOnlyDialog(
        titleKey: String,
        messageKey: String,
    ) {
        val context = context ?: parentActivity ?: return

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(t(titleKey))
                .setMessage(t(messageKey))
                .setPositiveButton(t(TranslationKey.Dialog.CalendarFix.OK), null)
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

            val newStatusesByMonthKey = SparseArray<SparseIntArray>()
            val newRevivesByMonthKey = SparseArray<SparseBooleanArray>()

            fun monthKey(day: LocalDate): Int =
                day.year * 100 + (day.monthValue - 1)

            for (record in snapshot.cachedActivity) {
                val monthKey = monthKey(record.day)
                val dayIndex = record.day.dayOfMonth - 1
                val statusesForMonth =
                    newStatusesByMonthKey.get(monthKey) ?: SparseIntArray().also {
                        newStatusesByMonthKey.put(monthKey, it)
                    }

                statusesForMonth.put(dayIndex, record.status)
            }

            for (revivedDay in snapshot.revivedDays) {
                val monthKey = monthKey(revivedDay)
                val dayIndex = revivedDay.dayOfMonth - 1
                val revivesForMonth =
                    newRevivesByMonthKey.get(monthKey) ?: SparseBooleanArray().also {
                        newRevivesByMonthKey.put(monthKey, it)
                    }

                revivesForMonth.put(dayIndex, true)
            }

            AndroidUtilities.runOnUIThread {
                cachedStatusesByMonthKey.clear()
                cachedRevivesByMonthKey.clear()
                manualRevivesUsed = snapshot.manualRevivesUsed
                streakStartDate = snapshot.streak?.createdAt

                for (index in 0 until newStatusesByMonthKey.size()) {
                    val monthKey = newStatusesByMonthKey.keyAt(index)
                    cachedStatusesByMonthKey.put(monthKey, newStatusesByMonthKey.valueAt(index))
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
        return LocalDate.of(year, monthIndex + 1, dayIndex + 1)
    }

    private fun getCachedActivityStatus(monthKey: Int, dayIndex: Int): StreakActivityStatus? {
        val monthStatuses = cachedStatusesByMonthKey.get(monthKey) ?: return null
        val code = monthStatuses.get(dayIndex, Int.MIN_VALUE)
        if (code == Int.MIN_VALUE) {
            return null
        }

        return StreakActivityStatus.fromCode(code)
    }

    private fun isCachedRevived(monthKey: Int, dayIndex: Int): Boolean =
        cachedRevivesByMonthKey.get(monthKey)?.get(dayIndex, false) ?: false

    private fun isNextDayRevived(year: Int, monthIndex: Int, dayIndex: Int): Boolean {
        val nextDay = LocalDate.of(year, monthIndex + 1, dayIndex + 1).plusDays(1)
        val nextMonthKey = nextDay.year * 100 + (nextDay.monthValue - 1)
        val nextDayIndex = nextDay.dayOfMonth - 1
        return isCachedRevived(nextMonthKey, nextDayIndex)
    }

    private fun shouldDecorateDay(year: Int, monthIndex: Int, dayIndex: Int): Boolean {
        val day = LocalDate.of(year, monthIndex + 1, dayIndex + 1)
        if (streakStartDate?.let { day.isBefore(it) } == true) {
            return false
        }

        val monthKey = year * 100 + monthIndex
        if (isCachedRevived(monthKey, dayIndex)) {
            return true
        }
        if (isNextDayRevived(year, monthIndex, dayIndex)) {
            return true
        }

        return getCachedActivityStatus(monthKey, dayIndex) != null
    }

    private fun resolveActivityColor(year: Int, monthIndex: Int, dayIndex: Int): Int? {
        if (!shouldDecorateDay(year, monthIndex, dayIndex)) {
            return null
        }

        val monthKey = year * 100 + monthIndex
        if (isCachedRevived(monthKey, dayIndex)) {
            return REVIVE_ACTIVITY_COLOR
        }
        if (isNextDayRevived(year, monthIndex, dayIndex)) {
            return PRE_REVIVE_ACTIVITY_COLOR
        }

        return when (getCachedActivityStatus(monthKey, dayIndex) ?: return null) {
            StreakActivityStatus.BOTH -> BOTH_ACTIVITY_COLOR
            StreakActivityStatus.NO_ACTIVITY,
            StreakActivityStatus.OWNER,
            StreakActivityStatus.PEER,
                -> ONE_SIDED_OR_EMPTY_ACTIVITY_COLOR
        }
    }

    private fun installMessagesByYearMonthProxy() {
        if (messagesByYearMonthProxy != null) {
            return
        }

        val original =
            getFieldValue<SparseArray<Any>>(
                CalendarActivity::class.java,
                this,
                "messagesByYearMounth"
            ) ?: return

        if (original is MessagesByYearMonthProxy) {
            messagesByYearMonthProxy = original
            return
        }

        val proxy = MessagesByYearMonthProxy().apply { copyFrom(original) }
        messagesByYearMonthProxy = proxy
        setFieldValue(CalendarActivity::class.java, this, "messagesByYearMounth", proxy)
    }

    private fun patchPeriodDay(monthKey: Int, dayIndex: Int, value: Any?): Any? {
        if (value == null) {
            return null
        }

        val year = monthKey / 100
        val monthIndex = monthKey % 100

        if (shouldDecorateDay(year, monthIndex, dayIndex)) {
            setFieldValue(periodDayClass, value, "hasImage", false)
        }

        return value
    }

    private fun t(key: String): String =
        Plugin.getInstance().translator.translate(key)

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
