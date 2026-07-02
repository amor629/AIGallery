package com.example.aigallery.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room 数据库（应用级单例，由 Hilt 提供）
 * version=1：首次发布，如后续变更 schema 需递增并提供迁移
 */
@Database(entities = [PhotoTagEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoTagDao(): PhotoTagDao
}
