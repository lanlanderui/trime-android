# 剪贴板图片发送 · 设计稿

> 状态：**已搁置（2026-10-18 决定不做）**——评估结论是复杂度不划算。本文仅作留档，**勿据此排期或实现**。
> 范围：trime 输入法剪贴板面板（`com.osfans.trime.ime.clipboard`）与剪贴板数据层（`com.osfans.trime.data.db`）
> 目标读者：实现者、reviewer

---

## 0. 先纠正一个前提

本仓库**目前没有图片发送功能**，所以这不是"给已有功能加剪贴板图片"，而是在文字剪贴板上**新建**一条图片通道。核对结果：

| 事实 | 位置 | 影响 |
| --- | --- | --- |
| 剪贴板回调只取文本 | `ClipboardHelper.onPrimaryClipChanged()` → `DatabaseBean.fromClipData()`，后者只读 `clipData.getItemAt(0).text` | 图片类剪贴内容被静默丢弃（`text` 为 null 后 `return@withLock`） |
| `DatabaseBean` 里还留着图片字段 | `imagePath: String?`、`BeanType.IMAGE` | 注释写明"图片支持做过又撤了"，两处仅为兼容 v5 数据库的列与 ordinal 而保留 |
| 全仓库没有 `commitContent` | `app/src/main` 搜不到 `commitContent` / `InputContentInfo` | 输入法→编辑器的富内容通道从未接过 |
| 面板点击只上屏文本 | `ClipboardWindow.onPaste` → `service.commitText(text)` | 图片行即使显示出来也无处可去 |
| `type` 字段从未被 UI 读取 | 全仓库只有 `DatabaseBean` 自己引用 `BeanType` | 适配器必须开始按 `type` 分视图类型 |
| 面板没有空态 | `ClipboardPageUi` 只有 `RecyclerView` | "剪贴板为空"目前是一片纯空白，无文案可挂 |

好消息是底子够：`imagePath` 列已存在（省一次加列）、`FileProvider` 已配好（`${applicationId}.fileprovider`，`provider_paths.xml` 已含 `files-path` / `cache-path`）、`showDialog` 的 IME 附着对话框通道已成熟、`PrivacyMode` 判断已在回调首行。

---

## 1. 结论摘要（四个必答问题）

1. **检测时机**：只在 4 个点碰剪贴板 —— ①系统剪贴板变化回调（被动捕获）②剪贴板面板打开时补一次（补偿）③用户点"发送剪贴板图片"按钮（一次性直发）④用户点历史里的缩略图。**不做轮询、不做 `ContentObserver`、不做无障碍监听**。
2. **支持格式**：以**解码结果**为准，放行 PNG / JPEG / WebP / GIF / HEIF / BMP / AVIF 七种；声明 MIME 只当提示，不当作准入门槛。单张上限 **16 MB**、最长边 **8192 px**。
3. **预览与确认**：点缩略图 → IME 附着式预览对话框（信息行 + 自适应大图）→ 点"**发送**"才真正落地。**预览本身就是确认步骤**，不再叠一层"确定吗"。
4. **错误处理**：**被动捕获全程静默**（只写日志），只有用户主动发起的动作才弹提示；空剪贴板表现为"按钮不可用 + 点击给一句 toast"；12 种失败情形逐条给文案（见 §7）。

---

## 2. 目标与非目标

### 2.1 目标

- **G1** 用户复制图片或截图后，图片进入剪贴板历史，面板里可见缩略图。
- **G2** 用户能从历史里挑一张图，预览后发送到当前输入框。
- **G3** 用户能跳过历史，直接把"此刻剪贴板里的那张图"发送出去（这条路径不依赖记录开关）。
- **G4** 四种失败（空剪贴板 / 非图片 / 读不出 / 发不出）都有明确、可区分的反馈。

### 2.2 非目标

- 图片编辑、裁剪、旋转、OCR、压缩参数面板。
- GIF/WebP 动图播放（列表与预览一律显示首帧，发送时保留原文件）。
- 把图片塞进纯文本 `EditText`（技术上不可能；这类编辑器走分享降级）。
- 相册选择器 / 拍照入口（属于另一个功能，本文不涉及）。
- 云端同步、跨设备。

### 2.3 术语

- **发送**：输入法把图片作为富内容交给当前编辑器，唯一正道是 `InputConnection.commitContent()`。
- **粘贴**：用户视角的"把剪贴板里的图贴进去"，落到实现上仍然是"发送"——输入法没有自己的编辑区，`paste` 这个词在这里只是入口名字。

---

## 3. 检测时机与触发方式 ★

### 3.1 四个触发点

| 编号 | 触发点 | 代码挂点 | 行为 | 为什么需要 |
| --- | --- | --- | --- | --- |
| **T1** | 系统剪贴板变化 | `ClipboardHelper.onPrimaryClipChanged()`（已注册的 `OnPrimaryClipChangedListener`） | 判断 `description.hasMimeType("image/*")` 且 `item.uri != null` → 抓取入库 | 主路径："复制完就已经在历史里了" |
| **T2** | 剪贴板面板打开 | `ClipboardWindow.onAttached()` | 调一次 `ClipboardHelper.syncCurrentClip()`，复刻 T1 的判定，并刷新标题按钮可用态 | 补偿 T1 漏掉的情况（见 §3.4） |
| **T3** | 用户点"发送剪贴板图片" | `ClipboardTitleUi` 新增的 `imageButton` | 立即读 `primaryClip`，有图 → 直接开预览；无图/不合法 → 就地提示 | 一次性路径，不落库、不受记录开关限制 |
| **T4** | 用户点历史缩略图 | `ClipboardAdapter.onPaste(bean)` 按 `type` 分支 | 打开预览 | "从剪贴板中选择后发送" |

