package ru.n08i40k.streaks.util

import android.os.Bundle
import org.telegram.messenger.MessagesStorage
import org.telegram.ui.DialogsActivity
import java.lang.reflect.Proxy

private const val DIALOGS_TYPE_FORWARD = 3

private val DELEGATE_CLASS = DialogsActivity.DialogsActivityDelegate::class.java

// the delegate signature drifts between client versions, so it is implemented via a proxy
fun createDialogPicker(onPicked: (peerId: Long) -> Unit): DialogsActivity {
    val fragment = DialogsActivity(Bundle().apply {
        putBoolean("onlySelect", true)
        putBoolean("resetDelegate", false)
        putBoolean("closeFragment", true)
        putInt("dialogsType", DIALOGS_TYPE_FORWARD)
    })

    val delegate = Proxy.newProxyInstance(
        DELEGATE_CLASS.classLoader,
        arrayOf(DELEGATE_CLASS)
    ) { _, method, args ->
        if (method.name != "didSelectDialogs")
            return@newProxyInstance false

        val peerId = (args?.getOrNull(1) as? List<*>)
            ?.filterIsInstance<MessagesStorage.TopicKey>()
            ?.firstOrNull()
            ?.dialogId

        // the client ignores the returned value and never closes the picker on its own
        fragment.finishFragment()

        peerId?.let(onPicked)

        true
    } as DialogsActivity.DialogsActivityDelegate

    fragment.setDelegate(delegate)

    return fragment
}
