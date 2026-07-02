package com.example.aigallery.data.local.tag

import com.example.aigallery.data.local.db.PhotoTagDao
import com.example.aigallery.data.local.db.PhotoTagEntity
import com.example.aigallery.data.local.db.TagAlbumRow
import com.example.aigallery.domain.repository.ITagRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Room DAO 包装实现，将数据库操作映射到 Domain 接口 */
@Singleton
class TagRepositoryImpl @Inject constructor(
    private val dao: PhotoTagDao
) : ITagRepository {
    override fun getTagAlbums(): Flow<List<TagAlbumRow>> = dao.getTagAlbums()
    override fun getPhotoUrisByTag(tag: String) = dao.getPhotoUrisByTag(tag)
    override suspend fun getAllTaggedUris() = dao.getAllTaggedUris()
    override suspend fun saveTags(photoUri: String, tags: List<String>) {
        dao.insertAll(tags.map { PhotoTagEntity(photoUri = photoUri, tag = it) })
    }
    override fun getTaggedPhotoCount() = dao.getTaggedPhotoCount()
    override suspend fun clearAll() = dao.clearAll()
}
