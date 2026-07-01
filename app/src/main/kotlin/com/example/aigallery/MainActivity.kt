package com.example.aigallery

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.getValue
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
import com.example.aigallery.ui.detail.PhotoDetailScreen
import com.example.aigallery.ui.gallery.GalleryScreen
import com.example.aigallery.ui.gallery.GalleryViewModel
import com.example.aigallery.ui.settings.SettingsScreen
import com.example.aigallery.domain.model.MediaType
import com.example.aigallery.ui.LocalAnimatedVisibilityScope
import com.example.aigallery.ui.LocalSharedTransitionScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 应用主 Activity
 *
 * 职责：持有 NavController，管理全局路由；通过 [MainViewModel] 订阅主题并应用。
 *
 * 路由表：
 *   "gallery"  → GalleryScreen（应用启动页）
 *   "settings" → SettingsScreen（AI 配置入口）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * 通过 Hilt-ViewModel 桥接获取主题偏好
     * viewModels() 由 activity-ktx 提供，Hilt 注入 MainViewModel 的构造参数
     */
    private val mainViewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ installSplashScreen() 必须在 super.onCreate() 之前调用，
        //    否则系统 SplashScreen 控制权无法被接管，仍会出现白屏/黑屏。
        //    此调用不会阻塞 UI，第一帧渲染完毕后 Splash 自动消失。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToDetail = { mediaItem ->
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

                        // 复用 gallery 路由已存在的 GalleryViewModel，共享完整媒体列表。
                        // getBackStackEntry("gallery") 在此处始终成功（detail 只能从 gallery 打开）。
                        val galleryEntry = remember(navController) {
                            navController.getBackStackEntry("gallery")
                        }
                        val galleryViewModel: GalleryViewModel = hiltViewModel(galleryEntry)
                        val allMedia by galleryViewModel.allMedia.collectAsStateWithLifecycle()

                        CompositionLocalProvider(
                            LocalSharedTransitionScope   provides this@SharedTransitionLayout,
                            LocalAnimatedVisibilityScope provides animScope
                        ) {
                            PhotoDetailScreen(
                                allMedia       = allMedia,
                                initialUri     = uri,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // ---- 设置页（AI 配置入口）----
                    composable("settings") {
                        SettingsScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
                } // SharedTransitionLayout
            }
        }
    }
}
