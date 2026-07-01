package com.example.aigallery.domain.repository

import com.example.aigallery.domain.model.Album
import com.example.aigallery.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * 媒体 Repository 接口（Domain 层抽象）
 *
 * 所有上层（ViewModel、UseCase）只依赖此接口，不依赖 MediaStore 具体实现。
 * 这样即便底层改为网络相册或 Room 缓存，上层代码无需修改。
 *
 * Flow 语义：
 * - 每个 Flow 在订阅后立即发射一次当前数据
 * - MediaStore 内容发生变化时（拍照、删除、导入），Flow 自动发射新数据
 * - 调用方取消收集时，底层 ContentObserver 自动注销（无内存泄漏）
 */
interface IMediaRepository {

    /**
     * 获取设备上所有媒体文件（图片 + 视频），按拍摄时间降序排列
     *
     * 响应式：MediaStore 变化时自动推送新列表
     * 内存安全：只返回元数据，不加载像素
     *
     * Step 2 注：此方法返回完整列表，Paging3 的 PagingSource 将在 Step 2 补充
     */
    fun getAllMedia(): Flow<List<MediaItem>>

    /**
     * 获取相册列表，按媒体数量降序排列（媒体最多的相册排在最前）
     * 每个相册包含封面图、名称和媒体数量
     */
    fun getAlbums(): Flow<List<Album>>

    /**
     * 获取指定相册内的媒体文件，按拍摄时间降序排列
     *
     * @param bucketId 相册 ID（来自 Album.id）
     */
    fun getMediaByAlbum(bucketId: Long): Flow<List<MediaItem>>
}
