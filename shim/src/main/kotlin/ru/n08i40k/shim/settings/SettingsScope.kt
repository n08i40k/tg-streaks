package ru.n08i40k.shim.settings

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.exteragram.messenger.plugins.models.DividerSetting
import com.exteragram.messenger.plugins.models.EditTextSetting
import com.exteragram.messenger.plugins.models.HeaderSetting
import com.exteragram.messenger.plugins.models.InputSetting
import com.exteragram.messenger.plugins.models.SelectorSetting
import com.exteragram.messenger.plugins.models.SettingItem
import com.exteragram.messenger.plugins.models.SwitchSetting
import com.exteragram.messenger.plugins.models.TextSetting
import ru.n08i40k.shim.chaquopy.fromLambda
import ru.n08i40k.shim.chaquopy.fromLambda0
import ru.n08i40k.shim.utils.`TextSetting$$init$$12_5_1`
import ru.n08i40k.shim.utils.`TextSetting$$init$$12_1_1`

public class SettingsScope {
    private val settings = mutableListOf<SettingItem>()

    public fun header(text: String) {
        settings.add(HeaderSetting(text))
    }

    public fun divider(text: String? = null) {
        settings.add(DividerSetting(text))
    }

    public abstract class SettingScope {
        public var icon: String? = null
        public var linkAlias: String? = null
        protected var longClickListener: SettingLongClickListener? = null

        public fun onLongClick(listener: SettingLongClickListener) {
            this.longClickListener = listener
        }

        internal abstract fun toItem(): SettingItem
    }

    public class SelectorSettingScope(private val key: String) : SettingScope() {
        public var text: String? = null
        public var defaultValue: Int = 0
        private val items = mutableListOf<String>()
        private var changeListener: SettingChangeListener<Int>? = null

        public fun item(item: String) {
            items.add(item)
        }

        public fun items(items: Iterable<String>) {
            this.items.addAll(items)
        }

        public fun items(vararg items: String) {
            this.items.addAll(items)
        }

        public fun onChange(listener: SettingChangeListener<Int>) {
            this.changeListener = listener
        }

        override fun toItem(): SettingItem {
            if (key.isEmpty())
                throw IllegalStateException("SelectorSetting 'key' string should be not empty")

            if (text.isNullOrEmpty())
                throw IllegalStateException("SelectorSetting 'text' string should be not empty")

            if (defaultValue < 0 || defaultValue >= items.size)
                throw IllegalStateException("SelectorSetting 'defaultValue' should be in range 0 until ${items.size}")

            return SelectorSetting(
                key,
                text,
                defaultValue,
                items.toTypedArray(),
                icon,
                onChangeCallback = changeListener
                    ?.let { fromLambda(it::onChange) },
                onLongClickCallback = longClickListener
                    ?.let { fromLambda(it::onLongClick) },
                linkAlias
            )
        }
    }

    public fun selector(key: String, init: SelectorSettingScope.() -> Unit) {
        settings.add(
            SelectorSettingScope(key)
                .apply(init)
                .toItem()
        )
    }

    public class InputSettingScope(private val key: String) : SettingScope() {
        public var text: String? = null
        public var subtext: String? = null
        public var defaultValue: String = ""
        private var changeListener: SettingChangeListener<String>? = null

        public fun onChange(listener: SettingChangeListener<String>) {
            this.changeListener = listener
        }

        override fun toItem(): SettingItem {
            if (key.isEmpty())
                throw IllegalStateException("InputSetting 'key' string should be not empty")

            if (text.isNullOrEmpty())
                throw IllegalStateException("InputSetting 'text' string should be not empty")

            return InputSetting(
                key,
                text,
                defaultValue,
                subtext,
                icon,
                onChangeCallback = changeListener
                    ?.let { fromLambda(it::onChange) },
                onLongClickCallback = longClickListener
                    ?.let { fromLambda(it::onLongClick) },
                linkAlias
            )
        }
    }

    public fun input(key: String, init: InputSettingScope.() -> Unit) {
        settings.add(
            InputSettingScope(key)
                .apply(init)
                .toItem()
        )
    }

