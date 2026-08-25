package ru.n08i40k.streaks.util

import android.content.pm.PackageInfo
import com.exteragram.messenger.utils.text.LocaleUtils
import org.telegram.messenger.ApplicationLoader

fun getClientName(): String = LocaleUtils.getAppName()

private fun packageInfo(): PackageInfo =
    with(ApplicationLoader.applicationContext) { packageManager.getPackageInfo(packageName, 0) }

fun getClientVersionName(): String =
    packageInfo().versionName ?: "unknown"

@Suppress("DEPRECATION")
fun getClientVersionFull(): String =
    with(packageInfo()) { "${versionName ?: "unknown"} $versionCode" }

fun isClientVersionBelow(target: String): Boolean {
    fun parseVersion(version: String): List<Int> =
        version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    val current = parseVersion(getClientVersionName())
    val required = parseVersion(target)

    val max = maxOf(current.size, required.size)
    for (i in 0 until max) {
        val a = current.getOrElse(i) { 0 }
        val b = required.getOrElse(i) { 0 }
        if (a != b) return a < b
    }
    return false
}