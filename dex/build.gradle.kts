import com.android.build.api.variant.BuildConfigField
import dev.reformator.stacktracedecoroutinator.gradleplugin.DecoroutinatorPluginExtension
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.coreLibraryDesugaring
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

private val COMPILE_SDK = 36
private val COMPILE_SDK_MINOR = 1
private val MIN_SDK = 26
private val TARGET_SDK = 36
private val BUILD_TOOLS_VERSION = "36.1.0"
private val PROGUARD_RULES_FILE = "proguard-rules.pro"
// Host API compile classpath: InnerClasses-repaired copy of the raw
// libs/Telegram.jar, produced offline by tools/FixTelegramJar.java. Regenerate
// and re-commit it whenever the host jar is refreshed.
private val TELEGRAM_COMPILE_JAR_PATH = "libs/Telegram-compile.jar"

// Host-provided packages the plugin references at runtime and that therefore must
// NOT be relocated (see RELOCATION_EXCLUDED_PREFIXES). This is a *runtime* concern
// and is intentionally narrower than the compile jar, which includes almost all
// host classes for supertype resolution (see tools/FixTelegramJar.java). Add a
// package here only when the plugin actually references its classes directly.
private val TELEGRAM_COMPILE_PACKAGE_PREFIXES =
    listOf(
        "org/telegram/",
        "com/exteragram/",
        "de/robv/android/xposed/",
        "org/json/",
        "java/",
        "j$/"
    )

private val SHADED_PACKAGE = "ru/n08i40k/streaks_shaded"

// Packages that must NOT be relocated — provided by the host app or Android runtime.
// compileOnly-only dependencies must also be listed here (their classes are resolved
// from the host classloader at runtime, so references to them must stay unrelocated).
private val RELOCATION_EXCLUDED_PREFIXES: List<String> by lazy {
    TELEGRAM_COMPILE_PACKAGE_PREFIXES + listOf(
        "ru/n08i40k/streaks/",
        "android/",
        "dalvik/",
        "javax/",
        "androidx/recyclerview/",
    )
}

// Individual classes excluded from relocation (use when excluding the whole package is too broad)
private val RELOCATION_EXCLUDED_CLASSES = setOf(
    "androidx/collection/LongSparseArray",
)

private class ShadedRemapper : Remapper() {
    override fun map(internalName: String): String {
        if (RELOCATION_EXCLUDED_PREFIXES.any { internalName.startsWith(it) }) return internalName
        if (internalName in RELOCATION_EXCLUDED_CLASSES) return internalName
        // R and R$* are generated resource classes — they live in the host APK, not our DEX
        val simpleName = internalName.substringAfterLast('/')
        if (simpleName == "R" || simpleName.startsWith("R\$")) return internalName
        return "$SHADED_PACKAGE/$internalName"
    }
}

private val SHADED_REMAPPER = ShadedRemapper()

private fun remapClassBytes(bytes: ByteArray): ByteArray {
    val cr = ClassReader(bytes)
    val cw = ClassWriter(0)
    cr.accept(ClassRemapper(cw, SHADED_REMAPPER), 0)
    return cw.toByteArray()
}

private fun File.isJarFile(): Boolean = isFile && extension.equals("jar", ignoreCase = true)

private val MODULE_NAME_WITH_VERSION = Regex("""^(.+?)-\d[\w.+-]*$""")

private fun File.artifactKey(): String {
    val pathSegments = absolutePath.split(File.separatorChar)
    val cacheIndex = pathSegments.indexOf("files-2.1")

    return if (cacheIndex >= 0 && pathSegments.size > cacheIndex + 3) {
        pathSegments[cacheIndex + 2]
    } else {
        name
            .removeSuffix(".jar")
            .let { jarName ->
                MODULE_NAME_WITH_VERSION.matchEntire(jarName)?.groupValues?.get(1) ?: jarName
            }
            .removeSuffix("-decoroutinator")
            .removeSuffix("-api")
            .removeSuffix("-runtime")
            .removeSuffix("-R")
    }
}

