package ru.n08i40k.streaks.ui.emojiPack.edit

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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.data.StreakEmojiPack
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.onEachWithOnMainThread
import ru.n08i40k.streaks.extension.setSectionsCompat
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.runOnMainThread
import java.util.SortedMap

class StreakEmojiPackEditFragment(private val viewModel: ViewModel) : BaseFragment(),
    EjectNotifier.Delegate {

    data class ViewState(
        val emojiPack: StreakEmojiPack,
    )

    interface ViewModel {
        fun state(): Flow<ViewState>

        fun update(emojiPack: StreakEmojiPack)

        fun destroy()
    }

    companion object {
        private const val TYPE_DESCRIPTION = 0
        private const val TYPE_EMOJI = 1

        private const val DONE_BUTTON_ID = 1

        private const val MAX_NAME_LENGTH = 64
    }

    private data class Item(
        val key: String,
        val oldValue: StreakEmojiInfo,
        val newValue: StreakEmojiInfo
    ) {
        companion object {
            fun fromSortedMap(
                parentEmojiPack: StreakEmojiPack,
                map: SortedMap<String, StreakEmojiInfo>
            ): List<Item> =
                map.entries
                    .map { Item(it.key, parentEmojiPack.emojis[it.key]!!, it.value) }
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

        override fun getItemCount() = items.size + 1

        override fun isEnabled(holder: RecyclerView.ViewHolder) = when (holder.itemViewType) {
            TYPE_EMOJI -> true
            else -> false
        }

        override fun getItemViewType(position: Int) = when (position) {
            in items.indices -> TYPE_EMOJI
            else -> TYPE_DESCRIPTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
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

                        setName(StreakLevel.nameById(emoji.key))
                        setEmojiInfo(emoji.newValue)
                        setChanged(emoji.oldValue != emoji.newValue)
                        setOnResetListener { updateEmoji(emoji.key, emoji.oldValue) }
                        setOnUpdateListener { updateEmoji(emoji.key, it) }
                    }

                else -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_streak_emoji_pack_edit_desc()
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

    private var emojiPack: StreakEmojiPack? = null
    private var items: List<Item>? = null

    // views
    private lateinit var listView: RecyclerListView
    private lateinit var doneItem: ActionBarMenuItem
    private lateinit var nameField: EditTextBoldCursor

    private fun setEmojiPack(emojiPack: StreakEmojiPack) {
        val parentEmojiPack = StreakEmojiPack.builtin.find { it.id == emojiPack.basedOn }!!

        this.emojiPack = emojiPack
        this.items = Item.fromSortedMap(parentEmojiPack, emojiPack.sortedEmojis)

        if (nameField.text.toString() != emojiPack.name) {
            nameField.setText(emojiPack.name)
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
                                BulletinHelper.show(Strings.status_info_streak_emoji_pack_name_empty())
                                return
                            }

                            val newEmojis = items!!.associate { it.key to it.newValue }

                            AndroidUtilities.hideKeyboard(nameField)

                            viewModel.update(
                                emojiPack!!.copy(name = name, emojis = newEmojis)
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
            setHintText(Strings.menu_streak_emoji_pack_edit_name_hint())
            setCursorColor(Theme.getColor(Theme.key_actionBarDefaultTitle, resourceProvider))
            setCursorSize(dp(20f))
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
            .onEachWithOnMainThread { setEmojiPack(emojiPack) }
            .launchIn(backgroundScope)

        return fragmentView
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