# 课迹 Taskra

面向大学生的方格纸个人作业本：随手记下题目、板书照片与课堂录音 → 待办页按轻重缓急一眼看清 → 完成后一笔勾销归入课程。

- 本地优先，核心功能全部离线可用；无账号、无服务器、无广告、无埋点。
- 技术栈：Kotlin + Jetpack Compose + Material 3（自定义暖白方格纸与暖灰深色主题 + 霞鹜文楷字体）+ Room（DB v5）+ DataStore + 协程/Flow。
- 单 `app` 模块，按功能组织界面，按职责分离数据、时间、图片、录音、提醒、备份与应用内更新服务。

## 固定版本（不使用动态依赖）

| 组件 | 版本 |
|---|---|
| 应用版本 | `1.0.2`（`versionCode = 3`） |
| Gradle Wrapper / 本地 Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.20（compose 插件同版本） |
| KSP | 2.0.20-1.0.24 |
| compileSdk / targetSdk | 35（构建工具 35.0.0） |
| minSdk | 26 |
| Compose BOM | 2024.09.00 |
| navigation-compose | 2.7.0 |
| Room（含 testing） | 2.6.1 |
| DataStore Preferences | 1.1.1 |
| Coil Compose | 2.6.0 |
| kotlinx-serialization-json | 1.7.3 |
| kotlinx-coroutines | 1.8.1 |
| lifecycle（runtime/viewmodel） | 2.8.6 |
| activity-compose | 1.9.2 |
| core-ktx | 1.13.1 |
| exifinterface / documentfile | 1.3.7 / 1.0.1 |
| JDK | 17 |

## 构建与安装（无需打开 Android Studio）

前置：JDK 17，Android SDK（含 `platform-tools`、`platforms/android-35`、`build-tools/35.0.0`）。
`local.properties` 已指向本机 SDK；换机器请修改 `sdk.dir`。