    public class EditTextSettingScope(private val key: String) : SettingScope() {
        public var hint: String? = null
        public var mask: String? = null
        public var defaultValue: String = ""
        public var maxLength: Int = 0
        public var multiline: Boolean = true

        private var changeListener: SettingChangeListener<String>? = null

        public fun onChange(listener: SettingChangeListener<String>) {
            this.changeListener = listener
        }

        override fun toItem(): SettingItem {
            if (key.isEmpty())
                throw IllegalStateException("EditTextSetting 'key' string should be not empty")

            if (hint.isNullOrEmpty())
                throw IllegalStateException("EditTextSetting 'hint' string should be not empty")

            return EditTextSetting(
                key,
                hint,
                defaultValue,
                multiline,
                maxLength,
                mask,
                onChangeCallback = changeListener
                    ?.let { fromLambda(it::onChange) },
            ).apply {
                this@apply.icon = this@EditTextSettingScope.icon

                this@apply.onLongClickCallback = this@EditTextSettingScope.longClickListener
                    ?.let { fromLambda(it::onLongClick) }

                this@apply.linkAlias = this@EditTextSettingScope.linkAlias
            }
        }
    }

    public fun editText(key: String, init: EditTextSettingScope.() -> Unit) {
        settings.add(
            EditTextSettingScope(key)
                .apply(init)
                .toItem()
        )
    }

    public class SwitchSettingScope(private val key: String) : SettingScope() {
        public var text: String? = null
        public var subtext: String? = null
        public var defaultValue: Boolean = false
        private var changeListener: SettingChangeListener<Boolean>? = null

        public fun onChange(listener: SettingChangeListener<Boolean>) {
            this.changeListener = listener
        }

        override fun toItem(): SettingItem {
            if (key.isEmpty())
                throw IllegalStateException("SwitchSetting 'key' string should be not empty")

            if (text.isNullOrEmpty())
                throw IllegalStateException("SwitchSetting 'text' string should be not empty")

            return SwitchSetting(
                key,
                text,
                defaultValue,
                subtext,
                icon,
                onChangeCallback = changeListener
                    ?.let { fromLambda(it::onChange) },
                onLongClickCallback = longClickListener
                    ?.let { fromLambda(it::onLongClick) },
                linkAlias
            )
        }
    }

    public fun switch(key: String, init: SwitchSettingScope.() -> Unit) {
        settings.add(
            SwitchSettingScope(key)
                .apply(init)
                .toItem()
        )
    }

    public class TextSettingScope : SettingScope() {
        public var text: String? = null
        public var subtext: String? = null
        public var accent: Boolean = false
        public var red: Boolean = false
        private var clickListener: SettingClickListener? = null
        private var createSubFragmentListener: (() -> PyObject)? = null

        public fun onClick(listener: SettingClickListener) {
            this.clickListener = listener
        }

        public fun subFragment(init: SettingsScope.() -> Unit) {
            this.createSubFragmentListener = {
                val list = Python.getInstance().builtins.callAttr("list")

                SettingsScope()
                    .apply(init)
                    .toList()
                    .forEach { list.callAttr("append", it) }

                list
            }
        }

        override fun toItem(): SettingItem {
            if (text.isNullOrEmpty())
                throw IllegalStateException("TextSetting 'text' string should be not empty")

            val onClick = clickListener
                ?.let { fromLambda(it::onClick) }
            val createSubFragment = createSubFragmentListener
                ?.let(::fromLambda0)
            val onLongClick = longClickListener
                ?.let { fromLambda(it::onLongClick) }

            `TextSetting$$init$$12_5_1`?.let {
                return it.invoke(
                    text, subtext, icon, accent, red,
                    onClick, createSubFragment, onLongClick, linkAlias,
                ) as TextSetting
            }

            `TextSetting$$init$$12_1_1`?.let {
                return it.invoke(
                    text, icon, accent, red,
                    onClick, createSubFragment, onLongClick, linkAlias,
                ) as TextSetting
            }

            throw NoSuchMethodException("TextSetting constructor is not available")
        }
    }

    public fun text(init: TextSettingScope.() -> Unit) {
        settings.add(
            TextSettingScope()
                .apply(init)
                .toItem()
        )
    }

    public fun toList(): List<SettingItem> = settings
}