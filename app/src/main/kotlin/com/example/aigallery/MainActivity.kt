package com.example.aigallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.aigallery.domain.model.AppTheme
import com.example.aigallery.ui.MainViewModel
import com.example.aigallery.ui.albums.SmartAlbumsScreen
import com.example.aigallery.ui.detail.DetailPagerHolder
import com.example.aigallery.ui.detail.PhotoDetailScreen
import com.example.aigallery.ui.edit.LocalImageEditScreen
import com.example.aigallery.ui.edit.VideoTrimScreen
import com.example.aigallery.domain.model.LocalEditMode
import com.example.aigallery.domain.model.MediaItem
import com.example.aigallery.ui.gallery.GalleryScreen
import com.example.aigallery.ui.gallery.GalleryViewModel
import com.example.aigallery.ui.hidden.HiddenAlbumScreen
import com.example.aigallery.ui.settings.SettingsScreen
import com.example.aigallery.ui.waste.WasteCleanupScreen
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.ui.LocalAnimatedVisibilityScope
import com.example.aigallery.ui.LocalSharedTransitionScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 外部"打开方式/分享"进来的待查看媒体（来自其他 App 的 VIEW / SEND 意图）
 */
private data class PendingExternalMedia(val uri: Uri, val name: String, val mediaType: MediaType)

