package ru.n08i40k.shim.menu

import com.chaquo.python.PyObject

public class MenuScope internal constructor() {
    internal val items = mutableListOf<PyObject>()

    @PublishedApi
    internal fun newItem(type: String, id: String, action: MenuItemScope.() -> Unit) {
        val scope = MenuItemScope(type, id)
        scope.action()

        items.add(scope.toPyObject())
    }

    public fun inChatActionMenu(id: String, action: MenuItemScope.() -> Unit): Unit =
        newItem("chat_action_menu", id, action)

    public fun inDrawerMenu(id: String, action: MenuItemScope.() -> Unit): Unit =
        newItem("drawer_menu", id, action)

    public fun inMessageContextMenu(id: String, action: MenuItemScope.() -> Unit): Unit =
        newItem("message_context_menu", id, action)

    public fun inProfileActionMenu(id: String, action: MenuItemScope.() -> Unit): Unit =
        newItem("profile_action_menu", id, action)
}