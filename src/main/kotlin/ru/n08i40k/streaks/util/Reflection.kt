package ru.n08i40k.streaks.util

import java.lang.reflect.Field
import java.lang.reflect.Modifier

fun getAccessibleFields(klass: Class<*>): Set<Field> {
    val fields = hashSetOf<Field>()

    var c: Class<*>? = klass

    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue

            f.isAccessible = true
            fields.add(f)
        }

        c = c.superclass
    }

    return fields
}
fun cloneFields(
    src: Any,
    dest: Any,
    // can be got by calling getAccessibleFields
    fields: Collection<Field>
) {
    for (field in fields) {
       field.set(dest, field.get(src))
    }
}

fun getField(klass: Class<*>, name: String): Field {
    val field = klass.getDeclaredField(name)
    field.isAccessible = true

    return field
}

inline fun <reified T> Field.getAs(obj: Any?): T? =
    this.get(obj) as? T

inline fun <reified T> Field.getAsUnchecked(obj: Any?): T =
    this.get(obj) as T

fun Field.addInt(obj: Any?, value: Int) =
    set(obj, getAs<Int>(obj)!! + value)

