package ru.n08i40k.streaks.constants

@Suppress("FunctionName")
object ServiceMessage {
    const val CREATE_TEXT = "tg-streaks:create"
    val UPGRADE_REGEX = Regex("^tg-streaks:upgrade:(\\d+)$")

    const val DEATH_TEXT = "tg-streaks:death"
    const val RESTORE_TEXT = "tg-streaks:restore"

    fun isServiceText(text: String?): Boolean =
        text == CREATE_TEXT
            || text == DEATH_TEXT
            || text == RESTORE_TEXT
            || text?.let { UPGRADE_REGEX.matches(it) } == true

    fun UPGRADE_TEXT(length: Int) = "tg-streaks:upgrade:$length"
}
