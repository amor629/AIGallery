package com.example.aigallery.data.local.waste

import com.example.aigallery.data.local.db.WastePhotoDao
import com.example.aigallery.data.local.db.WastePhotoEntity
import com.example.aigallery.domain.repository.IWasteRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Room DAO 包装实现，将数据库操作映射到 Domain 接口 */
@Singleton
class WasteRepositoryImpl @Inject constructor(
    private val dao: WastePhotoDao
) : IWasteRepository {
    override fun getWasteResults() = dao.getWasteResults()
    override suspend fun getAllScannedUris() = dao.getAllScannedUris()
    override suspend fun saveResults(results: List<Pair<String, String?>>) {
        dao.insertAll(results.map { (uri, reason) -> WastePhotoEntity(uri = uri, reason = reason) })
    }
    override suspend fun removeResults(uris: List<String>) {
        if (uris.isNotEmpty()) dao.deleteByUris(uris)
    }
    override suspend fun clearAll() = dao.clearAll()
}
