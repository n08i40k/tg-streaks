package ru.n08i40k.streaks.ui.emojiPack.select

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.util.AnimatedEmojiView

class StreakEmojiListView(
    context: Context,
) : RecyclerView(context) {
    private class ListAdapter : Adapter<ListAdapter.ViewHolder>() {
        companion object {
            // must not clash with the view types of the list sharing the recycled view pool
            const val TYPE_EMOJI = 1000
        }

        private class ViewHolder(parent: ViewGroup) :
            RecyclerView.ViewHolder(FrameLayout(parent.context)) {
            companion object {
                const val SIZE = 32f
            }

            private val backupImageView = BackupImageView(itemView.context).apply {
                setRoundRadius(dp(SIZE / 2))
            }

            init {
                with(itemView as FrameLayout) {
                    setPadding(dp(8f), 0, dp(8f), 0)

                    addView(
                        backupImageView,
                        LayoutHelper.createFrame(SIZE.toInt(), SIZE)
                    )
                }
            }

            fun bind(emojiInfo: StreakEmojiInfo) =
                AnimatedEmojiView.apply(
                    backupImageView,
                    emojiInfo.documentId,
                    dp(SIZE),
                    emojiInfo.accentColor?.toArgb()
                )
        }

        private class ItemsCallback(
            private val old: List<StreakEmojiInfo>,
            private val new: List<StreakEmojiInfo>
        ) : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size

            override fun getNewListSize(): Int = new.size

            override fun areItemsTheSame(
                oldItemPosition: Int,
                newItemPosition: Int
            ): Boolean = areContentsTheSame(oldItemPosition, newItemPosition)

            override fun areContentsTheSame(
                oldItemPosition: Int,
                newItemPosition: Int
            ): Boolean = old[oldItemPosition] == new[newItemPosition]
        }

        private var items: List<StreakEmojiInfo> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(parent)

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(items[position])

        override fun getItemViewType(position: Int): Int = TYPE_EMOJI

        override fun getItemCount(): Int =
            items.size

        fun setItems(items: List<StreakEmojiInfo>) {
            val diff = DiffUtil.calculateDiff(ItemsCallback(this.items, items))
            this.items = items

            diff.dispatchUpdatesTo(this)
        }
    }

    init {
        adapter = ListAdapter()

        isHorizontalScrollBarEnabled = true

        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        layoutParams = FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
    }

    fun setItems(items: List<StreakEmojiInfo>) =
        (adapter as ListAdapter).setItems(items)
}