### 3.2 明确不做的事

- **不轮询**。剪贴板没有"变化版本号"可查，轮询既耗电又会被 Android 10+ 的后台读取限制反噬。
- **不注册 `ContentObserver`**。剪贴板不是 `ContentProvider`，没有可靠的 URI 可观察。
- **不监听 `ACTION_PASTE`**。那是编辑器侧的动作，输入法收不到。
- **不在每次按键/每次 `onStartInput` 时探测**。打开面板那一次（T2）已经覆盖了"用户要用了"的时机。

### 3.3 事件去重

保留现有两级去重，图片分支**额外**加一层内容哈希：

1. API 26+ 用 `clip.description.timestamp` 相同即丢弃（现有逻辑）。
2. API 21–25 用"100 ms 内 + `clip.hashCode()` 相同"丢弃（现有逻辑）。
3. 图片落盘时算 **SHA-256**，`hash` 相同则**不插新行**，只 `updateTime` 把旧行顶到最前（以及删掉刚写的临时文件）。

> 第 3 层是必需的：同一个 URI 被反复 `setPrimaryClip`（某些 App 每次前台都重设一次）时，前两层挡不住，会刷出一串同样的缩略图。

### 3.4 可读性前提与补偿

Android 10+ 起，**只有持有焦点的应用或默认输入法**能读剪贴板内容。三种会拿到 `null` 或读不到的情形：

| 情形 | 表现 | 补偿 |
| --- | --- | --- |
| trime 不是当前默认输入法（用户临时切到别的输入法） | 回调照常触发，`primaryClip` 返回 `null`，或 `openInputStream` 抛 `SecurityException` | 下次打开面板时 T2 重试 |
| 应用进程刚重启，剪贴板里还有旧图 | 监听器注册晚于复制动作，T1 从未触发 | T2 补抓 |
| 用户先关掉"记录剪贴板历史"，之后又打开 | 关闭期间 T1 被摘掉 | 重新打开时 T2 补抓 |

`syncCurrentClip()` 的语义：**幂等**。它和 T1 走同一个 `ingestImage()`，靠哈希去重，重复调用不会产生第二行。

### 3.5 隐私与敏感内容

- `PrivacyMode.isActive` 时 **T1 / T2 直接返回**（现有首行判断已覆盖），不抓、不落库、不留文件。
- **T3 仍允许**：那是用户当场按下的动作，不是"记录"。但同样不写库、不写盘（直接读流 → 预览）。
- API 33+ 额外检查 `clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)`：为真则**连 T3 也拒绝**，提示"该剪贴板内容被标记为敏感，已拒绝读取"（密码管理器就是这么标记的）。

### 3.6 监听开关的语义

沿用现有 `clipboardListening`：

| 开关 | T1 | T2 | T3 | 历史 |
| --- | --- | --- | --- | --- |
| 开 | 抓 | 抓 | 可用 | 有图片行 |
| 关（"记录剪贴板历史"） | 不抓 | 不抓 | **仍可用** | 无新行 |

理由：关掉记录是"别存我的东西"，不是"别让我用"。T3 全程不落盘，符合这个语义。

---

## 4. 支持的图片格式 ★

### 4.1 白名单

| 格式 | MIME | 最低 API | 预览 | 发送时 | 备注 |
| --- | --- | --- | --- | --- | --- |
| PNG | `image/png` | 1 | ✅ | **原样** | 保透明通道 |
| JPEG | `image/jpeg` | 1 | ✅ | **原样** | 截图/照片主格式 |
| WebP | `image/webp` | 14（动图 28） | ✅ 首帧 | **原样** | 含动图，发送保留动画 |
| GIF | `image/gif` | 1 | ✅ 首帧 | **原样** | 预览不播放，发送保留动画 |
| HEIF/HEIC | `image/heif` / `image/heic` | 28 | ✅ | **转码 JPEG** | 目标 App 普遍不认 HEIF |
| BMP | `image/bmp` | 1 | ✅ | **转码 PNG** | 体积大，转码顺带压缩 |
| AVIF | `image/avif` | 31 | ✅ | **转码 JPEG** | 低版本解码直接失败 |

### 4.2 准入判定：不信声明，信解码

剪贴板里的 MIME 声明极不可靠：微信/浏览器常给 `image/*` 通配，有的给 `application/octet-stream`，还有的一边声明 `text/uri-list` 一边塞 `content://` 图片。

判定顺序（`ImageClipProbe`）：

