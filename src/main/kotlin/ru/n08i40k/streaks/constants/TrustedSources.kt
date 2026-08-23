package ru.n08i40k.streaks.constants

object TrustedSources {
    data class TrustedSource(val id: Long, val tag: String)

    val LEAD = TrustedSource(996004735, "n08i40k")
    val CHANNEL = TrustedSource(3740294298, "n08i40k_extera")
    val CHAT = TrustedSource(3873784231, "n08i40k_extera_chat")
}