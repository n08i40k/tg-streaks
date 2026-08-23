package ru.n08i40k.streaks.extension

fun <T> MutableList<T>.removeFirstBy(condition: (T) -> Boolean): T? {
    val index = this.indexOfFirst(condition)

    return if (index != -1) this.removeAt(index) else null
}

fun <T> MutableList<T>.removeCountBy(count: Int, condition: (T) -> Boolean): List<T> {
    val result = mutableListOf<T>()

    for (i in 0..<count)
        result.add(this.removeFirstBy(condition) ?: break)

    return result
}
