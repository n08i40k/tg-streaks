package ru.n08i40k.guides.api

import android.util.Log

interface GuideLogger {
    fun info(message: String)
    fun error(message: String, error: Throwable)

    companion object {
        val DEFAULT: GuideLogger = object : GuideLogger {
            override fun info(message: String) {
                Log.i("TelegramGuides", message)
            }

            override fun error(message: String, error: Throwable) {
                Log.e("TelegramGuides", message, error)
            }
        }
    }
}