```kotlin
sealed interface ImageProbe {
    data class Ok(val mime: String, val width: Int, val height: Int, val bytes: Long) : ImageProbe
    data object NotImage : ImageProbe                 // 声明明确不是图片 → 走文本路径
    data class TooLarge(val bytes: Long) : ImageProbe
    data object Unreadable : ImageProbe               // 打不开流 / 权限失效
    data object Undecodable : ImageProbe             // 解不出尺寸：格式不支持或文件损坏
    data object Empty : ImageProbe
}
```

1. `description.hasMimeType("image/*")` 为真 → 候选。
2. 声明是具体的非图片类型（`text/*`、`video/*`、`application/pdf`…）→ `NotImage`，**不猜**。宁可漏，不可误。
3. 声明缺失 / `image/*` / `application/octet-stream` → 打开流做 `inJustDecodeBounds` 探测，用 `BitmapFactory.Options.outMimeType` 得到**真实 MIME**，再对照白名单。
4. 探测失败 → `Undecodable`。

### 4.3 上限

| 限制 | 值 | 超限行为 |
| --- | --- | --- |
| 源文件字节 | 16 MB（可配，1–64） | T1/T2 静默丢弃；T3 toast "图片过大（xx MB），上限 16 MB" |
| 解码最长边 | 8192 px | 同上 |
| 解码内存 | 预览最长边 ≤ 1600 px；列表缩略图 160 px | 靠 `inSampleSize` 保证，绝不整图加载 |

### 4.4 明确不支持

`image/svg+xml`（矢量，无位图解码）、`image/x-icon`、PSD/RAW、`video/*`、`audio/*`、压缩包、以及任何非图片类型。命中时按 §7 的"不支持的图片格式"处理。

### 4.5 转码策略（发送前）

```
原始字节 ≤ 2 MB 且属于 {png, jpeg, webp, gif}  → 原样发送
否则 → 最长边压到 1920，JPEG q85（有 alpha 时用 WebP q90）
```

转码产物写到 `cacheDir/clipboard_send/<hash>.jpg`，发送完成后即删；每次进程启动清一次该目录（崩溃残留）。

---

## 5. 存储与数据模型

### 5.1 目录与命名

```
filesDir/clipboard_images/<sha256>.<ext>
```

- 用 **filesDir 而不是 cacheDir**：系统可以随时清 cache，清完就只剩一堆"图片已失效"的死行。图片行的寿命应当由我们的配额决定，不由系统决定。
- 文件名即哈希 → 天然去重；同图再复制一次只需 `updateTime`。
- `imagePath` 存**相对文件名**，不存绝对路径。理由：应用数据目录在备份恢复、分身/多用户、迁移后会变；绝对路径一挪就全失效。读取时统一走 `resolveImageFile()`，**同时兼容旧行里的绝对路径**（`File(path).isAbsolute` 就走原样）。

### 5.2 数据库迁移（v5 → v6）

`DatabaseBean` 新增 5 列，`imagePath` 直接复用（不再"仅兼容保留"）：

```kotlin
val imagePath: String? = null,   // 已有列，改为真正存相对文件名
val mime: String? = null,
val width: Int? = null,
val height: Int? = null,
val byteSize: Long? = null,
val hash: String? = null,
```

`Database.version = 6`，新增：

```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} ADD COLUMN mime TEXT")
        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} ADD COLUMN width INTEGER")
        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} ADD COLUMN height INTEGER")
        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} ADD COLUMN byteSize INTEGER")
        db.execSQL("ALTER TABLE ${DatabaseBean.TABLE_NAME} ADD COLUMN hash TEXT")
    }
}
```

必须加进 `Database.open()` 的 `.addMigrations(...)` 列表——注释里已经记录过这个坑：**漏一条迁移，第一次查询就抛，而分页查询发生在面板打开时，会带着整个进程一起崩**。两个库（`clipboard.db` / `collection.db`）共用 `open()`，所以只改一处。

**不加索引**：表最多几十行，`findByHash` 全表扫可接受；加索引就必须在迁移里同步 `CREATE INDEX`，否则 Room 打开时 schema 校验直接失败，不值当。

**旧行的兼容**：v5 之前留下、`type = IMAGE` 但 `mime` 为 null 的行，渲染为"图片已失效"占位行，可长按删除。这是现有注释里已经承诺的行为，本次一并兑现。

### 5.3 清理：删行必须删文件

这是最容易漏的一处。现有所有删除路径都要挂上文件清理：

| 方法 | 现状 | 要补 |
| --- | --- | --- |
| `ClipboardHelper.delete(id)` | 只删行 | 先 `get(id)` 拿到 `imagePath` 再删文件 |
| `deleteAll(KEEP_PINNED)` | 直接 `clbDao.deleteAllUnpinned()`，不返回被删行 | 先 `getAllUnpinned()` 取回整批行 → 删文件 → 再删行；顺序反了就是一批孤儿文件 |
| `deleteAll(ALL)` | `clbDao.deleteAll()` | 先 `allBeansOnce()` 或清目录 |
| `removeOutdated()` | `outdated` 已是 `List<DatabaseBean>` | 遍历删文件，顺手 |
| 启动时 | 无 | `sweepOrphans()`：删掉目录里未被任何行引用、且 mtime > 24h 的文件 |

**配额**：图片总量上限 200 MB（硬编码）。`ingestImage` 落盘前查 `sum(byteSize)`，超了就从最旧的未置顶图片开始删，直到放下。置顶行永不自动删。

