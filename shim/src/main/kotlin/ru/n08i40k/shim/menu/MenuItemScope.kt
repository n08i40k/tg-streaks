package ru.n08i40k.shim.menu

import com.chaquo.python.PyObject
import org.mvel2.MVEL
import ru.n08i40k.shim.chaquopy.fromLambda
import ru.n08i40k.shim.chaquopy.newDict
import ru.n08i40k.shim.chaquopy.setItem

public class MenuItemScope internal constructor(
    private val type: String,
    private val id: String
) {
    public var icon: String? = null

    public var text: String? = null
    public var subtext: String? = null

    public var priority: Int = 0

    public var condition: String? = null

    private var clickListener: MenuItemClickListener? = null

    public fun onClick(listener: MenuItemClickListener) {
        this.clickListener = listener
    }

    internal fun toPyObject(): PyObject {
        if (text.isNullOrEmpty() || clickListener == null)
            throw IllegalArgumentException("MenuItemRecord missing essential fields: text or onClickCallback.")

        try {
            if (!condition.isNullOrBlank())
                MVEL.compileExpression(condition)
        } catch (e: Throwable) {
            throw IllegalArgumentException("Bad MVEL expression passed into MenuItemRecord.", e)
        }

        return newDict().apply {
            setItem("menu_type", type)
            setItem("item_id", id)

            if (!icon.isNullOrBlank())
                setItem("icon", icon)

            setItem("text", text)

            if (!subtext.isNullOrBlank())
                setItem("subtext", subtext)

            setItem("priority", priority)

            if (!condition.isNullOrBlank())
                setItem("condition", condition)

            clickListener?.let { setItem("on_click", fromLambda(it::onClick)) }
        }
    }
}