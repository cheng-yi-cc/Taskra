# Taskra 项目规则（AI 必读）

单模块原生安卓应用：Kotlin + Compose + Material3（自定义暖色主题）+ Room + DataStore。
构建：`.\gradlew.bat :app:assembleDebug --no-daemon`；单元测试：`:app:testDebugUnitTest`。
UI 测试：`:app:connectedDebugAndroidTest`，需模拟器在线（`adb devices` 见 `emulator-5554`）；跑完应用会被卸载，DB 不跨 run 保留。

## 数据红线（违反会丢用户数据）

- DAO 写入一律 `@Upsert`，**禁止 `@Insert(onConflict = REPLACE)`**：REPLACE 系先删再插，
  会触发外键级联（删作业内容块、置空课程归属）。回归测试：`RepoRoundTripTest`。
- 作业状态只有 `TODO`/`DONE`；草稿、逾期（派生计算）、复习中、回收站（`deletedAt`）不进状态字段。
- 完成/恢复/进出回收站只改状态时间戳，不碰 `createdAt`、内容块、课程归属。
- DB 升级必须写 `Migration`（见 `data/Migrations.kt`），禁止 destructive 重建用户库。

## 线程与草稿

- 跟随 Room 挂起调用后的导航回调，必须经 `postOnMain` 切回真实主线程（测试调度器下会崩）。
- 正式保存前先取消待触发的自动保存 `saveJob`，再删草稿，否则旧草稿复活覆盖。

## UI 约束

- 主题：暖白 `#FAF7F2` + 番茄红 `#B94335`；禁止蓝紫主视觉、动态取色、大面积渐变。
- 编辑器单文档模型：`ContentBlock`（TEXT/IMAGE + `spanJson` 富文本标记），图片只存 `filesDir/images/<id>.jpg`。
- 解答区已删除：只记题目（QUESTION），SOLUTION 为历史数据，仅备份兼容，不再创建与展示。

## UI 测试定位约定

- 作业卡片文本节点被合并：用 contentDescription `"作业：<标题>"` 定位卡片，不用文本查询。
- 懒加载列表断言前先滑动到目标（`todo_list` / `course_list` 有 testTag），否则节点尚未组合。
- 关键 testTag：`editor_title`、`question_text`、`course_name_field`、`motto_field`、`nav_todo/nav_courses/nav_settings`。

## 深入文档

- `README.md` — 构建安装、固定版本、设计选择。
- `TEST-REPORT.md` — 验收对照（已验证/未验证）与修复记录。
- `docs/ARCHITECTURE.md` — 结构、数据模型、核心流程、备份格式。
