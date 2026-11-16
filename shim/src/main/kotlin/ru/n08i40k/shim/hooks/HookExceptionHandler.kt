package ru.n08i40k.shim.hooks

import java.lang.reflect.Member

public fun interface HookExceptionHandler {
    public fun onException(member: Member, hookOrder: HookOrder, cause: Throwable)
}