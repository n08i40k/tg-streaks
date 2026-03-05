@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package ru.n08i40k.streaks

import java.lang.reflect.Field
import java.lang.reflect.Modifier

enum class CloneFieldDirection {
    FROM_SOURCE,
    FROM_DESTINATION
}

fun cloneFields(
    src: Object,
    dest: Object,
    direction: CloneFieldDirection = CloneFieldDirection.FROM_SOURCE
) {
    var c: Class<*>? = when (direction) {
        CloneFieldDirection.FROM_SOURCE -> src.`class`
        CloneFieldDirection.FROM_DESTINATION -> dest.`class`
    }

    while (c != null && c != Object::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue;

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

inline fun <reified T> getFieldValue(klass: Class<*>, obj: Any, name: String): T? =
    getField(klass, name).get(obj) as? T

fun setFieldValue(klass: Class<*>, obj: Any, name: String, value: Any) =
    getField(klass, name).set(obj, value)
