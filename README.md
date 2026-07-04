# 忆刻 AI Gallery

**忆刻** 是一款运行在 Android 14+ 设备上的本地优先相册应用：基础浏览功能完全离线可用，AI 能力（识图、文案、图片编辑、智能分类等）作为可选模块，由用户自行配置阿里云百炼 API Key 后启用。

> 核心理念：**本地优先、隐私安全、AI 可选**。未配置 AI 时，应用 100% 正常使用；AI 入口仅显示引导提示，不报错、不崩溃。

---

## ✨ 功能特性

### 基础相册
- 时间轴分组浏览本地图片 / 视频，网格瀑布流展示
- 图片/视频详情页：双指缩放、双击放大、下滑退出、左右滑动切换
- 相册（文件夹）列表、长按多选、批量删除（含 Android 系统删除授权流程）
- 详情页单张删除（二次确认后再触发系统授权）

### AI 可选模块（需自行配置阿里云百炼 API Key）
- **AI 识图**：一句话描述图片内容
- **AI 生成文案**：一键生成适合朋友圈的文案
- **AI 老照片修复**：去划痕、去噪点、褪色泛黄修复
- **AI 照片美化**：高强度人像精修美颜、风景画质增强，不移除原图水印
- **AI 智能分类相册**：后台批量打标签，按标签自动归类相册
- **AI 废片清理**：自动识别模糊、闭眼、重复照片，一键批量清理
- **AI 视觉内容搜索**：用自然语言描述搜索相册中的图片

### 本地离线编辑（无需联网）
- 打马赛克（像素化涂抹，仿"加密"效果，支持自由涂抹区域）
- 裁剪（网格线 + 可拖拽四角把手）
- 涂鸦
- 视频截取（基于 Media3 Transformer）

### 隐私保护
- 隐藏相册：生物识别（指纹/人脸/锁屏密码）验证后进入，独立私有存储目录

### 系统集成
- 已声明 `VIEW` / `SEND` intent-filter，支持在系统"打开方式"或部分厂商 ROM 的"默认应用"设置中被选为图片/视频查看器

---

## 🛠 技术栈

| 分类 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 架构 | MVVM + Clean Architecture（UI → ViewModel → Domain → Data 单向依赖）|
| 依赖注入 | Hilt |
| 本地数据库 | Room |
| 配置存储 | Jetpack DataStore（AI Key 等敏感配置加密存储）|
| 图片加载 | Coil 3 |
| 视频播放/处理 | Media3（ExoPlayer + Transformer）|
| 后台任务 | WorkManager |
| 分页加载 | Paging 3 |
| 网络请求 | Retrofit + OkHttp |
| AI 能力 | 阿里云百炼（DashScope）：Qwen-VL 视觉理解 / 文案生成、万相 wanx2.1-imageedit 图像编辑 |

---

## 📱 环境要求

| 项目 | 要求 |
|------|------|
| 最低系统版本 | Android 14（API 34）|
| 目标系统版本 | Android 15（API 35）|
| 开发工具 | Android Studio（最新稳定版）|
| JDK | 17 |

---

## 🚀 快速开始

1. 克隆项目并用 Android Studio 打开：

   ```bash
   git clone https://github.com/amor629/AIGallery.git
   ```

2. 直接点击 Run 即可编译安装 Debug 版本（无需任何额外配置，AI 功能默认处于"未配置"引导态，不影响基础相册功能使用）。

3. 如需启用 AI 功能，打开 App → 设置页，填写：
   - **Base URL**：阿里云百炼兼容模式接口地址
   - **API Key**：你在阿里云百炼控制台申请的 Key

   填写完成后可点击"测试连接"验证是否配置成功。

> ⚠️ API Key 属于敏感信息，仅保存在你本机的加密 DataStore 中，不会写入代码仓库，也不会上传到除阿里云百炼官方接口以外的任何地方。

### 打包签名 Release 版本（可选）

Release 签名信息从根目录的 `keystore.properties` 读取，该文件**不提交到 Git**（已在 `.gitignore` 中排除）。如果该文件不存在，Release 构建会自动回退为 Debug 签名，不影响正常编译。若需要正式签名，在项目根目录新建 `keystore.properties`：

```properties
storeFile=你的keystore文件路径.jks
storePassword=你的密钥库密码
keyAlias=你的密钥别名
keyPassword=你的密钥密码
```

---

## 📂 项目结构

```
com.example.aigallery/
├── ui/            # UI 层：Compose 页面 + ViewModel（按功能模块分包）
├── domain/        # 领域层：数据模型、Repository 接口（纯 Kotlin，无 Android 依赖）
├── data/          # 数据层：MediaStore / Room / DataStore / 远程 AI API 的具体实现
├── ai/            # AI 可选模块：配置存取、全局状态管理、动态网络客户端
├── work/          # WorkManager 后台任务（智能打标、废片扫描）
└── di/            # Hilt 依赖注入配置
```

更详细的分层规则与设计约束见 [`ARCHITECTURE.md`](ARCHITECTURE.md)，开发阶段规划见 [`PROJECT_PLAN.md`](PROJECT_PLAN.md)。

---

## 🔒 权限说明

| 权限 | 用途 |
|------|------|
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | 读取本地图片/视频（Android 13+）|
| `READ_MEDIA_VISUAL_USER_SELECTED` | 支持用户仅授权部分媒体（Android 14+）|
| `INTERNET` | 仅 AI 可选模块使用，未配置 Key 时不会发起任何网络请求 |
| `POST_NOTIFICATIONS` | 智能打标 / 废片扫描后台任务的进度通知 |

所有权限均按需在用户实际触发相关功能时申请，不在启动时批量弹窗；拒绝授权时显示友好引导，不会强制退出应用。

---

## 🤝 核心设计约束

1. 禁止硬编码任何 AI API Key 或 Base URL，一律由用户在设置页自行填写
2. AI 功能永远是可选模块，未配置时基础功能 100% 离线可用
3. UI 层禁止直接操作数据库或网络，所有数据访问必须经过 Repository
4. 内存安全优先：Domain 模型只存储元数据，像素数据按需加载、及时释放
