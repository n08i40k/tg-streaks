package ru.n08i40k.streaks.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan


// Nested spans are not supported, only sequenced
fun buildSpanned(
    text: String,
    replacements: Collection<Triple<String, String, () -> Collection<ReplacementSpan>>>
): SpannableString {
    val sb = StringBuilder()
    val sp: ArrayList<Triple<Int, Int, ReplacementSpan>> = arrayListOf()

    var leftStartIndex = 0
    while (leftStartIndex < text.length) {
        var found = false
        val remainingLength = text.length - leftStartIndex

        for ((leftPattern, rightPattern, buildSpan) in replacements) {
            if (!text.startsWith(leftPattern, leftStartIndex))
                continue

            if (remainingLength < leftPattern.length + 1 + rightPattern.length)
                continue

            val middleStartIndex = leftStartIndex + leftPattern.length
            val rightStartIndex = text.indexOf(rightPattern, middleStartIndex)

            if (rightStartIndex == -1 || middleStartIndex == rightStartIndex)
                continue

            val spanStartIndex = sb.length
            val spanEndIndex = spanStartIndex + (rightStartIndex - middleStartIndex)
            buildSpan().forEach { sp.add(Triple(spanStartIndex, spanEndIndex, it)) }

            sb.append(text, middleStartIndex, rightStartIndex)

            leftStartIndex = rightStartIndex + rightPattern.length
            found = true
            break
        }

        if (!found)
            sb.append(text[leftStartIndex++])
    }

    val spannable = SpannableString(sb)

    for ((startIndex, endIndex, span) in sp)
        spannable.setSpan(span, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    return spannable
}