/**
 * 应用主 Activity
 *
 * 职责：持有 NavController，管理全局路由；通过 [MainViewModel] 订阅主题并应用。
 *
 * 路由表：
 *   "gallery"       → GalleryScreen（应用启动页）
 *   "settings"      → SettingsScreen（AI 配置入口）
 *   "hidden_album"  → HiddenAlbumScreen（隐藏相册，进入前已在 GalleryScreen 完成生物识别验证）
 *
 * ⚠️ 继承 FragmentActivity（而非 ComponentActivity）：
 *    androidx.biometric.BiometricPrompt 只能挂载在 FragmentActivity/Fragment 上，
 *    用于隐藏相册入口的验证弹窗。FragmentActivity 是 ComponentActivity 的子类，
 *    不影响现有的 Compose / SplashScreen / enableEdgeToEdge 用法。
 *
 * ⚠️ launchMode="singleTask"：确保通过"打开方式/分享"重复调起时复用同一个 Activity
 *    实例（走 [onNewIntent]），而不是在任务栈里堆叠出多个 MainActivity。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /**
     * 通过 Hilt-ViewModel 桥接获取主题偏好
     * viewModels() 由 activity-ktx 提供，Hilt 注入 MainViewModel 的构造参数
     */
    private val mainViewModel: MainViewModel by viewModels()

    /**
     * 待处理的外部查看请求（"用忆刻打开"这张图片/视频、或"分享"过来的一张）。
     * - onCreate：冷启动时从 intent 中提取一次
     * - onNewIntent：App 已在后台运行时，系统复用现有 Activity 并回调此方法
     * Compose 端通过 LaunchedEffect 监听此状态，一旦非空立即跳转到详情页查看，
     * 查看完成后重置为 null，避免屏幕旋转/重组时重复跳转。
     */
    private var pendingExternalMedia by mutableStateOf<PendingExternalMedia?>(null)

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ installSplashScreen() 必须在 super.onCreate() 之前调用，
        //    否则系统 SplashScreen 控制权无法被接管，仍会出现白屏/黑屏。
        //    此调用不会阻塞 UI，第一帧渲染完毕后 Splash 自动消失。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingExternalMedia = extractExternalMedia(intent)

        setContent {
            // 订阅主题 Flow
            val appTheme by mainViewModel.appTheme.collectAsStateWithLifecycle()

            // 根据用户选择决定是否启用暗色主题
            val useDarkTheme = when (appTheme) {
                AppTheme.LIGHT  -> false                  // 强制亮色
                AppTheme.DARK   -> true                   // 强制暗色
                AppTheme.SYSTEM -> isSystemInDarkTheme()  // 跟随系统
            }

            // Material3 内置配色方案（可后续扩展为 Dynamic Color）
            MaterialTheme(
                colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()
            ) {
                val navController = rememberNavController()

                // ---- 处理"用忆刻打开"/"分享到忆刻"的外部图片或视频 ----
                // 单独构造一个只含这一项的列表（而非依赖 MediaStore 全量列表按 URI 查找），
                // 这样即便外部 URI 不属于本机相册（如聊天软件缓存文件的 FileProvider URI），
                // 也总能正确显示，不会出现"打开了却显示错误的照片"的问题。
                LaunchedEffect(pendingExternalMedia) {
                    val media = pendingExternalMedia ?: return@LaunchedEffect
                    val item = MediaItem(
                        id = 0L,
                        uri = media.uri,
                        name = media.name,
                        dateAdded = System.currentTimeMillis(),
                        dateTaken = 0L,
                        mimeType = if (media.mediaType == MediaType.VIDEO) "video/*" else "image/*",
                        mediaType = media.mediaType,
                        width = 0,
                        height = 0,
                        size = 0L,
                        duration = 0L,
                        bucketId = 0L,
                        bucketName = ""
                    )
                    DetailPagerHolder.items = listOf(item)
                    val encodedUri = URLEncoder.encode(media.uri.toString(), StandardCharsets.UTF_8.name())
                    val encodedName = URLEncoder.encode(media.name, StandardCharsets.UTF_8.name())
                    navController.navigate(
                        "detail?uri=$encodedUri&name=$encodedName&type=${media.mediaType.name}"
                    )
                    pendingExternalMedia = null
                }

                SharedTransitionLayout {
                NavHost(
                    navController = navController,
                    startDestination = "gallery"
                ) {
                    // ---- 相册主页 ----
                    composable("gallery") {
                        val animScope = this  // AnimatedContentScope : AnimatedVisibilityScope
                        CompositionLocalProvider(
                            LocalSharedTransitionScope   provides this@SharedTransitionLayout,
                            LocalAnimatedVisibilityScope provides animScope
                        ) {
                        GalleryScreen(
                            onNavigateToSettings  = { navController.navigate("settings") },
                            onNavigateToWaste     = { navController.navigate("waste") },
                            onNavigateToSmartAlbums = { navController.navigate("smart_albums") },
                            onNavigateToHiddenAlbum = { navController.navigate("hidden_album") },
                            onNavigateToDetail = { mediaItem, pagerList ->
                                // 记录点击时所在的列表，供 detail 路由限定左右滑动范围
                                DetailPagerHolder.items = pagerList
                                // 将 content URI 编码后作为查询参数，避免路径分隔符冲突
                                val encodedUri = URLEncoder.encode(
                                    mediaItem.uri.toString(),
                                    StandardCharsets.UTF_8.name()
                                )
                                val encodedName = URLEncoder.encode(
                                    mediaItem.name,
                                    StandardCharsets.UTF_8.name()
                                )
                                navController.navigate("detail?uri=$encodedUri&name=$encodedName&type=${mediaItem.mediaType.name}")
                            }
                        )
                        } // CompositionLocalProvider
                    }

                    // ---- 图片/视频详情页 ----
                    composable(
                        route = "detail?uri={uri}&name={name}&type={type}",
                        arguments = listOf(
                            navArgument("uri")  { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                            navArgument("type") { type = NavType.StringType; defaultValue = "IMAGE" }
                        )
                    ) { backStackEntry ->
                        val animScope = this
                        val rawUri = backStackEntry.arguments?.getString("uri") ?: ""
                        val uri = android.net.Uri.parse(
                            URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name())
                        )

                        // 复用 gallery 路由已存在的 GalleryViewModel，作为兜底数据源。
                        // getBackStackEntry("gallery") 在此处始终成功（detail 只能从 gallery/smart_albums 打开）。
                        val galleryEntry = remember(navController) {
                            navController.getBackStackEntry("gallery")
                        }
                        val galleryViewModel: GalleryViewModel = hiltViewModel(galleryEntry)
                        val fallbackAllMedia by galleryViewModel.allMedia.collectAsStateWithLifecycle()

                        // 翻页范围：优先使用调用方点击时所在的列表（时间轴/搜索结果/标签相册），
                        // 用 remember 固定，避免后续重组重复读取；理论上不会为空，为空时兜底用全量相册
                        val pagerMedia = remember(uri) {
                            DetailPagerHolder.items
                        }.ifEmpty { fallbackAllMedia }

                        CompositionLocalProvider(
                            LocalSharedTransitionScope   provides this@SharedTransitionLayout,
                            LocalAnimatedVisibilityScope provides animScope
                        ) {
                            PhotoDetailScreen(
                                allMedia       = pagerMedia,
                                initialUri     = uri,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToImageEdit = { editUri, mode ->
                                    val encoded = URLEncoder.encode(
                                        editUri.toString(), StandardCharsets.UTF_8.name()
                                    )
                                    navController.navigate(
                                        "image_edit?uri=$encoded&mode=${mode.name}"
                                    )
                                },
                                onNavigateToVideoTrim = { videoUri, name ->
                                    val encodedUri = URLEncoder.encode(
                                        videoUri.toString(), StandardCharsets.UTF_8.name()
                                    )
                                    val encodedName = URLEncoder.encode(
                                        name, StandardCharsets.UTF_8.name()
                                    )
                                    navController.navigate(
                                        "video_trim?uri=$encodedUri&name=$encodedName"
                                    )
                                }
                            )
                        }
                    }

                    // ---- 设置页（AI 配置入口）----
                    composable("settings") {
                        SettingsScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    // ---- AI 废片清理页 ----
                    composable("waste") {
                        WasteCleanupScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    // ---- 智能相册（AI 打标分类）----
                    composable("smart_albums") {
                        SmartAlbumsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToDetail = { mediaItem, pagerList ->
                                // 记录点击时所在的标签相册列表，供 detail 路由限定左右滑动范围
                                DetailPagerHolder.items = pagerList
                                val encodedUri = java.net.URLEncoder.encode(
                                    mediaItem.uri.toString(),
                                    java.nio.charset.StandardCharsets.UTF_8.name()
                                )
                                val encodedName = java.net.URLEncoder.encode(
                                    mediaItem.name,
                                    java.nio.charset.StandardCharsets.UTF_8.name()
                                )
                                navController.navigate("detail?uri=$encodedUri&name=$encodedName&type=${mediaItem.mediaType.name}")
                            }
                        )
                    }

                    // ---- 隐藏相册（生物识别验证已在 GalleryScreen 入口完成）----
                    composable("hidden_album") {
                        HiddenAlbumScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToDetail = { mediaItem, pagerList ->
                                DetailPagerHolder.items = pagerList
                                val encodedUri = URLEncoder.encode(
                                    mediaItem.uri.toString(),
                                    StandardCharsets.UTF_8.name()
                                )
                                val encodedName = URLEncoder.encode(
                                    mediaItem.name,
                                    StandardCharsets.UTF_8.name()
                                )
                                navController.navigate("detail?uri=$encodedUri&name=$encodedName&type=${mediaItem.mediaType.name}")
                            }
                        )
                    }

                    // ---- 本地图片编辑（马赛克 / 裁剪 / 涂鸦，纯离线）----
                    composable(
                        route = "image_edit?uri={uri}&mode={mode}",
                        arguments = listOf(
                            navArgument("uri") { type = NavType.StringType },
                            navArgument("mode") { type = NavType.StringType; defaultValue = "MOSAIC" }
                        )
                    ) { backStackEntry ->
                        val rawUri = backStackEntry.arguments?.getString("uri") ?: ""
                        val uri = android.net.Uri.parse(
                            URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name())
                        )
                        val modeName = backStackEntry.arguments?.getString("mode") ?: "MOSAIC"
                        val mode = runCatching { LocalEditMode.valueOf(modeName) }
                            .getOrDefault(LocalEditMode.MOSAIC)

                        LocalImageEditScreen(
                            sourceUri = uri,
                            mode = mode,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    // ---- 视频截取 ----
                    composable(
                        route = "video_trim?uri={uri}&name={name}",
                        arguments = listOf(
                            navArgument("uri") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "video.mp4" }
                        )
                    ) { backStackEntry ->
                        val rawUri = backStackEntry.arguments?.getString("uri") ?: ""
                        val uri = android.net.Uri.parse(
                            URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name())
                        )
                        val rawName = backStackEntry.arguments?.getString("name") ?: "video.mp4"
                        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())

                        VideoTrimScreen(
                            sourceUri = uri,
                            displayName = name,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
                } // SharedTransitionLayout
            }
        }
    }

    /**
     * App 已在运行（单实例任务栈复用）时，系统通过此回调而非 onCreate 传入新意图。
     * 典型场景：文件管理器/微信里已切到过忆刻，此时再"打开方式"选择忆刻查看另一张图片。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingExternalMedia = extractExternalMedia(intent)
    }

    /**
     * 从外部传入的 Intent 中提取要查看的图片/视频 URI。
     * 支持两类来源：
     * - [Intent.ACTION_VIEW]：文件管理器/浏览器等"打开方式"选中本 App，URI 在 [Intent.getData]
     * - [Intent.ACTION_SEND]：其他 App"分享"单张图片/视频过来，URI 在 EXTRA_STREAM
     */
    private fun extractExternalMedia(intent: Intent?): PendingExternalMedia? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return null

        val mime = intent.type ?: contentResolver.getType(uri) ?: ""
        val mediaType = if (mime.startsWith("video/")) MediaType.VIDEO else MediaType.IMAGE
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "media"
        return PendingExternalMedia(uri, name, mediaType)
    }

    /** 查询 content:// URI 的显示文件名，查询失败（如非 content scheme）时返回 null */
    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}
