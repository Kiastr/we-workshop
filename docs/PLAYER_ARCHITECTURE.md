# 播放器架构决策（ADR-001）

状态：**已定稿**  
决策日期：2026-11-16  
关联：`docs/VIDEO_CLIENT_REDESIGN.md` 第 5、6、7 节，`docs/NATIVE_SUPER_RESOLUTION.md`

---

## 1. 结论

**单内核：Media3 ExoPlayer 1.8.1 硬解直出 SurfaceView，超分作为同一内核上的可选 `GlEffect` 链。不引入 libmpv，也不做双内核切换。**

这不是"省包体"或"躲 GPL"的选择——那两条在这个项目里都不成立（应用依附 Steam 创意工坊，不分发、不商用）。真正的理由是下面三条，且都能被验证。

---

## 2. 三个决定性事实

### 2.1 libmpv 在 Android 上没有"既保色彩又保 4K60"的解码路径

mpv 手册对 `--hwdec=mediacodec` 的原文：

> `mediacodec` is not safe. It forces RGB conversion (not with `-copy`) and how well it handles non-standard colorspaces is not known. In the rare cases where 10-bit is supported the bit depth of the output will be reduced to 8.

拆开看是二选一：

| 模式 | 色彩 | 4K60 |
| --- | --- | --- |
| `hwdec=mediacodec`（zero-copy，Surface 零拷贝） | 强制 RGB 转换，10bit 降到 8bit | 可用 |
| `hwdec=mediacodec-copy`（拷回系统内存） | 保留原始 YUV / 10bit | 每帧一次 4K 内存往返，**必然掉帧** |

动画内容里 10bit HEVC 是常态（压制组的默认规格）。为了超分把 10bit 源降到 8bit，等于花 GPU 算力做增强、再在源头丢掉精度，方向相反。

Media3 的路径不经过这个取舍：`MediaCodec` 直接把帧写进 `Surface`（不落内存），Surface 是 YUV（Android 10+ 支持 P010），YUV→RGB 与位深处理交给 HWC/GPU 的显示管线。**既不拷内存，也不由播放器强制降位深。**

### 2.2 4K60 已经在 Media3 上实测通过

v0.6.1 的真机结论是"可以正常播放"。上一版 10 帧的根因是 WebView 逐帧 `texSubImage2D` + 写死 `targetFps=30`，不是内核能力不足。换成 libmpv 等于把一个**已经解决**的问题重新打开一次，还要重新验证拖动、旋转、音频焦点、字幕。

### 2.3 超分不需要第二个内核

Animeko 已经在 Media3 的 `BaseGlShaderProgram` 上跑通 Anime4K 的 7 段卷积（`Anime4kQualityEffects.kt`），证明 GL 管线足以承载。移植工作是**一次性且可估算**的：每个 pass 一个 fragment shader，纹理绑定手工管理；难点（多 feature map 并存、depth-to-space）已有可对照的实现。

而 libmpv 的"shader 原样复用"收益，只覆盖超分这一块；代价是播放页全部重做（UI、字幕、音轨、倍速、缓冲状态机），且要和 Media3 的 `Player` 接口并存或整体替换。**用一次性移植换掉长期双栈，是更划算的一边。**

---

## 3. 候选方案评分

| 维度 | Media3 单内核 | libmpv 单内核 | 双内核（Media3 原画 + mpv 增强） |
| --- | --- | --- | --- |
| 4K60 原画 | ✅ 已实测 | 需重验 | ✅ |
| 10bit / 色彩 | ✅ 解码器直出 Surface，不强制降位深 | ⚠️ zero-copy 必降 8bit | ✅ 原画档 |
| 超分落地 | 移植 Anime4K（一次性） | shader 原样复用 | 两套都要 |
| 边下边看 | 自定义 `DataSource`（见 §4） | demuxer cache 天然支持 | 两套都要 |
| 播放页 UI | 已有，增量补齐 | 全部重写 | 两套 UI 或强行统一 |
| 格式覆盖 | mp4 / H.264 / HEVC / AV1 全覆盖 | 更广（mkv/webm/ASS） | 最广 |
| 维护面 | 1 套 | 1 套 + JNI + 原生 so | 2 套 + 状态同步 |
| 包体 | 无原生 so | +约 59 MB AAR | +约 59 MB |

工坊内容只有 mp4，格式覆盖这一列的差异在本项目里**用不上**。

---

## 4. 边下边看怎么并入单内核（不换内核）

现状：`LocalVideoServer` 的 `/stream/` 在水位不足时返回 `503`。WebView 的 `<video>` 会自己重试，ExoPlayer 会把 5xx 当硬错误直接 `onPlayerError`。

