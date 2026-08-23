package ru.n08i40k.streaks.util

import androidx.room.RoomDatabase
import androidx.room.withTransaction

class DatabaseTransactor(private val db: RoomDatabase) {
    suspend fun <R> wrap(block: suspend () -> R): R =
        db.withTransaction(block)
}