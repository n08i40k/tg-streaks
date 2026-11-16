package ru.n08i40k.shim.settings

public fun interface SettingChangeListener<T> {
    public fun onChange(value: T)
}