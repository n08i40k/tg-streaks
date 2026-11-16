package ru.n08i40k.streaks.settings

import com.exteragram.messenger.plugins.PluginsController
import ru.n08i40k.shim.compat.PluginsControllerCompat
import ru.n08i40k.shim.settings.SettingsScope
import ru.n08i40k.streaks.Plugin

sealed class PluginSetting<T>(val key: String) {
    abstract val value: T

    protected val controller: PluginsController
        get() = PluginsControllerCompat.instance
}

class BooleanSetting(key: String, val default: Boolean) : PluginSetting<Boolean>(key) {
    override val value: Boolean
        get() = runCatching { controller.getPluginSettingBoolean(Plugin.ID, key, default) }
            .getOrDefault(default)
}

class ChoiceSetting<T>(
    key: String,
    val options: List<T>,
    val defaultIndex: Int,
) : PluginSetting<T>(key) {
    init {
        require(defaultIndex in options.indices) { "defaultIndex is out of options range" }
    }

    val index: Int
        get() = runCatching { controller.getPluginSettingInt(Plugin.ID, key, defaultIndex) }
            .getOrDefault(defaultIndex)
            .coerceIn(options.indices)

    override val value: T
        get() = options[index]

    fun optionAt(index: Int): T =
        options[index.coerceIn(options.indices)]
}

fun SettingsScope.switch(
    setting: BooleanSetting,
    init: SettingsScope.SwitchSettingScope.() -> Unit,
) = switch(setting.key) {
    defaultValue = setting.default
    init()
}

fun <T> SettingsScope.selector(
    setting: ChoiceSetting<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    init: SettingsScope.SelectorSettingScope.() -> Unit,
) = selector(setting.key) {
    defaultValue = setting.defaultIndex
    items(setting.options.map(label))
    onChange { onSelect(setting.optionAt(it)) }
    init()
}
