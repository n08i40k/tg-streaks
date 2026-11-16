package ru.n08i40k.shim.chaquopy

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities
import java.lang.reflect.Proxy

private fun proxy(`interface`: Class<*>, invoke: (Array<out Any?>?) -> Any?): PyObject =
    PyObject.fromJava(
        Proxy.newProxyInstance(
            ApplicationLoader::class.java.classLoader,
            arrayOf(`interface`),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy == args?.get(0)
                "toString" -> "PyCallback@${Integer.toHexString(System.identityHashCode(proxy))}"
                else -> invoke(args).takeUnless { it === Unit }
            }
        }
    )

internal fun <R> fromLambda0(callback: () -> R): PyObject =
    proxy(Utilities.Callback0Return::class.java) { callback() }

internal inline fun <reified T, R> fromLambda(noinline callback: (T) -> R): PyObject =
    proxy(Utilities.CallbackReturn::class.java) { callback(it?.firstOrNull() as T) }

internal fun newDict(): PyObject =
    Python.getInstance().builtins.callAttr("dict")

internal inline fun <reified T> PyObject.setItem(key: String, value: T): PyObject? =
    this.callAttr("__setitem__", key, value)