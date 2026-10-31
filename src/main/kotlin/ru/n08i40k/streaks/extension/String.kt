package ru.n08i40k.streaks.extension

internal fun String.withoutBidiControls(): String = filterNot { character ->
    character == '\u061C' || character == '\u200E' || character == '\u200F' ||
        character in '\u202A'..'\u202E' || character in '\u2066'..'\u2069'
}
