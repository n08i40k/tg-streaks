package ru.n08i40k.reflection.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode

internal class Version private constructor(
    private val parts: List<Int>,
    val text: String
) : Comparable<Version> {
    val suffix: String
        get() = text.replace('.', '_')

    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }

            if (diff != 0)
                return diff
        }

        return 0
    }

    override fun toString(): String =
        text

    companion object {
        private val PATTERN = Regex("\\d+(\\.\\d+)*")

        fun parse(text: String): Version? =
            if (PATTERN.matches(text))
                Version(text.split('.').map { it.toInt() }, text)
            else
                null
    }
}

/** Client versions that have the member: [since] inclusive, [before] exclusive. */
internal class Availability(val optional: Boolean, val since: Version?, val before: Version?) {
    val nullable: Boolean
        get() = optional || since != null || before != null

    fun intersect(other: Availability): Availability =
        Availability(
            optional || other.optional,
            listOfNotNull(since, other.since).maxOrNull(),
            listOfNotNull(before, other.before).minOrNull(),
        )

    companion object {
        val ALWAYS = Availability(false, null, null)
    }
}

/** [descriptor] is the JVM descriptor, [expression] evaluates to the Class<*> in generated code. */
internal class TypeRef(
    val expression: String,
    val descriptor: String,
    val runtimeClass: RuntimeClass?
)

internal class Parameter(
    val type: TypeRef,
    val name: String?
)

internal class RuntimeClass(
    val declaration: KSClassDeclaration,
    val binaryName: String,
    val propertyName: String,
    val availability: Availability,
    val public: Boolean,
) {
    val internalName: String
        get() = binaryName.replace('.', '/')
}

internal class Owner(
    val declaration: KSClassDeclaration,
    val prefix: String,
    val internalName: String,
    val expression: String,
    val runtimeClass: RuntimeClass?,
    val public: Boolean,
    val members: List<Member>,
)

internal enum class FieldAccess {
    GET,
    SET
}

internal sealed class Member {
    abstract val node: KSNode
    abstract val propertyName: String
    abstract val availability: Availability
    abstract val doc: String?
}

internal class FieldMember(
    override val node: KSNode,
    override val propertyName: String,
    override val availability: Availability,
    override val doc: String?,
    val access: FieldAccess,
    val name: String,
    val retype: List<TypeRef>?,
) : Member()

/** A method, or a constructor when [name] is `<init>`. */
internal class MethodMember(
    override val node: KSNode,
    override val propertyName: String,
    override val availability: Availability,
    override val doc: String?,
    val name: String,
    val parameters: List<Parameter>,
    val retype: List<TypeRef>?,
) : Member() {
    val isConstructor: Boolean
        get() = name == "<init>"

    val parametersDescriptor: String
        get() = parameters.joinToString("", "(", ")") { it.type.descriptor }
}

internal class FieldsMember(
    override val node: KSNode,
    override val propertyName: String,
    override val availability: Availability,
    override val doc: String?,
) : Member()
