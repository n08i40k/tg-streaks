package ru.n08i40k.guides.model

data class GuideScript(val title: String, val steps: List<GuideStep>) {
    init {
        require(steps.isNotEmpty()) { "A guide must have at least one step" }

        val state = ValidationState()

        for ((index, step) in steps.withIndex()) {
            for (action in step.actions) {
                try {
                    state.apply(action)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Step ${index + 1} \"${step.heading}\", $action: ${error.message}", error)
                }
            }
        }
    }
}

private class ValidationState {
    private var chats = emptyMap<Long, GuideChat>()
    private var inChat = false
    private var panels = 0
    private var menuOpen = false
    private var messages = emptyList<GuideMessage>()

    fun apply(action: GuideAction) {
        when (action) {
            is GuideAction.ShowChats -> {
                chats = action.chats.associateBy { it.id }
                require(chats.size == action.chats.size) { "Duplicate guide chat IDs" }
                inChat = false
                panels = 0
                menuOpen = false
                messages = emptyList()
            }

            is GuideAction.UpdateChat -> {
                requireList()
                requireChat(action.chat.id)
                chats = chats + (action.chat.id to action.chat)
            }

            is GuideAction.ScrollToChat -> {
                requireList()
                requireChat(action.id)
            }

            is GuideAction.TapChat -> {
                requireList()
                requireChat(action.id)
            }

            is GuideAction.HighlightChat -> {
                requireList()
                action.id?.let(::requireChat)
            }

            is GuideAction.SetBadge -> requireChat(action.id)

            is GuideAction.OpenChat -> {
                require(!inChat) { "Return to the list before opening another chat" }
                require(requireChat(action.id).profile != null) { "Only chats with a profile can be opened" }
                inChat = true
                messages = action.messages
            }

            GuideAction.ReturnToChats -> {
                require(inChat) { "No chat is open" }
                inChat = false
                panels = 0
                menuOpen = false
            }

            is GuideAction.ShowMessages -> {
                requireChatScene()
                messages = action.messages
            }

            is GuideAction.SendMessage -> {
                require(action.message.outgoing) { "SendMessage expects an outgoing message" }
                append(action.message)
            }

            is GuideAction.ReceiveMessage -> {
                require(!action.message.outgoing) { "ReceiveMessage expects an incoming message" }
                append(action.message)
            }

            is GuideAction.AppendMessage -> append(action.message)

            is GuideAction.TapMessage -> {
                requireChatScene()
                require(messages.any { it.id == action.id }) { "Unknown guide message ${action.id}" }
            }

            GuideAction.OpenChatMenu -> {
                requireChatScene()
                menuOpen = true
            }

            GuideAction.OpenPluginsMenu,
            is GuideAction.TapMenuItem -> {
                requireChatScene()
                require(menuOpen) { "Use OpenChatMenu first" }
            }

            is GuideAction.OpenPanel -> {
                require(inChat) { "Panels open over a chat" }
                panels++
                menuOpen = false
            }

            GuideAction.Back -> {
                require(panels > 0) { "No panel is open" }
                panels--
            }

            is GuideAction.Wait,
            is GuideAction.PrefetchProfiles,
            is GuideAction.TapAt,
            is GuideAction.SetTitle,
            is GuideAction.TapTarget,
            is GuideAction.ScrollTarget,
            is GuideAction.Extension -> Unit
        }
    }

    private fun requireChat(id: Long): GuideChat =
        requireNotNull(chats[id]) { "Unknown guide chat $id; list it with ShowChats first" }

    private fun requireList() =
        require(!inChat) { "Use ReturnToChats before list actions" }

    private fun requireChatScene() =
        require(inChat && panels == 0) { "Open a chat and close its panels first" }

    private fun append(message: GuideMessage) {
        requireChatScene()
        messages = messages + message
        validateGuideMessages(messages)
    }
}
