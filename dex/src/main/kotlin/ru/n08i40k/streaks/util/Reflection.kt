@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "unused")

package ru.n08i40k.streaks.util

import java.lang.reflect.Field
import java.lang.reflect.Modifier

fun cloneFields(
    src: Object,
    dest: Object,
    klass: Class<*>
) {
    var c: Class<*>? = klass

    while (c != null && c != Object::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue

            f.isAccessible = true
            f.set(dest, f.get(src))
        }

        c = c.superclass
    }
}

fun getField(klass: Class<*>, name: String): Field {
    val field = klass.getDeclaredField(name)
    field.isAccessible = true

    return field
}

inline fun <reified T> getFieldValue(obj: Any, name: String): T? =
    getField(obj.javaClass, name).get(obj) as? T

inline fun <reified T> getFieldValue(klass: Class<*>, obj: Any, name: String): T? =
    getField(klass, name).get(obj) as? T

fun setFieldValue(obj: Any, name: String, value: Any?) =
    getField(obj.javaClass, name).set(obj, value)

fun setFieldValue(klass: Class<*>, obj: Any, name: String, value: Any?) =
    getField(klass, name).set(obj, value)

fun addIntFieldValue(klass: Class<*>, obj: Any, name: String, value: Int) {
    val field = getField(klass, name)
    field.set(obj, field.get(obj) as Int + value)
}

fun addIntFieldValue(obj: Any, name: String, value: Int) {
    val field = getField(obj.javaClass, name)
    field.set(obj, field.get(obj) as Int + value)
}