**历史条数**：图片与文本共享现有 `clipboardLimit`（默认 10）。理由是可预期——"最近 10 条"就是最近 10 条，用户不会算"其中几张是图"。

### 5.4 缩略图

- **不落盘**，绑定时按 `inSampleSize` 解码到 160 px，结果进 `LruCache<String, Bitmap>`（8 MB / 64 条）。
- `RecyclerView` 复用后必须 `cancel` 上一次的加载（用绑定时的 `bean.id` 比对，或 `ViewHolder` 上挂 `Job`）。
- 解码统一走 `decodeSampled(file, maxEdge)`，内部 `try/catch (OutOfMemoryError)`，失败返回 null，UI 走占位图。

---

## 6. 预览与确认流程 ★

### 6.1 入口与两条路径

```
路径 A（直接粘贴当前剪贴板）
  面板 → 点标题栏 [图片] 按钮 ──> 读 primaryClip
                                    ├─ 有图 → 预览 → 发送
                                    └─ 无图/不可读 → toast（§7）

路径 B（从历史选择）
  复制/截图 ──T1──> 历史出现缩略图 ──点击──> 预览 → 发送
  面板打开 ──T2──> 补抓 ────────────┘
```

### 6.2 列表行

图片行高度 88 dp（文本行最低 30 dp），横向布局：

```
┌──────────────────────────────────────────────┐
│ ┌────────┐  1920 × 1080 · 1.4 MB      [PNG] │
│ │ 72×72  │  PNG · 刚刚                       │
│ └────────┘                            📌     │
└──────────────────────────────────────────────┘
```

- 缩略图 `centerCrop` + 圆角（`roundCorner/2`），底色用 `key_back_color` 派生，避免透明 PNG 看不出边界。
- 第二行元数据：`<宽>×<高> · <人类可读大小>`；右侧格式徽标 + 置顶角标（复用 `ClipboardBeanUi.pin` 的图标与 0.3 alpha 画法）。
- 文件缺失：缩略图位置画灰底棋盘 + 文案"图片已失效"，整行仍可点（点了给提示），长按可删。

`ClipboardAdapter` 需要**开始按 `type` 分视图类型**：

```kotlin
override fun getItemViewType(position: Int): Int =
    if (getItem(position)?.type == BeanType.IMAGE) TYPE_IMAGE else TYPE_TEXT

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = when (viewType) {
    TYPE_IMAGE -> ViewHolder(ClipboardImageBeanUi(parent.context, scope))
    else -> ViewHolder(ClipboardBeanUi(parent.context, scope))
}
```

`ViewHolder` 现在直接持有 `ClipboardBeanUi`，要抽一个 `ClipboardRowUi` 接口（`root` / `refreshColors()` / `setBean`），两个实现各自填。**注意 `PagingDataAdapter` 的占位行**：`getItem()` 在占位时返回 null，`getItemViewType` 必须能容忍，统一按文本类型走。

长按菜单按类型裁剪：

| 菜单项 | 文本行 | 图片行 |
| --- | --- | --- |
| 编辑 | ✅ | ❌（图片没有可编辑的文本） |
| 分享 | ✅ | ✅（`ACTION_SEND` + `EXTRA_STREAM`） |
| 分词 | ✅ | ❌ |
| 收藏 | ✅ | ❌（收藏表是文本模型） |
| 置顶 / 取消置顶 | ✅ | ✅ |
| 删除 | ✅ | ✅ |

### 6.3 预览对话框

用现成的 `TrimeInputMethodService.showDialog(dialog)`：`TYPE_APPLICATION_ATTACHED_DIALOG` + `FLAG_NOT_FOCUSABLE` + `DIM_BEHIND`，且 `onFinishInputView` 会调 `dismissShowingDialog()`，键盘一收预览自动消失——不需要自己管生命周期。

> ⚠️ 不要给它加 `FLAG_ALT_FOCUSABLE_IM`，历史上这个 flag 会让 IME 里的浮层"一点就闪"。预览里没有输入框，`FLAG_NOT_FOCUSABLE` 足够。

布局：

```
┌────────────────────────────────────┐
│ 1920 × 1080 · 1.4 MB · PNG         │  信息行（14sp，次要色）
├────────────────────────────────────┤
│                                    │
│          [ 图片预览 ]              │  ImageView，fitCenter
│                                    │  高 ≤ min(屏幕 55%, 420dp)
│                                    │
├────────────────────────────────────┤
│            取消        发送        │  右主左次
└────────────────────────────────────┘
```

两段解码，避免点开就卡：

1. 立刻用列表里已缓存的 160 px 缩略图放大顶上（`RoundedBitmapDrawable` 或直接 `ImageView` + 圆角 clip）。
2. 后台解码中等尺寸（最长边 ≤ 1600），完成后 `setImageBitmap` 替换；期间"发送"按钮可用（发送用的是原文件，不依赖预览）。

**状态机**：

