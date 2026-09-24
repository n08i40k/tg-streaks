import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliucord.com/snapshots")
        maven("https://maven.aliucord.com/releases")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliucord.com/snapshots")
        maven("https://maven.aliucord.com/releases")
    }
}

rootProject.name = "Streaks Plugin"

private val localProperties = Properties().apply {
    file("local.properties")
        .takeIf(File::exists)
        ?.run { inputStream().use(::load) }
}

private val badgesSdkDir =
    providers.gradleProperty("badgesSdk.dir").orNull
        ?: localProperties.getProperty("badgesSdk.dir")

includeBuild(badgesSdkDir)