private fun String.toVariantTitle(): String = replaceFirstChar(Char::uppercaseChar)

private fun File.extractAarJars(outputDir: File): List<String> {
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }

    val artifactDir =
        outputDir.resolve("${artifactKey()}-${absolutePath.hashCode().toUInt().toString(16)}")
    if (artifactDir.exists()) {
        artifactDir.deleteRecursively()
    }
    artifactDir.mkdirs()

    return ZipFile(this).use { zip ->
        zip.entries()
            .asSequence()
            .filter {
                !it.isDirectory &&
                        (it.name == "classes.jar" || it.name.startsWith("libs/")) &&
                        it.name.endsWith(".jar")
            }
            .map { entry ->
                val outputFile = artifactDir.resolve(entry.name.removePrefix("libs/"))
                outputFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
                outputFile.absolutePath
            }
            .toList()
    }
}

private fun Project.resolveClasspathArtifactPaths(
    configurationName: String,
    extractionRoot: File
): List<String> =
    configurations.findByName(configurationName)
        ?.resolve()
        .orEmpty()
        .flatMap { artifact ->
            when {
                artifact.isJarFile() -> listOf(artifact.absolutePath)
                artifact.extension.equals("aar", ignoreCase = true) ->
                    artifact.extractAarJars(extractionRoot.resolve(configurationName))

                else -> emptyList()
            }
        }

private fun Project.resolveAndroidSdkDir(): File {
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    val sdkDirPath =
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(localProperties::load)
            localProperties.getProperty("sdk.dir")
        } else {
            null
        }
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: throw GradleException(
                "Android SDK location is not configured. Set sdk.dir in local.properties " +
                        "or define ANDROID_HOME / ANDROID_SDK_ROOT."
            )

    return file(sdkDirPath)
}

private fun Project.resolveAndroidJar(): File {
    val platformVersion =
        if (COMPILE_SDK_MINOR > 0) "$COMPILE_SDK.$COMPILE_SDK_MINOR" else COMPILE_SDK.toString()
    val androidJar =
        resolveAndroidSdkDir().resolve("platforms/android-$platformVersion/android.jar")

    if (!androidJar.exists()) {
        throw GradleException("Android platform jar not found: ${androidJar.absolutePath}")
    }

    return androidJar
}

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.ow2.asm:asm:9.7.1")
        classpath("org.ow2.asm:asm-commons:9.7.1")
    }
}

plugins {
    id("com.android.library") version "9.0.1"
    id("com.google.devtools.ksp") version "2.3.5"
    id("dev.reformator.stacktracedecoroutinator") version "2.6.1"
}

configure<DecoroutinatorPluginExtension> {
    // Android 11 ART rejects some transformed coroutine/runtime bytecode.
    // Keep decoroutinator active only for debug-oriented tasks/configurations.
    tasks.include = setOf(""".*Debug.*""")
    tasks.exclude = setOf(""".*Release.*""")
    tasksSkippingSpecMethods.exclude = setOf(""".*Release.*""")

    regularDependencyConfigurations.include = setOf("""debug.*""")
    regularDependencyConfigurations.exclude = setOf("""release.*""")
    androidDependencyConfigurations.include = setOf("""debug.*""")
    androidDependencyConfigurations.exclude = setOf("""release.*""")
    androidRuntimeDependencyConfigurations.include = setOf("""debug.*""")
    androidRuntimeDependencyConfigurations.exclude = setOf("""release.*""")
    transformedClassesConfigurations.include = setOf("""debug.*""")
    transformedClassesConfigurations.exclude = setOf("""release.*""")
    transformedClassesSkippingSpecMethodsConfigurations.exclude = setOf("""release.*""")
    embeddedDebugProbesConfigurations.exclude = setOf("""release.*""")
}

