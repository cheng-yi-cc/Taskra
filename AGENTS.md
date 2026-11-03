# Taskra 项目规则（AI 必读）

单模块原生安卓应用：Kotlin + Compose + Material3（自定义暖色主题）+ Room + DataStore。
构建：`.\gradlew.bat :app:assembleDebug --no-daemon`；单元测试：`:app:testDebugUnitTest`。
UI 测试仅在隔离模拟器运行；`connectedDebugAndroidTest` 可能操作所有已连接设备并卸载应用，连接有用户数据的手机时禁止运行。按模拟器序列号使用 `adb -s <序列号> shell am instrument` 指定测试。

## 数据红线（违反会丢用户数据）

- 每次更新有用户数据的手机应用前，先核对安装包的应用 ID、签名和数据库版本及迁移路径；禁止使用旧数据库版本的构建覆盖新版本，禁止 destructive migration。
- 更新前保存并校验手机当前数据的完整副本，包含一致的数据库及其 WAL、设置、图片和录音；校验失败或无法确认备份可用时停止安装。不要中断用户正在编辑的内容。
- 先在隔离模拟器上验证覆盖更新和关键流程；真机禁止卸载、清库或运行会改变真实数据的测试。安装只能使用保留数据的覆盖更新，更新前后及首次启动后逐项核对原有数据库记录、设置和附件内容；出现差异立即停止，保留现场和备份。
- DAO 写入一律 `@Upsert`，**禁止 `@Insert(onConflict = REPLACE)`**：REPLACE 系先删再插，会触发外键级联（删作业内容块、置空课程归属）。回归测试：`RepoRoundTripTest`。
- 作业状态只有 `TODO`/`DONE`；草稿、逾期（派生计算）、复习中、回收站（`deletedAt`）不进状态字段。
- 完成/恢复/进出回收站只改状态时间戳，不碰 `createdAt`、内容块、课程归属。
- DB 升级必须写 `Migration`（见 `data/Migrations.kt`），禁止 destructive 重建用户库。

## 线程与草稿

- 跟随 Room 挂起调用后的导航回调，必须经 `postOnMain` 切回真实主线程（测试调度器下会崩）。
- 正式保存前先取消待触发的自动保存 `saveJob`，再删草稿，否则旧草稿复活覆盖。

## UI 约束

- 主题：暖白 `#FAF7F2` + 番茄红 `#B94335`；禁止蓝紫主视觉、动态取色、大面积渐变。
- 编辑器单文档模型：`ContentBlock`（TEXT/IMAGE/AUDIO + `spanJson` 富文本标记），图片只存 `filesDir/images/<id>.jpg`，录音只存 `filesDir/audio/<id>.m4a`。
- 解答区已删除：只记题目（QUESTION），SOLUTION 为历史数据，仅备份兼容，不再创建与展示。

## UI 测试定位约定

- 作业卡片文本节点被合并：用 contentDescription `"作业：<标题>"` 定位卡片，不用文本查询。
- 懒加载列表断言前先滑动到目标（`todo_list` / `course_list` 有 testTag），否则节点尚未组合。
- 关键 testTag：`question_text`、`publish_homework`、`draft_feedback`、`deadline_button`、`deadline_confirm`、`course_name_field`、`motto_field`、`nav_todo/nav_courses/nav_settings`。

## 深入文档

- `README.md` — 构建安装、固定版本、设计选择。
- `TEST-REPORT.md` — 验收对照（已验证/未验证）与修复记录。
- `docs/ARCHITECTURE.md` — 结构、数据模型、核心流程、备份与发版架构。

## 编辑页与视觉约定

- 不提供标题输入；草稿状态图标不可点击，只有正式发布图标可点击。
- 删除功能说明段落，保留内容、功能名称与必要错误提示；主题遵循暖白纸感。
- 截止时间默认 NONE，只选日期时次日零点才算逾期；日期弹窗取消不提交临时选择。
- 录音与图文同属有序内容块，必须纳入草稿、正式保存、播放、备份和恢复。
- DB 当前版本 5；备份格式 4，兼容版本 1/2/3。新增存储字段必须无损迁移。

## 列表、浮窗与完成交互约定

- 搜索、筛选均使用浮窗；取消不更改当前条件。
- 草稿发布完成后，依据删除当前草稿后的数据库结果决定返回草稿箱还是待办页。
- 待办完成没有成功 Snackbar；先持久化，同时保留卡片快照播放消散动画。
- 编辑页元信息固定，正文独立滚动；占位文字仅在整个文档为空时显示一次。
- 不再展示学期管理、学期筛选；历史学期字段保留用于无损兼容，课程跨学期展示。

## 左滑与附件编辑约定

- 待办左滑是移入回收站，不得改为 DONE；课程左滑连同全部已完成/未完成作业一起软删除。
- Course.deletedAt 标记课程回收；恢复课程只恢复同批次作业，历史上单独删除的作业仍留在回收站。
- SwipeToTrash 每次手势只提交一次；数据库删除由 ViewModel 持有，页面切换不取消写入。
- 已发布图文/录音重新编辑须补齐输入位置；删除附件须接回两侧文字与富文本范围，保留用户自己输入的空格和换行。
- 课程详情默认 ALL，筛选收进浮窗，不再展示复习入口；历史复习数据仅为兼容保留。

## 草稿、排序与课程模板

- 草稿卡片点击继续编辑，右滑删除；模板草稿使用 `template:<courseId>`，不出现在普通草稿箱，但纳入备份和附件引用检查。
- 三处列表长按拖动；顺序存入 DataStore `listOrders`，待办与课程内作业顺序相互独立，筛选后的拖动保留隐藏条目的槽位。
- Course.templateJson 保存图文/录音模板和每周截止规则；仅新建作业应用模板，已有正文不覆盖。已发布作业编辑不自动套用。
- 所有模板附件及模板草稿附件必须参与引用检查，不能在删除某份作业时误删共享附件。
- 真机有用户数据时，禁止 `connectedDebugAndroidTest`、其他写入测试以及卸载/清库；功能测试在隔离模拟器进行，真机只覆盖安装并核对原始记录、设置与附件。

## 发版与官网约定

- 发版时须同步递增 `app/build.gradle.kts` 的 `versionCode` 与 `versionName`，保证旧版可无损覆盖升级。
- `.github/workflows/release.yml` 部署 Cloudflare Pages 必须显式传 `--branch=main`（防止 tag 游离 HEAD 部署到预览分支），并同时发布 `taskra-${TAG}.apk`、`taskra-latest.apk` 与 `.sha256`。