| 状态 | 触发 | 界面 |
| --- | --- | --- |
| `Loading` | 打开时 | 缩略图占位 + 发送按钮可用 |
| `Ready` | 中等图解码完成 | 大图 + 发送/取消 |
| `Broken` | 文件丢失 / 解码失败 | 占位图 + "图片已失效" + **只留"删除"** |
| `Sending` | 点发送 | 按钮转圈并禁用，防止连点 |
| `Failed` | 发送抛错 / 返回 false 且无降级可用 | 回到 `Ready` + 错误文案（见 §7） |

### 6.4 确认语义

**预览即确认。** 用户已经明确点了一张图，再弹"确定要发送这张图片吗？"属于多余的第三层。危险动作（删除）不需要二次确认——它可从历史里重新复制。

点"取消"或对话框外 → 回到面板，滚动位置保持不变。

### 6.5 发送时序

```
点"发送"
  ↓
① 若 composingText 非空 → clearComposition() + finishComposingText()
  ↓（避免图片插进未上屏的拼音中间）
② ic = currentInputConnection        ── null ──> 提示"当前没有可输入的应用"，留在预览
  ↓
③ 需要转码吗？ → 是则写 cacheDir/clipboard_send/，得到临时文件
  ↓
④ uri = FileProvider.getUriForFile(ctx, "${packageName}.fileprovider", file)
  ↓
⑤ InputConnectionCompat.commitContent(
       ic, currentInputEditorInfo,
       InputContentInfoCompat(uri, ClipDescription("image", arrayOf(mime)), null),
       InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
  ↓
   true  → 成功：dismiss + updateTime(bean) + toast + 按 clipboardReturnAfterPaste 决定是否回键盘
   false → 降级 1
   SecurityException/IOException → 报错，留在预览
```

**降级链**（顺序固定）：

1. **`commitContent`** —— 主路径。目标编辑器实现 `InputConnection.commitContent` 才能收图。
2. **`ACTION_SEND` 分享选择器** —— 与现有 `launchTextSharing()` 同构，改成 `type = mime` + `EXTRA_STREAM = uri` + `FLAG_GRANT_READ_URI_PERMISSION`，chooser 加 `FLAG_ACTIVITY_NEW_TASK`。提示"当前输入框不支持插入图片，已改为分享"。
3. **塞回系统剪贴板** —— `clipboardManager.setPrimaryClip(ClipData.newUri(contentResolver, "image", uri))`，提示"已复制，请在输入框长按粘贴"。适用于"支持剪贴板贴图但不支持 commitContent"的 App。注意这会把我们自己的 T1 再触发一次，靠 `hash` 去重挡住，只 `updateTime`。

**关于兼容性要诚实**：微信、QQ、备忘录对 `commitContent` 的支持度随版本变化，且没有任何 API 能提前问出来。所以**降级不是兜底选项，是必须实现的一等公民**。

### 6.6 发送成功后

- `ClipboardHelper.updateTime(id, now)`，让刚发过的图升到历史最前（和文本 paste 的行为对齐）。
- 若 `clipboardReturnAfterPaste` 为真 → `windowManager.attachWindow(KeyboardWindow)`（与文本 paste 完全一致）。
- toast 用 `context.toast(...)`（`util/Toast.kt` 里的扩展），时长 `LENGTH_SHORT`。

---

## 7. 错误与空态 ★

### 7.1 反馈原则

1. **被动路径不打扰**：T1/T2 失败只写 `Timber.w`，绝不在用户打字时弹 toast。用户没有发起请求，就不该收到错误。
2. **主动路径必须回应**：T3/T4 是用户按下去的，任何失败都要有可见反馈，且**文案要区分"没有"和"读不出来"**——这两种情况用户的下一步动作不同。
3. **一次只提示一条**：同一秒内多个失败只留最后一条（用一个 `lastToastAt` 节流，200 ms）。

### 7.2 错误矩阵

| # | 情形 | 检测点 | 反馈 | 是否落库 |
| --- | --- | --- | --- | --- |
| 1 | 剪贴板为空（`primaryClip == null` 或 `itemCount == 0`） | T3 点击时 / T2 | 按钮置灰；强行点击 → toast "剪贴板是空的" | 否 |
| 2 | 剪贴内容是纯文本 | `hasMimeType("image/*")` 为假 | 走原文本路径，**不提示** | 按文本规则 |
| 3 | 声明是图片但没有 URI（个别 App 只在 `text` 里放了个 `content://` 字符串） | `item.uri == null` | T1/T2 静默；T3 toast "剪贴板里没有图片" | 否 |
| 4 | URI 打不开（`SecurityException` / `FileNotFoundException` / 流为 0 字节） | `openFileDescriptor` | T1/T2 静默（日志带 URI）；T3 toast "无法读取剪贴板中的图片，可能已被移除" | 否 |
| 5 | 超过 16 MB | 边读边计数 | T1/T2 静默；T3 toast "图片过大（xx MB），上限 16 MB" | 否 |
| 6 | 解码失败 / 不支持的格式 | `inJustDecodeBounds` 返回 null | T1/T2 静默；T3 toast "不支持的图片格式：<mime 或 未知>" | 否 |
| 7 | 解码 OOM | `catch (OutOfMemoryError)` | toast "图片过大，无法显示"；回收位图 | 否 |
| 8 | 存储写入失败（空间不足 / IO 错） | `IOException` | toast "存储空间不足，无法保存图片" | 否 |
| 9 | 已存条目的文件丢了 | `resolveImageFile().exists()` 为假 | 行显示"图片已失效"；点按 toast "图片文件已丢失，请删除该条目"；长按仍可删 | 已在库 |
| 10 | 发送时没有输入框 | `currentInputConnection == null` | toast "当前没有可输入的应用"，预览保留 | 不影响 |
| 11 | `commitContent` 返回 false 且分享也不可用（无 Activity 处理） | 降级链走完 | toast "无法发送图片：没有可用的目标应用" | 不影响 |
| 12 | 隐私模式 / 敏感标记 | `PrivacyMode.isActive` / `EXTRA_IS_SENSITIVE` | 敏感标记在 T3 时 toast "该剪贴板内容被标记为敏感，已拒绝读取"；隐私模式不提示（用户自己开的） | 否 |

