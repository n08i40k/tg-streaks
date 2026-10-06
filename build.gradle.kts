import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private val minSdkMajorProperty: Provider<Int> =
    providers.gradleProperty("minSdkMajor").map { it.toInt() }

private val targetSdkMajorProperty: Provider<Int> =
    providers.gradleProperty("targetSdkMajor").map { it.toInt() }

private val targetSdkMinorProperty: Provider<Int> =
    providers.gradleProperty("targetSdkMinor").map { it.toInt() }

buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.symbol.processing.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.exterastuff.plugin)
    alias(libs.plugins.i18n4k)
    alias(libs.plugins.ksp)
}

i18n4k {
    packageName = "ru.n08i40k.streaks.i18n"
    sourceCodeLocales = listOf("en", "ru")
}

android {
    namespace = "ru.n08i40k.streaks"

    buildFeatures {
        buildConfig = true
    }

    compileSdk {
        version = release(targetSdkMajorProperty.get()) {
            minorApiLevel = targetSdkMinorProperty.get()
        }
    }

    defaultConfig {
        minSdk = minSdkMajorProperty.get()

        lint {
            targetSdk = targetSdkMajorProperty.get()
        }
    }

    buildTypes {
        debug {
            buildConfigField("long", "BUILD_TIME", "0")
        }

        release {
            buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

// no sun.misc.Unsafe
val compileUnsafeStub = tasks.register<JavaCompile>("compileUnsafeStub") {
    description = "Compiles an sun.misc.Unsafe stub"
    source = fileTree("java-stubs")
    classpath = files()
    destinationDirectory = layout.buildDirectory.dir("java-stubs/classes")
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.compilerArgs.addAll(listOf("--limit-modules", "java.base"))
}

dependencies {
    compileOnly(files(compileUnsafeStub))
    implementation(project(":badges-sdk:api"))
    implementation(project(":badges-sdk"))

    compileOnly(libs.aliuhook)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.androidx.annotation)
    implementation(libs.i18n4k.core)
    implementation(libs.jetbrains.kotlin.stdlib)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    ksp(libs.androidx.room.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

extera {
    telegram {
        jar = file("libs/Telegram.jar")

        conflictingPackages = listOf(
            "kotlin",
            "kotlinx",
            "androidx.room",
            "androidx.sqlite",
        )
    }

    r8 {
        minSdk = minSdkMajorProperty.get()
        proguardFiles = files("proguard-rules.pro")
    }

    shadow {
        targetPackage = "o_0"

        relocate("kotlin", "kotlinx", "de.comahe.i18n4k")

        relocate("androidx.room", "androidx.sqlite", "androidx.arch.core")
    }

    dexOutputDir = project.layout.projectDirectory.dir("dist/dex")
}