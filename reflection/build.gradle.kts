import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // TODO: fix EGP instead of using AGP for library that doesn't requires it
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.n08i40k.reflection"

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

// no sun.misc.Unsafe
val compileUnsafeStub = tasks.register<JavaCompile>("compileUnsafeStub") {
    description = "Compiles an sun.misc.Unsafe stub"
    source = fileTree("unsafe")
    classpath = files()
    destinationDirectory = layout.buildDirectory.dir("unsafe/classes")
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.compilerArgs.addAll(listOf("--limit-modules", "java.base"))
}

dependencies {
    compileOnly(files(compileUnsafeStub))
}
