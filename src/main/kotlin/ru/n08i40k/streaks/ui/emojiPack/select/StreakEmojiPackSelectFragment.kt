package ru.n08i40k.streaks.ui.emojiPack.select

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakEmojiPack
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.onEachWith
import ru.n08i40k.streaks.extension.onEachWithOnMainThread
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.emojiPack.edit.StreakEmojiPackEditFragment
import ru.n08i40k.streaks.util.createDialogPicker
import ru.n08i40k.streaks.util.runOnMainThread
import java.util.UUID

class StreakEmojiPackSelectFragment(private val viewModel: ViewModel) : BaseFragment(),
    EjectNotifier.Delegate {

    data class ViewState(
        val emojiPacks: List<StreakEmojiPack>,
        val activeEmojiPack: StreakEmojiPack,
    )

    interface ViewModel {
        fun state(): Flow<ViewState>

        fun setActiveEmojiPack(id: UUID)

        fun create(basedOn: UUID)

        fun share(id: UUID, peerId: Long)

        fun update(emojiPack: StreakEmojiPack)

        fun delete(id: UUID)
    }

    companion object {
        private const val TYPE_DESCRIPTION = 0
        private const val TYPE_PACK = 1

        private const val DONE_BUTTON_ID = 1
    }

    private inner class ListAdapter(private val context: Context) :
        RecyclerListView.SelectionAdapter() {

        private inner class ItemsCallback(
            private val old: List<StreakEmojiPack>,
            private val new: List<StreakEmojiPack>,
            private val oldCheckedId: UUID?,
            private val newCheckedId: UUID?
        ) : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size

            override fun getNewListSize(): Int = new.size

            override fun areItemsTheSame(
                oldItemPosition: Int,
                newItemPosition: Int
            ): Boolean = old[oldItemPosition].id == new[newItemPosition].id

            override fun areContentsTheSame(
                oldItemPosition: Int,
                newItemPosition: Int
            ): Boolean {
                val old = old[oldItemPosition]
                val new = new[newItemPosition]

                return old.basedOn == new.basedOn
                        && old.name == new.name
                        && old.version == new.version
                        && old.sortedEmojis == new.sortedEmojis
                        && (old.id == oldCheckedId) == (new.id == newCheckedId)
            }

        }

        private var items: List<StreakEmojiPack> = listOf()
        private var checkedId: UUID? = null

        override fun getItemCount() = items.size + 1

        override fun isEnabled(holder: RecyclerView.ViewHolder) = when (holder.itemViewType) {
            TYPE_PACK -> true
            else -> false
        }

        override fun getItemViewType(position: Int) = when (position) {
            in items.indices -> TYPE_PACK
            else -> TYPE_DESCRIPTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                TYPE_DESCRIPTION -> TextInfoPrivacyCell(context)

                TYPE_PACK -> StreakEmojiPackCell(context, true)
                    .apply {
                        setRecycledViewPool(this@StreakEmojiPackSelectFragment.listView.recycledViewPool)

                        setActionListener { action, id ->
                            when (action) {
                                StreakEmojiPackCell.Action.SHARE ->
                                    presentFragment(createDialogPicker { peerId ->
                                        viewModel.share(id, peerId)
                                    })
                                StreakEmojiPackCell.Action.EDIT -> openEditFragment(id)
                                StreakEmojiPackCell.Action.INHERIT -> viewModel.create(id)
                                StreakEmojiPackCell.Action.DELETE -> viewModel.delete(id)
                            }
                        }
                    }

                else -> View(context)
            }

            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                in items.indices -> (holder.itemView as StreakEmojiPackCell)
                    .apply {
                        val emojiPack = items[position]

                        setEmojiPack(emojiPack, StreakEmojiPack.builtin.contains(emojiPack))
                        setChecked(emojiPack.id == checkedId)
                    }

                else -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_streak_emoji_pack_select_desc()
            }
        }

        fun setItems(items: List<StreakEmojiPack>, checkedId: UUID?) {
            val diff = DiffUtil.calculateDiff(
                ItemsCallback(this.items, items, this.checkedId, checkedId)
            )

            this.items = items
            this.checkedId = checkedId

            diff.dispatchUpdatesTo(this)
        }

        fun setCheckedId(checkedId: UUID?) {
            if (this.checkedId == checkedId)
                return

            val oldPosition = items.indexOfFirst { it.id == this.checkedId }
            val newPosition = items.indexOfFirst { it.id == checkedId }

            this.checkedId = checkedId

            if (oldPosition != -1)
                notifyItemChanged(oldPosition)
            if (newPosition != -1)
                notifyItemChanged(newPosition)
        }
    }

    private fun openEditFragment(id: UUID) {
        val viewModel = object : StreakEmojiPackEditFragment.ViewModel {
            private val coroutineScope = Plugin.childCoroutineScope()

            private val stateFlow =
                MutableStateFlow(StreakEmojiPackEditFragment.ViewState(emojiPacks.find { it.id == id }!!))

            init {
                viewModel.state()
                    .onEachWith {
                        val currentEmojiPack = emojiPacks.find { it.id == id }
                            ?: return@onEachWith

                        stateFlow.emit(StreakEmojiPackEditFragment.ViewState(currentEmojiPack))
                    }
                    .launchIn(coroutineScope)
            }

            override fun state(): Flow<StreakEmojiPackEditFragment.ViewState> = stateFlow

            override fun update(emojiPack: StreakEmojiPack) {
                viewModel.update(emojiPack)

                coroutineScope.launch {
                    stateFlow.emit(StreakEmojiPackEditFragment.ViewState(emojiPack))
                }
            }

            override fun destroy() = coroutineScope.cancel()
        }

        presentFragment(StreakEmojiPackEditFragment(viewModel))
    }

    private val backgroundScope = Plugin.childCoroutineScope()

    // eject
    private val unsubscribe = EjectNotifier.subscribe(this)

    private var emojiPacks: List<StreakEmojiPack> = listOf()

    // won't change until apply button is pressed
    private var activeEmojiPackId: UUID? = null

    // can be changed before apply button press
    private var selectedEmojiPackId: UUID? = null

    // views
    private lateinit var listView: RecyclerListView
    private lateinit var doneItem: ActionBarMenuItem

    private var pendingItemsUpdate = false

    // adapter can't be notified while the list is laying out or scrolling
    private val itemsUpdate = object : Runnable {
        override fun run() {
            if (listView.isComputingLayout
                || listView.scrollState != RecyclerView.SCROLL_STATE_IDLE
            ) {
                listView.post(this)
                return
            }

            pendingItemsUpdate = false
            (listView.adapter as? ListAdapter)?.setItems(emojiPacks, selectedEmojiPackId)
        }
    }

    override fun onEject() {
        runOnMainThread { finishFragment() }
    }

    override fun onFragmentClosed() {
        unsubscribe()
        backgroundScope.cancel()
        super.onFragmentClosed()
    }

    private fun setEmojiPacks(emojiPacks: List<StreakEmojiPack>) {
        this.emojiPacks = emojiPacks

        if (pendingItemsUpdate)
            return

        pendingItemsUpdate = true
        itemsUpdate.run()
    }

    override fun createView(context: Context): View {
        actionBar.apply {
            setBackButtonImage(R.drawable.ic_ab_back)
            setTitle(Strings.menu_streak_emoji_pack_select_title())

            actionBarMenuOnItemClick = object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        DONE_BUTTON_ID -> {
                            viewModel.setActiveEmojiPack(selectedEmojiPackId!!)
                            finishFragment()
                        }
                    }
                }
            }
        }

        doneItem = actionBar
            .createMenu()
            .addItem(DONE_BUTTON_ID, R.drawable.ic_ab_done)
            .apply { visibility = View.GONE }

        listView = RecyclerListView(context).apply {
            adapter = ListAdapter(context)

            isVerticalScrollBarEnabled = true
            layoutManager = LinearLayoutManager(
                context,
                LinearLayoutManager.VERTICAL,
                false
            )

            setOnItemClickListener { view, position ->
                if (view.isEnabled)
                    onRowClicked(position)
            }
        }

        fragmentView = FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider))
            addView(listView, LayoutHelper.createFrame(-1, -1f))
        }

        viewModel.state()
            .onEachWithOnMainThread {
                activeEmojiPackId = activeEmojiPack.id
                selectedEmojiPackId = selectedEmojiPackId
                    ?.takeIf { id -> emojiPacks.any { it.id == id } }
                    ?: activeEmojiPackId

                setEmojiPacks(emojiPacks)
                updateDoneVisibility()
            }
            .launchIn(backgroundScope)

        return fragmentView
    }

    private fun updateDoneVisibility() {
        doneItem.visibility = if (selectedEmojiPackId == activeEmojiPackId)
            View.GONE
        else
            View.VISIBLE
    }

    private fun onRowClicked(newPosition: Int) {
        when (newPosition) {
            in emojiPacks.indices -> {
                val newPackId = emojiPacks[newPosition].id

                if (selectedEmojiPackId == newPackId)
                    return

                selectedEmojiPackId = newPackId

                updateDoneVisibility()
                (listView.adapter as? ListAdapter)?.setCheckedId(newPackId)
            }

            else -> return
        }
    }
}