package ru.n08i40k.shim.compat

import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsController
import ru.n08i40k.shim.utils.`PluginsController$getInstance`
import ru.n08i40k.shim.utils.`PluginsController$notifyPluginsChanged`
import ru.n08i40k.shim.utils.`PluginsController$plugins`
import ru.n08i40k.shim.utils.`PluginsController$runOnPluginsQueue$$12_5_1`

public object PluginsControllerCompat {
    public val instance: PluginsController
        get() = `PluginsController$getInstance`.invokeExact() as PluginsController

    private val pluginsMap: Map<*, *>
        get() = `PluginsController$plugins`.invokeExact(instance) as Map<*, *>

    @Suppress("UNCHECKED_CAST")
    public val plugins: Collection<Plugin>
        get() = pluginsMap.values as Collection<Plugin>

    public fun getPlugin(pluginId: String): Plugin? =
        pluginsMap[pluginId] as? Plugin

    public fun notifyPluginsChanged() {
        `PluginsController$notifyPluginsChanged`.invoke(instance)
    }

    public fun runOnPluginsQueue(action: Runnable) {
        val handle = `PluginsController$runOnPluginsQueue$$12_5_1`

        if (handle != null)
            handle.invoke(action)
        else
            action.run()
    }
}
