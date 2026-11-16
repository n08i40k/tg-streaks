package ru.n08i40k.shim.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member

public class HooksScope(
    @PublishedApi internal val unhooks: MutableList<XC_MethodHook.Unhook>,
    @PublishedApi internal val exceptionHandler: HookExceptionHandler,
) {
    public inline fun before(
        member: Member,
        crossinline action: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        unhooks += XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    action(param)
                } catch (e: Throwable) {
                    exceptionHandler.onException(member, HookOrder.BEFORE, e)
                }
            }
        })
    }

    public inline fun after(
        member: Member,
        crossinline action: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        unhooks += XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    action(param)
                } catch (e: Throwable) {
                    exceptionHandler.onException(member, HookOrder.AFTER, e)
                }
            }
        })
    }
}

public fun hooks(exceptionHandler: HookExceptionHandler, action: HooksScope.() -> Unit): Unhooks {
    val unhooks = mutableListOf<XC_MethodHook.Unhook>()

    try {
        HooksScope(unhooks, exceptionHandler).action()
    } catch (e: Throwable) {
        unhooks.forEach(XC_MethodHook.Unhook::unhook)
        throw e
    }

    return Unhooks {
        unhooks.forEach(XC_MethodHook.Unhook::unhook)
        unhooks.clear()
    }
}