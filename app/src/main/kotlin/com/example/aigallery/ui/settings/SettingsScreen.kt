package com.example.aigallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aigallery.ai.AiState
import com.example.aigallery.domain.model.AppTheme

/**
 * 设置页主入口（Composable）
 *
 * 包含：
 * - AI 配置状态指示器（未配置 / 已配置）
 * - Base URL 输入框
 * - API Key 输入框（密码模式，可切换显示/隐藏）
 * - 保存配置 / 测试连接 / 清除配置 按钮
 *
 * @param onNavigateBack 返回上一页的回调（由导航层提供）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 监听 snackbarMessage，有消息时弹出提示
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.onSnackbarShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),   // 键盘弹起时在底部追加等同键盘高度的 padding，内容可滚动到键盘上方
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ---- AI 状态指示卡片 ----
            AiStateCard(aiState = uiState.aiState)

            // ---- 快速上手引导卡（仅未配置时展示）----
            if (uiState.aiState is AiState.NotConfigured) {
                DashScopeQuickStartCard()
            }

            // ---- 主题切换卡 ----
            ThemeCard(
                currentTheme = uiState.currentTheme,
                onThemeSelected = viewModel::setTheme
            )

            // ---- AI 配置表单 ----
            AiConfigForm(
                uiState = uiState,
                onBaseUrlChange = viewModel::onBaseUrlChange,
                onApiKeyChange = viewModel::onApiKeyChange,
                onSave = viewModel::saveConfig,
                onTestConnectivity = viewModel::testConnectivity,
                onClearConfig = viewModel::clearConfig
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ============================================================
// AI 状态指示卡片
// ============================================================

@Composable
private fun AiStateCard(aiState: AiState) {
    val (icon, tint, title, description) = when (aiState) {
        is AiState.NotConfigured -> listOf(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "AI 功能未配置",
            "基础相册功能正常使用。填写 API Key 后可解锁智能标签、语义搜索等 AI 功能。"
        )
        is AiState.Configured -> listOf(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "AI 功能已配置",
            "AI 功能已就绪，可在相册中使用智能功能。"
        )
        is AiState.Unavailable -> listOf(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "AI 暂不可用",
            "配置已保存，但最近一次请求失败：${aiState.reason}"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                contentDescription = null,
                tint = tint as androidx.compose.ui.graphics.Color,
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    text = title as String,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description as String,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================
// AI 配置表单
// ============================================================

@Composable
private fun AiConfigForm(
    uiState: SettingsUiState,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnectivity: () -> Unit,
    onClearConfig: () -> Unit
) {
    // API Key 明文/密文切换状态
    var apiKeyVisible by remember { mutableStateOf(false) }

    // ---- 章节标题 ----
    Text(
        text = "AI 接口配置",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )

    // ---- Base URL 输入框 ----
    OutlinedTextField(
        value = uiState.baseUrlInput,
        onValueChange = onBaseUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API 地址 (Base URL)") },
        placeholder = { Text("https://dashscope.aliyuncs.com/compatible-mode/v1") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        supportingText = {
            Text("填写 OpenAI 兼容接口的基础地址，不含具体路径")
        }
    )

    // ---- API Key 输入框（密码模式）----
    OutlinedTextField(
        value = uiState.apiKeyInput,
        onValueChange = onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API Key") },
        placeholder = { Text("sk-...") },
        singleLine = true,
        // 根据切换状态决定是否明文显示
        visualTransformation = if (apiKeyVisible) VisualTransformation.None
                               else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        // 右侧眼睛图标：切换 Key 显示/隐藏
        trailingIcon = {
            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                Icon(
                    imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff
                                  else Icons.Default.Visibility,
                    contentDescription = if (apiKeyVisible) "隐藏 Key" else "显示 Key"
                )
            }
        },
        supportingText = {
            Text("Key 会加密存储于设备本地，不会上传到任何服务器")
        }
    )

    // ---- 操作按钮行 ----
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 保存按钮
        Button(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            enabled = !uiState.isSaving && !uiState.isTesting
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("保存配置")
            }
        }

        // 测试连接按钮（仅已配置时可用）
        FilledTonalButton(
            onClick = onTestConnectivity,
            modifier = Modifier.weight(1f),
            enabled = uiState.aiState is AiState.Configured
                    && !uiState.isTesting
                    && !uiState.isSaving
        ) {
            if (uiState.isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("测试连接")
            }
        }
    }

    // ---- 清除配置按钮（仅已配置时显示）----
    if (uiState.aiState is AiState.Configured) {
        TextButton(
            onClick = onClearConfig,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("清除 AI 配置")
        }
    }
}

// ============================================================
// DashScope 快速上手引导卡（未配置状态专用）
// ============================================================

/**
 * 当用户尚未配置 AI 时，展示阿里云 DashScope 的注册 / 获取 Key 步骤
 *
 * 设计原则：纯展示组件，无任何副作用，无状态
 */
@Composable
private fun DashScopeQuickStartCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "快速上手：阿里云 DashScope",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // 步骤说明
            val steps = listOf(
                "1. 访问 dashscope.aliyun.com，注册阿里云账号",
                "2. 进入「模型服务 > API-KEY 管理」，创建新的 API Key",
                "3. 复制 API Key（以 sk- 开头）并粘贴到下方输入框",
                "4. API 地址填写如下（已预填）："
            )
            steps.forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // DashScope 地址（可让用户复制参考）
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    text = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Text(
                text = "使用模型 qwen-vl-max，新用户有免费额度可试用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ============================================================
// 主题切换卡片
// ============================================================

/**
 * 主题选择卡片
 *
 * 提供三个选项（跟随系统 / 亮色 / 暗色），使用单选按钮（RadioButton）。
 * 选中项立即写入 DataStore，MainActivity 订阅到新值后自动重组 UI。
 *
 * @param currentTheme   当前生效的主题（从 ViewModel 读取）
 * @param onThemeSelected 用户选中新主题时的回调
 */
@Composable
private fun ThemeCard(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit
) {
    // 主题选项列表：(枚举值, 显示文字, 图标)
    val options = listOf(
        Triple(AppTheme.SYSTEM, "跟随系统", Icons.Default.PhoneAndroid),
        Triple(AppTheme.LIGHT,  "亮色模式", Icons.Default.LightMode),
        Triple(AppTheme.DARK,   "暗色模式", Icons.Default.DarkMode)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 章节标题
            Text(
                text = "外观主题",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 单选按钮组（selectableGroup 确保无障碍语义正确）
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { (theme, label, icon) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // selectable 使整行可点击，并提供无障碍 role
                            .selectable(
                                selected = currentTheme == theme,
                                onClick  = { onThemeSelected(theme) },
                                role     = Role.RadioButton
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 单选圆圈
                        RadioButton(
                            selected = currentTheme == theme,
                            onClick  = null  // 已由外层 Row 的 selectable 处理点击
                        )
                        // 主题图标
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (currentTheme == theme)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 主题名称
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (currentTheme == theme)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
