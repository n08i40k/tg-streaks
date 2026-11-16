plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

group = "ru.n08i40k"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
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
