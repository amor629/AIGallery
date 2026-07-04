package com.example.aigallery.ui.detail

import com.example.aigallery.domain.model.MediaItem

/**
 * 详情页左右滑动列表的临时传递载体。
 *
 * 背景：Navigation Compose 的路由参数只能传基本类型/字符串，无法直接携带媒体列表；
 * 而不同入口（相册时间轴 / 搜索结果 / 智能相册标签）打开详情页时，
 * 左右滑动应限定在各自当时展示的列表范围内，而不是永远退化成"完整相册"。
 *
 * 用法：调用方在 `navController.navigate("detail...")` 之前，
 * 把"当前应该用于翻页的列表"写入 [items]；detail 路由的 Composable 只需读取一次
 * （不需要响应式更新——进入详情页那一刻列表就已经确定，后续变化不应影响正在滑动的 Pager）。
 */
object DetailPagerHolder {
    var items: List<MediaItem> = emptyList()
}
