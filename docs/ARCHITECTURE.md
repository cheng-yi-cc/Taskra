# Taskra 架构说明

面向第一次接触本项目的人：5 分钟了解代码放在哪、数据怎么流、备份与发版长什么样。
构建安装见 `README.md`，验收状态见 `TEST-REPORT.md`。

## 1. 模块与分层

单 `app` 模块，不拆多模块：

```
app/src/main/java/com/taskra/
  MainActivity.kt        三底部导航（待办/课程/设置）+ NavHost 路由 + 多源深色检测 + 开屏动画
  TaskraApp.kt           Room / Repository / Prefs / ImageStore / BackupManager / 同步 / 提醒 / 更新器组装
  data/                  Room 实体·DAO·Repository·备份(v4)·图片/录音存储·偏好·课程模板
  reminders/             提醒规划（ReminderPlan）、系统闹钟（AlarmManager）与通知（HomeworkReminders）
  sync/                  设备配对、同步日志、冲突判定、附件传输与局域网连接
  update/                GitHub Release 检查、SHA-256 流式校验下载与系统安装器调起（AppUpdater）
  util/                  时间工具（含可注入时钟）、排序、标题生成、富文本、编辑器文档、主线程投递
  ui/
    AppViewModel.kt      全局偏好流、待办筛选状态、手动排序持久化、完成快照与软删除
    theme/               暖白方格纸与暖灰深色设计令牌（无动态取色）+ 霞鹜文楷字体
    components/          方格纸背景、作业卡片、手势左滑、对钩+纸片消散、长按拖拽排序、录音与贴图组件
    screens/             Todo / Courses / CourseDetail / Editor / BlockView /
                         Settings / Trash / Drafts / ImageViewer（Review 仅保留历史兼容）
```

数据流向：界面 → ViewModel/协程 → `TaskraRepository` → Room / DataStore / 内部存储文件。
待办列表、课程计数与详情均从同一套 Room 数据实时派生，不各自存副本。

## 2. 数据模型（DB v6）

| 表 | 关键字段 |
|---|---|
| `courses` | `id`、`semesterId`（内部兼容保留）、`name`、`colorIndex`、`isActive`、`deletedAt`（v4 软删除）、`templateJson`（v5 课程模板） |
| `homework` | `id`、`semesterId`、可空 `courseId`、`title`、`TODO/DONE`、截止类型+日期+时间+时区ID、`createdAt`（永不改）、`completedAt`、`deletedAt`（回收站） |
| `content_blocks` | `id`、`homeworkId`、`region`（仅使用 `QUESTION`）、`kind`（`TEXT/IMAGE/AUDIO`）、`position`、`text/imageId`、`spanJson`（v2）、`audioId/audioDurationMs`（v3） |
| `image_assets` | `id`、相对路径（`images/<id>.jpg`）、宽高、大小 |
| `editor_drafts` | 以 `new` / `edit:<id>` / `template:<courseId>` 为键，保存有序题目 blocks JSON（含富文本 spans 与附件引用） |
| `semesters` | 历史兼容表（界面已移除学期分组与筛选；课程跨学期扁平展示，新建作业沿用所选课程的 `semesterId`） |
| `review_sessions` / `review_items` | 历史兼容表（界面已移除复习入口，仅在数据库与备份恢复中无损保留旧数据） |

- **富文本**：`SpanMark(type, start, end, level)`，类型 `BOLD` / `HIGHLIGHT` / `HEADING(1-3)`。纯文本与标记分开存储，编辑时按前后缀 diff 重映射（见 `util/RichText.kt`）。
- **截止语义**：仅日期 = 当天结束前（边界为截止时区次日 `00:00`）；日期时间 = 按具体时刻；逾期状态由当前时间与作业保存的 `deadlineZoneId` 实时派生计算。
- **同步日志**：`MIGRATION_5_6` 添加默认关闭的同步控制、脏记录、收发队列和设置快照；启用后保留删除墓碑和待发送版本。

## 3. 核心流程与组件机制

### 3.1 单文档原地编辑器与附件生命周期
- **点开即编辑**：不设独立只读详情页与标题输入框，已有作业直接进入 `EditorScreen` 原地编辑（右上角提供保存、完成/恢复、移入回收站），正文首行自动生成列表摘要（历史作业标题保留）。输入暂停 500ms 防抖串行落盘草稿，顶部云朵图标仅作保存状态缩放反馈（不可点击）。正式保存前取消并等待防抖任务，在同一 Room 事务内写入作业、内容块并清理对应草稿。
- **录音与图片**：
  - `AudioRecorder.kt`：前台申请麦克风权限并录制临时文件，停止成功后转为 `filesDir/audio/<id>.m4a` 正式附件；切后台自动停止并插入，取消则清理临时文件。
  - `ImagePasteHost`（`ImagePaste.kt`）：包装系统文本粘贴菜单、`Ctrl+V` 与输入法 `commitContent`，显式粘贴后将图片导入 `filesDir/images/<id>.jpg`。
- **重编辑与删除缝合**（`EditorDocument.kt`）：`editableDocument` 在加载纯图片/纯录音作业时自动补齐首尾可编辑文本位；`removeEditorAttachment` 删除附件时合并两侧文字并平移右侧富文本区间，保留用户输入的空格与换行。

