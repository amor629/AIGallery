package com.example.aigallery.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room 数据库（应用级单例，由 Hilt 提供）
 * version=4：新增 waste_photos 表（后台废片扫描记录，支持幂等跳过 + 跨页面持久化进度）。
 * AppModule 中 Room.databaseBuilder 配置了 fallbackToDestructiveMigration()，
 * 版本号变化会直接清空重建表，无需手写 Migration；代价是升级后已打的标签/废片记录需要重新扫描一次。
 * ⚠️ hidden_photos 表本身不受此影响丢数据——因为这是新表，不存在旧版本数据；
 *    但如果以后修改 hidden_photos 的字段，destructive migration 会清空隐藏索引，
 *    导致已隐藏的照片文件仍在私有目录里却找不到记录，需格外小心。
 */
@Database(
    entities = [PhotoTagEntity::class, PhotoOcrEntity::class, HiddenPhotoEntity::class, WastePhotoEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoTagDao(): PhotoTagDao
    abstract fun photoOcrDao(): PhotoOcrDao
    abstract fun hiddenPhotoDao(): HiddenPhotoDao
    abstract fun wastePhotoDao(): WastePhotoDao
}
