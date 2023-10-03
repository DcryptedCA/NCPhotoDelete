package com.example.myapp2

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Media(
    @PrimaryKey val id: Long,
    val name: String,
    val size: Long,
    val data: String,
    val date: Long,
    val status: String
)