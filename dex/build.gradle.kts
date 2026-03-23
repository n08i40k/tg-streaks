import com.android.build.api.variant.BuildConfigField
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.coreLibraryDesugaring
import java.io.File
import java.util.zip.ZipFile
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private val COMPILE_SDK = 36
private val COMPILE_SDK_MINOR = 1
private val MIN_SDK = 26
private val TARGET_SDK = 36
private val BUILD_TOOLS_VERSION = "36.1.0"
private val PROGUARD_RULES_FILE = "proguard-rules.pro"
private val TELEGRAM_JAR_PATH = "libs/Telegram.jar"
private val TELEGRAM_COMPILE_PACKAGE_PREFIXES = listOf(
    "org/telegram/",
    "com/exteragram/",
    "androidx/recyclerview/",
    "java/",
)

private fun File.isJarFile(): Boolean = isFile && extension.equals("jar", ignoreCase = true)

private val MODULE_NAME_WITH_VERSION = Regex("""^(.+?)-\d[\w.+-]*$""")

private fun File.artifactKey(): String {
    val pathSegments = absolutePath.split(File.separatorChar)
    val cacheIndex = pathSegments.indexOf("files-2.1")

    return if (cacheIndex >= 0 && pathSegments.size > cacheIndex + 3) {
        pathSegments[cacheIndex + 2]
    } else {
        name.removeSuffix(".jar")
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
        zip.entries().asSequence()
            .filter {
                !it.isDirectory && (it.name == "classes.jar" || it.name.startsWith("libs/")) && it.name.endsWith(
                    ".jar"
                )
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

plugins {
    id("com.android.library") version "9.0.1"
    id("com.google.devtools.ksp") version "2.3.5"
    id("dev.reformator.stacktracedecoroutinator") version "2.6.1"
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

val unpackTelegramCompileClasspath by tasks.registering(Sync::class) {
    val outputDir = layout.buildDirectory.dir("intermediates/telegram-compile-host-java/classes")

    from(zipTree(TELEGRAM_JAR_PATH))
    into(outputDir)
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    TELEGRAM_COMPILE_PACKAGE_PREFIXES.forEach { include("${it}**/*.class") }
}

val telegramCompileClasspathJar by tasks.registering(Jar::class) {
    dependsOn(unpackTelegramCompileClasspath)
    archiveBaseName.set("Telegram-compile")
    archiveVersion.set("host-api-java")
    destinationDirectory.set(layout.buildDirectory.dir("generated/compile-jars"))
    includeEmptyDirs = false

    // Keep Telegram API classes only; strip bundled runtime/platform namespaces first.
    from(layout.buildDirectory.dir("intermediates/telegram-compile-host-java/classes"))
}

val embed by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.androidx.room.compiler)

    compileOnly(libs.aliuhook)
    compileOnly(libs.jetbrains.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(files(telegramCompileClasspathJar))
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

            val classInputCandidates = listOf(
                buildDirFile.resolve("intermediates/built_in_kotlinc/$variant/compile${variantTitle}Kotlin/classes"),
                buildDirFile.resolve("intermediates/javac/$variant/compile${variantTitle}JavaWithJavac/classes"),
                buildDirFile.resolve(
                    "intermediates/compile_library_classes_jar/$variant/" +
                            "bundleLibCompileToJar$variantTitle/classes.jar"
                )
            )
            val classInputs = classInputCandidates
                .filter(File::exists)
                .flatMap { input ->
                    if (input.isDirectory) {
                        fileTree(input)
                            .matching { include("**/*.class") }
                            .files
                            .map(File::getAbsolutePath)
                    } else {
                        listOf(input.absolutePath)
                    }
                }
                .ifEmpty {
                    fileTree(buildDirFile.resolve("tmp/kotlin-classes/$variant"))
                        .matching { include("**/*.class") }
                        .files
                        .map(File::getAbsolutePath)
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
                runtimeJars.filterNot { jarPath ->
                    File(jarPath).artifactKey() in embeddedModules
                }

            val dexInputs = (classInputs + filteredRuntimeJars + embeddedJars).distinct()
            if (dexInputs.isEmpty()) {
                throw GradleException(
                    "No class inputs found for variant '$variant'. Run $compileKotlinTask first."
                )
            }

            val dexInputKeys = dexInputs.map { File(it).artifactKey() }.toSet()
            val classpathJars = compileJars
                .filterNot {
                    val jar = File(it)
                    it == androidJar.absolutePath ||
                            jar.artifactKey() in dexInputKeys
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
