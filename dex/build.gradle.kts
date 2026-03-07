import com.android.build.api.variant.BuildConfigField
import java.io.File
import java.util.Locale
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

private val COMPILE_SDK = 36
private val COMPILE_SDK_MINOR = 1
private val MIN_SDK = 26
private val TARGET_SDK = 36
private val BUILD_TOOLS_VERSION = "36.1.0"
private val PROGUARD_RULES_FILE = "proguard-rules.pro"
private val TELEGRAM_JAR_PATH = "libs/Telegram.jar"

private fun File.isJarFile(): Boolean = isFile && extension.equals("jar", ignoreCase = true)

private fun String.toVariantTitle(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
}

plugins {
    id("com.android.library") version "8.13.2"
    alias(libs.plugins.kotlin.android)
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
        targetSdk = TARGET_SDK
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                PROGUARD_RULES_FILE
            )
        }
        release {
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

val telegramCompileClasspathJar by tasks.registering(Jar::class) {
    archiveBaseName.set("Telegram-compile")
    archiveVersion.set("1")
    destinationDirectory.set(layout.buildDirectory.dir("generated/compile-jars"))

    from(zipTree(TELEGRAM_JAR_PATH)) {
        // Keep Telegram API classes only; exclude bundled runtime/platform classes.
        exclude("kotlin/**")
        exclude("java/**")
        exclude("javax/**")
        exclude("sun/**")
        // Avoid collision with R8's own internal RecordTag aliasing.
        exclude("com/android/tools/r8/**")
        exclude("j$/com/android/tools/r8/**")
    }
}

dependencies {
    implementation(libs.aliuhook)
    compileOnly(libs.jetbrains.kotlin.stdlib)
    compileOnly(files(telegramCompileClasspathJar))
}

fun registerBuildDexTask(taskName: String, variant: String, assembleTask: String) {
    tasks.register(taskName) {
        group = "build"
        dependsOn(assembleTask)

        doLast {
            val buildDirPath = layout.buildDirectory.asFile.get().absolutePath
            val variantTitle = variant.toVariantTitle()
            val androidLibs = android.bootClasspath.map { it.absolutePath }
            val proguardRules = file("${project.projectDir.absolutePath}/$PROGUARD_RULES_FILE")

            if (androidLibs.isEmpty()) {
                throw GradleException("Could not resolve Android boot classpath.")
            }
            if (!proguardRules.exists()) {
                throw GradleException("Missing Proguard config: ${proguardRules.absolutePath}")
            }

            val compileJar = file(
                "$buildDirPath/intermediates/compile_library_classes_jar/$variant/" +
                        "bundleLibCompileToJar$variantTitle/classes.jar"
            )
            val classInputs = if (compileJar.exists()) {
                listOf(compileJar.absolutePath)
            } else {
                val kotlinDir = file("$buildDirPath/tmp/kotlin-classes/$variant")
                fileTree(kotlinDir)
                    .matching { include("**/*.class") }
                    .files
                    .map { it.absolutePath }
            }

            fun resolveJarPaths(configurationName: String): List<String> =
                configurations.findByName(configurationName)
                    ?.resolve()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isJarFile() }
                    .map { it.absolutePath }
                    .toList()

            val runtimeJars = resolveJarPaths("${variant}RuntimeClasspath")
            val compileJars = resolveJarPaths("${variant}CompileClasspath")
            val compileTaskJars =
                (tasks.findByName("compile${variantTitle}Kotlin") as? KotlinCompile)
                    ?.libraries
                    ?.files
                    .orEmpty()
                    .asSequence()
                    .filter { it.isJarFile() }
                    .map { it.absolutePath }
                    .toList()

            val dexInputs = (classInputs + runtimeJars).distinct()
            if (dexInputs.isEmpty()) {
                throw GradleException(
                    "No class inputs found for variant '$variant'. Run $assembleTask first."
                )
            }

            val classpathJars = (compileTaskJars + compileJars)
                .filterNot {
                    it.toString().contains("Aliuhook") || it in dexInputs || it in androidLibs
                }
                .distinct()

            val outputDir = "$buildDirPath/outputs/dex/$variant"
            val outputDirFile = file(outputDir)
            if (outputDirFile.exists() && !outputDirFile.isDirectory) {
                throw GradleException("d8 output path is not a directory: '$outputDir'.")
            }
            if (!outputDirFile.exists() && !outputDirFile.mkdirs()) {
                throw GradleException("Failed to create d8 output directory: '$outputDir'.")
            }

            val buildToolsDir = "${android.sdkDirectory}/build-tools/$BUILD_TOOLS_VERSION"
            val r8Jar = "$buildToolsDir/lib/d8.jar"
            val javaBin =
                if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                    "java.exe"
                } else {
                    "java"
                }

            val out = try {
                providers.exec {
                    executable = javaBin
                    isIgnoreExitValue = true

                    args("-cp", r8Jar, "com.android.tools.r8.R8")
                    args(if (variant.equals("debug", ignoreCase = true)) "--debug" else "--release")
                    args("--dex")
                    args("--output", outputDirFile.absolutePath)
                    args("--min-api", MIN_SDK.toString())
                    args("--pg-conf", proguardRules.absolutePath)
                    androidLibs.forEach { args("--lib", it) }
                    classpathJars.forEach { args("--classpath", it) }
                    args(dexInputs)
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to execute r8 for variant '$variant'. " +
                            "r8Jar='$r8Jar', outputDir='$outputDir', " +
                            "classInputs=${classInputs.size}, runtimeJars=${runtimeJars.size}, " +
                            "dexInputs=${dexInputs.size}.",
                    e
                )
                throw GradleException(
                    "r8 execution failed for variant '$variant'. See logs above.",
                    e
                )
            }

            val standardOutput = out.standardOutput.asText.get()
            val standardError = out.standardError.asText.get()
            if (standardOutput.isNotBlank()) logger.lifecycle(standardOutput)
            if (standardError.isNotBlank()) logger.error(standardError)

            val exitCode = out.result.get().exitValue
            if (exitCode == 0) {
                logger.lifecycle("Dex created for $variant at: $outputDir/classes.dex")
            } else {
                logger.error(
                    "r8 exited with code $exitCode for variant '$variant'. " +
                            "r8Jar='$r8Jar', outputDir='$outputDir'."
                )
                throw GradleException("r8 failed for variant '$variant' with exit code $exitCode.")
            }
        }
    }
}

registerBuildDexTask("buildDexDebug", "debug", "assembleDebug")
registerBuildDexTask("buildDexRelease", "release", "assembleRelease")

tasks.register("buildDex") {
    group = "build"
    dependsOn("buildDexDebug", "buildDexRelease")
}
