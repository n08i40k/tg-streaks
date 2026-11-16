@file:Suppress("unused")

package ru.n08i40k.shim.utils

import com.chaquo.python.PyObject
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.models.TextSetting
import com.exteragram.messenger.preferences.BasePreferencesActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.reflection.generator.Before
import ru.n08i40k.reflection.generator.Getter
import ru.n08i40k.reflection.generator.Mirror
import ru.n08i40k.reflection.generator.Constructor
import ru.n08i40k.reflection.generator.Retype
import ru.n08i40k.reflection.generator.Since

@Mirror(LaunchActivity::class)
internal interface LaunchActivityMirror {
    @Getter
    val actionBarLayout: Any?
}

@Mirror(BasePreferencesActivity::class)
internal interface BasePreferencesActivityMirror {
    @Getter
    val listView: Any?
}

@Mirror(PluginsController::class)
internal interface PluginsControllerMirror {
    /** Котлиновский PluginsController.getInstance() идёт через Companion, которого нет у Java-класса 12.1.1 */
    @Retype(PluginsController::class)
    fun getInstance()

    /** j$.util.concurrent.ConcurrentHashMap или java.util.concurrent.ConcurrentHashMap, оба реализуют Map */
    @Getter(retype = [Map::class, PluginsController::class])
    val plugins: Any?

    fun notifyPluginsChanged()

    @Since("12.5.1")
    fun runOnPluginsQueue(action: Runnable)
}

@Mirror(TextSetting::class)
internal interface TextSettingMirror {
    @Constructor
    @Since("12.5.1")
    fun create(
        text: String,
        subtext: String,
        icon: String,
        accent: Boolean,
        red: Boolean,
        onClick: PyObject,
        createSubFragment: PyObject,
        onLongClick: PyObject,
        linkAlias: String,
    )

    @Constructor
    @Since("12.1.1")
    @Before("12.5.1")
    fun create(
        text: String,
        icon: String,
        accent: Boolean,
        red: Boolean,
        onClick: PyObject,
        createSubFragment: PyObject,
        onLongClick: PyObject,
        linkAlias: String,
    )
}
