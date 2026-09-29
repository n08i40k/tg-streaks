package ru.n08i40k.streaks.util

import sun.misc.Unsafe
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

fun joinTypes(vararg parameterTypes: Class<*>?): String =
    parameterTypes
        .map { it?.name ?: "null" }
        .joinToString(", ")

// Classes

fun getClassIfExists(name: String): Class<*>? {
    try {
        return Class.forName(name)
    } catch (e: Throwable) {
        Logger.info("Class $name is not available: ${e.javaClass.simpleName}: ${e.message}")
        return null
    }
}

// Methods

fun getConstructorIfExists(
    klass: Class<*>?,
    vararg parameterTypes: Class<*>?
): Constructor<out Any>? {
    if (klass == null) {
        Logger.info("Constructor (${joinTypes(*parameterTypes)}) is not available: class is null")
        return null
    }

    if (parameterTypes.any { it == null }) {
        Logger.info("Constructor (${joinTypes(*parameterTypes)}) is not available: parameter type is null")
        return null
    }

    try {
        val method = klass.getDeclaredConstructor(*parameterTypes)
        method.isAccessible = true

        return method
    } catch (e: Throwable) {
        Logger.info("Constructor $klass(${joinTypes(*parameterTypes)}) is not available: ${e.javaClass.simpleName}: ${e.message}")
        return null
    }
}

fun getMethodHandle(klass: Class<*>, name: String, vararg parameterTypes: Class<*>): MethodHandle {
    val method = klass.getDeclaredMethod(name, *parameterTypes)
    method.isAccessible = true

    return MethodHandles.lookup().unreflect(method)
}

// Fields

class ClonableFields(
    val fields: Array<Field>,
    val kinds: ByteArray,
    val offsets: LongArray?,
)

private const val KIND_OBJECT: Byte = 0
private const val KIND_BOOLEAN: Byte = 1
private const val KIND_BYTE: Byte = 2
private const val KIND_CHAR: Byte = 3
private const val KIND_SHORT: Byte = 4
private const val KIND_INT: Byte = 5
private const val KIND_LONG: Byte = 6
private const val KIND_FLOAT: Byte = 7
private const val KIND_DOUBLE: Byte = 8

private val unsafe: Unsafe? =
    try {
        val field = Unsafe::class.java.declaredFields
            .first { Modifier.isStatic(it.modifiers) && it.type == Unsafe::class.java }
        field.isAccessible = true
        field.get(null) as Unsafe
    } catch (e: Throwable) {
        Logger.info("sun.misc.Unsafe is not available, falling back to reflection: ${e.message}")
        null
    }

private fun fieldKind(type: Class<*>): Byte =
    when (type) {
        Boolean::class.javaPrimitiveType -> KIND_BOOLEAN
        Byte::class.javaPrimitiveType -> KIND_BYTE
        Char::class.javaPrimitiveType -> KIND_CHAR
        Short::class.javaPrimitiveType -> KIND_SHORT
        Int::class.javaPrimitiveType -> KIND_INT
        Long::class.javaPrimitiveType -> KIND_LONG
        Float::class.javaPrimitiveType -> KIND_FLOAT
        Double::class.javaPrimitiveType -> KIND_DOUBLE
        else -> KIND_OBJECT
    }

fun getAccessibleFields(klass: Class<*>): ClonableFields {
    val fields = arrayListOf<Field>()

    var c: Class<*>? = klass

    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue

            f.isAccessible = true
            fields.add(f)
        }

        c = c.superclass
    }

    val offsets = unsafe?.let { unsafe ->
        try {
            LongArray(fields.size) { unsafe.objectFieldOffset(fields[it]) }
        } catch (e: Throwable) {
            Logger.info("Unable to resolve field offsets of $klass, falling back to reflection: ${e.message}")
            null
        }
    }

    return ClonableFields(
        fields.toTypedArray(),
        ByteArray(fields.size) { fieldKind(fields[it].type) },
        offsets,
    )
}

fun cloneFields(
    src: Any,
    dest: Any,
    // can be got by calling getAccessibleFields
    fields: ClonableFields
) {
    val offsets = fields.offsets
    val unsafe = unsafe

    if (offsets == null || unsafe == null) {
        for (field in fields.fields) {
            field.set(dest, field.get(src))
        }
        return
    }

    val kinds = fields.kinds

    for (i in offsets.indices) {
        val offset = offsets[i]

        when (kinds[i]) {
            KIND_BOOLEAN -> unsafe.putBoolean(dest, offset, unsafe.getBoolean(src, offset))
            KIND_BYTE -> unsafe.putByte(dest, offset, unsafe.getByte(src, offset))
            KIND_CHAR -> unsafe.putChar(dest, offset, unsafe.getChar(src, offset))
            KIND_SHORT -> unsafe.putShort(dest, offset, unsafe.getShort(src, offset))
            KIND_INT -> unsafe.putInt(dest, offset, unsafe.getInt(src, offset))
            KIND_LONG -> unsafe.putLong(dest, offset, unsafe.getLong(src, offset))
            KIND_FLOAT -> unsafe.putFloat(dest, offset, unsafe.getFloat(src, offset))
            KIND_DOUBLE -> unsafe.putDouble(dest, offset, unsafe.getDouble(src, offset))
            else -> unsafe.putObject(dest, offset, unsafe.getObject(src, offset))
        }
    }
}

// invokeExact требует точного совпадения статических типов в месте вызова,
// поэтому handle заранее приводится к типам, которые там доступны
fun MethodHandle.retype(returnType: Class<*>, vararg parameterTypes: Class<*>): MethodHandle =
    asType(MethodType.methodType(returnType, parameterTypes))

private fun getAccessibleField(klass: Class<*>, name: String): Field {
    val field = klass.getDeclaredField(name)
    field.isAccessible = true

    return field
}

fun getFieldGetter(klass: Class<*>, name: String): MethodHandle =
    MethodHandles.lookup()
        .unreflectGetter(getAccessibleField(klass, name))

fun getFieldSetter(klass: Class<*>, name: String): MethodHandle =
    MethodHandles.lookup()
        .unreflectSetter(getAccessibleField(klass, name))

fun getFieldGetterIfExists(klass: Class<*>?, name: String): MethodHandle? {
    if (klass == null) {
        Logger.info("Field $name is not available: class is null")
        return null
    }

    try {
        return getFieldGetter(klass, name)
    } catch (e: Throwable) {
        Logger.info("Field $klass.$name is not available: ${e.javaClass.simpleName}: ${e.message}")
        return null
    }
}
