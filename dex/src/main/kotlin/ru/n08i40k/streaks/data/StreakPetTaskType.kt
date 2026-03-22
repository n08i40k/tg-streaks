package ru.n08i40k.streaks.data

enum class StreakPetTaskType {
    EXCHANGE_ONE_MESSAGE,
    SEND_FOUR_MESSAGES_EACH,
    SEND_TEN_MESSAGES_EACH,
    ;

    val defaultPayload: StreakPetTaskPayload
        get() =
            when (this) {
                EXCHANGE_ONE_MESSAGE -> StreakPetTaskPayload.ExchangeOneMessage.empty
                SEND_FOUR_MESSAGES_EACH -> StreakPetTaskPayload.SendFourMessagesEach.empty
                SEND_TEN_MESSAGES_EACH -> StreakPetTaskPayload.SendTenMessagesEach.empty
            }
}