### 7.3 空态

`ClipboardPageUi` 现在只有 `RecyclerView`，需要补一个空态（`FrameLayout` 里叠一层 `TextView`，`RecyclerView` 有数据时 `GONE`）：

- 无任何历史：居中"剪贴板是空的\n复制文字或图片后会出现在这里"（12sp，`keyTextColor` 50% alpha）。
- 有历史但当前 Tab 是收藏且为空："还没有收藏"。
- 空态文案要随 `PrivacyMode` 切换为"隐私模式下不记录剪贴板"。

判定方式：`ClipboardAdapter.itemCount`（`PagingDataAdapter` 已提供）+ `addLoadStateListener`，不要自己再查库。

### 7.4 文案（zh / en）

| key | zh-rCN | en |
| --- | --- | --- |
| `clipboard_image` | 发送剪贴板图片 | Send clipboard image |
| `clipboard_image_empty` | 剪贴板是空的 | Clipboard is empty |
| `clipboard_image_none` | 剪贴板里没有图片 | No image in clipboard |
| `clipboard_image_unreadable` | 无法读取剪贴板中的图片，可能已被移除 | Couldn't read the image: it may have been removed |
| `clipboard_image_too_large` | 图片过大（%1$s），上限 %2$s | Image too large (%1$s), limit %2$s |
| `clipboard_image_unsupported` | 不支持的图片格式：%1$s | Unsupported image format: %1$s |
| `clipboard_image_decode_failed` | 图片过大，无法显示 | Image too large to display |
| `clipboard_image_storage_full` | 存储空间不足，无法保存图片 | Not enough space to save the image |
| `clipboard_image_missing` | 图片文件已丢失，请删除该条目 | The image file is gone; delete this entry |
| `clipboard_image_broken` | 图片已失效 | Image unavailable |
| `clipboard_image_no_target` | 当前没有可输入的应用 | No app is accepting input |
| `clipboard_image_send_failed` | 当前输入框不支持插入图片，已改为分享 | This field can't take images; sharing instead |
| `clipboard_image_sensitive` | 该剪贴板内容被标记为敏感，已拒绝读取 | Clipboard content is marked sensitive; refused |
| `clipboard_image_sent` | 图片已发送 | Image sent |
| `clipboard_image_copied` | 已复制，请在输入框长按粘贴 | Copied; long-press the field to paste |
| `clipboard_image_disable` | 记录图片 | Record images |
| `clipboard_image_max_size` | 单张图片上限 | Max image size |
| `clipboard_preview_send` | 发送 | Send |
| `clipboard_preview_delete` | 删除 | Delete |

---

## 8. 设置项

挂在 `AppPrefs.Clipboard`，页面挂进 `ClipboardSettingsFragment` 的"记录"段（该页是三段式 `PrefSection`，新增行必须**同时**写进 `keys` 列表，否则 `includeUnlisted` 会把它甩到页尾）：

| key | 类型 | 默认 | 范围 | 依赖 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `clipboard_image_listening` | switch | `true` | — | `clipboardListening` | "记录图片"。关掉后 T1/T2 停，T3 仍可用 |
| `clipboard_image_max_mb` | int | `16` | 1–64，单位 MB | 同上 | 单张上限 |

不加"图片配额"旋钮：200 MB 写死，靠 `sweepOrphans` + 时间淘汰兜住。设置项越少越好，这个值用户没有调的需求。

`imagePath` / `mime` 这些是内部字段，不做导入导出（`format 3` 的三来源配置不含剪贴板数据）。

---

## 9. 线程与性能

| 环节 | 线程 | 说明 |
| --- | --- | --- |
| T1 回调 | 主线程（system_server 派发） | **只做两件事**：`openFileDescriptor(uri, "r")` 拿到 fd（权限检查 + open，很快），然后把 fd 投给 IO 线程。绝不在主线程读流 |
| 落盘 + 哈希 + 探测 | `Dispatchers.IO.limitedParallelism(1)` | 串行，避免连拍多张时互相抢带宽；也天然给"同一张图连发两次"排序 |
| DB 写入 | 同上 + 现有 `mutex` | 只锁 DB 段，不锁整个复制过程（现有代码把整段包在 `mutex` 里，图片要把复制挪出去，否则一次 5 MB 拷贝会把文本回调堵住） |
| 列表缩略图 | `Dispatchers.IO` + `LruCache` | 绑定时异步，`ViewHolder` 复用要能取消 |
| 预览中等图 | `Dispatchers.IO` | 最长边 1600 |
| 发送 | 主线程调 API，转码在 IO | `commitContent` 必须在主线程 |

