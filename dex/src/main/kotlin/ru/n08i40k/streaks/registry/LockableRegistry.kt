package ru.n08i40k.streaks.registry

open class LockableRegistry<T> {
    private val map = HashMap<String, T>()
    private var frozen = false

    fun register(key: String, value: T) {
        if (frozen)
            throw IllegalStateException("Registry is frozen")

        if (map.containsKey(key))
            throw IllegalArgumentException("Registry already has entry with provided key")

        map[key] = value
    }

    fun freeze() {
        if (frozen)
            throw IllegalStateException("Registry already frozen")

        frozen = true
    }

    fun get(key: String): T {
        if (!frozen)
            throw IllegalStateException("Registry isn't frozen")

        return map[key]
            ?: throw IllegalArgumentException("Registry doesn't contains value with provided key")
    }

    fun clear() {
        frozen = false
        map.clear()
    }
}
