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
    const val PET_INVITE_ACCEPTED_TEXT = "tg-streaks:pet:invite:accepted"

    val PET_SET_NAME_REGEX = Regex("^tg-streaks:pet:set-name:(.+)$")

    fun PET_SET_NAME_TEXT(name: String) = "tg-streaks:pet:set-name:$name"

    fun isServiceText(text: String?): Boolean =
        text == CREATE_TEXT
                || text == DEATH_TEXT
                || text == RESTORE_TEXT
                || text == PET_INVITE_TEXT
                || text == PET_INVITE_ACCEPTED_TEXT
                || text?.let { UPGRADE_REGEX.matches(it) } == true
                || text?.let { PET_SET_NAME_REGEX.matches(it) } == true
}
