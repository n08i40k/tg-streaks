package ru.n08i40k.shim.hooks

public enum class HookOrder {
    BEFORE,
    AFTER;

    override fun toString(): String {
        return when (this) {
            BEFORE -> "before"
            AFTER -> "after"
        }
    }
}