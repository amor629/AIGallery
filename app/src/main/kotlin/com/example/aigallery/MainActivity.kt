package com.example.aigallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用主 Activity
 *
 * @AndroidEntryPoint 让 Hilt 能够向此 Activity 注入依赖
 *
 * 当前为阶段零占位版本：
 * - 阶段一将替换为完整的 NavHost 导航结构
 * - 阶段三将集成 AI 状态引导 UI
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用全面屏（内容延伸至状态栏和导航栏区域）
        enableEdgeToEdge()

        setContent {
            // MaterialTheme 提供 Material 3 颜色、字体、形状系统
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // ⚠️ 临时占位内容，阶段一将替换为真实相册 UI
                    InitSuccessPlaceholder()
                }
            }
        }
    }
}

/**
 * 工程初始化成功占位页
 * 能看到这个页面说明工程基建配置正确，编译通过
 */
@Composable
private fun InitSuccessPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "AI Gallery\n工程初始化成功 ✓",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
