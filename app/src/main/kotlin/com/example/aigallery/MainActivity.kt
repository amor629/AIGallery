package com.example.aigallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aigallery.ui.gallery.GalleryScreen
import com.example.aigallery.ui.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用主 Activity
 *
 * 职责：持有 NavController，管理全局路由
 *
 * 路由表：
 *   "gallery"  → GalleryScreen（应用启动页）
 *   "settings" → SettingsScreen（AI 配置入口）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "gallery"
                ) {
                    composable("gallery") {
                        GalleryScreen(
                            onNavigateToSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
