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
  reminders/             提醒规划、系统闹钟与通知
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

## 2. 数据模型（DB v5）

| 表 | 关键字段 |
|---|---|
| `semesters` | id、name、isArchived（归档≠删除） |
| `courses` | id、semesterId、name（同学期唯一）、colorIndex、isActive、deletedAt、templateJson |
| `homework` | id、semesterId、可空 courseId、title、`TODO/DONE`、截止类型+日期+时间+时区ID、`createdAt`（永不改）、`completedAt`、`deletedAt`（回收站） |
| `content_blocks` | id、homeworkId、region（只用 QUESTION）、kind（TEXT/IMAGE/AUDIO）、position、`text/imageId`、`spanJson`（v2）、`audioId/audioDurationMs`（v3） |
| `image_assets` | id、相对路径（`images/<id>.jpg`）、宽高、大小 |
| `editor_drafts` | `new` / `edit:<id>` / `template:<courseId>` 为键，题目 blocks JSON（含 spans） |
| `review_sessions` / `review_items` | 轮次作业 ID 快照、当前位置、每项结果（MASTERED/NEED_RETRY） |

富文本：`SpanMark(type,start,end,level)`，类型 BOLD / HIGHLIGHT / HEADING(1-3)，
纯文本与标记分开存，编辑时按前后缀 diff 重映射（见 `util/RichText.kt`，有单测）。

截止语义：仅日期 = 当天结束前（边界为截止时区次日 00:00）；日期时间 = 按时刻；逾期为派生计算。

## 3. 核心流程

- 新建/编辑：单文档编辑器 → 输入法上方工具条（录音/相册/相机/格式）→ 正式保存（事务写作业+内容块并删草稿）。顶部云朵只反馈保存状态，不提供点击操作。
- 完成：事务改状态 + `completedAt`，待办消失、档案保留；撤销/恢复清 `completedAt`，不碰 `createdAt`。
- 复习：开轮时固定作业 ID 快照；结果独立存 `review_items`，原作业只读。
- 删除：先入回收站（`deletedAt`）；彻底删除清块+文件（先查引用）。
- 备份：SAF 导出版本化 zip（`manifest.json` 校验 + `data.json` + `assets/`原图 + `audio/`录音）；当前格式 v4 兼容 v1/v2/v3，恢复先全量校验附件再事务覆盖，失败回滚被覆盖文件。

## 4. 测试

- 单元（JVM）：时间边界、排序、筛选、标题生成、span 映射、备份编解码。
- 界面（模拟器）：仓库往返回归（含完成/回收站数据保持）、自励编辑、新建→完成→归档→复习全链路。
- UI 定位约定见项目根 `AGENTS.md`（卡片合并、懒列表先滑动）。

## 5. 编辑页组件与生命周期

- `EditorControls.kt`：草稿反馈动画、键盘工具栏、带日期/可选时刻的弹窗。
- `AudioRecorder.kt`：临时录音文件 → 停止成功 → 正式附件；离开前台停止并插入，取消则清理临时文件。
- `AudioAttachment.kt`：录音播放、进度与暂停，离开页面或进入后台释放/暂停播放器。
- `PaperTopBar.kt`：统一无色块标题栏，系统栏图标跟随应用自身主题。
- 编辑状态变动才保存；保存互斥锁、防抖取消与等待共同防止草稿倒序写入。
- 原标题字段保留用于旧数据和列表展示；编辑器移除标题输入，正文首行生成新作业名称。

## 交互补充（2026-09-28）

课程界面不再按学期分组；`observeUncatAll` 汇总所有历史学期的未分类记录。
数据库中的 Semester 及外键仍保留，避免破坏旧记录与备份；编辑时所选课程决定内部 semesterId。
发布事务结束并删除草稿后，读取剩余草稿并经 postOnMain 返回导航结果，避免 Flow 尚未刷新造成误判。
待办完成写入由 AppViewModel 持有，离开列表也继续执行；TodoScreen 保留退出快照，TileDismiss 完成且数据库移除该待办后才丢弃快照。

