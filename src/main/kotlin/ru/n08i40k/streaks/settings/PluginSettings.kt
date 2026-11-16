package ru.n08i40k.streaks.settings

object PluginSettings {
    val updateCheck = BooleanSetting("update_check_enabled", default = true)

    val autoStreakCreation = BooleanSetting("auto_streak_creation_enabled", default = true)

    val petFabSize = ChoiceSetting(
        "pet_fab_size_index",
        options = listOf(64, 80, 96, 112, 128),
        defaultIndex = 1,
    )
}
