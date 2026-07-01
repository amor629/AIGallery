# AI Gallery — 架构设计文档

> 版本：v0.1 | 创建日期：2026-07-01

---

## 一、整体架构：MVVM + Clean Architecture

```
┌─────────────────────────────────────────────────────┐
│                     UI 层（View）                    │
│   Jetpack Compose Screen + StateFlow 收集            │
│   ⚠️ 禁止直接访问数据库、网络、文件系统              │
├─────────────────────────────────────────────────────┤
│                  ViewModel 层                        │
│   持有 UseCase，将业务结果转换为 UI State            │
│   通过 StateFlow / SharedFlow 暴露状态               │
├─────────────────────────────────────────────────────┤
│                  领域层（Domain）                    │
│   UseCase：单一职责的业务逻辑单元                    │
│   仅依赖抽象接口（Repository Interface）             │
├─────────────────────────────────────────────────────┤
│                  数据层（Data）                      │
│   Repository 实现：MediaStore / Room / DataStore /  │
│   远程 API（可选）                                   │
│   ⚠️ 外部依赖细节只能在此层出现                     │
└─────────────────────────────────────────────────────┘
```

**分层规则：**
- 依赖方向只能从上到下（UI → ViewModel → Domain → Data）。  
- Domain 层不依赖任何 Android 框架类（纯 Kotlin）。  
- 上层只知道接口，不知道实现。

---

## 二、包结构设计

```
com.example.aigallery/
├── ui/                         # UI 层
│   ├── gallery/                # 相册列表功能模块
│   │   ├── GalleryScreen.kt    # Compose 页面
│   │   └── GalleryViewModel.kt # ViewModel
│   ├── detail/                 # 媒体详情模块
│   ├── settings/               # 设置模块（含 AI 配置）
│   └── components/             # 公共 UI 组件
│
├── domain/                     # 领域层（纯 Kotlin）
│   ├── model/                  # 数据模型（MediaItem, Album 等）
│   ├── repository/             # Repository 抽象接口
│   └── usecase/                # UseCase 实现
│
├── data/                       # 数据层
│   ├── mediastore/             # MediaStore 封装
│   ├── local/                  # Room 数据库
│   ├── preferences/            # DataStore 封装
│   └── remote/                 # 远程 AI API（可选模块）
│
├── ai/                         # AI 可选模块
│   ├── AiConfigRepository.kt   # AI 配置存取（DataStore 加密）
│   ├── AiStateManager.kt       # 全局 AI 状态管理
│   └── AiApiClient.kt          # 动态读取配置的网络客户端
│
└── di/                         # 依赖注入（Hilt）
    └── AppModule.kt
```

---

## 三、UI 层规范

### 3.1 Compose + StateFlow
- 每个页面对应一个 `Screen` 函数（Composable）和一个 `ViewModel`。
- ViewModel 通过 `StateFlow<UiState>` 暴露唯一的 UI 状态对象。
- Screen 只负责：**渲染状态** 和 **发送用户事件**，绝不包含业务逻辑。

```kotlin
// ✅ 正确示例
@Composable
fun GalleryScreen(viewModel: GalleryViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 只根据 uiState 渲染 UI
}

// ❌ 禁止示例 —— UI 层直接查询数据库
@Composable
fun GalleryScreen(db: AppDatabase) {  // 禁止！
    val photos = db.photoDao().getAll() // 禁止！
}
```

### 3.2 UiState 设计模式
每个功能模块定义独立的密封类 UiState：

```kotlin
// 以相册列表为例
sealed interface GalleryUiState {
    object Loading : GalleryUiState
    data class Success(val albums: List<Album>) : GalleryUiState
    data class Error(val message: String) : GalleryUiState
}
```

---

## 四、数据层规范

### 4.1 MediaStore 封装
- 通过 `MediaStoreRepository` 封装所有 `ContentResolver` 查询。
- 返回类型统一为 `Flow<List<MediaItem>>`，由 Repository 负责线程调度。
- ViewModel / UseCase 无需关心 Cursor、投影列等 Android 细节。

### 4.2 Room 本地数据库
- 仅存储**用户操作数据**（收藏、标签、备注等），不缓存 MediaStore 数据。
- 数据库版本迁移必须编写 Migration，禁止 `fallbackToDestructiveMigration`。

### 4.3 DataStore（Preferences）
- 轻量级配置：主题偏好、排序方式、AI 配置（加密）。
- 禁止用 DataStore 存储大量列表数据。

---

## 五、AI 可选模块设计（关键）

### 5.1 设计原则
> AI 功能是锦上添花，基础功能永远不依赖 AI。

- **未配置状态**：AI 入口显示引导 UI（"点击配置 AI 功能"），不报错，不崩溃。  
- **已配置状态**：AI 功能正常工作。  
- **网络故障状态**：AI 功能静默失败，基础功能不受影响。

### 5.2 AiStateManager（全局状态）

```kotlin
// 全局 AI 状态，由 AiConfigRepository 驱动
sealed interface AiState {
    object NotConfigured : AiState   // 未配置 Key/URL
    object Configured : AiState      // 已配置，可以尝试调用
    object Unavailable : AiState     // 网络不通或 API 报错
}
```

### 5.3 AiConfigRepository（安全存储）
- 使用 **Jetpack DataStore + EncryptedSharedPreferences** 或 **DataStore + Android Keystore** 存储。  
- 绝对禁止将 API Key 以明文形式出现在代码、日志、Crash 报告中。  
- 提供 `testConnectivity(): Flow<Boolean>` 方法供设置页"测试连接"按钮调用。

### 5.4 AiApiClient（动态配置）
- 网络客户端在每次请求前**动态读取**当前配置，而非启动时固定注入。
- `base_url` 和 `api_key` 均来自 `AiConfigRepository`，不存在编译时常量。

---

## 六、核心依赖版本锁定表

| 依赖 | 作用 | 最低版本要求 |
|------|------|-------------|
| Jetpack Compose BOM | UI 框架 | 2024.x 最新稳定 |
| Coil 3 | 图片加载 | 3.x 最新稳定 |
| Media3 (ExoPlayer) | 视频播放 | 1.x 最新稳定 |
| Paging 3 | 分页加载 | 3.x 最新稳定 |
| Room | 本地数据库 | 2.6.x+ |
| DataStore | 配置存储 | 1.1.x+ |
| Hilt | 依赖注入 | 2.x 最新稳定 |
| OkHttp / Retrofit | 网络（AI 可选） | 最新稳定 |
| Kotlin Coroutines | 异步 | 1.8.x+ |

> 具体版本号在 `build.gradle.kts` 的 `libs.versions.toml` 中统一管理。

---

## 七、权限声明规范

| 权限 | 用途 | 申请时机 |
|------|------|----------|
| `READ_MEDIA_IMAGES` | 读取图片（Android 13+） | 首次打开相册前 |
| `READ_MEDIA_VIDEO` | 读取视频（Android 13+） | 首次打开相册前 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 部分授权（Android 14+）| 首次打开相册前 |
| `INTERNET` | AI API 网络请求 | 配置 AI 时 |

- 权限申请必须在用户触发相关功能时（按需申请），禁止应用启动时批量请求。  
- 拒绝授权时显示友好引导，不强制退出应用。

---

## 八、测试策略

| 层级 | 测试类型 | 工具 |
|------|----------|------|
| Domain / UseCase | 单元测试 | JUnit 5 + MockK |
| ViewModel | 单元测试 | Turbine（Flow 测试）|
| Repository | 集成测试 | Room 内存数据库 |
| UI | UI 测试 | Compose UI Test |
| 端到端 | 真机验收 | 手动测试 |
