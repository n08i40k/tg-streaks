package ru.n08i40k.guides.api

interface GuideBadgeSession {
    fun update(badges: Map<Long, GuideBadge?>)

    fun close()
}
