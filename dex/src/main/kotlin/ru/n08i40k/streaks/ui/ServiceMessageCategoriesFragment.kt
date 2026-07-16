package ru.n08i40k.streaks.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.extension.setSectionsCompat
import ru.n08i40k.streaks.extension.setTextAndValueAndCheckCompat
import ru.n08i40k.streaks.i18n.Strings

class ServiceMessageCategoriesFragment(private val adapter: Adapter) : BaseFragment() {
    interface Adapter {
        fun isCategoryEnabled(category: String): Boolean
        fun setCategoryEnabled(category: String, value: Boolean)

        fun isPeerHasPluginInstalled(): Boolean
    }

    enum class Row {
        HEADER,

        LIFECYCLE_SW,
        LEVEL_UP_SW,
        PET_SW,

        DESC,
        PEER_DEPENDENT_DESC,

        COUNT
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SWITCH = 1
        private const val TYPE_DESCRIPTION = 2
    }

    private lateinit var listAdapter: ListAdapter

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle(Strings.menu_service_categories_title())
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val listView = RecyclerListView(context)
        listView.setSectionsCompat()
        listView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        listView.isVerticalScrollBarEnabled = true
        listView.itemAnimator = AlphaAwareItemAnimator()

        listAdapter = ListAdapter(context)
        listView.adapter = listAdapter

        listView.setOnItemClickListener { view, position ->
            if (!view.isEnabled) return@setOnItemClickListener
            onRowClicked(view, position)
        }

        fragmentView = FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider))
            addView(listView, LayoutHelper.createFrame(-1, -1f))
        }

        return fragmentView
    }

    private fun onRowClicked(view: View, position: Int) {
        when (position) {
            Row.LEVEL_UP_SW.ordinal -> {
                val cell = view as TextCheckCell
                cell.isChecked = !cell.isChecked
                adapter.setCategoryEnabled(ServiceMessageCategory.LEVEL_UP, cell.isChecked)
            }
        }
    }

    inner class ListAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun getItemCount() = Row.COUNT.ordinal

        @Suppress("DEPRECATION")
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean =
            when (holder.adapterPosition) {
                Row.LIFECYCLE_SW.ordinal,
                Row.LEVEL_UP_SW.ordinal -> true

                else -> false
            }

        override fun getItemViewType(position: Int) = when (position) {
            Row.HEADER.ordinal -> TYPE_HEADER

            Row.LIFECYCLE_SW.ordinal,
            Row.LEVEL_UP_SW.ordinal,
            Row.PET_SW.ordinal -> TYPE_SWITCH

            else -> TYPE_DESCRIPTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                TYPE_HEADER -> HeaderCell(context)
                TYPE_SWITCH -> TextCheckCell(context)
                TYPE_DESCRIPTION -> TextInfoPrivacyCell(context)
                else -> View(context)
            }
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                Row.HEADER.ordinal -> (holder.itemView as HeaderCell)
                    .setText(Strings.menu_service_categories_header())

                Row.LIFECYCLE_SW.ordinal -> (holder.itemView as TextCheckCell)
                    .setTextAndValueAndCheckCompat(
                        Strings.menu_service_categories_lifecycle_title(),
                        Strings.menu_service_categories_lifecycle_desc(),
                        adapter.isCategoryEnabled(ServiceMessageCategory.LIFECYCLE),
                        true,
                        true
                    )

                Row.LEVEL_UP_SW.ordinal -> (holder.itemView as TextCheckCell)
                    .setTextAndValueAndCheckCompat(
                        Strings.menu_service_categories_level_up_title(),
                        Strings.menu_service_categories_level_up_desc(),
                        adapter.isCategoryEnabled(ServiceMessageCategory.LEVEL_UP),
                        true,
                        true
                    )

                Row.PET_SW.ordinal -> (holder.itemView as TextCheckCell)
                    .setTextAndValueAndCheckCompat(
                        Strings.menu_service_categories_pet_title(),
                        Strings.menu_service_categories_pet_desc(),
                        adapter.isCategoryEnabled(ServiceMessageCategory.PET),
                        true,
                        true
                    )

                Row.DESC.ordinal -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_service_categories_desc()

                Row.PEER_DEPENDENT_DESC.ordinal -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_service_categories_peer_dependent_desc()
            }

            holder.itemView.alpha = when (holder.itemViewType) {
                TYPE_SWITCH -> if (isEnabled(holder)) 1f else 0.5f
                else -> 1f
            }
        }
    }
}
