package ru.n08i40k.reflection.processor

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import org.objectweb.asm.Type
import java.io.File

private const val ANNOTATIONS = "ru.n08i40k.reflection.generator"
private const val MIRROR = "${ANNOTATIONS}.Mirror"
private const val RUNTIME_MIRROR = "${ANNOTATIONS}.RuntimeMirror"
private const val GETTER = "${ANNOTATIONS}.Getter"
private const val SETTER = "${ANNOTATIONS}.Setter"
private const val FIELDS = "${ANNOTATIONS}.Fields"
private const val CONSTRUCTOR = "${ANNOTATIONS}.Constructor"
private const val RETYPE = "${ANNOTATIONS}.Retype"
private const val SINCE = "${ANNOTATIONS}.Since"
private const val BEFORE = "${ANNOTATIONS}.Before"
private const val OPTIONAL = "${ANNOTATIONS}.Optional"
private const val TYPE = "${ANNOTATIONS}.Type"
private const val NAME = "${ANNOTATIONS}.Name"

/** Kotlin name, descriptor and boxed class of the primitives. */
private val PRIMITIVES = mapOf(
    "kotlin.Boolean" to Triple("Boolean", "Z", "java/lang/Boolean"),
    "kotlin.Byte" to Triple("Byte", "B", "java/lang/Byte"),
    "kotlin.Char" to Triple("Char", "C", "java/lang/Character"),
    "kotlin.Short" to Triple("Short", "S", "java/lang/Short"),
    "kotlin.Int" to Triple("Int", "I", "java/lang/Integer"),
    "kotlin.Long" to Triple("Long", "J", "java/lang/Long"),
    "kotlin.Float" to Triple("Float", "F", "java/lang/Float"),
    "kotlin.Double" to Triple("Double", "D", "java/lang/Double"),
)

private val PRIMITIVE_ARRAYS = mapOf(
    "kotlin.BooleanArray" to "[Z",
    "kotlin.ByteArray" to "[B",
    "kotlin.CharArray" to "[C",
    "kotlin.ShortArray" to "[S",
    "kotlin.IntArray" to "[I",
    "kotlin.LongArray" to "[J",
    "kotlin.FloatArray" to "[F",
    "kotlin.DoubleArray" to "[D",
)

/** Kotlin classes that are compiled to other JVM classes. */
private val MAPPED = mapOf(
    "kotlin.Any" to "java/lang/Object",
    "kotlin.String" to "java/lang/String",
    "kotlin.CharSequence" to "java/lang/CharSequence",
    "kotlin.Number" to "java/lang/Number",
    "kotlin.Throwable" to "java/lang/Throwable",
    "kotlin.Comparable" to "java/lang/Comparable",
    "kotlin.Enum" to "java/lang/Enum",
    "kotlin.Annotation" to "java/lang/annotation/Annotation",
    "kotlin.Cloneable" to "java/lang/Cloneable",
    "kotlin.collections.Iterable" to "java/lang/Iterable",
    "kotlin.collections.MutableIterable" to "java/lang/Iterable",
    "kotlin.collections.Iterator" to "java/util/Iterator",
    "kotlin.collections.MutableIterator" to "java/util/Iterator",
    "kotlin.collections.Collection" to "java/util/Collection",
    "kotlin.collections.MutableCollection" to "java/util/Collection",
    "kotlin.collections.List" to "java/util/List",
    "kotlin.collections.MutableList" to "java/util/List",
    "kotlin.collections.ListIterator" to "java/util/ListIterator",
    "kotlin.collections.MutableListIterator" to "java/util/ListIterator",
    "kotlin.collections.Set" to "java/util/Set",
    "kotlin.collections.MutableSet" to "java/util/Set",
    "kotlin.collections.Map" to "java/util/Map",
    "kotlin.collections.MutableMap" to "java/util/Map",
    "kotlin.collections.Map.Entry" to $$"java/util/Map$Entry",
    "kotlin.collections.MutableMap.MutableEntry" to $$"java/util/Map$Entry",
)

