package ru.n08i40k.streaks.ui

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Cells.TextSettingsCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.ServiceMessageCategory
import ru.n08i40k.streaks.extension.setSectionsCompat
import ru.n08i40k.streaks.extension.setTextAndValueAndCheckCompat
import ru.n08i40k.streaks.extension.toOffsetString
import ru.n08i40k.streaks.i18n.Strings


class StreakControlFragment(private val viewModel: ViewModel) : BaseFragment() {
    data class ViewState(
        val hasPet: Boolean = false,
        val timeZone: TimeZone = TimeZone.currentSystemDefault(),
        val peerHasPluginInstalled: Boolean = false,
        val petFabEnabled: Boolean = true,
        val canRestoreStreak: Boolean = false,
        val serviceMessageCategories: Map<String, Boolean> = mapOf(
            ServiceMessageCategory.LIFECYCLE to true,
            ServiceMessageCategory.LEVEL_UP to false,
            ServiceMessageCategory.PET to false,
        ),
    )

    interface ViewModel {
        fun state(): Flow<ViewState>

        fun setTimeZone(value: TimeZone)

        fun setPeerHasPluginInstalled(value: Boolean)

        fun offerSync()

        fun setServiceMessagesCategoryEnabled(categoryName: String, value: Boolean)

        fun setPetFabEnabled(value: Boolean)

        fun rebuildBoth()
        fun rebuildPet()

        fun restoreStreak()
        fun createPet()

        fun goToStreakStart()

        fun deleteBoth()
        fun deletePet()
    }

    enum class Row {
        TIME_HEADER, TIME_ZONE_SELECTOR, TIME_ZONE_DESC,

        SYNC_HEADER, SYNC_PEER_HAS_PLUGIN_ENABLED_SW, SYNC_OFFER_BTN, SYNC_OFFER_DESC,

        SERVICE_MESSAGES_HEADER, SERVICE_MESSAGES_CATS_LINK, SERVICE_MESSAGES_DESC,

        PET_HEADER, PET_CREATE_BTN, PET_FAB_SW,

        ACTIONS_HEADER, ACTIONS_RESTORE_STREAK_BTN, ACTIONS_GO_TO_STREAK_START_BTN,

        DANGER_ZONE_HEADER, DANGER_ZONE_REBUILD_BOTH_BTN, DANGER_ZONE_REBUILD_PET_BTN, DANGER_ZONE_DELETE_BOTH_BTH, DANGER_ZONE_DELETE_PET_BTN, DANGER_ZONE_DESC,

        COUNT
    }

    private lateinit var listView: RecyclerListView
    private lateinit var listAdapter: ListAdapter
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var viewState = ViewState()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SWITCH = 1
        private const val TYPE_BUTTON = 2
        private const val TYPE_LINK = 3
        private const val TYPE_DESCRIPTION = 4

