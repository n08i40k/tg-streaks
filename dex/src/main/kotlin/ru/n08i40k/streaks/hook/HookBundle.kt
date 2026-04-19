package ru.n08i40k.streaks.hook


abstract class HookBundle {
    abstract fun inject(before: InstallHook, after: InstallHook)

    fun eject() {}
}