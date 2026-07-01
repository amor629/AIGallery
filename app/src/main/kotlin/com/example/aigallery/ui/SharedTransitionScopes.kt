package com.example.aigallery.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

/**
 * 全局 CompositionLocal：向子树传递共享元素过渡所需的 Scope
 *
 * 使用方式：
 *  1. 在 MainActivity 的 NavHost 中，每个 composable { } 入口处通过
 *     CompositionLocalProvider 提供真实值
 *  2. 叶子组件（MediaThumbnailItem、PhotoDetailScreen）直接消费，
 *     无需逐层传参（prop drilling）
 *
 * 当值为 null 时（如 @Preview），sharedElement 修饰符自动跳过，内容正常显示
 */

/** SharedTransitionLayout 提供的 Scope，用于创建 rememberSharedContentState */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** Navigation Compose 每个 composable 路由的 AnimatedContentScope（实现了 AnimatedVisibilityScope） */
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }
