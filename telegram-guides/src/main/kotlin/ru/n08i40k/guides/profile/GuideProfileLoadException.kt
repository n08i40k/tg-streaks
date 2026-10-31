package ru.n08i40k.guides.profile

internal class GuideProfileLoadException(val profile: String, reason: String) :
    IllegalStateException("Could not load $profile: $reason")
