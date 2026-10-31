package ru.n08i40k.guides.api

import android.content.Context
import android.os.Bundle
import android.view.View
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.guides.scene.chat.GuideChatActivity

interface GuidePresentation {
    fun chat(arguments: Bundle): GuideChatActivity =
        GuideChatActivity(arguments)

    fun message(account: Int, source: TLRPC.Message, content: GuideMessageContent): MessageObject =
        error("Unsupported guide message content: ${content.javaClass.name}")

    fun openBadges(root: View, account: Int): GuideBadgeSession? =
        null

    fun panel(context: Context, key: String, peer: TLObject, account: Int): GuideScene
}
