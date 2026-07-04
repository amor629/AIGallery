package com.example.aigallery.domain.repository

import android.content.IntentSender
import android.net.Uri
import com.example.aigallery.data.local.db.HiddenPhotoEntity
import com.example.aigallery.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * 隐藏相册 Repository 接口（Domain 层抽象）
 *
 * "隐藏"采用目录隔离方案：把选中照片从 MediaStore 管理的公共目录物理移动到
 * 应用私有沙盒目录（外部 App 无法通过 MediaStore 扫描/读取），并在本地 Room 建索引。
 *
 * 由于删除 MediaStore 原文件在 Android 10+ 需要系统确认弹窗（[buildDeleteRequest]），
 * "隐藏"被拆成三步、由 ViewModel + UI 协作完成：
 *   1. [prepareHide]      —— 复制文件到私有目录（不触碰原文件，随时可安全回滚）
 *   2. UI 用 [buildDeleteRequest] 返回的 IntentSender 弹出系统删除确认框
 *   3a. 用户确认 → [commitHide] 把记录写入本地索引，隐藏正式生效
 *   3b. 用户取消 → [rollbackHide] 删除第 1 步复制出的私有文件，恢复原状
 */
interface IHiddenPhotoRepository {

    /** 隐藏相册列表（按隐藏时间降序），响应式：隐藏/恢复后自动刷新 */
    fun getHiddenPhotos(): Flow<List<MediaItem>>

    /** 已隐藏照片数量（可用于入口角标展示） */
    fun getHiddenCount(): Flow<Int>

    /**
     * 第 1 步：把选中的照片复制到私有目录，返回每一项的（待落库记录, 原始 MediaStore URI）。
     * 复制失败的单张照片会被跳过（不影响其余照片），不抛异常中断整批操作。
     */
    suspend fun prepareHide(items: List<MediaItem>): List<PreparedHide>

    /**
     * 第 2 步：构建删除原文件的系统确认请求（复用与"废片清理/相册删除"相同的
     * [MediaStore.createDeleteRequest] 机制）。
     */
    suspend fun buildDeleteRequest(uris: List<Uri>): IntentSender?

    /** 第 3a 步：用户确认删除原文件后，将隐藏记录持久化 */
    suspend fun commitHide(batch: List<HiddenPhotoEntity>)

    /** 第 3b 步：用户取消删除后，清理已复制到私有目录的文件，恢复原状 */
    suspend fun rollbackHide(batch: List<HiddenPhotoEntity>)

    /**
     * 恢复：把私有目录里的文件写回系统相册（MediaStore 新增记录），
     * 成功后删除私有文件与本地索引记录。
     *
     * @param uris [getHiddenPhotos] 返回的 [MediaItem.uri]（FileProvider content:// URI）
     * @return 成功恢复的数量（部分失败不影响其余照片）
     */
    suspend fun restore(uris: List<Uri>): Int
}

/** [IHiddenPhotoRepository.prepareHide] 返回的单项结果 */
data class PreparedHide(
    val entity: HiddenPhotoEntity,
    val originalUri: Uri
)
