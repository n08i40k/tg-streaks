package ru.n08i40k.streaks.registry

class LockableCallbackRegistry {
    private val map = HashMap<String, java.util.function.Consumer<Long>>()
    private var frozen = false

    fun register(key: String, callback: java.util.function.Consumer<Long>) {
        if (frozen)
            throw IllegalStateException("Registry is frozen")

        if (map.containsKey(key))
            throw IllegalArgumentException("Registry already has entry with provided key")

        map[key] = callback
    }

    fun freeze() {
        if (frozen)
            throw IllegalStateException("Registry already frozen")

        frozen = true
    }

    fun get(key: String): java.util.function.Consumer<Long> {
        if (!frozen)
            throw IllegalStateException("Registry isn't frozen")

        return map[key]
            ?: throw IllegalArgumentException("Registry doesn't contains callback with provided key")
    }

    fun clear() {
        frozen = false
        map.clear()
    }
}