```bat
cd "D:\My Project\Taskra"
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（**调试版**，包名 `com.taskra.debug`）。

建议先安装到不含用户数据的模拟器，使用 `adb devices -l` 确认序列号：

```bat
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe -s <模拟器序列号> install -r app\build\outputs\apk\debug\app-debug.apk
```

**已有用户数据的手机**：覆盖安装本身不保证数据库安全。更新前须核对包名、签名及数据库版本和迁移路径，完整备份并校验数据库（包括 WAL）、设置和图片、录音；先在隔离模拟器验证升级，再覆盖安装，安装前后及首次启动后核对原有数据。禁止卸载、清除数据、使用更旧的数据库版本或 destructive migration；备份无法验证时不要安装。日常可在设置中使用“导出完整备份”保存标准备份包。

发布版：`release` 构建类型已接正式签名（未启用混淆收缩），包名 `com.taskra`。
签名信息从 `keystore.properties`（本地）或 `TASKRA_KEYSTORE_*` 环境变量（CI）读取；缺省时 release 包为未签名构建，CI 发版时必须提供密钥。请勿将调试包当作发布包分发。

## 发版、官网与应用内更新（Release + taskra.chengyi.me）

- **打 tag 自动构建与发版**：推送 `v*` 标签（如 `v1.0.2`）后，`.github/workflows/release.yml` 自动将标签注入 `TASKRA_VERSION`（自动派生 `versionName` 与递增 `versionCode`）并用正式签名构建 `:app:assembleRelease`，上传 `taskra-${TAG}.apk`、`taskra-latest.apk` 与 `taskra-${TAG}.apk.sha256` 到 GitHub Releases，同时把 `site/` 连同安装包与 `version.json` 部署到 Cloudflare Pages 生产环境（`--branch=main`，域名 https://taskra.chengyi.me）。
- **应用内检查更新**：进程启动（开屏动画结束后）后台静默检查一次 GitHub Releases Latest，设置页「版本与更新」卡片亦支持随时手动点击检查。发现新版本后支持应用内流式下载 APK（带进度条与 SHA-256 完整性校验），校验通过后经 `FileProvider` 直接调起系统安装器（首次未授权「安装未知应用」时引导跳转授权，返回后自动续装；同时支持「忽略此版本」与浏览器下载兜底）。
- **官网独立更新**：当 `main` 分支仅修改 `site/**` 时，工作流自动拉取最新 GitHub Release 的 APK 与校验值同步部署至 Cloudflare Pages，避免重新编译导致 SHA-256 不一致。
- **本地签名配置**：复制 `keystore.properties.example` 为 `keystore.properties` 后填写（已进 `.gitignore`）；CI 从 GitHub Secrets 读取 `ANDROID_KEYSTORE_BASE64` 等同名环境变量。
- **官网源码与本地预览**：源码位于 `site/`（纯静态：`index.html` + `styles.css` + `app.js` + `favicon.svg` + `version.json`），采用与新应用一致的方格纸 + 霞鹜文楷手账风格，启动时双源校验同源 `version.json` 与 GitHub Releases Latest API。本地预览可直接运行：
  ```powershell
  python -m http.server 8080 --bind 127.0.0.1 --directory site
  ```
  浏览器访问 http://127.0.0.1:8080 即可预览。

## 关键设计选择记录

### 视觉与交互
- **方格纸与手写感主题**：暖白方格纸 `#FAF7F2` + 朱红批注 `#B94335`（深色模式为暖灰纸面 `#1C1917`），禁用系统动态取色；标题与卡片摘要采用霞鹜文楷（LXGW WenKai），平板最大内容宽度 720dp 居中。新建作业与新建课程统一使用主题主色圆形加号 FAB。
- **全链路深浅色实时跟随**：外观支持跟随系统/浅色/深色；针对澎湃 OS / MIUI 等定制系统单应用深色拦截与控制中心切换，通过 `Settings.Secure("ui_night_mode")`、`UiModeManager.nightMode` 与 `Configuration.uiMode` 多源解析并配合 `ContentObserver` 实时刷新，无需重建进程。
- **首次进入开屏与桌面图标**：进程首次启动播放约 1.6 秒钢笔书写墨线动画（可点击跳过，后台返回不重复播放）；启动图标采用方格纸与钢笔图案，支持 Android 13 主题图标。
- **搜索、筛选与自励便签**：搜索与筛选均使用独立浮窗（取消不更改当前条件）；「给自己的话」自励便签在待办页直接编辑，设置页仅保留显隐开关。

### 单文档编辑器与多媒体附件
- **点开即编辑（无只读详情页）**：列表摘要由正文首行自动生成（历史作业标题保留）；点开已有作业直接进入可编辑页（右上角提供保存、完成/恢复、移入回收站），正文原地编辑，图片点击可全屏查看。编辑页固定课程、截止时间与「题目与要求」元信息栏，仅正文区域独立滚动，整篇为空时显示一次占位提示。
- **图文与录音混排**：键盘上方工具栏提供录音、相册、相机、粘贴图片、文字格式（加粗、荧光笔高亮、三档字号）。原图存 `filesDir/images/<id>.jpg`，录音存 `filesDir/audio/<id>.m4a`（前台申请麦克风权限录制，详情页可内嵌播放）。
- **重编辑与删除缝合**：纯图片/纯录音作业再次编辑时自动补齐光标输入位置；删除附件时自动合并两侧文字与富文本区间，保留用户输入的空格与换行。
- **500ms 防抖草稿**：输入暂停 500ms 串行落盘草稿（键为 `new` / `edit:<id>` / `template:<courseId>`），顶部云朵图标缩放反馈保存状态；返回、切后台和调起相机前自动提交。从草稿箱发布后，若仍有剩余草稿则返回草稿箱，否则返回待办页。

### 状态、手势、拖拽排序与课程模板
- **状态纯净**：作业状态仅有 `TODO / DONE`；草稿、逾期（按作业保存的 `deadlineZoneId` 实时派生，仅日期截止以次日 `00:00` 为边界）、回收站（`deletedAt`）均不污染状态字段。全部 DAO 写入统一使用 `@Upsert`，禁用 `REPLACE`。
- **两段式完成消散与左滑回收**：勾选待办先持久化，再播放两段动效（方框内约 320ms 画对钩，随后 620ms 整张卡片方块纸片消散，从作业编辑页点“完成”返回待办时自动补播同一动效），无完成 Snackbar；待办与课程卡片支持左滑移入回收站。课程左滑连同其名下全部未删作业按同一批次时间戳软删除，恢复课程时仅恢复同批次作业，不误恢复更早单独删除的作业。
- **长按拖拽排序**：待办、课程列表、课程内作业各自独立保存手动顺序（存入 DataStore `listOrders`），拖到列表边缘自动滚动；筛选状态下拖动保留隐藏条目的原槽位。
- **扁平课程与课程模板**：移除学期管理与复习入口（历史数据在数据库与备份中无损兼容保留）。课程详情右上角可配置「课程模板」（预设图文/录音内容块及每周几+可选时刻截止规则）；仅新建空白作业选定课程时自动套用，已有正文不覆盖。

### 作业截止提醒与 v4 备份
- **双模式准时提醒**：支持「截止前一天指定时刻（默认 `09:00`）」与「提前指定时长（`1 / 6 / 12` 小时或 `1 / 2 / 3 / 7` 天；仅设日期的作业以截止日 `09:00` 为基准）」两种统一模式。仅提醒未完成、未进回收站且设有截止日期的正式作业；完成、回收、改期或关闭提醒自动撤销失效通知。
- **v4 版本化备份**：SAF 导出 `.zip`（`manifest.json` + `data.json` + `assets/` 原图 + `audio/` 录音），当前备份格式 v4 完整包含作业、图片、录音、课程回收状态、课程模板、模板草稿、手动排序与提醒偏好，向下兼容 v1 / v2 / v3 备份包；恢复前全量校验，失败自动回滚。

## 文档指引

- `AGENTS.md` — 给 AI 的项目规则（数据红线、UI 与测试定位约定）。
- `docs/ARCHITECTURE.md` — 模块结构、数据模型（DB v5）、核心机制、备份与发版架构。
- `TEST-REPORT.md` — 历次改版验收对照、真机无损迁移与修复记录。