        private val ROW_UPDATE_PAYLOAD = Any()
    }

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle(Strings.menu_control_title())
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        listView = RecyclerListView(context)
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

        observeState()

        return fragmentView
    }

    private fun <T> Flow<T>.observe(action: (T) -> Unit) {
        viewScope.launch {
            collectLatest { value -> AndroidUtilities.runOnUIThread { action(value) } }
        }
    }

    private fun observeState() {
        fun ListAdapter.notifyRowChanged(row: Row) =
            notifyItemChanged(row.ordinal, ROW_UPDATE_PAYLOAD)

        viewModel.state()
            .observe { state ->
                Plugin.getInstance().backgroundScope.launch {
                    while (listView.isComputingLayout) {
                        delay(100)
                    }

                    listView.post {
                        viewState = state

                        listView.stopScroll()
                        listAdapter.notifyRowChanged(Row.DANGER_ZONE_REBUILD_PET_BTN)
                        listAdapter.notifyRowChanged(Row.DANGER_ZONE_DELETE_PET_BTN)
                        listAdapter.notifyRowChanged(Row.TIME_ZONE_SELECTOR)
                        listAdapter.notifyRowChanged(Row.SYNC_PEER_HAS_PLUGIN_ENABLED_SW)
                        listAdapter.notifyRowChanged(Row.SYNC_OFFER_BTN)
                        listAdapter.notifyRowChanged(Row.PET_FAB_SW)
                        listAdapter.notifyRowChanged(Row.ACTIONS_RESTORE_STREAK_BTN)
                    }
                }
            }
    }

    override fun onFragmentDestroy() {
        viewScope.cancel()
        super.onFragmentDestroy()
    }

    private fun onRowClicked(view: View, position: Int) {
        when (position) {
            Row.TIME_ZONE_SELECTOR.ordinal -> {
                presentFragment(TimeZoneSelectFragment(object : TimeZoneSelectFragment.Adapter {
                    override fun getCurrentTimeZone(): TimeZone = viewState.timeZone

                    override fun setCurrentTimeZone(value: TimeZone) {
                        viewModel.setTimeZone(value)
                    }

                    override fun hasPet(): Boolean = viewState.hasPet
                }))
            }

            Row.SYNC_PEER_HAS_PLUGIN_ENABLED_SW.ordinal -> {
                val newState = !viewState.peerHasPluginInstalled
                viewModel.setPeerHasPluginInstalled(newState)
                (view as TextCheckCell).isChecked = newState
            }

            Row.SYNC_OFFER_BTN.ordinal -> {
                showConfirmDialog(
                    Strings.dialog_control_sync_offer_title(),
                    Strings.dialog_control_sync_offer_message(),
                    Strings.dialog_control_sync_offer_button(),
                ) { viewModel.offerSync() }
            }

            Row.SERVICE_MESSAGES_CATS_LINK.ordinal -> {
                presentFragment(ServiceMessageCategoriesFragment(object :
                    ServiceMessageCategoriesFragment.Adapter {
                    override fun isCategoryEnabled(category: String): Boolean =
                        viewState.serviceMessageCategories[category] ?: false

                    override fun setCategoryEnabled(category: String, value: Boolean) =
                        viewModel.setServiceMessagesCategoryEnabled(category, value)

                    override fun isPeerHasPluginInstalled(): Boolean =
                        viewState.peerHasPluginInstalled
                }))
            }

            Row.PET_FAB_SW.ordinal -> {
                val newState = !viewState.petFabEnabled
                viewModel.setPetFabEnabled(newState)
                (view as TextCheckCell).isChecked = newState
            }

            Row.PET_CREATE_BTN.ordinal -> {
                viewModel.createPet()
            }

            Row.ACTIONS_RESTORE_STREAK_BTN.ordinal -> {
                viewModel.restoreStreak()
            }

            Row.ACTIONS_GO_TO_STREAK_START_BTN.ordinal -> {
                viewModel.goToStreakStart()
                finishFragment()
            }

            Row.DANGER_ZONE_REBUILD_BOTH_BTN.ordinal -> {
                showConfirmDialog(
                    Strings.dialog_control_rebuild_streak_title(),
                    Strings.dialog_control_rebuild_streak_message(),
                    Strings.dialog_control_rebuild_button(),
                ) { viewModel.rebuildBoth() }
            }

            Row.DANGER_ZONE_REBUILD_PET_BTN.ordinal -> {
                showConfirmDialog(
                    Strings.dialog_control_rebuild_pet_title(),
                    Strings.dialog_control_rebuild_pet_message(),
                    Strings.dialog_control_rebuild_button(),
                ) { viewModel.rebuildPet() }
            }

            Row.DANGER_ZONE_DELETE_BOTH_BTH.ordinal -> {
                showConfirmDialog(
                    Strings.dialog_control_delete_streak_with_pet_title(),
                    Strings.dialog_control_delete_streak_with_pet_message(),
                    Strings.dialog_control_delete_button(),
                    makeRed = true,
                ) {
                    viewModel.deleteBoth()
                    finishFragment()
                }
            }

            Row.DANGER_ZONE_DELETE_PET_BTN.ordinal -> {
                showConfirmDialog(
                    Strings.dialog_control_delete_pet_title(),
                    Strings.dialog_control_delete_pet_message(),
                    Strings.dialog_control_delete_button(),
                    makeRed = true,
                ) {
                    viewModel.deletePet()
                }
            }
        }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        positiveButtonText: String,
        makeRed: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        val activity = parentActivity ?: return

        val builder = AlertDialog.Builder(activity).setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButtonText) { _, _ -> onConfirm() }
            .setNegativeButton(Strings.dialog_control_cancel(), null)

        if (makeRed) builder.makeRed(DialogInterface.BUTTON_POSITIVE)

        builder.show()
    }

    inner class ListAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun getItemCount() = Row.COUNT.ordinal

        @Suppress("DEPRECATION")
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean =
            when (holder.adapterPosition) {
                Row.SYNC_OFFER_BTN.ordinal -> false // viewState.peerHasPluginInstalled

                Row.DANGER_ZONE_REBUILD_PET_BTN.ordinal, Row.DANGER_ZONE_DELETE_PET_BTN.ordinal -> viewState.hasPet

                Row.PET_FAB_SW.ordinal -> viewState.hasPet

                Row.PET_CREATE_BTN.ordinal -> !viewState.hasPet

                Row.ACTIONS_RESTORE_STREAK_BTN.ordinal -> viewState.canRestoreStreak

                else -> when (holder.itemViewType) {
                    TYPE_SWITCH, TYPE_BUTTON, TYPE_LINK -> true
                    else -> false
                }
            }

        override fun getItemViewType(position: Int) = when (position) {
            Row.TIME_HEADER.ordinal, Row.SYNC_HEADER.ordinal, Row.SERVICE_MESSAGES_HEADER.ordinal, Row.PET_HEADER.ordinal, Row.ACTIONS_HEADER.ordinal, Row.DANGER_ZONE_HEADER.ordinal -> TYPE_HEADER

            Row.SYNC_PEER_HAS_PLUGIN_ENABLED_SW.ordinal, Row.PET_FAB_SW.ordinal -> TYPE_SWITCH

            Row.TIME_ZONE_SELECTOR.ordinal, Row.SYNC_OFFER_BTN.ordinal, Row.PET_CREATE_BTN.ordinal, Row.ACTIONS_RESTORE_STREAK_BTN.ordinal, Row.DANGER_ZONE_REBUILD_BOTH_BTN.ordinal, Row.DANGER_ZONE_REBUILD_PET_BTN.ordinal, Row.DANGER_ZONE_DELETE_BOTH_BTH.ordinal, Row.DANGER_ZONE_DELETE_PET_BTN.ordinal -> TYPE_BUTTON

            Row.SERVICE_MESSAGES_CATS_LINK.ordinal, Row.ACTIONS_GO_TO_STREAK_START_BTN.ordinal -> TYPE_LINK

            Row.TIME_ZONE_DESC.ordinal, Row.SYNC_OFFER_DESC.ordinal, Row.SERVICE_MESSAGES_DESC.ordinal, Row.DANGER_ZONE_DESC.ordinal -> TYPE_DESCRIPTION

            else -> throw NotImplementedError("Unreachable")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                TYPE_HEADER -> HeaderCell(context)
                TYPE_SWITCH -> TextCheckCell(context)
                TYPE_BUTTON -> TextSettingsCell(context)
                TYPE_LINK -> TextSettingsCell(context)
                TYPE_DESCRIPTION -> TextInfoPrivacyCell(context)
                else -> View(context)
            }
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                Row.TIME_HEADER.ordinal -> (holder.itemView as HeaderCell)
                    .setText(Strings.menu_control_time_zone_header())

                Row.TIME_ZONE_SELECTOR.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setTextAndValue(
                            Strings.menu_control_time_zone_selector(),
                            viewState.timeZone.toOffsetString(),
                            true
                        )
                        setIcon(R.drawable.msg_arrowright)
                    }

                Row.TIME_ZONE_DESC.ordinal -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_control_time_zone_desc()

                Row.SYNC_HEADER.ordinal -> (holder.itemView as HeaderCell)
                    .setText(Strings.menu_control_sync_header())

                Row.SYNC_PEER_HAS_PLUGIN_ENABLED_SW.ordinal -> (holder.itemView as TextCheckCell)
                    .setTextAndCheck(
                        Strings.menu_control_sync_peer_has_plugin_toggle(),
                        viewState.peerHasPluginInstalled,
                        true
                    )

                Row.SYNC_OFFER_BTN.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_sync_offer_button(), true)
                        setIcon(0)
                    }

                Row.SYNC_OFFER_DESC.ordinal -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_control_sync_offer_desc()

                Row.SERVICE_MESSAGES_HEADER.ordinal -> (holder.itemView as HeaderCell)
                    .setText(Strings.menu_control_service_messages_header())

                Row.SERVICE_MESSAGES_CATS_LINK.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_service_messages_categories_link(), true)
                        setIcon(R.drawable.msg_arrowright)
                    }

                Row.SERVICE_MESSAGES_DESC.ordinal -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_control_service_messages_desc()

                Row.PET_HEADER.ordinal -> (holder.itemView as HeaderCell)
                    .setText(Strings.menu_control_pet_header())

                Row.PET_FAB_SW.ordinal -> (holder.itemView as TextCheckCell)
                    .setTextAndValueAndCheckCompat(
                        Strings.menu_control_pet_fab_toggle(),
                        Strings.menu_control_pet_fab_toggle_desc(),
                        viewState.petFabEnabled,
                        false,
                        true
                    )

                Row.PET_CREATE_BTN.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_pet_create_button(), true)
                        setIcon(0)
                    }

                Row.ACTIONS_HEADER.ordinal -> (holder.itemView as HeaderCell)
                    .setText(Strings.menu_control_actions_header())

                Row.ACTIONS_RESTORE_STREAK_BTN.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_actions_restore_streak_button(), true)
                        setIcon(0)
                    }

                Row.ACTIONS_GO_TO_STREAK_START_BTN.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_actions_go_to_streak_link(), true)
                        setIcon(R.drawable.msg_arrowright)
                    }

                Row.DANGER_ZONE_HEADER.ordinal -> (holder.itemView as HeaderCell)
                    .setText(Strings.menu_control_danger_zone_header())

                Row.DANGER_ZONE_REBUILD_BOTH_BTN.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_danger_zone_rebuild_streak_button(), true)
                        setIcon(0)
                    }

                Row.DANGER_ZONE_REBUILD_PET_BTN.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_danger_zone_rebuild_pet_button(), true)
                        setIcon(0)
                    }

                Row.DANGER_ZONE_DELETE_BOTH_BTH.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(
                            Strings.menu_control_danger_zone_delete_streak_with_pet_button(),
                            true
                        )
                        setTextColor(Theme.getColor(Theme.key_color_red))
                        setIcon(0)
                    }

                Row.DANGER_ZONE_DELETE_PET_BTN.ordinal -> (holder.itemView as TextSettingsCell)
                    .apply {
                        setText(Strings.menu_control_danger_zone_delete_pet_button(), true)
                        setTextColor(Theme.getColor(Theme.key_color_red))
                        setIcon(0)
                    }

                Row.DANGER_ZONE_DESC.ordinal -> (holder.itemView as TextInfoPrivacyCell)
                    .text = Strings.menu_control_danger_zone_desc()
            }

            holder.itemView.isEnabled = isEnabled(holder)
            holder.itemView.alpha = when (holder.itemViewType) {
                TYPE_SWITCH, TYPE_BUTTON, TYPE_LINK -> if (isEnabled(holder)) 1f else 0.5f
                else -> 1f
            }
        }
    }
}