internal class ReflectionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private var processed = false
    private var failed = false

    private val runtimeClasses = HashMap<String, RuntimeClass>()

    private fun error(message: String, node: KSNode?) {
        failed = true
        logger.error(message, node)
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed)
            return emptyList()

        processed = true

        val mirrors =
            (resolver.getSymbolsWithAnnotation(MIRROR) +
                    resolver.getSymbolsWithAnnotation(RUNTIME_MIRROR))
                .filterIsInstance<KSClassDeclaration>()
                .distinct()
                .sortedWith(
                    compareBy(
                        { location(it).first },
                        { location(it).second }
                    )
                )
                .toList()

        if (mirrors.isEmpty())
            return emptyList()

        for (mirror in mirrors) {
            if (mirror.annotation(RUNTIME_MIRROR) != null)
                declareRuntimeClass(mirror)
        }

        val owners = mirrors
            .mapNotNull { buildOwner(it) }

        if (failed)
            return emptyList()

        validate(owners)

        if (failed)
            return emptyList()

        val optIn = options["reflection.optIn"]
            ?.toBoolean()
            ?: false

        for ((packageName, packageOwners) in owners.groupBy { it.declaration.packageName.asString() }) {
            val classes = runtimeClasses.values
                .filter { it.declaration.packageName.asString() == packageName }
                .sortedWith(
                    compareBy(
                        { location(it.declaration).first },
                        { location(it.declaration).second }
                    )
                )

            val code = generateFile(packageName, classes, packageOwners, optIn)

            val sources = packageOwners
                .mapNotNull { it.declaration.containingFile }
                .distinct()

            codeGenerator
                .createNewFile(
                    Dependencies(true, *sources.toTypedArray()),
                    packageName,
                    "Accessors"
                )
                .writer()
                .use { it.write(code) }
        }

        return emptyList()
    }

    private fun location(node: KSNode): Pair<String, Int> =
        (node.location as? FileLocation)
            ?.let { it.filePath to it.lineNumber }
            ?: ("" to 0)

    // Mirrors

    private fun declareRuntimeClass(mirror: KSClassDeclaration) {
        if (mirror.annotation(MIRROR) != null) {
            error("A class cannot be both @Mirror and @RuntimeMirror", mirror)
            return
        }

        val binaryName = mirror
            .annotation(RUNTIME_MIRROR)!!
            .argument("binaryName") as? String
            ?: return

        val availability = availability(mirror)
            ?: return

        val simpleName = binaryName
            .substringAfterLast('.')

        val propertyName = simpleName + (availability.since?.let { "$$${it.suffix}" } ?: "")

        val existing = runtimeClasses.put(
            binaryName,
            RuntimeClass(mirror, binaryName, propertyName, availability, mirror.isPublic())
        )

        if (existing != null) error(
            "$binaryName is already declared by ${existing.declaration.simpleName.asString()}",
            mirror
        )
    }

    private fun buildOwner(mirror: KSClassDeclaration): Owner? {
        if (mirror.classKind != ClassKind.INTERFACE) {
            error("A mirror must be an interface", mirror)
            return null
        }

        val runtimeClass = runtimeClasses.values
            .firstOrNull { it.declaration == mirror }

        val prefix: String
        val internalName: String
        val expression: String

        if (runtimeClass != null) {
            prefix = runtimeClass.propertyName
                .substringBefore("$$")

            internalName = runtimeClass.internalName
            expression = "`${runtimeClass.propertyName}`"
        } else {
            val target = (mirror.annotation(MIRROR)!!
                .argument("value") as? KSType)
                ?.declaration as? KSClassDeclaration

            if (target == null) {
                error("Cannot resolve the @Mirror class", mirror)
                return null
            }

            prefix = classNames(target)
                .joinToString("$")

            internalName = binaryName(target)
            expression = "${target.qualifiedName!!.asString()}::class.java"
        }

        val ownerAvailability = runtimeClass?.availability ?: Availability.ALWAYS
        val members = ArrayList<Member>()

        for (declaration in mirror.declarations) {
            when (declaration) {
                is KSPropertyDeclaration -> members += propertyMembers(
                    declaration,
                    prefix,
                    ownerAvailability
                )

                is KSFunctionDeclaration -> functionMember(
                    declaration,
                    prefix,
                    ownerAvailability
                )?.let { members += it }

                else -> error("Only properties and functions are allowed in a mirror", declaration)
            }
        }

        val names = HashMap<String, Member>()

        for (member in members) {
            names.put(member.propertyName, member)?.let {
                error(
                    "Accessor `${member.propertyName}` is already declared",
                    member.node
                )
            }
        }

        return Owner(
            mirror,
            prefix,
            internalName,
            expression,
            runtimeClass,
            mirror.isPublic(),
            members
        )
    }

    private fun propertyMembers(
        property: KSPropertyDeclaration,
        prefix: String,
        ownerAvailability: Availability
    ): List<Member> {
        val getter = property.annotation(GETTER)
        val setter = property.annotation(SETTER)
        val fields = property.annotation(FIELDS)

        if (getter == null && setter == null && fields == null) {
            error("Mark the property with @Getter, @Setter or @Fields", property)
            return emptyList()
        }

        if (fields != null && (getter != null || setter != null)) {
            error("@Fields cannot be combined with @Getter or @Setter", property)
            return emptyList()
        }

        val availability = availability(property)
            ?.intersect(ownerAvailability)
            ?: return emptyList()

        val doc = property.docString

        val suffix = availability(property)
            ?.since
            ?.let { "$$${it.suffix}" }
            ?: ""

        if (fields != null) {
            if (ownerAvailability.nullable) {
                error("@Fields requires a class that is always available", property)
                return emptyList()
            }

            return listOf(FieldsMember(property, $$$"$$${prefix}$$fields", availability, doc))
        }

        val name = property
            .annotation(NAME)
            ?.argument("value") as? String
            ?: property.simpleName.asString()

        return listOfNotNull(
            getter?.let {
                FieldMember(
                    property,
                    "${prefix}$${name}${suffix}",
                    availability,
                    doc,
                    FieldAccess.GET,
                    name,
                    retype(it, "retype", property)
                )
            },
            setter?.let {
                FieldMember(
                    property,
                    "${prefix}$${name}$\$setter${suffix}",
                    availability,
                    doc,
                    FieldAccess.SET,
                    name,
                    retype(it, "retype", property)
                )
            },
        )
    }

    private fun functionMember(
        function: KSFunctionDeclaration,
        prefix: String,
        ownerAvailability: Availability
    ): Member? {
        val own = availability(function)
            ?: return null

        var availability = own.intersect(ownerAvailability)

        val parameters = function.parameters.map { parameter ->
            val runtimeName = parameter
                .annotation(TYPE)
                ?.argument("binaryName") as? String

            val type = if (runtimeName != null) {
                val runtimeClass = runtimeClasses[runtimeName]

                if (runtimeClass == null) {
                    error("$runtimeName is not declared with @RuntimeMirror", parameter)
                    return null
                }

                TypeRef(
                    "`${runtimeClass.propertyName}`",
                    "L${runtimeClass.internalName};",
                    runtimeClass
                )
            } else {
                typeRef(parameter.type.resolve(), parameter)
                    ?: return null
            }

            type.runtimeClass
                ?.let { availability = availability.intersect(it.availability) }

            Parameter(type, parameter.name?.asString())
        }

        val retype = function
            .annotation(RETYPE)
            ?.let { retype(it, "types", function) }

        val suffix = own.since
            ?.let { "$$${it.suffix}" }
            ?: ""

        if (function.annotation(CONSTRUCTOR) != null) {
            return MethodMember(
                function,
                $$$"$$${prefix}$$init$$${suffix}",
                availability,
                function.docString,
                "<init>",
                parameters,
                retype
            )
        }

        val name = function
            .annotation(NAME)
            ?.argument("value") as? String
            ?: function.simpleName.asString()

        return MethodMember(
            function,
            "${prefix}$${name}${suffix}",
            availability,
            function.docString,
            name,
            parameters,
            retype
        )
    }

    private fun retype(annotation: KSAnnotation, argument: String, node: KSNode): List<TypeRef>? {
        val types = (annotation.argument(argument) as? List<*>)
            ?.filterIsInstance<KSType>()

        if (types.isNullOrEmpty())
            return null

        return types.map { typeRef(it, node) ?: return null }
    }

    private fun availability(node: KSAnnotated): Availability? {
        fun version(name: String): Pair<Boolean, Version?> {
            val text = node
                .annotation(name)
                ?.argument("version") as? String
                ?: return true to null

            val version = Version.parse(text)

            if (version == null)
                error("Invalid version '${text}'", node)

            return (version != null) to version
        }

        val (sinceValid, since) = version(SINCE)
        val (beforeValid, before) = version(BEFORE)

        if (!sinceValid || !beforeValid)
            return null

        if (since != null && before != null && since >= before) {
            error("@Since(${since}) must be lower than @Before(${before})", node)
            return null
        }

        return Availability(node.annotation(OPTIONAL) != null, since, before)
    }

    // Types

    private fun classNames(declaration: KSClassDeclaration): List<String> {
        val names = ArrayList<String>()
        var current: KSDeclaration? = declaration

        while (current is KSClassDeclaration) {
            names.add(current.simpleName.asString())
            current = current.parentDeclaration
        }

        return names.reversed()
    }

    private fun binaryName(declaration: KSClassDeclaration): String {
        val packageName = declaration.packageName.asString()

        val names = classNames(declaration)
            .joinToString("$")

        return if (packageName.isEmpty())
            names
        else
            "${packageName.replace('.', '/')}/$names"
    }

    private fun typeRef(type: KSType, node: KSNode): TypeRef? {
        val written = type
            .declaration
            .qualifiedName
            ?.asString()

        var expanded = type

        while (true) {
            val alias = expanded.declaration as? KSTypeAlias
                ?: break

            expanded = alias.type.resolve()
        }

        val declaration = expanded.declaration as? KSClassDeclaration

        if (declaration == null || written == null) {
            error("Unsupported type $type", node)
            return null
        }

        val name = declaration.qualifiedName!!.asString()

        PRIMITIVES[name]?.let { (kotlinName, descriptor, boxed) ->
            return if (type.isMarkedNullable)
                TypeRef("${kotlinName}::class.javaObjectType", "L${boxed};", null)
            else
                TypeRef("${kotlinName}::class.javaPrimitiveType!!", descriptor, null)
        }

        if (name == "kotlin.Unit")
            return TypeRef("Void.TYPE", "V", null)

        PRIMITIVE_ARRAYS[name]
            ?.let { return TypeRef("${name}::class.java", it, null) }

        if (name == "kotlin.Array") {
            val element = expanded.arguments
                .firstOrNull()
                ?.type
                ?.resolve()

            if (element == null) {
                error("Unsupported array type $type", node)
                return null
            }

            val elementType = typeRef(element, node)
                ?: return null

            return TypeRef(
                "java.lang.reflect.Array.newInstance(${elementType.expression}, 0).javaClass",
                "[${elementType.descriptor}",
                null,
            )
        }

        val internalName = MAPPED[name]
            ?: binaryName(declaration)

        return TypeRef("${written}::class.java", "L${internalName};", null)
    }

    // Validation

    private fun validate(owners: List<Owner>) {
        val classpath = options["reflection.classpath"]
            ?.split(File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?.map(::File)

        if (classpath.isNullOrEmpty()) {
            logger.warn("reflection.classpath is not set, mirrors are not checked against the client")
            return
        }

        ClientJar(classpath).use { jar ->
            val versionText = options["reflection.clientVersion"]
                ?: jar.clientVersion()

            val version = versionText
                ?.let(Version::parse)

            if (version == null) {
                logger.warn("Cannot determine the client version of ${classpath.joinToString()}, mirrors are not checked")
                return
            }

            for (runtimeClass in runtimeClasses.values) {
                check(
                    version,
                    runtimeClass.availability,
                    runtimeClass.declaration,
                    "Class ${runtimeClass.binaryName}",
                    jar.find(runtimeClass.internalName) != null,
                    null,
                )
            }

            for (owner in owners) {
                val node = jar.find(owner.internalName)

                if (node == null) {
                    if (owner.runtimeClass == null) {
                        logger.info(
                            "${owner.internalName} is not in the client jars, its members are not checked",
                            owner.declaration
                        )
                    }

                    continue
                }

                for (member in owner.members) {
                    val display = owner.internalName
                        .substringAfterLast('/')
                        .replace('$', '.')

                    when (member) {
                        is FieldsMember -> Unit

                        is FieldMember -> check(
                            version,
                            member.availability,
                            member.node,
                            "Field ${display}.${member.name}",
                            node.fields.any { it.name == member.name },
                            null,
                        )

                        is MethodMember -> {
                            val prefix = member.parametersDescriptor

                            val candidates = node.methods
                                .filter { it.name == member.name }

                            val exists = candidates
                                .any { it.desc.startsWith(prefix) && (!member.isConstructor || it.desc == "${prefix}V") }

                            val what = if (member.isConstructor)
                                "Constructor ${display}${readable(prefix)}"
                            else
                                "Method ${display}.${member.name}${readable(prefix)}"

                            val hint = candidates
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString("\n", "Declared in ${version}:\n") {
                                    "  ${readable(it.desc.substringBefore(')') + ")")}"
                                }

                            check(version, member.availability, member.node, what, exists, hint)
                        }
                    }
                }
            }
        }
    }

    private fun readable(parametersDescriptor: String): String =
        Type.getArgumentTypes("${parametersDescriptor}V")
            .joinToString(", ", "(", ")") { it.className }

    private fun check(
        version: Version,
        availability: Availability,
        node: KSNode,
        what: String,
        exists: Boolean,
        hint: String?,
    ) {
        if (availability.since != null && version < availability.since)
            return

        if (availability.before != null && version >= availability.before) {
            if (exists) {
                logger.warn(
                    "$what exists in client $version, but is marked @Before(\"${availability.before}\")",
                    node
                )
            }

            return
        }

        if (exists)
            return

        val message = buildString {
            append(what)
            append(" is not found in client ")
            append(version)
            append('.')

            if (availability.optional) {
                append(" It is @Optional, so the accessor is null there.")
            } else {
                append(" If it is removed, mark it @Before(\"")
                append(version)
                append("\") or with the first version that lacks it; if only some clients have it, mark it @Optional.")
            }

            hint?.let {
                append('\n')
                append(it)
            }
        }

        if (availability.optional)
            logger.warn(message, node)
        else
            error(message, node)
    }
}

internal fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? =
    annotations
        .firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }

internal fun KSAnnotation.argument(name: String): Any? =
    arguments
        .firstOrNull { it.name?.asString() == name }
        ?.value