关键指标：单张 5 MB 截图的入库耗时应在 100 ms 量级（拷贝 + 哈希一遍过），面板打开到缩略图出现不额外增加首帧时间——T2 的补抓是异步的，**不阻塞 `onAttached`**。

---

## 10. 平台兼容与已知坑

| 坑 | 说明 | 对策 |
| --- | --- | --- |
| `commitContent` 是 API 25+ | `minSdk = 21` | 走 `InputConnectionCompat`；21–24 上只有接了 AndroidX 兼容协议的编辑器能收，其余直接降级分享 |
| `InputContentInfo` 要 URI 授权 | 编辑器读不到 URI 就静默失败 | `FileProvider` 已 `grantUriPermissions=true`，发送时带 `INPUT_CONTENT_GRANT_READ_URI_PERMISSION` |
| HEIF 需要 API 28、AVIF 需要 31 | 低版本解码返回 null | 按 §7 #6 提示"不支持的格式" |
| Android 10+ 后台剪贴板限制 | 非默认输入法/无焦点时读不到 | T2 补偿；不要为此申请任何权限 |
| OEM 差异（HyperOS/MIUI） | 截图入剪贴板的时机、URI 形态、`label` 都可能不同 | 抓取只依赖 `mime + uri`，不依赖 `label`；`label` 至多用于日志 |
| `FLAG_ALT_FOCUSABLE_IM` | IME 里会导致浮层"一点就闪" | 预览用 `showDialog` 现成的 `FLAG_NOT_FOCUSABLE`，不加别的 flag |
| `android.widget.PopupMenu` 不可用 | 已有 `ImePopupMenu` | 图片行长按菜单沿用 `ImePopupMenu` |
| VectorDrawable 的 `?attr` 填充 | IME 上下文里整张图会空白 | 新图标 `ic_baseline_image_24` 用 `@android:color/white` 填充 + 运行时 `setTint` |
| 深色/浅色主题 | 图片行要跟配色方案走 | `refreshColors()` 里同时刷背景 `decorDrawable` 与文字色，和 `ClipboardBeanUi` 一致 |
| DB schema 校验 | 迁移与实体差一列就崩在首查 | 迁移逐列对齐；单测里跑一次 `MIGRATION_5_6` |

---

## 11. 改动清单

| 文件 | 改动 | 量级 |
| --- | --- | --- |
| `data/db/DatabaseBean.kt` | 加 `mime/width/height/byteSize/hash`；`fromClipData` 不变 | S |
| `data/db/Database.kt` | `version = 6` + `MIGRATION_5_6` + 注册 | S |
| `data/db/DatabaseDao.kt` | `findByHash`、`allBeansOnce`、`sumImageBytes`、`getAllUnpinned` 复用 | S |
| `data/db/ClipboardHelper.kt` | T1 图片分支、`syncCurrentClip()`、`ingestImage()`、删行带删文件 | M |
| `data/db/ClipboardImageStore.kt`（新） | 目录、拷贝+哈希一遍过、`resolveImageFile`、`delete`、`sweepOrphans`、配额 | M |
| `data/db/ImageClipProbe.kt`（新） | 探测 + 结果类型 + `decodeSampled()` | M |
| `ime/clipboard/ClipboardImageBeanUi.kt`（新） | 图片行 | M |
| `ime/clipboard/ClipboardRowUi.kt`（新） | 抽公共接口，`ClipboardBeanUi` 实现它 | S |
| `ime/clipboard/ClipboardAdapter.kt` | 视图类型、图片行绑定、菜单裁剪 | M |
| `ime/clipboard/ClipboardWindow.kt` | `onAttached` 补抓、发送编排、降级链 | M |
| `ime/clipboard/ImagePreviewDialog.kt`（新） | 预览对话框 + 状态机 | M |
| `ime/clipboard/ClipboardTitleUi.kt` | 标题栏加图片按钮（`deleteAllButton` 左侧，`endToStart` 链） | S |
| `ime/clipboard/ClipboardPageUi.kt` | 空态 | S |
| `data/prefs/AppPrefs.kt` + `ClipboardSettingsFragment.kt` | 2 个偏好 | S |
| `res/drawable/ic_baseline_image_24.xml`（新） | 图标 | S |
| `res/values/strings.xml`、`values-zh-rCN/strings.xml` | 19 条文案 | S |
| `app/src/test/.../data/db/*` | 4 个测试类 | M |

不需要动的：`ClipboardSuggestionUi` / `InputBarDelegate` 的联想逻辑。图片入历史时 `updateLastBean(bean)` 传的是 `text = null` 的 bean，`onClipboardUpdateListener` 里已有 `if (it.text.isNullOrEmpty()) { isClipboardFresh = false }` 分支，会**自动收起文本联想条**——正是想要的行为（否则条上还挂着一条和当前剪贴板不符的旧文本）。这一点要写进测试，防止以后有人把 null 当异常。

---

## 12. 测试计划

