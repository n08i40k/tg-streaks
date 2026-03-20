package ru.n08i40k.streaks.util

sealed class MyResult<T, E> {
    data class Ok<T, E>(val value: T) : MyResult<T, E>()
    data class Err<T, E>(val error: E) : MyResult<T, E>()

    fun isOk() = this is Ok
    fun isErr() = this is Err
}