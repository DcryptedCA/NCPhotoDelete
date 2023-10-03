package com.example.myapp2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaDao {
    @Query("SELECT EXISTS (SELECT 1 FROM media WHERE data = :filePath)")
    fun isFileExists(filePath: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(vararg media: Media)

    @Query("SELECT COUNT(*) FROM media")
    fun getTotalFilesCount(): Int
}