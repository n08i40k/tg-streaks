package ru.n08i40k.streaks.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DividerCell
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RadioButton
import org.telegram.ui.Components.RecyclerListView
import kotlinx.datetime.TimeZone
import ru.n08i40k.streaks.extension.rawOffset
import ru.n08i40k.streaks.extension.setSectionsCompat
import ru.n08i40k.streaks.extension.toDisplayName
import ru.n08i40k.streaks.extension.toLocalStartString
import ru.n08i40k.streaks.extension.toOffsetString
import ru.n08i40k.streaks.i18n.Strings

class TimeZoneSelectFragment(private val adapter: Adapter) : BaseFragment() {
    interface Adapter {
        fun getCurrentTimeZone(): TimeZone
        fun setCurrentTimeZone(value: TimeZone)

        fun hasPet(): Boolean
    }

    enum class Row {
        DESC,
        LOCAL_TIME_ZONE,
        DIVIDER,

        COUNT
    }

    companion object {
        private const val TYPE_DESCRIPTION = 0
        private const val TYPE_TIME_ZONE = 1
        private const val TYPE_DIVIDER = 2

        private const val DONE_BUTTON_ID = 1

        private val TIME_ZONES = listOf(
            "GMT-08:00",
            "GMT-07:30", "GMT-07:00",
            "GMT-06:30", "GMT-06:00",
            "GMT-05:30", "GMT-05:00",
            "GMT-04:30", "GMT-04:00",
            "GMT-03:30", "GMT-03:00",
            "GMT-02:30", "GMT-02:00",
            "GMT-01:30", "GMT-01:00",
            "GMT-00:30", "GMT-00:00", "GMT+00:30",
            "GMT+01:00", "GMT+01:30",
            "GMT+02:00", "GMT+02:30",
            "GMT+03:00", "GMT+03:30",
            "GMT+04:00", "GMT+04:30",
            "GMT+05:00", "GMT+05:30",
            "GMT+06:00", "GMT+06:30",
            "GMT+07:00", "GMT+07:30",
            "GMT+08:00",
        ).map(TimeZone::of)
    }

    private lateinit var listView: RecyclerListView
    private lateinit var listAdapter: ListAdapter

    private lateinit var originalTimeZone: TimeZone
    private lateinit var timeZone: TimeZone
    private lateinit var doneItem: ActionBarMenuItem

    private inner class ListAdapter(
        private val context: Context
    ) : RecyclerListView.SelectionAdapter() {
        override fun getItemCount() = Row.COUNT.ordinal + TIME_ZONES.size

        override fun isEnabled(holder: RecyclerView.ViewHolder) = when (holder.itemViewType) {
            TYPE_TIME_ZONE -> true
            else -> false
        }

        override fun getItemViewType(position: Int) = when (position) {
            Row.DESC.ordinal -> TYPE_DESCRIPTION
            Row.DIVIDER.ordinal -> TYPE_DIVIDER
            else -> TYPE_TIME_ZONE
        }


        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                TYPE_DESCRIPTION -> TextInfoPrivacyCell(context)
                TYPE_TIME_ZONE -> TimeZoneCell(context)
                TYPE_DIVIDER -> DividerCell(context)
                else -> View(context)
            }

