import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ru.n08i40k.shim"

    compileSdk {
        version = release(providers.gradleProperty("targetSdkMajor").get().toInt()) {
            minorApiLevel = providers.gradleProperty("targetSdkMinor").get().toInt()
        }
    }

    defaultConfig {
        minSdk = providers.gradleProperty("minSdkMajor").get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }

    explicitApi()
}

dependencies {
    implementation(libs.reflection)
    ksp(libs.reflection.processor)

    compileOnly(files(rootProject.tasks.named("processTelegramJar")))

    implementation(libs.jetbrains.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
}
