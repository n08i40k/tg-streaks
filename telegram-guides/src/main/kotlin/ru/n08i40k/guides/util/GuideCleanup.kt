package ru.n08i40k.guides.util

internal object GuideCleanup {
    fun run(vararg actions: Runnable) {
        var failure: Throwable? = null
        for (action in actions) {
            try {
                action.run()
            } catch (error: Throwable) {
                val original = failure
                if (original == null)
                    failure = error
                else if (original !== error)
                    original.addSuppressed(error)
            }
        }

        failure?.let { throw it }
    }
}