            return RecyclerListView.Holder(view)
        }


        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

            when (position) {
                Row.DESC.ordinal -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_timezone_select_desc()

                Row.DIVIDER.ordinal -> return

                else -> (holder.itemView as TimeZoneCell)
                    .apply {
                        val timeZone = getTimeZoneByPosition(position)
                        setTimeZone(timeZone)
                        setIsSelected(timeZone.rawOffset == this@TimeZoneSelectFragment.timeZone.rawOffset)
                    }
            }
        }

    }

    class TimeZoneCell(
        context: Context,
        private val resourcesProvider: Theme.ResourcesProvider?
    ) : LinearLayout(context) {
        private var timeZone = TimeZone.currentSystemDefault()
        private var isSelected = false

        private val offsetTextView = TextView(context)
        private val nameTextView = TextView(context)
        private val localTextView = TextView(context)
        private val radioButton = RadioButton(context)

        constructor(context: Context) : this(context, null)

        fun setTimeZone(timeZone: TimeZone) {
            this.timeZone = timeZone

            offsetTextView.text = timeZone.toOffsetString()
            nameTextView.text = timeZone.toDisplayName()
            localTextView.text = timeZone.toLocalStartString()
        }

        fun setIsSelected(isSelected: Boolean) {
            this.isSelected = isSelected
            radioButton.setChecked(isSelected, true)
        }

        init {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )

            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(21, 21, 21, 21)

            offsetTextView.apply {
                layoutParams = LayoutParams(dp(52f), LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER

                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(
                    Theme.getColor(
                        Theme.key_windowBackgroundWhiteGrayText2,
                        resourcesProvider
                    )
                )
                setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
                setLines(1)
                maxLines = 1
                isSingleLine = true

                addView(this)
            }

            nameTextView.apply {
                layoutParams = LayoutParams(
                    0,
                    LayoutParams.WRAP_CONTENT,
                    1f
                )

                setPadding(21, 0, 21, 0)

                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(
                    Theme.getColor(
                        Theme.key_windowBackgroundWhiteBlackText,
                        resourcesProvider
                    )
                )
                setTypeface(Typeface.DEFAULT_BOLD)
                setLines(1)
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END

                addView(this)
            }

            localTextView.apply {
                layoutParams = LayoutParams(dp(52f), LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER

                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(
                    Theme.getColor(
                        Theme.key_windowBackgroundWhiteGrayText2,
                        resourcesProvider
                    )
                )
                setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
                setLines(1)
                maxLines = 1
                isSingleLine = true

                addView(this)
            }

            radioButton.apply {
                layoutParams = LayoutParams(dp(24f), dp(24f))

                setSize(dp(24f))
                setColor(
                    Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                    Theme.getColor(Theme.key_radioBackgroundChecked, resourcesProvider)
                )

                addView(this)
            }
        }
    }

    override fun createView(context: Context): View {
        originalTimeZone = adapter.getCurrentTimeZone()
        timeZone = originalTimeZone

        listAdapter = ListAdapter(context)

        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle(Strings.menu_timezone_select_title())
        actionBar.actionBarMenuOnItemClick = object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    DONE_BUTTON_ID -> showTimeZoneChangeConfirmDialog()
                }
            }
        }

        doneItem = actionBar.createMenu().addItem(DONE_BUTTON_ID, R.drawable.ic_ab_done)
        doneItem.visibility = View.GONE

        listView = RecyclerListView(context)

        listView.adapter = listAdapter
        listView.isVerticalScrollBarEnabled = true
        listView.layoutManager = LinearLayoutManager(
            context,
            LinearLayoutManager.VERTICAL,
            false
        )

        listView.setSectionsCompat()
        listView.setOnItemClickListener { view, position ->
            if (!view.isEnabled) return@setOnItemClickListener
            onRowClicked(position)
        }

        fragmentView = FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider))
            addView(listView, LayoutHelper.createFrame(-1, -1f))
        }

        return fragmentView
    }

    private fun showTimeZoneChangeConfirmDialog() {
        val activity = parentActivity ?: return

        val message = if (adapter.hasPet())
            Strings.dialog_control_time_zone_change_message_with_pet()
        else
            Strings.dialog_control_time_zone_change_message()

        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(Strings.dialog_control_time_zone_change_title())
                .setMessage(message)
                .setPositiveButton(
                    Strings.dialog_control_time_zone_change_button()
                ) { _, _ ->
                    adapter.setCurrentTimeZone(timeZone)
                    finishFragment()
                }
                .setNegativeButton(Strings.dialog_control_cancel(), null)
                .create()
        )
    }

    fun getTimeZoneByPosition(position: Int): TimeZone =
        when (position) {
            Row.DESC.ordinal, Row.DIVIDER.ordinal -> throw IllegalArgumentException("Unable to get timezone of non-timezone element")
            Row.LOCAL_TIME_ZONE.ordinal -> TimeZone.currentSystemDefault()
            else -> TIME_ZONES[position - Row.COUNT.ordinal]
        }

    private fun onRowClicked(newPosition: Int) {
        when (newPosition) {
            Row.DESC.ordinal, Row.DIVIDER.ordinal -> return
            else -> {
                val newTimeZone = getTimeZoneByPosition(newPosition)

                if (timeZone.rawOffset == newTimeZone.rawOffset)
                    return

                val oldLocalPosition = Row.LOCAL_TIME_ZONE.ordinal
                val oldGmtPosition = TIME_ZONES.indexOfFirst { it.rawOffset == timeZone.rawOffset }
                    .let { if (it >= 0) it + Row.COUNT.ordinal else -1 }

                timeZone = newTimeZone
                doneItem.visibility = if (timeZone.rawOffset == originalTimeZone.rawOffset)
                    View.GONE
                else
                    View.VISIBLE

                val listAdapter = listAdapter

                listAdapter.notifyItemChanged(oldLocalPosition)
                listAdapter.notifyItemChanged(oldGmtPosition)
                listAdapter.notifyItemChanged(newPosition)
            }
        }
    }

}
