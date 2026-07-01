package com.example.aigallery.crash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aigallery.MainActivity

/**
 * 崩溃友好提示 Activity
 *
 * ⚠️ 此 Activity 故意 **不** 使用 @AndroidEntryPoint（不依赖 Hilt）。
 *    原因：崩溃发生时 Hilt 的依赖图可能已损坏，如果此处再用 Hilt，
 *         大概率触发二次崩溃，导致什么提示都无法显示。
 *    因此本页面只依赖最基础的 ComponentActivity + Compose + MaterialTheme。
 *
 * 提供两个操作：
 *   - 「重新启动应用」：清空任务栈后重启 MainActivity
 *   - 「退出」：直接调用 finish() 关闭本页面（系统会回到桌面）
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // 跟随系统深浅色，不走 DataStore（DataStore 需要 Hilt，此处不可用）
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // ---- 崩溃图标 ----
                        Icon(
                            imageVector = Icons.Default.SentimentDissatisfied,
                            contentDescription = null,
                            modifier = Modifier.size(88.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(Modifier.height(28.dp))

                        // ---- 主标题 ----
                        Text(
                            text = "哎呀，发生了一点小意外",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(12.dp))

                        // ---- 副说明文字 ----
                        Text(
                            text = "应用遇到了意外错误并已自动记录日志。\n您可以尝试重新启动，或直接退出。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(48.dp))

                        // ---- 重新启动按钮 ----
                        Button(
                            onClick = { restartApp() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重新启动应用")
                        }

                        Spacer(Modifier.height(12.dp))

                        // ---- 退出按钮 ----
                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("退出")
                        }
                    }
                }
            }
        }
    }

    /**
     * 清除整个回退栈，重新启动 MainActivity。
     * 使用 FLAG_ACTIVITY_CLEAR_TASK 确保不会保留任何已崩溃的 Activity 实例。
     */
    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