## 课程回收与编辑文档

Course.deletedAt 为可空软删除字段；课程事务删除为此次仍可见作业设置同一批次时间戳，且避开历史回收记录时间戳。恢复课程仅恢复此批次，不改变作业完成状态、createdAt、附件、课程关系。彻底删除课程时一并清理草稿、旧复习关联，并在提交后进行附件引用检查。
备份格式升至 3 以避免旧版应用忽略课程回收字段；格式 1/2 缺少此字段时默认为 null。
editableDocument 在加载时补齐非文本附件前后可编辑位置；removeEditorAttachment 合并被附件拆开的文字并平移右侧富文本范围。没有通过 isBlank 去吞掉用户输入的空白字符。
OpeningAnimation 由进程级 openingShown 和页面保存态控制，只有进程首次进入播放；钢笔平移与墨线终点共用进度，动画可点击跳过。

待办背景由 Notebook 的缓存格线绘制；HomeworkCard 读取正文摘要、课程色与截止信息，提供独立的打开和完成触控区。半透明卡片静止时，SwipeToTrash 不绘制其下的删除提示；滑动时才显示。便签边线和字体仅影响展示，不改写设置内容。

## 模板、剪贴板与手动排序

Course.templateJson 序列化 CourseTemplate（有序 BlockJson、weekday、可选 time），v4→v5 仅添加可空列。模板编辑复用 EditorScreen，通过 templateCourseId 区分保存目标，不创建正式作业。模板草稿独立，保留自动保存及录音生命周期保护。

新建时自动计算最近一次未到期的每周截止日期；仅日期按次日零点计算。模板正文仅应用于空文档，每个块生成新 ID，附件可共享但删除前检查所有模板、草稿与正式作业引用。

ImagePasteHost 包装系统文字工具菜单和 InputConnection，保留 AnnotatedString 富文本；输入法提交 URI 的临时读取权限持续到导入完成。显式点击粘贴后才读取剪贴板内容，附件仍复制到应用私有目录。

DragOrderState 负责长按拖动、邻项换位和边缘滚动；LazyColumn 的位置动画配合拖动卡片抬起反馈。DataStore 保存 todo、courses、course:<id> 三类独立顺序，ViewModel 完成异步写入。筛选下重排只替换可见 ID 的原槽位。

备份格式 v4 新增模板语义和排序偏好；模板引用的图片、录音及尚未提交的模板草稿均纳入备份。

### 拖动动画的连续性

浮起卡片不使用 LazyColumn placement 动画，只有未拖动的邻项使用临界阻尼弹簧让位。手势节点放在缩放/位移图层外，保证输入坐标不随抬起缩放改变。松手保留视觉位置，待最终槽位布局完成后在 220ms 内落位；异步保存未确认前保留本地顺序，避免旧数据回传引发二次交换。换位时固定列表可见索引，避免首项 key 锚定触发整列滚动。

## 作业提醒

ReminderPlan 使用作业保存的截止时区计算两种模式：默认在截止日前一自然日的指定时刻提醒；提前时长模式按具体截止时刻倒推，仅日期则以截止当天 09:00 为基准。前者在截止日零点结束补发，后者以真实截止边界结束补发。只处理未完成、未回收且有有效截止日期的正式作业。

HomeworkReminders 在 Application 的 IO 协程中观察 Room 待办与 DataStore 偏好，互斥更新，只向 AlarmManager 登记下一次最早触发或清理。接收闹钟时重新读取数据并核对；有精确闹钟授权时使用 setExactAndAllowWhileIdle，否则使用 setAndAllowWhileIdle。完成、回收、修改期限或关闭提醒会取消失效通知。

UserPrefs 保存开关、模式、指定时刻和提前时长，均纳入 v4 备份；旧设置默认沿用前一天指定时刻模式。已发送记录独立保存在 reminder_delivery SharedPreferences，系统权限不进入备份。设备重启、更新、系统时间与时区改变后重新安排；强行停止应用后需重新打开。
