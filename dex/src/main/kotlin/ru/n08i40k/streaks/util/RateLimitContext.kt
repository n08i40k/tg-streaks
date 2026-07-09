package ru.n08i40k.streaks.util

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class RateLimitContext(
    val onThrottleUpdate: ((throttlingClock: Pair<Int, Int>?) -> Unit)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RateLimitContext>
}
