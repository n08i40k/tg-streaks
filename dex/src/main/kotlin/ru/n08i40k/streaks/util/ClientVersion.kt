package ru.n08i40k.streaks.util

import org.telegram.messenger.ApplicationLoader

fun getClientVersionName(): String {
    val context = ApplicationLoader.applicationContext
    val packageName = context.packageName
    val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
    return packageInfo.versionName ?: "0.0.0"
}

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