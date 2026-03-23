package ru.n08i40k.streaks.constants

@Suppress("FunctionName")
object ServiceMessage {
    // streak
    const val CREATE_TEXT = "tg-streaks:create"
    const val DEATH_TEXT = "tg-streaks:death"

    const val RESTORE_TEXT = "tg-streaks:restore"

    val UPGRADE_REGEX = Regex("^tg-streaks:upgrade:(\\d+)$")

    fun UPGRADE_TEXT(length: Int) = "tg-streaks:upgrade:$length"

    // streak pet
    const val PET_INVITE_TEXT = "tg-streaks:pet:invite"

    fun isServiceText(text: String?): Boolean =
        text == CREATE_TEXT
                || text == DEATH_TEXT
                || text == RESTORE_TEXT
                || text == PET_INVITE_TEXT
                || text?.let { UPGRADE_REGEX.matches(it) } == true
}