### 12.1 单元测试（`app/src/test`，kotest `BehaviorSpec`，与现有 `ClipboardLimitTest` 同风格）

| 测试 | 覆盖 |
| --- | --- |
| `ImageClipProbeTest` | PNG/JPEG/GIF/WebP/HEIF 头识别；`application/octet-stream` + PNG 头 → Ok；`text/plain` + PNG 头 → NotImage；截断文件 → Undecodable；0 字节 → Empty；超 16 MB → TooLarge |
| `ClipboardImageStoreTest` | 同图两次只留一个文件；相对路径解析；**旧行绝对路径仍能解析**；超配额删最旧且不删置顶；`sweepOrphans` 只删 24h 前的孤儿 |
| `ImageSendPlanTest` | 1 MB PNG 原样；3 MB PNG 转码；GIF 原样；BMP→PNG；HEIF→JPEG；有 alpha 时输出 WebP |
| `ImagePurgeTest` | `delete(id)` / `deleteAll(KEEP_PINNED)` / `deleteAll(ALL)` 三条路径都真的删了文件（fake store 记录调用） |
| `ClipboardLimitTest`（回归） | 现有"按时间而非 id 淘汰"的行为不变 |

### 12.2 手工用例（真机 `5e551466`，另加平板）

1. 系统截图 → 打开键盘 → 剪贴板面板：首行是截图缩略图，尺寸/大小正确。
2. 点缩略图 → 预览清晰 → "发送"到备忘录：图片插入成功；`clipboardReturnAfterPaste` 打开时回到键盘。
3. 相册"复制图片" → 同上。
4. 浏览器长按"复制图片" → 同上。
5. 微信输入框 → 发送：成功或走到分享降级，**不能静默失败**。
6. 清空剪贴板 → 点标题栏图片按钮：提示"剪贴板是空的"，按钮应为置灰态。
7. 关掉"记录图片" → 复制图片：历史无新增；标题按钮仍能一次性发送。
8. 开隐私模式 → 复制图片：不入库；面板空态文案切换。
9. 删除全部 → `filesDir/clipboard_images/` 为空（`run-as` 只能读，验证用 `ls`）。
10. 从 v5 升级（保留旧 IMAGE 行）→ 不崩，显示"图片已失效"，可长按删除。
11. 复制 20 MB 大图 → 不崩，T3 给"图片过大"。
12. 复制一张图后连续再复制一次同一张 → 历史只有一行，时间被顶到最前。

### 12.3 回归

- 文本剪贴：去重规则、过滤规则、上限淘汰、联想条、长按菜单、编辑页。
- 主题切换：`refreshColors()` 对图片行生效（背景、文字、置顶图标）。
- 面板打开性能：无图片历史时首帧不变；有 10 张图时不出现明显掉帧。

---

## 13. 风险与待决

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 目标 App 对 `commitContent` 支持度不可控 | "发送"失败率高，用户觉得功能没用 | 三级降级链（§6.5），且降级后**必须**告诉用户发生了什么 |
| `openFileDescriptor` 在回调里也可能被拒 | 极少数 App 的 URI 只在复制瞬间可读 | 回调里立刻拿 fd（不等队列）；拿不到就静默丢，不重试 |
| 图片把文本历史挤掉 | 用户开了 10 条上限，5 张图占一半 | 已选"共享上限"，行为可预期；若用户反馈强烈，再加独立图片上限 |
| 存储增长 | 200 MB 配额 + 淘汰 | `sweepOrphans` 兜崩溃残留 |
| 大图转码耗时 | 点发送后按钮转圈 200–500 ms | 转码在 IO，按钮禁用防连点；预览不依赖转码结果 |
| 对话框与键盘同屏 | 小屏上预览被裁 | 预览高度取 `min(屏幕 55%, 420dp)`，且 `fitCenter` 保证不裁内容 |

**待决**（需要产品/用户拍板，不影响主体实现）：

1. 标题栏按钮 vs 长按剪贴板键进入路径 A —— 本文选标题栏（可发现性更好），代价是标题栏从两个控件变三个，窄屏可能挤。
2. 是否显示"来自截图"标签 —— 需要多存一个 `label` 列，收益一般，暂不做。
3. 图片是否参与"收藏" —— 收藏表是文本模型，要改就是另一摊，暂不支持。

---

## 14. 实施分期

| 期 | 内容 | 完成判据 |
| --- | --- | --- |
| **P0** | `ClipboardImageStore` + `ImageClipProbe` + DB v6 + T1/T2 捕获（无 UI） | 复制截图后库里有一行、目录里有文件；文本路径零回归 |
| **P1** | 图片行 + 空态 + 长按菜单裁剪 | 面板能看到缩略图，点击有反应（先接 toast） |
| **P2** | 预览对话框 + `commitContent` + 三级降级 | 能真的把图发进备忘录/微信（或明确降级） |
| **P3** | 标题栏直发按钮 + 两个设置项 + 全部文案 | 空/过大/不可读三条路径文案正确 |
| **P4** | 单测 + 手工用例 + 性能回归 | §12 全绿 |

每期结束都要跑一次**文本剪贴的全量手工回归**——这条链路上任何一处改动都可能把"复制文字"弄坏，而那是用户每天用几十次的功能。