### 3.2 待办交互、手势左滑与拖拽排序
- **待办完成与两段式消散**：点击手绘勾选框后由 `AppViewModel` 立即持久化 `DONE` 状态与 `completedAt`（离开页面不中断），`TodoScreen` 先播放约 320ms 方框画对钩动画，再播放 620ms `TileDismiss` 方块纸片消散动画（从作业编辑页点“完成”返回待办时，通过 `AppViewModel` 保存的卡片快照补播同一段对钩+消散动效），无撤销 Snackbar。
- **左滑回收站（`SwipeToTrash.kt`）**：
  - 单条待办左滑移入回收站（写 `deletedAt`，不改 `TODO/DONE` 状态）。
  - 课程左滑在单事务内将该课程及名下全部当前未删除的作业写入同一批次 `deletedAt` 时间戳；从回收站恢复课程时仅恢复同批次作业，不误恢复更早单独删除的作业。
- **长按拖拽排序（`DragOrder.kt`）**：
  - DataStore `listOrders` 独立保存 `todo`、`courses`、`course:<id>` 三处顺序；筛选状态下拖动仅替换可见项槽位，保留隐藏项位置。
  - 浮起卡片关闭列表 placement 动画，仅邻项使用弹簧让位；松手后在 220ms 内平滑落位，异步保存完成前保持本地顺序防回跳。

### 3.3 课程模板（`CourseTemplate.kt`）
- `Course.templateJson` 序列化有序 `BlockJson`、每周截止 `weekday` 与可选 `time`。模板编辑复用 `EditorScreen`（草稿键 `template:<courseId>`，不出现在普通草稿箱）。
- 仅在新建空白作业并选择课程时自动填入模板内容块并计算最近一个未到期的每周截止日期；已有正文或编辑已发布作业时不覆盖正文。
- 彻底删除作业、草稿或课程时，引用检查覆盖全部正式作业、普通草稿、课程模板与模板草稿，防止误删共享图片或录音。

### 3.4 作业截止提醒（`ReminderPlan.kt` + `HomeworkReminders.kt`）
- 支持两种提醒模式：
  1. **截止前一天指定时刻**（默认 `09:00`，在截止日 `00:00` 前可补发）；
  2. **提前指定时长**（`1 / 6 / 12` 小时或 `1 / 2 / 3 / 7` 天；有具体时刻按截止时刻倒推，仅日期按截止当天 `09:00` 倒推，在真实截止边界前可补发）。
- `HomeworkReminders` 在 Application IO 协程监听 Room 待办与 DataStore 偏好，每次只向系统 `AlarmManager` 注册最早的下一次触发；已授权精确闹钟时使用 `setExactAndAllowWhileIdle`，否则降级为 `setAndAllowWhileIdle`。作业完成、移入回收站、改期或关闭提醒时自动撤销失效通知。

### 3.5 备份与恢复（格式 v4）
- 通过系统 SAF 导出版本化 `.zip`（`manifest.json` SHA-256 校验 + `data.json` + `assets/` 原图 + `audio/` 录音）。
- 当前格式为 **v4**（涵盖作业、课程回收状态、课程模板、模板草稿、手动排序、提醒配置及全部引用图片与录音），向下兼容 v1 / v2 / v3 备份包。
- 恢复时先全量校验清单与附件哈希，再在单一 Room 事务内使用 `@Upsert` 覆盖写入；若文件或数据库阶段失败，自动回滚已覆盖的本地附件文件。

### 3.6 自建服务同步
- 本地事务记录待发送变更，服务器按版本或不可信时钟的接收顺序选出胜出记录；首次导入冲突不静默覆盖。
- `SyncCoordinator` 驱动配对、网络恢复和周期同步；`SyncEngine` 处理日志及附件，`SyncPreferences` 协调 DataStore 与 Room。编辑中、附件操作和远端应用受互斥保护。
- 同网段优先证书校验后的局域网连接，其余情况使用 Cloudflare Tunnel 提供的 HTTPS；设备令牌保存在 Android 加密存储。服务端运维见 `sync-server/README.md`。

## 4. 发版、官网与应用内更新架构（`.github/workflows/release.yml` + `site/` + `update/`）

- **自动发版与版本注入流水线**：推送 `v*` 标签（如 `v1.0.2`）时，GitHub Actions 将标签传入 `TASKRA_VERSION` 环境变量（`app/build.gradle.kts` 自动解析语义化版本并计算单调递增的 `versionCode`），解密签名密钥构建 `:app:assembleRelease`，向 GitHub Releases 上传 `taskra-${TAG}.apk`、`taskra-latest.apk` 与 `taskra-${TAG}.apk.sha256`，随后打包 `site/` 并通过 Wrangler（显式指定 `--branch=main` 生产分支）部署至 Cloudflare Pages（`https://taskra.chengyi.me`）。
- **官网独立更新与双源校验**：当 `main` 分支仅修改 `site/**` 或工作流文件时，跳过 Android 编译，直接拉取最新 GitHub Release 的安装包与 SHA-256 同步部署至 Cloudflare Pages；`site/app.js` 加载时并行请求同源 `version.json` 与 GitHub Releases Latest API，始终指向最新正式版 APK。
- **应用内更新（`AppUpdater.kt`）**：
  - 开屏动画结束后在后台静默检查一次 `https://api.github.com/repos/cheng-yi-cc/Taskra/releases/latest`（设置页「版本与更新」卡片亦支持随时手动触发）。
  - 发现更高语义化版本时下载并校验 APK 的 SHA-256、包名及签名，校验通过后通过 `FileProvider` 调起系统安装器。
  - 若 Android 8.0+ 尚未授予 `REQUEST_INSTALL_PACKAGES` 权限，引导跳转系统「安装未知应用」设置页，用户授权返回应用（`onResume`）时自动复用已校验缓存 APK 续装；同时支持「忽略此版本」与系统浏览器下载兜底。
