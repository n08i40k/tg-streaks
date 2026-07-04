package ru.n08i40k.streaks.extension

fun Throwable.format(): String {
    val builder = StringBuilder()
    var current: Throwable? = this
    var depth = 0

    while (current != null) {
        if (depth == 0) {
            builder.append(current.toString())
        } else {
            builder.append("\nCaused by: ").append(current.toString())
        }

        if (current.stackTrace.isNotEmpty()) {
            builder.append('\n')
            builder.append(current.stackTrace.joinToString("\n"))
        }

        current = current.cause
        depth++
    }

    return builder.toString()
}