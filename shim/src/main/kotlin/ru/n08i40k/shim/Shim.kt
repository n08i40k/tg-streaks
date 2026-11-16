package ru.n08i40k.shim

import android.annotation.SuppressLint
import com.chaquo.python.PyObject
import com.exteragram.messenger.plugins.PythonPluginsEngine
import com.exteragram.messenger.plugins.models.SettingItem
import com.exteragram.messenger.plugins.ui.PluginsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.LaunchActivity
import ru.n08i40k.shim.compat.PluginsControllerCompat
import ru.n08i40k.shim.hooks.HookExceptionHandler
import ru.n08i40k.shim.hooks.HooksScope
import ru.n08i40k.shim.hooks.Unhooks
import ru.n08i40k.shim.hooks.hooks
import ru.n08i40k.shim.menu.MenuScope
import ru.n08i40k.shim.settings.SettingsScope
import ru.n08i40k.shim.utils.`BasePreferencesActivity$listView`
import ru.n08i40k.shim.utils.`LaunchActivity$actionBarLayout`
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@ExperimentalAtomicApi
public class Shim(
    public val pluginId: String,
    private val onError: (message: String, error: Throwable) -> Unit,
    private val settings: (SettingsScope.() -> Unit)?,
    private val menu: (MenuScope.() -> Unit)?,
) {
    private val failed = AtomicBoolean(false)

    private val exceptionHandler = HookExceptionHandler { member, order, throwable ->
        if (!failed.compareAndSet(expectedValue = false, newValue = true))
            return@HookExceptionHandler

        close()
        onError("An unknown exception occurred in $member $order-call hook", throwable)
    }

    private var shimUnhooks: Unhooks? = null
    private var pluginUnhooks: Unhooks? = null

    init {
        shimUnhooks = runCatching {
            hooks(exceptionHandler) {
                before(
                    PythonPluginsEngine::class.java.getDeclaredMethod(
                        "loadPluginSettings",
                        String::class.java
                    )
                ) { param ->
                    if (param.args[0] as String != pluginId)
                        return@before

                    val plugin = PluginsControllerCompat.getPlugin(pluginId)

                    if (plugin == null || !plugin.isEnabled() || plugin.hasError())
                        return@before

                    if (settings == null) {
                        param.result = null
                        return@before
                    }

                    try {
                        param.result = SettingsScope()
                            .apply(settings)
                            .toList()
                    } catch (e: Throwable) {
                        onError("Failed to create plugin settings", e)
                        param.result = null
                    }
                }

                before(
                    PythonPluginsEngine::class.java.getDeclaredMethod(
                        "parsePySettingDefinitions",
                        List::class.java
                    )
                ) { param ->
                    // пытаемся скастить java классы обратно
                    // какая-то лютая хуйня получилась
                    param.result = (param.args[0] as List<*>)
                        .map {
                            runCatching { (it as PyObject).toJava(Any::class.java) }
                                .getOrNull() as? SettingItem
                                ?: return@before
                        }
                }
            }
        }.onFailure {
            onError("Failed to hook methods", it)
            close()
        }.getOrThrow()

        sync()
    }

    public fun installHooks(action: HooksScope.() -> Unit) {
        pluginUnhooks = runCatching {
            hooks(exceptionHandler, action)
        }.onFailure {
            onError("Failed to hook methods", it)
            close()
        }.getOrThrow()
    }

    private fun addMenuItems() {
        if (menu == null)
            return

        val pluginsController = PluginsControllerCompat.instance

        MenuScope()
            .apply(menu)
            .items
            .forEach { pluginsController.addMenuItem(pluginId, it) }
    }

    private fun removeMenuItems() {
        if (menu == null)
            return

        PluginsControllerCompat.instance
            .removeMenuItemsByPluginId(pluginId)
    }

    @Synchronized
    public fun sync() {
        // menu items
        removeMenuItems()
        addMenuItems()

        // settings
        val pluginsController = PluginsControllerCompat.instance

        pluginsController.invalidatePluginSettings(pluginId)
        pluginsController.loadPluginSettings(pluginId)

        PluginsControllerCompat.notifyPluginsChanged()

        PluginsControllerCompat.runOnPluginsQueue {
            AndroidUtilities.runOnUIThread(::rebindPluginsLists)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun rebindPluginsLists() {
        (`LaunchActivity$actionBarLayout`.invokeExact(LaunchActivity.instance) as ActionBarLayout?)
            ?.fragmentStack
            ?.filterIsInstance<PluginsActivity>()
            ?.forEach {
                val adapter = (`BasePreferencesActivity$listView`.invoke(it) as UniversalRecyclerView?)?.adapter
                    ?: return@forEach

                adapter.update(false)
                adapter.notifyDataSetChanged()
            }
    }

    public fun close() {
        pluginUnhooks?.unhook()
        pluginUnhooks = null

        shimUnhooks?.unhook()
        shimUnhooks = null

        // menu
        removeMenuItems()

        // settings
        PluginsControllerCompat.instance
            .invalidatePluginSettings(pluginId)
    }
}

