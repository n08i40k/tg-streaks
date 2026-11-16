package ru.n08i40k.reflection.processor

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import java.io.File
import java.util.zip.ZipFile

/** Classes of the client jars, read lazily. */
internal class ClientJar(files: List<File>) : AutoCloseable {
    private val zips = files.map { ZipFile(it) }
    private val classes = HashMap<String, ClassNode?>()

    private fun read(internalName: String, flags: Int): ClassNode? {
        for (zip in zips) {
            val entry = zip.getEntry("${internalName}.class")
                ?: continue

            val node = ClassNode()

            zip.getInputStream(entry)
                .use { ClassReader(it).accept(node, flags) }

            return node
        }

        return null
    }

    fun find(internalName: String): ClassNode? {
        if (internalName in classes)
            return classes[internalName]

        val node = read(
            internalName,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        )

        classes[internalName] = node

        return node
    }

    /** BuildVars.BUILD_VERSION_STRING is assigned in the static initializer, so it is taken from its bytecode. */
    fun clientVersion(): String? {
        val node = read(
            "org/telegram/messenger/BuildVars",
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        )
            ?: return null

        val initializer = node.methods
            .firstOrNull { it.name == "<clinit>" }
            ?: return null

        var lastString: String? = null

        for (instruction in initializer.instructions) {
            if (instruction is LdcInsnNode && instruction.cst is String)
                lastString = instruction.cst as String

            if (instruction is FieldInsnNode
                && instruction.opcode == Opcodes.PUTSTATIC
                && instruction.name == "BUILD_VERSION_STRING"
            )
                return lastString
        }

        return null
    }

    override fun close() {
        zips.forEach { it.close() }
    }
}
