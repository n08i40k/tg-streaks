package ru.n08i40k.streaks.data

enum class StreakPetTaskType(val points: Int) {
    EXCHANGE_ONE_MESSAGE(1),
    SEND_FOUR_MESSAGES_EACH(2),
    SEND_TEN_MESSAGES_EACH(4),
    ;

    val defaultPayload: StreakPetTaskPayload
        get() =
            when (this) {
                EXCHANGE_ONE_MESSAGE -> StreakPetTaskPayload.ExchangeOneMessage.empty
                SEND_FOUR_MESSAGES_EACH -> StreakPetTaskPayload.SendFourMessagesEach.empty
                SEND_TEN_MESSAGES_EACH -> StreakPetTaskPayload.SendTenMessagesEach.empty
            }
}
