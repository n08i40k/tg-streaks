import com.android.build.api.variant.BuildConfigField
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.coreLibraryDesugaring
import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private val COMPILE_SDK = 36
private val COMPILE_SDK_MINOR = 1
private val MIN_SDK = 26
private val TARGET_SDK = 36
private val BUILD_TOOLS_VERSION = "36.1.0"
private val PROGUARD_RULES_FILE = "proguard-rules.pro"
private val TELEGRAM_JAR_PATH = "libs/Telegram.jar"
private val TELEGRAM_COMPILE_STRIP_PREFIXES = listOf(
    "kotlin/",
    "kotlinx/",
    "java/",
    "javax/",
    "jdk/",
    "sun/",
    "com/android/tools/r8/",
    "j$/com/android/tools/r8/"
)
private val TELEGRAM_COMPILE_STRIP_EXACT_PATHS = setOf("module-info.class")
private val TELEGRAM_COMPILE_STRIP_META_INF_SUFFIXES = listOf(".kotlin_module")
private val TELEGRAM_COMPILE_STRIP_META_INF_PREFIXES = listOf(
    "META-INF/services/kotlin.",
    "META-INF/services/kotlinx."
)

private fun File.isJarFile(): Boolean = isFile && extension.equals("jar", ignoreCase = true)

private fun File.artifactKey(): String {
    val pathSegments = absolutePath.split(File.separatorChar)
    val cacheIndex = pathSegments.indexOf("files-2.1")

    return if (cacheIndex >= 0 && pathSegments.size > cacheIndex + 3) {
        "${pathSegments[cacheIndex + 1]}:${pathSegments[cacheIndex + 2]}"
    } else {
        name.removeSuffix(".jar")
            .removeSuffix("-api")
            .removeSuffix("-runtime")
            .removeSuffix("-R")
    }
}

private fun String.toVariantTitle(): String = replaceFirstChar(Char::uppercaseChar)

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

private fun shouldStripTelegramCompileEntry(path: String): Boolean {
    val normalizedPath = path.removePrefix("/")

    return TELEGRAM_COMPILE_STRIP_PREFIXES.any(normalizedPath::startsWith) ||
            normalizedPath in TELEGRAM_COMPILE_STRIP_EXACT_PATHS ||
            TELEGRAM_COMPILE_STRIP_META_INF_PREFIXES.any(normalizedPath::startsWith) ||
            (normalizedPath.startsWith("META-INF/") &&
                    TELEGRAM_COMPILE_STRIP_META_INF_SUFFIXES.any(normalizedPath::endsWith))
}

plugins {
    id("com.android.library") version "9.0.1"
    id("com.google.devtools.ksp") version "2.3.5"
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
    }
}

val unpackTelegramCompileClasspath by tasks.registering(Sync::class) {
    val outputDir = layout.buildDirectory.dir("intermediates/telegram-compile/classes")

    from(zipTree(TELEGRAM_JAR_PATH))
    into(outputDir)
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    eachFile {
        if (shouldStripTelegramCompileEntry(path)) {
            exclude()
        }
    }
}

val telegramCompileClasspathJar by tasks.registering(Jar::class) {
    dependsOn(unpackTelegramCompileClasspath)
    archiveBaseName.set("Telegram-compile")
    archiveVersion.set("1")
    destinationDirectory.set(layout.buildDirectory.dir("generated/compile-jars"))
    includeEmptyDirs = false

    // Keep Telegram API classes only; strip bundled runtime/platform namespaces first.
    from(layout.buildDirectory.dir("intermediates/telegram-compile/classes"))
}

val embed by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.aliuhook)
    compileOnly(libs.jetbrains.kotlin.stdlib)
    compileOnly(files(telegramCompileClasspathJar))
    add(embed.name, libs.jetbrains.kotlin.stdlib)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

fun resolveJarPaths(configurationName: String): List<String> =
    configurations.findByName(configurationName)
        ?.resolve()
        .orEmpty()
        .filter(File::isJarFile)
        .map(File::getAbsolutePath)

fun registerBuildDexTask(variant: String) {
    val variantTitle = variant.toVariantTitle()
    val taskName = "buildDex$variantTitle"
    val assembleTask = "assemble$variantTitle"
    val sdkDirectory = project.resolveAndroidSdkDir()
    val androidJar = project.resolveAndroidJar()

    tasks.register(taskName) {
        group = "build"
        dependsOn(assembleTask)

        doLast {
            val buildDirFile = layout.buildDirectory.asFile.get()
            val proguardRules = file(PROGUARD_RULES_FILE)
            if (!proguardRules.exists()) {
                throw GradleException("Missing Proguard config: ${proguardRules.absolutePath}")
            }

            val compileJar = buildDirFile.resolve(
                "intermediates/compile_library_classes_jar/$variant/" +
                        "bundleLibCompileToJar$variantTitle/classes.jar"
            )
            val classInputs = if (compileJar.exists()) {
                listOf(compileJar.absolutePath)
            } else {
                fileTree(buildDirFile.resolve("tmp/kotlin-classes/$variant"))
                    .matching { include("**/*.class") }
                    .files
                    .map { it.absolutePath }
            }

            val runtimeJars = resolveJarPaths("${variant}RuntimeClasspath")
            val compileJars = resolveJarPaths("${variant}CompileClasspath")
            val embeddedJars = resolveJarPaths(embed.name)

            val embeddedModules = embeddedJars.map { File(it).artifactKey() }.toSet()
            val filteredRuntimeJars =
                runtimeJars.filterNot { jarPath ->
                    File(jarPath).artifactKey() in embeddedModules
                }

            val dexInputs = (classInputs + filteredRuntimeJars + embeddedJars).distinct()
            if (dexInputs.isEmpty()) {
                throw GradleException(
                    "No class inputs found for variant '$variant'. Run $assembleTask first."
                )
            }

            val dexInputKeys = dexInputs.map { File(it).artifactKey() }.toSet()
            val classpathJars = compileJars
                .filterNot {
                    val jar = File(it)
                    it.contains("Aliuhook") ||
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
