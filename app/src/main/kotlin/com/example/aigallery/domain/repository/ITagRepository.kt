package com.example.aigallery.domain.repository

import com.example.aigallery.data.local.db.TagAlbumRow
import kotlinx.coroutines.flow.Flow

/** 照片标签 Repository 接口（Domain 层抽象） */
interface ITagRepository {
    /** 获取所有标签相册（标签名 + 数量 + 封面 URI），响应式 */
    fun getTagAlbums(): Flow<List<TagAlbumRow>>
    /** 获取某个标签下的所有照片 URI，响应式 */
    fun getPhotoUrisByTag(tag: String): Flow<List<String>>
    /** 返回所有已打标照片的 URI（Worker 跳过已处理图片用） */
    suspend fun getAllTaggedUris(): List<String>
    /** 批量保存某张照片的标签 */
    suspend fun saveTags(photoUri: String, tags: List<String>)
    /** 已打标照片数量（Flow） */
    fun getTaggedPhotoCount(): Flow<Int>
    /** 保存某张截图的 OCR 文本（非截图无需调用） */
    suspend fun saveOcrText(photoUri: String, text: String)
    /** 本地检索：OCR 文本包含查询词的照片 URI 列表（免费、瞬时，替代实时 AI 扫描） */
    suspend fun searchOcrText(query: String): List<String>
    /** 清空所有标签与 OCR 文本 */
    suspend fun clearAll()
}
