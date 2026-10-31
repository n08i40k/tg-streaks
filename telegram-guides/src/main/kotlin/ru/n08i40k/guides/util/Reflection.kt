package ru.n08i40k.guides.util

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field

internal fun MethodHandle.retype(returnType: Class<*>, vararg parameterTypes: Class<*>): MethodHandle =
    asType(MethodType.methodType(returnType, parameterTypes))

private fun getAccessibleField(klass: Class<*>, name: String): Field {
    val field = klass.getDeclaredField(name)
    field.isAccessible = true

    return field
}

internal fun getFieldGetter(klass: Class<*>, name: String): MethodHandle =
    MethodHandles.lookup()
        .unreflectGetter(getAccessibleField(klass, name))

internal fun getFieldSetter(klass: Class<*>, name: String): MethodHandle =
    MethodHandles.lookup()
        .unreflectSetter(getAccessibleField(klass, name))

internal fun getMethodHandle(klass: Class<*>, name: String, vararg parameterTypes: Class<*>): MethodHandle {
    val method = klass.getDeclaredMethod(name, *parameterTypes)
    method.isAccessible = true

    return MethodHandles.lookup().unreflect(method)
}
