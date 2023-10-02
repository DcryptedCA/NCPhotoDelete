package com.example.myapp2

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = arrayOf(Media::class), version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
}