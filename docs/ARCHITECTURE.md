# Taskra 架构说明

面向第一次接触本项目的人：5 分钟了解代码放在哪、数据怎么流、备份长什么样。
构建安装见 `README.md`，验收状态见 `TEST-REPORT.md`。

## 1. 模块与分层

单 `app` 模块，不拆多模块：

```
app/src/main/java/com/taskra/
  MainActivity.kt        三底部导航（待办/课程/设置）+ NavHost 路由
  TaskraApp.kt           Room / Repository / Prefs / ImageStore / BackupManager 组装
  data/                  Room 实体·DAO·Repository·备份·图片存储·偏好
  util/                  时间工具（含可注入时钟）、排序、标题生成、富文本、主线程投递
  ui/
    AppViewModel.kt      全局偏好流、待办筛选状态、完成/撤销
    theme/               暖色设计令牌（亮/暗两套），无动态取色
    components/          作业卡片等复用组件
    screens/             Todo / Courses / CourseDetail / HomeworkDetail /
                         Editor / Review / Settings / Trash / Drafts / ImageViewer
```

数据流向：界面 → ViewModel/协程 → `TaskraRepository` → Room / DataStore / 文件。
待办列表、课程计数、详情、复习都从同一套 Room 数据派生，不各自存副本。

## 2. 数据模型（DB v2）

| 表 | 关键字段 |
|---|---|
| `semesters` | id、name、isArchived（归档≠删除） |
| `courses` | id、semesterId、name（同学期唯一）、colorIndex、isActive |
| `homework` | id、semesterId、可空 courseId、title、`TODO/DONE`、截止类型+日期+时间+时区ID、`createdAt`（永不改）、`completedAt`、`deletedAt`（回收站） |
| `content_blocks` | id、homeworkId、region（只用 QUESTION）、kind（TEXT/IMAGE）、position、`text/imageId`、`spanJson`（富文本标记，v2 新增） |
| `image_assets` | id、相对路径（`images/<id>.jpg`）、宽高、大小 |
| `editor_drafts` | `new` / `edit:<id>` 为键，题目 blocks JSON（含 spans） |
| `review_sessions` / `review_items` | 轮次作业 ID 快照、当前位置、每项结果（MASTERED/NEED_RETRY） |

富文本：`SpanMark(type,start,end,level)`，类型 BOLD / HIGHLIGHT / HEADING(1-3)，
纯文本与标记分开存，编辑时按前后缀 diff 重映射（见 `util/RichText.kt`，有单测）。

截止语义：仅日期 = 当天结束前（边界为截止时区次日 00:00）；日期时间 = 按时刻；逾期为派生计算。

## 3. 核心流程

- 新建/编辑：单文档编辑器 → 输入法上方工具条（相机/相册/T→格式栏）→ 正式保存（事务写作业+内容块）→ 删草稿。
- 完成：事务改状态 + `completedAt`，待办消失、档案保留；撤销/恢复清 `completedAt`，不碰 `createdAt`。
- 复习：开轮时固定作业 ID 快照；结果独立存 `review_items`，原作业只读。
- 删除：先入回收站（`deletedAt`）；彻底删除清块+文件（先查引用）。
- 备份：SAF 导出版本化 zip（`manifest.json` 校验 + `data.json` + `assets/`原图）；恢复先全量校验再事务覆盖。

## 4. 测试

- 单元（JVM）：时间边界、排序、筛选、标题生成、span 映射、备份编解码。
- 界面（模拟器）：仓库往返回归（含完成/回收站数据保持）、自励编辑、新建→完成→归档→复习全链路。
- UI 定位约定见项目根 `AGENTS.md`（卡片合并、懒列表先滑动）。
