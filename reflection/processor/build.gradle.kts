import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "ru.n08i40k"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }

    explicitApi()
}

dependencies {
    implementation(libs.symbol.processing.api)
    implementation(libs.asm)
    implementation(libs.asm.tree)
}
