package ru.n08i40k.streaks.ui.levelPack.edit

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.data.StreakLevelPack
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.onEachWithOnMainThread
import ru.n08i40k.streaks.extension.setSectionsCompat
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.dp
import ru.n08i40k.streaks.util.runOnMainThread
import java.util.SortedMap

class StreakLevelPackEditFragment(private val viewModel: ViewModel) : BaseFragment(),
    EjectNotifier.Delegate {

    data class ViewState(
        val levelPack: StreakLevelPack,
    )

    interface ViewModel {
        fun state(): Flow<ViewState>

        fun update(levelPack: StreakLevelPack)

        fun destroy()
    }

    companion object {
        private const val TYPE_DESCRIPTION = 0
        private const val TYPE_EMOJI = 1
        private const val TYPE_ADD = 2

        private const val DONE_BUTTON_ID = 1

        private const val MAX_NAME_LENGTH = 64
    }

    private data class Item(
        val key: String,
        val oldValue: StreakEmojiInfo?,
        val newValue: StreakEmojiInfo
    ) {
        companion object {
            fun fromSortedMap(
                parentLevelPack: StreakLevelPack,
                map: SortedMap<String, StreakEmojiInfo>
            ): List<Item> =
                map.entries
                    .map { Item(it.key, parentLevelPack.emojis[it.key], it.value) }
                    .toList()
        }
    }

    private inner class ListAdapter(private val context: Context) :
        RecyclerListView.SelectionAdapter() {


        private inner class ItemsCallback(
            private val old: List<Item>,
            private val new: List<Item>
        ) : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size

            override fun getNewListSize(): Int = new.size

            override fun areItemsTheSame(
                oldItemPosition: Int,
                newItemPosition: Int
            ): Boolean = old[oldItemPosition].key == new[newItemPosition].key

            override fun areContentsTheSame(
                oldItemPosition: Int,
                newItemPosition: Int
            ): Boolean = old[oldItemPosition].oldValue == new[newItemPosition].oldValue
                    && old[oldItemPosition].newValue == new[newItemPosition].newValue
        }

        private var items: List<Item> = listOf()

        override fun getItemCount() = items.size + 2

        override fun isEnabled(holder: RecyclerView.ViewHolder) = when (holder.itemViewType) {
            TYPE_EMOJI, TYPE_ADD -> true
            else -> false
        }

        override fun getItemViewType(position: Int) = when (position) {
            in items.indices -> TYPE_EMOJI
            items.size -> TYPE_ADD
            else -> TYPE_DESCRIPTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                TYPE_ADD -> TextView(context).apply {
                    text = Strings.menu_streak_level_pack_edit_level_add()
                    setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText))
                    setTextSize(16f)
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                    setOnClickListener { editThreshold(null) }
                }

                TYPE_DESCRIPTION -> TextInfoPrivacyCell(context)

                TYPE_EMOJI -> EditableStreakEmojiCell(context)

                else -> View(context)
            }

            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                in items.indices -> (holder.itemView as EditableStreakEmojiCell)
                    .apply {
                        val emoji = items[position]

                        setName(
                            levelPack!!.findById(emoji.key)?.name
                                ?: Strings.streak_level_days_name(
                                    StreakLevelPack.extractLength(
                                        emoji.key
                                    )
                                )
                        )
                        setOnNameClickListener(
                            if (emoji.key in listOf("cold", "3d")) null
                            else ({ editThreshold(emoji.key) })
                        )
                        setEmojiInfo(emoji.newValue)
                        setChanged(emoji.oldValue != null && emoji.oldValue != emoji.newValue)
                        setOnResetListener { emoji.oldValue?.let { updateEmoji(emoji.key, it) } }
                        setOnUpdateListener { updateEmoji(emoji.key, it) }
                    }

                items.size -> Unit
                else -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_streak_level_pack_edit_desc()
            }
        }

        fun setItems(items: List<Item>) {
            val diff = DiffUtil.calculateDiff(ItemsCallback(this.items, items))
            this.items = items

            diff.dispatchUpdatesTo(this)
        }
    }

    private val backgroundScope = Plugin.childCoroutineScope()

    // eject
    private val unsubscribe = EjectNotifier.subscribe(this)

    private var levelPack: StreakLevelPack? = null
    private var items: List<Item>? = null

    // views
    private lateinit var listView: RecyclerListView
    private lateinit var doneItem: ActionBarMenuItem
    private lateinit var nameField: EditTextBoldCursor

    private fun setLevelPack(levelPack: StreakLevelPack) {
        if (this.levelPack != null) return
        val parentLevelPack = StreakLevelPack.builtin.find { it.id == levelPack.basedOn }!!

        this.levelPack = levelPack
        this.items = Item.fromSortedMap(parentLevelPack, levelPack.sortedEmojis)

        if (nameField.text.toString() != levelPack.name) {
            nameField.setText(levelPack.name)
            nameField.setSelection(nameField.text.length)
        }

        postItemsUpdate()
    }

    private fun currentName(): String = nameField.text.toString().trim()

    override fun createView(context: Context): View {
        actionBar.apply {
            setBackButtonImage(R.drawable.ic_ab_back)
            setAllowOverlayTitle(false)

            actionBarMenuOnItemClick = object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        DONE_BUTTON_ID -> {
                            val name = currentName()

                            if (name.isEmpty()) {
                                AndroidUtilities.shakeViewSpring(nameField)
                                BulletinHelper.show(Strings.status_info_streak_level_pack_name_empty())
                                return
                            }

                            val newEmojis = items!!.associate { it.key to it.newValue }

                            AndroidUtilities.hideKeyboard(nameField)

                            viewModel.update(
                                levelPack!!.copy(name = name, emojis = newEmojis, version = 2)
                            )
                            finishFragment()
                        }
                    }
                }
            }
        }

        doneItem = actionBar
            .createMenu()
            .addItem(DONE_BUTTON_ID, R.drawable.ic_ab_done)
            .apply { visibility = View.VISIBLE }

        // action bar height includes the status bar, so plain CENTER_VERTICAL would
        // push the field below the back button
        nameField = object : EditTextBoldCursor(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val statusBarHeight = AndroidUtilities.getStatusBarHeight(context)

                if (paddingTop != statusBarHeight)
                    setPadding(0, statusBarHeight, 0, 0)

                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_actionBarDefaultTitle, resourceProvider))
            setHintColor(Theme.getColor(Theme.key_actionBarDefaultSubtitle, resourceProvider))
            setHintText(Strings.menu_streak_level_pack_edit_name_hint())
            setCursorColor(Theme.getColor(Theme.key_actionBarDefaultTitle, resourceProvider))
            setCursorSize(dp(20))
            setCursorWidth(1.5f)

            background = null
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL

            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LENGTH))

            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE)
                    return@setOnEditorActionListener false

                AndroidUtilities.hideKeyboard(this)
                true
            }
        }

        actionBar.addView(
            nameField,
            LayoutHelper.createFrame(
                -1,
                -1f,
                Gravity.LEFT or Gravity.TOP,
                72f, 0f, 64f, 0f
            )
        )

        listView = RecyclerListView(context).apply {
            adapter = ListAdapter(context)

            isVerticalScrollBarEnabled = true
            layoutManager = LinearLayoutManager(
                context,
                LinearLayoutManager.VERTICAL,
                false
            )

            setSectionsCompat()
        }

        fragmentView = FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider))
            addView(listView, LayoutHelper.createFrame(-1, -1f))
        }

        viewModel.state()
            .onEachWithOnMainThread { setLevelPack(levelPack) }
            .launchIn(backgroundScope)

        return fragmentView
    }

    private fun editThreshold(key: String?) {
        val context = context
            ?: return

        val input = EditTextBoldCursor(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            hint = Strings.menu_streak_level_pack_edit_level_days_hint()
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setPadding(dp(24), dp(12), dp(24), dp(12))
            key?.let { setText(StreakLevelPack.extractLength(it).toString()) }
        }

        val builder = AlertDialog.Builder(context, resourceProvider)
            .setTitle(if (key == null) Strings.menu_streak_level_pack_edit_level_add() else Strings.menu_streak_level_pack_edit_level_edit())
            .setView(input)
            .setPositiveButton(Strings.menu_streak_level_pack_edit_level_apply()) { _, _ -> }
            .setNegativeButton(Strings.menu_streak_level_pack_edit_level_cancel()) { _, _ -> }

        if (key != null) {
            builder.setNeutralButton(Strings.menu_streak_level_pack_edit_level_delete()) { _, _ ->
                items = items!!.filterNot { it.key == key }
                postItemsUpdate()
            }
        }

        builder.create()
            .apply {
                this.show()

                getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        val days = input.text
                            .toString()
                            .toIntOrNull()

                        val newKey = "${days}d"

                        if (days == null || days < 4 || items!!.any { it.key == newKey && it.key != key }) {
                            input.error = Strings.menu_streak_level_pack_edit_level_invalid_days()
                            return@setOnClickListener
                        }

                        val old = items!!.find { it.key == key }

                        val value = old?.newValue
                            ?: items!!
                                .last { StreakLevelPack.extractLength(it.key) <= days }
                                .newValue

                        val parent = StreakLevelPack.builtin
                            .first { it.id == levelPack!!.basedOn }

                        items =
                            (items!!.filterNot { it.key == key }
                                    + Item(newKey, parent.emojis[newKey], value))
                                .sortedBy { StreakLevelPack.extractLength(it.key) }

                        postItemsUpdate()
                        AndroidUtilities.hideKeyboard(input)

                        dismiss()
                    }
            }

        input.requestFocus()
        AndroidUtilities.showKeyboard(input)
    }

    private fun updateEmoji(key: String, emojiInfo: StreakEmojiInfo) {
        this.items = this.items!!
            .map { if (it.key == key) it.copy(newValue = emojiInfo) else it }
            .toList()

        postItemsUpdate()
    }

    // adapter can't be notified while the list is laying out or scrolling
    private fun postItemsUpdate() =
        listView.post { (listView.adapter as? ListAdapter)?.setItems(items!!) }

    override fun onEject() {
        runOnMainThread { finishFragment() }
    }

    override fun onFragmentClosed() {
        if (::nameField.isInitialized)
            AndroidUtilities.hideKeyboard(nameField)

        unsubscribe()
        backgroundScope.cancel()
        viewModel.destroy()
        super.onFragmentClosed()
    }
}