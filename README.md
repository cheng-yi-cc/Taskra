# 课迹 Taskra

面向大学生的个人作业本：记录课程作业 → 查看未完成作业 → 完成后按课程保存 → 期末重新练习。

- 本地优先，核心功能全部离线可用；无账号、无服务器、无广告、无埋点。
- 技术栈：Kotlin + Jetpack Compose + Material 3（自定义暖色主题）+ Room + DataStore + 协程/Flow。
- 单 `app` 模块，按功能组织界面，按职责分离数据、时间、图片、备份服务。

## 固定版本（不使用动态依赖）

| 组件 | 版本 |
|---|---|
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

前置：JDK 17，Android SDK（含 platform-tools、platforms/android-35、build-tools/35.0.0）。
`local.properties` 已指向本机 SDK；换机器请修改 `sdk.dir`。

```bat
cd "D:\My Project\Taskra"
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（**调试版**，包名 `com.taskra.debug`）。

安装到已连接设备/模拟器：

```bat
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

发布版：`release` 构建类型已接正式签名（未启用混淆收缩），包名 `com.taskra`。
签名信息从 `keystore.properties`（本地）或 `TASKRA_KEYSTORE_*` 环境变量（CI）读取；
缺省时 release 包为未签名构建，CI 发版时必须提供密钥。请勿将调试包当作发布包分发。

## 发版与官网（Release + taskra.chengyi.me）

- 打 tag 自动构建：推送 `v*` 标签（如 `v1.0.1`）后，`.github/workflows/release.yml`
  自动用正式签名构建 `:app:assembleRelease`，发布到 GitHub Releases（APK + SHA-256），
  并把官网（含当版 APK 与 `version.json`）同步部署到 Cloudflare Pages。
- 本地签名配置：复制 `keystore.properties.example` 为 `keystore.properties` 后填写
 （该文件已进 `.gitignore`，不会提交）；CI 走同名环境变量
  `TASKRA_KEYSTORE_FILE / TASKRA_KEYSTORE_PASSWORD / TASKRA_KEY_ALIAS / TASKRA_KEY_PASSWORD`，
  密钥内容存 GitHub Secrets（`ANDROID_KEYSTORE_BASE64` 等），仓库里只有示例文件。
- 官网源码在 `site/`（纯静态：`index.html` + `styles.css` + `app.js` + `version.json`），
  暖色极简风格；页面启动时读 `version.json` 自动指向最新安装包，仅提供 Android 版。

## 关键设计选择记录
- 作业状态只有 `TODO/DONE`；草稿、逾期（派生）、复习中、回收站（`deletedAt`）都不进状态字段。
- 完成只改状态不删内容；归档=原记录；复习数据独立保存，不碰原作业字段。
- 仅日期截止=当天结束前，边界为次日 00:00（截止时区）；`DATETIME` 按时刻；设备时区变化不改已存期限（存 `deadlineZoneId`）。
- 图片原图存 `filesDir/images/<id>.jpg`（非缓存），DB 只存 ID+相对路径+元数据；导入先写 `tmp/` 校验再提交；拍照用 `TakePicture` + FileProvider 完整输出。
- 草稿 500ms 防抖串行写入，`EditorDraft` 以 `new` / `edit:<id>` 为键；提交成功后删除对应草稿。
- 备份为版本化 zip（`manifest.json` + `data.json` + `assets/`原图），SAF 选择位置；覆盖恢复，先校验后在 Room 事务内写入；仅成功导出后更新“最近备份时间”。
- 自励文字/显隐/主题/排序/当前学期存 DataStore，纳入备份。
- 主题：暖白 `#FAF7F2` + 番茄红 `#B94335`，禁用动态取色；深色模式为暖灰配色。
- 平板：内容最大宽度 720dp 居中（手机单列不变）。
- 编辑器（单文档模式）：初始一个无边框输入框；输入法弹出时底部出现工具条（相机/相册/T），
  T 切换为格式栏（高亮/加粗/H1-H3/叉返回），收起输入法工具条消失；图片在光标处插入、点角标叉删除。
  富文本以 SpanMark（存 `content_blocks.spanJson`，DB v2 迁移，老备份兼容）表达，只记题目，不记解答。
- 学期：首装自动建默认学期（按月份推春秋）；编辑器内不手动选学期，自动归属（预设→当前→未归档→新建）。
- 全部 DAO 写入使用 `@Upsert`，禁用 REPLACE（REPLACE 系先删再插，会触发级联丢数据）。

## 已知差距（见 TEST-REPORT）

真机/模拟器端到端验证、超大图集压力、系统字体极大值下的个别长列表项，以实际跑测为准；
`TEST-REPORT.md` 逐项列出“已验证 / 未验证”。

## 文档指引

- `AGENTS.md` — 给 AI 的项目规则（数据红线、测试定位约定）。
- `docs/ARCHITECTURE.md` — 结构、数据模型、核心流程、备份格式。
- `TEST-REPORT.md` — 验收对照与缺陷修复记录。
