import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private val minSdkMajorProperty: Provider<Int> =
    providers.gradleProperty("minSdkMajor").map { it.toInt() }

private val targetSdkMajorProperty: Provider<Int> =
    providers.gradleProperty("targetSdkMajor").map { it.toInt() }

private val targetSdkMinorProperty: Provider<Int> =
    providers.gradleProperty("targetSdkMinor").map { it.toInt() }

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
        freeCompilerArgs.add("-Xmetadata-version=2.2.0")
        freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(libs.badges.sdk)
    implementation(libs.badges.sdk.api)

    compileOnly(libs.aliuhook)
    compileOnly(libs.androidx.lifecycle.viewmodel)
    compileOnly(libs.androidx.recyclerview)
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
            "androidx.annotation",
            "androidx.arch",
            "androidx.collection",
            "androidx.core",
            "androidx.customview",
            "androidx.lifecycle",
            "androidx.recyclerview",
            "androidx.room",
            "androidx.sqlite",
            "androidx.versionedparcelable",
        )
    }

    r8 {
        minSdk = minSdkMajorProperty.get()
        proguardFiles = files("proguard-rules.pro")
    }

    shadow {
        targetPackage = "ru.n08i40k.streaks_shaded"

        relocate("kotlin", "kotlinx", "de.comahe.i18n4k")

        relocate("androidx") {
            exclude(
                "androidx/recyclerview/**",
                "androidx/core/view/inputmethod/InputContentInfoCompat",
                "androidx/collection/LongSparseArray"
            )
        }

        relocate("ru.n08i40k.badges")
    }

    dexOutputDir = project.layout.projectDirectory.dir("dist/dex")
}