android {
    namespace = "ru.n08i40k.streaks"

    buildFeatures {
        buildConfig = true
    }

    compileSdk {
        version = release(COMPILE_SDK) {
            minorApiLevel = COMPILE_SDK_MINOR
        }
    }

    defaultConfig {
        minSdk = MIN_SDK

        lint {
            targetSdk = TARGET_SDK
        }
    }

    buildTypes {
        all {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                PROGUARD_RULES_FILE
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        isCoreLibraryDesugaringEnabled = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "BUILD_TIME",
            BuildConfigField(
                "String",
                "\"${System.currentTimeMillis()}\"",
                "build timestamp"
            )
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xmetadata-version=2.2.0")
        freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
    }
}

val embed by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    compileOnly(libs.androidx.recyclerview)
    compileOnly(libs.androidx.lifecycle.viewmodel)
    ksp(libs.androidx.room.compiler)

    compileOnly(libs.aliuhook)
    compileOnly(libs.jetbrains.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(files(TELEGRAM_COMPILE_JAR_PATH))
    add(embed.name, libs.jetbrains.kotlin.stdlib)
    add(embed.name, libs.kotlinx.coroutines.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

fun registerBuildDexTask(variant: String) {
    val variantTitle = variant.toVariantTitle()
    val taskName = "buildDex$variantTitle"
    val compileKotlinTask = "compile${variantTitle}Kotlin"
    val compileJavaTask = "compile${variantTitle}JavaWithJavac"
    val sdkDirectory = project.resolveAndroidSdkDir()
    val androidJar = project.resolveAndroidJar()

    tasks.register(taskName) {
        group = "build"
        dependsOn(compileKotlinTask, compileJavaTask)

        doLast {
            val buildDirFile = layout.buildDirectory.asFile.get()
            val proguardRules = file(PROGUARD_RULES_FILE)
            if (!proguardRules.exists()) {
                throw GradleException("Missing Proguard config: ${proguardRules.absolutePath}")
            }

            val classRootCandidates = listOf(
                buildDirFile.resolve("intermediates/built_in_kotlinc/$variant/compile${variantTitle}Kotlin/classes"),
                buildDirFile.resolve("intermediates/javac/$variant/compile${variantTitle}JavaWithJavac/classes"),
                buildDirFile.resolve(
                    "intermediates/compile_library_classes_jar/$variant/" +
                            "bundleLibCompileToJar$variantTitle/classes.jar"
                )
            )
            val classRoots = classRootCandidates.filter(File::exists).ifEmpty {
                listOf(buildDirFile.resolve("tmp/kotlin-classes/$variant")).filter(File::exists)
            }
            if (classRoots.isEmpty()) {
                throw GradleException(
                    "No class inputs found for variant '$variant'. Run $compileKotlinTask first."
                )
            }

            val extractedArtifactRoot =
                buildDirFile.resolve("intermediates/extracted-classpath-artifacts")
            val runtimeJars =
                resolveClasspathArtifactPaths("${variant}RuntimeClasspath", extractedArtifactRoot)
            val compileJars =
                resolveClasspathArtifactPaths("${variant}CompileClasspath", extractedArtifactRoot)
            val embeddedJars = resolveClasspathArtifactPaths(embed.name, extractedArtifactRoot)

            val embeddedModules = embeddedJars.map { File(it).artifactKey() }.toSet()
            val filteredRuntimeJars =
                runtimeJars.filterNot { jarPath -> File(jarPath).artifactKey() in embeddedModules }

            // Merge all inputs into a single JAR with package relocation applied.
            // Plugin's own classes (ru.n08i40k.streaks.**) keep their names but get their
            // bytecode references updated to point to the relocated library classes.
            // Library JARs are moved to ru.n08i40k.streaks_shaded.* to avoid host conflicts.
            val mergedShadedJar =
                buildDirFile.resolve("intermediates/merged-shaded/$variant/classes.jar")
            mergedShadedJar.parentFile.mkdirs()
            val seen = HashSet<String>()
            ZipOutputStream(mergedShadedJar.outputStream()).use { zos ->
                fun add(name: String, bytes: ByteArray) {
                    if (seen.add(name)) {
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
                classRoots.forEach { root ->
                    if (root.isDirectory) {
                        root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                            val rel = f.toRelativeString(root).replace(File.separatorChar, '/')
                            add(rel, remapClassBytes(f.readBytes()))
                        }
                    } else {
                        ZipFile(root).use { zip ->
                            zip.entries().asSequence()
                                .filter { !it.isDirectory && it.name.endsWith(".class") }
                                .forEach { e ->
                                    add(e.name, remapClassBytes(zip.getInputStream(e).readBytes()))
                                }
                        }
                    }
                }
                (filteredRuntimeJars + embeddedJars).distinct().map(::File).forEach { jar ->
                    if (!jar.exists()) return@forEach
                    ZipFile(jar).use { zip ->
                        zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                            val bytes = zip.getInputStream(entry).readBytes()
                            if (entry.name.endsWith(".class")) {
                                val shadedName = SHADED_REMAPPER.map(entry.name.removeSuffix(".class"))
                                add("$shadedName.class", remapClassBytes(bytes))
                            } else {
                                add(entry.name, bytes)
                            }
                        }
                    }
                }
            }

            val dexInputs = listOf(mergedShadedJar.absolutePath)

            val dexModules = (embeddedJars + filteredRuntimeJars).map { File(it).artifactKey() }.toSet()
            val classpathJars = compileJars
                .filterNot {
                    val jar = File(it)
                    it == androidJar.absolutePath || jar.artifactKey() in dexModules
                }
                .distinct()

            val outputDirFile = layout.buildDirectory.dir("outputs/dex/$variant").get().asFile
            if (outputDirFile.exists() && !outputDirFile.isDirectory) {
                throw GradleException("d8 output path is not a directory: '${outputDirFile.absolutePath}'.")
            }
            if (!outputDirFile.exists() && !outputDirFile.mkdirs()) {
                throw GradleException(
                    "Failed to create d8 output directory: '${outputDirFile.absolutePath}'."
                )
            }

            val buildDexRules =
                buildDirFile.resolve("intermediates/build-dex/$variant/proguard-rules.pro")
            buildDexRules.parentFile.mkdirs()
            buildDexRules.writeText(
                """
                # The host Telegram classpath is intentionally partial here; only the plugin and
                # embedded runtime jars are packaged into the output dex.
                -ignorewarnings
                """.trimIndent()
            )

            val r8Jar = sdkDirectory.resolve("build-tools/$BUILD_TOOLS_VERSION/lib/d8.jar")
            val javaBin =
                if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                    "java.exe"
                } else {
                    "java"
                }

            val out = providers.exec {
                executable = javaBin
                isIgnoreExitValue = true

                args("-cp", r8Jar.absolutePath, "com.android.tools.r8.R8")
                // The addon dex must always be repackaged/obfuscated to avoid host collisions.
                args("--release")
                args("--dex")
                args("--output", outputDirFile.absolutePath)
                args("--min-api", MIN_SDK.toString())
                args("--pg-conf", proguardRules.absolutePath)
                args("--pg-conf", buildDexRules.absolutePath)
                args("--lib", androidJar.absolutePath)
                classpathJars.forEach { args("--classpath", it) }
                args(dexInputs)
            }

            val standardOutput = out.standardOutput.asText.get()
            val standardError = out.standardError.asText.get()
            if (standardOutput.isNotBlank()) logger.lifecycle(standardOutput)
            if (standardError.isNotBlank()) logger.error(standardError)

            val exitCode = out.result.get().exitValue
            if (exitCode != 0) {
                throw GradleException("r8 failed for variant '$variant' with exit code $exitCode.")
            }

            logger.lifecycle(
                "Dex created for $variant at: ${outputDirFile.absolutePath}/classes.dex"
            )
        }
    }
}

listOf("debug", "release").forEach(::registerBuildDexTask)

tasks.register("buildDex") {
    group = "build"
    dependsOn("buildDexDebug", "buildDexRelease")
}