三条路，选第三条：

| 方案 | 做法 | 问题 |
| --- | --- | --- |
| A. 改 503 语义 | 自定义 `LoadErrorHandlingPolicy`，把 503 当可重试 | 能跑，但每次重试都重建 HTTP 请求，拖动时放大延迟 |
| B. 阻塞式 HTTP | 水位不足时挂住连接而不是返回 503 | 简单，但每路播放占一个服务线程，且 HTTP 层多一次拷贝 |
| **C. 自定义 `DataSource`** | `ProgressiveMediaSource` 直接读统一字节缓存层，`read()` 在水位不足时阻塞等待 | 无 HTTP 往返、无重复拷贝，与"播放和下载共用同一份字节"的设计一致 |

C 与 `VIDEO_CLIENT_REDESIGN.md` 第 6 节本来就是同一个要求（统一字节缓存层），只是把消费端从 HTTP 换成 `DataSource`。这是纯 Java 实现，没有原生依赖。

拖动到未缓存区间的优先级调度也在这里落地：`DataSource.open(offset)` 把目标区间提升为高优先级，读完恢复。

---

## 5. 超分在单内核下的位置

不变，见 `docs/NATIVE_SUPER_RESOLUTION.md`：

- 原画档：`setVideoEffects(emptyList())`，解码器直出，零额外 GPU。
- 增强档：`setVideoEffects(listOf(...))`，GL 外部纹理就地处理。
- 只对 ≤720p 开 x2 放大；2K/4K 只给锐化/恢复，不生成 5K/8K。
- pass 数实测：`Upscale_CNN_x2_M` 9、`Restore_CNN_M` 8、`S x2` 5、`Restore_S` 4。
- 必须做三重校验（configure 返回值、首帧后输出尺寸、pass 计数），不能只看"注入成功"。

---

## 6. 什么情况下改判

留一条可验证的改判路径，而不是永久结论。满足**任意一条**且能在真机上复现，就重新评估 libmpv：

1. 出现必须播放 mkv / webm / 内嵌 ASS 字幕 / 多音轨的真实内容，且 Media3 侧无法用现有组件覆盖。
2. Anime4K 的 `GlEffect` 移植在目标机型上无法达到帧预算（连续 30 秒丢帧 > 5%），且降档到底仍不达标。
3. 需要 mpv 特有的能力（`--vo=gpu-next` 的 tone mapping、`--vf` 滤镜链、精确的 `--cache` 策略），且被证明是体验瓶颈。

改判时的形态是**替换内核、不是并存**：`PlayerActivity` 的控件层与 `Player` 接口解耦，届时只换下层。

---

## 7. 播放页还差什么（与重构方案第 5 节对齐）

当前已落地：返回 / 标题 / 更多 / 中央播放暂停 / 进度条 / 时间 / 全屏 / 单击显隐 / 双击左右 ±10s / 左亮度右音量 / 横向拖动带气泡。

按 `VIDEO_CLIENT_REDESIGN.md` 第 5 节还缺：

| 项 | 说明 | 优先级 |
| --- | --- | --- |
| 倍速 | 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0，长按临时倍速 | 高 |
| 画质面板 | 原始规格、呈现模式、当前内核、实际运行状态 | 高（超分的入口） |
| 画面比例 | FIT / ZOOM / FILL 三档，`AspectFrameLayout` 已支持，只差 UI | 中 |
| 方向锁定 | 图标已存在，需补"锁定后仍可全屏"的语义 | 中 |
| 小窗 | 全局悬浮播放 | 低（需要 `PictureInPicture` 或自绘悬浮窗） |
| 双指缩放 | 手势调整画面比例 | 低 |

---

## 8. 实施顺序（落到可交付的版本）

| 版本 | 内容 | 验收 |
| --- | --- | --- |
| 0.6.2 | 底部遮罩方向修正；播放页 UI 阻塞问题清理 | 控制层显示时画面无黑线 |
| 0.7.0 | 倍速、画面比例、画质面板骨架（只读现状） | 切档不黑帧，面板数据与日志一致 |
| 0.7.1 | 自定义 `DataSource`，边下边看并入原生内核 | 缓存中起播、拖动到未缓存区、不误报错误 |
| 0.8.0 | `EnhancementProfile` + RCAS 锐化 | 切档无黑帧，S2 上丢帧 < 0.5% |
| 0.8.1 | Anime4K Restore M / Upscale x2 M 移植 | 720p 源输出 2560×1440，pass 计数正确 |
| 0.9.0 | 删除 `assets/sr/` 旧 WebGL 链路，README 与兼容性说明 | 全矩阵通过，无残留引用 |
