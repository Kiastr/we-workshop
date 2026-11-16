# 原生超分接入方案（NATIVE_SUPER_RESOLUTION）

状态：技术路线已定，待分阶段实施  
记录日期：2026-11-16  
迁移状态：**不进入新仓库**。设计文档已移出本仓库，本文只保留实测数据与移植细节。  
关联：`docs/PLAYER_ARCHITECTURE.md`

---

## 0. 结论先行

**采用 Media3 单内核 + 可选 GlEffect 增强链，不引入 libmpv。**

> 内核选型的完整论证（含 libmpv 在 Android 上的解码两难、改判条件）见
> `docs/PLAYER_ARCHITECTURE.md`。这里只记结论。

| 决策 | 内容 |
| --- | --- |
| 播放内核 | Media3 ExoPlayer **1.8.1**，硬解直出 SurfaceView |
| 原画路径 | **不挂任何 `GlEffect`**，`setVideoEffects(emptyList())`，零额外 GPU 开销 |
| 增强路径 | `setVideoEffects(listOf(...))`，GL 外部纹理就地处理，帧不进 JavaScript |
| 超分实现 | 自写 `BaseGlShaderProgram`，移植 Anime4K / FSR1 的 GLSL（MIT） |
| 许可证 | Media3 Apache-2.0 + Anime4K MIT + FSR1 MIT，**不产生 GPL 义务** |
| 包体增量 | 8 个 aar 合计 3.97 MB；实测 APK 从 14.75 MB → 17.82 MB（+3.07 MB），**无原生 so** |
| 旧实现 | `app/src/main/assets/sr/`（WebGL 逐帧管线）**整体删除**，不留兼容开关 |

版本为什么是 1.8.1 而不是最新：

- `minCompileSdk = 35`，与项目 compileSdk 一致；1.11 起要求 36。
- `setScrubbingModeEnabled` 实测自 1.8 才进入 `ExoPlayer` 接口（1.6.1 / 1.7.1 的 `javap` 输出里没有这个方法），拖动跟手依赖它，不能降版本。

### 为什么要改掉上一版的 libmpv 结论

上一版推荐 libmpv，依据是"复用 Anime4K 的 mpv shader 最快"。核实后这条依据不成立：

1. mpv 手册确认 `hwdec=mediacodec` 在 `vo=gpu` 下可用，但 `mediacodec` 会**强制 RGB 转换**且 10bit 降到 8bit（手册原文 "forces RGB conversion, and how well it handles non-standard colorspaces is not known... 10-bit output will be reduced to 8"）。
2. 现成 Android 包 `libmpv-kotlin-multiplatform` 的 AAR 约 59 MB，原生二进制组合为 GPL-3.0-or-later，会传染整个应用。
3. Animeko 已经用 Media3 的 `GlEffect` 跑通了 Anime4K 的 7 段卷积（`Anime4kQualityEffects.kt`，`BaseGlShaderProgram` + 7 个 `GlProgram` + feature combine + depth-to-space），证明 **Media3 的 GL 管线足以承载 Anime4K**，不需要第二个内核。

代价是 Media3 的格式覆盖受系统解码器限制。但工坊视频只有 mp4（H.264 / HEVC，少量 AV1），Media3 全覆盖，这个代价为零。

> 保留条件：若后续出现"必须支持 mkv/webm/不常见编码"的真实需求，再评估 libmpv 作为**可选插件内核**，而不是默认内核。

---

## 1. 四条硬约束（来自附带的 `android-true-super-resolution` skill）

这四条是踩过坑的结论，直接落进设计：

| 约束 | 含义 | 在本方案中的落点 |
| --- | --- | --- |
| **输出目标必须大于源** | 没有放大就不叫超分；同尺寸只算"恢复/锐化" | 档位表按"输出尺寸 ÷ 源尺寸"分类，不混用命名 |
| **尺寸只能在管线创建时确定** | `configure(inputWidth, inputHeight)` 决定一切，运行中改不了 | 输入分辨率变化 → 必须重建 effect 链；重建前先暂停避免黑帧 |
| **必须确认真正的 Upscale pass 已执行** | 不能只看"注入成功" | 三重校验：`configure` 返回值、首帧后读回输出尺寸、pass 计数器 |
| **不允许逐帧拷贝到 JS / 位图** | 一旦拷贝，4K60 必然崩 | 播放路径与 WebView 完全隔离，原生 Activity 承载 |

---

## 2. 现状根因（为什么"大部分超分不可用"）

现有 `assets/sr/controller.js` + `renderer.js` 的三个致命限制：

```
① 表面上限   : maxDimension ≈ 2560×1440
② 放大倍数   : 要求 scale ≥ 1.2 才判为可用
③ 逐帧拷贝   : <video> → texSubImage2D → WebGL canvas
```

由此产生的连锁结果：

- 4K 源（3840×2160）已经超过 2560×1440 上限 → **所有档位判不可用**。
- 2K 源（2560×1440）恰好等于上限 → 只能 1.0x，达不到 1.2x → **所有放大档不可用**。
- 1080p 源需要 1.33x 才能到 1440p，但输出上限锁死 → 只有少数档位勉强可用。
- 就算档位"可用"，`targetFps` 固定 30，且每帧走 JS 拷贝 → 4K60 掉到 10 帧。

**结论：这不是"档位调参"问题，是管线本身不成立。** 任何在 WebView 内做超分的方案都无法满足 4K60 基线。

---

## 3. 三级画质策略

### 3.1 档位定义

| 档位 | 实际链路 | 允许的源 | 输出 | GPU pass | 默认 |
| --- | --- | --- | --- | --- | --- |
| **原画** | 无 `GlEffect`，解码器 → SurfaceView 直出 | 全部 | 源尺寸 | 0 | ✅ 默认 |
| **锐化** | FSR1 RCAS 单 pass（自适应对比度锐化） | 高度 ≤ 1440 | 源尺寸 | 1 | 可选 |
| **恢复** | Anime4K Restore CNN M（7 conv + combine） | 高度 ≤ 1080 | 源尺寸 | 8 | 可选 |
| **超分 x2** | Anime4K Upscale CNN x2 M（7 conv + combine + depth-to-space） | **高度 ≤ 720** | 2 倍（≤ 1440p） | 9 | 可选 |
| **超分 x2 轻量** | Anime4K Upscale CNN x2 S（3 conv + combine + depth-to-space） | 高度 ≤ 720 | 2 倍 | 5 | 中端机自动降级到此档 |

> pass 数不是估算，是对上游 GLSL 文件逐行解析 `//!DESC` / `//!WIDTH` 得到的实测值，见附录 A。S 档不是"画质阉割版"而是同一 CNN 的窄通道版本，中端机上 5 pass 比 9 pass 更容易守住 60fps。

**不提供的档位**：`Upscale x2 VL`（实测 18 pass，双路卷积，成本约为 M 档两倍）、`Denoise` 系列（与工坊内容无关，动画源没有噪点问题）。宁可少几个真能跑的档位，也不要一屏"不可用"。

**2K/4K 源只提供「原画」和「锐化」。** 这是刻意的产品决策，不是能力不足：

> 3840×2160 的视频在 6.7 英寸、2712×1220 的屏幕上本来就要缩小 2.8 倍显示。再放大到 5K/8K 只增加功耗，用户看不到任何收益。真正该做的是**保真缩小**（高质量降采样）而不是反向放大。

对应 UI 文案不要写"该档不可用"，要写"4K 源无需放大，建议原画"。

### 3.2 像素预算（为什么门槛定在 720p）

以 60fps 计，一次全屏 pass 的填充量 = 输出 W×H×60：

| 源 | 输出 | pass 数 | 每秒输出像素 | 理论采样量（RGBA8 / 9 tap） |
| --- | --- | --- | --- | --- |
| 1280×720 | 2560×1440 | 9（M 档） | 1.99 Gpix/s | ≈ 72 GB/s |
| 1280×720 | 2560×1440 | 5（S 档） | 1.10 Gpix/s | ≈ 40 GB/s |
| 1920×1080 | 1920×1080 | 8（M 档） | 0.995 Gpix/s | ≈ 36 GB/s |
| 1920×1080 | 1920×1080 | 4（S 档） | 0.50 Gpix/s | ≈ 18 GB/s |
| 2560×1440 | 2560×1440 | 1（RCAS） | 0.22 Gpix/s | ≈ 8 GB/s |
| 3840×2160 | 3840×2160 | 1（RCAS） | 0.50 Gpix/s | ≈ 18 GB/s |

> "理论采样量"按 9 次 4 字节读取、不计任何缓存算，是**上界**。实际 3×3 卷积的邻域高度重叠，移动 GPU 的纹理缓存通常能砍掉一半以上，真实带宽约为上界的 1/3。这个折减系数必须真机实测，不能当成已知值。

按上界判断余量是安全的：

- 旗舰（Adreno 740 / Immortalis-G715，LPDDR5 约 60–70 GB/s）：M 档上界 72 GB/s 压线，实际约 24 GB/s，**有余量**。
- 中端（Adreno 642 / Mali-G68，约 25–35 GB/s）：M 档实际约 24 GB/s **压线**，S 档约 13 GB/s **安全**。

所以真实可用的分档是：

- **旗舰机**：720p→1440p M 档可用；1080p Restore M 可用。
- **中端机**：720p→1440p 用 **S 档**；1080p 用 Restore S 或 RCAS。
- **低端机**：只允许原画 + RCAS。

这些估算**必须用真机标定**（见第 6 节），文档里的数字只是设计预算，不是承诺。

---

## 4. GlEffect 实现结构

### 4.1 管线位置

```text
MediaCodec（硬解，输出 OES 外部纹理）
    │
    ├─ 原画：直接送 SurfaceView                    ← 零拷贝，零 GPU 处理
    │
    └─ 增强：VideoFrameProcessor
              ├─ 外部纹理 → 2D 纹理（Media3 内部，1 次）
              ├─ Effect 1..N（我们的 GlShaderProgram）
              └─ 输出 Surface → SurfaceView
```

关键：**帧数据始终在 GPU 上**，不经过 `Bitmap`、不经过 `ByteBuffer`、不经过 JavaScript。这与现有 `sr/` 管线的本质区别在这里。

### 4.2 类结构

```kotlin
// 一个档位 = 一个 GlEffect
sealed interface EnhancementProfile {
    data object Original : EnhancementProfile            // 不挂 effect
    data object Sharpen : EnhancementProfile             // RCAS 1 pass
    data object Restore : EnhancementProfile             // Anime4K Restore M
    data object UpscaleX2 : EnhancementProfile           // Anime4K Upscale x2 M
}

// 核心：把 mpv 的 //!DESC 分段 shader 转成 Media3 的 GlShaderProgram
internal class Anime4kProgram(
    context: Context,
    asset: String,
    private val upscale: Boolean,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ false,   // 见 4.4：RGBA8 省一半带宽
    /* texturePoolCapacity = */ 1,
) {
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // 唯一能改尺寸的地方。输入变了就重建中间纹理。
        return if (upscale) Size(inputWidth * 2, inputHeight * 2)
               else Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) { /* 逐 pass FBO 绘制 */ }

    override fun release() { /* 删纹理、删 FBO、删 program */ }
}
```

### 4.3 三个必须做的校验（对应约束 ③）

```kotlin
// 校验 1：configure 输出尺寸必须大于输入
check(configure(w, h).width > w) { "profile does not upscale" }

// 校验 2：首帧渲染后，实际输出 buffer 尺寸
// 通过 VideoFrameProcessor 的输出 Surface 尺寸 + AnalyticsListener.onVideoInputFormatChanged 交叉验证

// 校验 3：pass 计数器。drawFrame 里累加实际执行的 FBO 数，
// 若某 pass 因 uniform/纹理绑定失败被跳过，计数对不上就判失败并回退
```

**任何一项不通过 → 立即回退原画，并在画质面板显示真实原因**（不是"不支持"，而是"着色器编译失败"/"输出尺寸异常"）。

### 4.3.1 一个真实的"注入成功但没执行"案例

上游 `AMD_FSR1_RCAS_RT.glsl` 第 28 行写着：

```glsl
//!WHEN SHARP 4.0 <
```

意思是：**只有当 SHARP 参数小于 4.0 时这个 pass 才执行**。如果直接把 shader 塞进 Media3 并把锐度设成上限 4.0，pass 会被静默跳过，输出与输入完全一致，但代码层面"注入成功"。

mpv 有 `//!WHEN` 机制，Media3 没有。移植时必须：

1. 把 `//!WHEN` / `//!PARAM` / `//!TYPE` 这些 mpv 预处理指令**剥掉**，参数改由 Kotlin 侧的 uniform 传入；
2. 对剥掉的每一个条件，在 Kotlin 里显式实现等价判断，不能默认"条件成立"；
3. 用 pass 计数器兜底 —— 代码里认为该跑 N 个 pass，实际只跑了 N-1 个就直接判失败。

这就是"必须确认真正的 Upscale pass 已执行"这条约束的来源。

### 4.4 精度与内存

| 选择 | 理由 |
| --- | --- |
| 中间纹理 RGBA8（`useHighPrecisionColorComponents = false`） | 8bit 源无需 16F，带宽直接减半；Anime4K M 系列在 8bit 下观感差异极小 |
| 中间纹理数量 = pass 数（不能 ping-pong） | Anime4K 的 combine pass 需要同时采样全部 7 个 feature map，无法复用 |
| 1080p Restore 内存上限 | 1920×1080×4B×7 ≈ 58 MB 显存。中低端机需在 `onTrimMemory` 时主动释放 effect 链 |
| 720p Upscale 内存上限 | 1280×720×4B×8 ≈ 29 MB，加输出 2560×1440×4B ≈ 14 MB，合计约 43 MB，可接受 |

---

## 5. 设置模型

三层，互不覆盖：

| 层级 | 存储 | 语义 |
| --- | --- | --- |
| 全局默认 | DataStore `playback.default_profile` | 用户选定的默认档位；`auto` 表示按源分辨率自动决策 |
| 单视频覆盖 | 内存 + 可选持久化（按 itemId） | 本次播放临时改档，不写回全局 |
| 运行时降档 | 会话内 + 设备画像 | 性能不足时临时降档，**不覆盖用户设置**，下次仍按用户设置起播 |

设置项：

```text
播放内核      [自动 | Media3]           ← 当前只有 Media3，留位
默认画质      [原画 | 自动 | 锐化 | 恢复 | 超分 x2]
性能策略      [流畅优先 | 均衡 | 画质优先]
自动降档      [开 | 关]
```

`auto` 的决策表：

| 源高度 | 流畅优先 | 均衡 | 画质优先 |
| --- | --- | --- | --- |
| ≤ 480 | 超分 x2 | 超分 x2 | 超分 x2 |
| 481–720 | 锐化 | 超分 x2 | 超分 x2 |
| 721–1080 | 原画 | 锐化 | 恢复 |
| 1081–1440 | 原画 | 原画 | 锐化 |
| > 1440 | 原画 | 原画 | 原画 |

**设备画像修正**：首次使用某档位失败（编译失败/丢帧超阈值/输出异常）→ 记入 `device_profile`，同分辨率区间不再自动选该档位。用户手动选仍可尝试。

---

## 6. 性能验证（不接受"看起来流畅"）

### 6.1 采集口径

`AnalyticsListener` 的三个回调构成唯一可信数据源：

```kotlin
override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long)
override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long)
override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?)
```

外加 `MediaCodecInfo` 侧的真实解码器名（`onVideoDecoderInitialized`），用来确认走的是硬解而不是 `c2.android.*.decoder`（软解）。

### 6.2 判定阈值

| 指标 | 达标 | 告警 | 失败 |
| --- | --- | --- | --- |
| 平均呈现帧率（60s 窗口） | ≥ 58 fps | 50–58 | < 50 |
| 累计丢帧率 | < 0.5% | 0.5%–2% | > 2% |
| 首帧时间（本地文件） | < 300 ms | 300–800 | > 800 |
| seek 响应（拖动松手 → 出帧） | < 400 ms | 400–1000 | > 1000 |
| 解码器 | 硬解（`isHardwareAccelerated`） | — | 软解 |

**原画 4K60 的验收线：≥ 58 fps 且丢帧 < 0.5%，任一项不达即视为未完成。**

### 6.3 自动降档状态机

```text
监控窗口 3s，滑动平均
    │
    ├─ 丢帧率 > 2% 连续 2 个窗口
    │      → 降一档（超分 x2 → 恢复 → 锐化 → 原画）
    │      → Toast「性能不足，已切回原画」
    │      → 写 device_profile
    │
    ├─ 降档后仍 > 2%
    │      → 直接原画；若原画仍不达标 → 提示"设备解码能力不足"
    │        （这是解码问题，不是超分问题，不能靠降档解决）
    │
    └─ 连续 30s 0 丢帧
           → 不自动升档（避免抖动），仅在画质面板提示"当前设备可尝试更高画质"
```

### 6.4 拖动性能（"随便挪"的具体机制）

厂商播放器拖动跟手，靠的不是更强的解码器，而是四件具体的事。缺任何一件，4K60 拖动都会卡：

| 手段 | 做法 | 作用 |
| --- | --- | --- |
| **关键帧对齐** | 拖动中 `setSeekParameters(SeekParameters.CLOSEST_SYNC)` | 只解关键帧，不回溯到目标帧；出帧时间从"一个 GOP 的解码量"降到"单帧解码" |
| **Scrubbing 模式** | 拖动中 `setScrubbingModeEnabled(true)` | 解码器以高于实时速度运行并丢弃非参考帧，音频静音；官方明确这是为暂停态拖动设计的 |
| **松手精确落点** | 松手后 `setSeekParameters(SeekParameters.EXACT)` + 最终 `seekTo` | 预览用关键帧，落地用精确帧，兼顾跟手与准确 |
| **SurfaceView 直出** | 不用 TextureView | TextureView 多一次 GPU 合成，4K60 每次 seek 都要重传整帧纹理 |

状态机：

```text
onScrubStart
    → seekParameters = CLOSEST_SYNC
    → setScrubbingModeEnabled(true)
    → 按 16ms 节流发送预览 seek

onScrubStop
    → setScrubbingModeEnabled(false)     // 必须关，否则正常播放持续高功耗
    → seekParameters = EXACT
    → seekTo(最终位置)
```

两条边界：

1. **`setScrubbingModeEnabled` 只在暂停/拖动期间为 true**。官方文档说明它会提高解码器运行速率并抑制音频，长期开启会显著增加功耗与发热，正常播放必须关闭。
2. **目标位置数据未缓存时，seek 会被网络阻塞**，此时跟手程度取决于缓存层，不是播放器。UI 必须在拖动条上标出"已缓存区间"，让用户知道哪里能瞬间跳过去——这是 B 站式体验的一部分。

### 6.5 测试矩阵

必须准备的真实样本（工坊下载，不转码）：

| 编号 | 编码 | 分辨率 | 帧率 | 码率 | 用途 |
| --- | --- | --- | --- | --- | --- |
| S1 | H.264 High | 1280×720 | 60 | ~5 Mbps | 超分 x2 基线 |
| S2 | H.264 High | 1920×1080 | 60 | ~12 Mbps | 恢复 / 锐化 |
| S3 | HEVC Main10 | 2560×1440 | 60 | ~25 Mbps | 2K 原画 |
| S4 | HEVC Main10 | 3840×2160 | 60 | ~45 Mbps | **4K60 主验收** |
| S5 | AV1 | 3840×2160 | 60 | ~30 Mbps | AV1 硬解覆盖（部分设备无） |
| S6 | H.264 | 3840×2160 | 30 | ~25 Mbps | 4K30 对照 |

每段播放 60 秒，同一帧起点 seek 5 次，记录上表全部指标。至少覆盖：一台旗舰（8 Gen 2 及以上）、一台中端、一台低端/老旧设备。

---

## 7. 与 4K60 基线的隔离规则

这一节是硬约束，代码 review 时按条对照：

1. `player.setVideoEffects(emptyList())` 必须是**默认状态**，不是"开启后关闭"。
2. 原画档不启用 `VideoFrameProcessor`：解码器输出直接绑定 `SurfaceView` 的 `Surface`，不经过 GL 上下文。传入空 list 就是关闭，不存在"开着再关"的状态。
3. 源高度 > 1440 时，`EnhancementProfile` 只允许 `Original`，UI 隐藏其他档位。
4. 任何 effect 相关异常（编译/绑定/尺寸）→ `catch` 后 `setVideoEffects(emptyList())` 并 seek 回当前位置重试一次。
5. 切档必须走"暂停 → 重建 → 恢复播放位置"，不能热插拔导致花屏或黑帧。
6. 不得为了"让超分看起来可用"而降低解码分辨率或转码源文件。
7. `SurfaceView` 而非 `TextureView`；`TextureView` 会多一次 GPU 合成，4K60 直接掉帧。
8. 播放期间禁止在 UI 线程做任何逐帧回调（现有 `requestVideoFrameCallback` 用法全部删除）。

---

## 8. 实施拆分

| 阶段 | 交付 | 验收 | 状态 |
| --- | --- | --- | --- |
| **5.1 播放内核** | Media3 PlayerActivity、SurfaceView、图标控制器、真实横屏全屏、`AnalyticsListener` 埋点 | S4（4K60）≥ 58fps、丢帧 < 0.5%；拖动 < 400ms | **已完成（v0.6.0）**，待真机数据 |
| **5.2 增强框架** | `EnhancementProfile` 抽象、设置三层模型、能力检测、自动降档状态机；先只实现 `Original` + `Sharpen`(RCAS) | 切档无黑帧；RCAS 在 S2 上丢帧 < 0.5% | 待实施 |
| **5.3 Anime4K 移植** | `Anime4kProgram`、Restore M、Upscale x2 M、资产打包、三项校验 | S1 上输出 2560×1440 且 pass 计数正确；S2 上 Restore 可用 | 待实施 |
| **5.4 收尾** | 删除 `assets/sr/`、删除 `player.js` 的 WebGL 分支、README 与兼容性说明、真机报告 | 全矩阵通过；无残留引用 | 待实施 |

5.1 的实际落地情况（v0.6.0 起，v0.6.1 修订）：

- `PlayerActivity` 走 `Media3 1.8.1` + `SurfaceView`，默认不挂任何 effect。
- **不引 `media3-ui`**：画面容器是自写的 `AspectFrameLayout`（适应 / 铺满裁切 / 拉伸），
  控制器全部自绘。`PlayerView` 自带的那套控制器与 `exo_*` 资源本项目一个都用不上，
  留着只会多一份 inflate 失败面，v0.6.1 已连同依赖一起删除。
- Surface 走 `SurfaceHolder.Callback` 显式接管：`surfaceCreated/Changed` 里把有效 Surface
  交给播放器，`surfaceDestroyed` 里 `clearVideoSurface()`，避免旋转或切后台后画面停在最后一帧。
- `enterScrub()` / `exitScrub()` 实现拖动两级策略：拖动中 `CLOSEST_SYNC` + `setScrubbingModeEnabled(true)`，松手 `EXACT`。scrubbing 只在暂停态启用（官方定位）。
- 全屏为真实 `SENSOR_LANDSCAPE` + 沉浸式，`configChanges` 覆盖旋转，不重建会话。
- `AnalyticsListener` 采集解码器名 / 首帧 / 丢帧，落 `weworkshop.log`，「播放信息」菜单可直接查看。
- `playNative` 只接受 `127.0.0.1` / `file` / `content` 地址，并在主线程 `startActivity`
  （`@JavascriptInterface` 跑在 JavaBridge 线程，非主线程启动 Activity 在部分 ROM 上会被拦）。
- **启动失败不再闪退**：`onCreate` 整体 `try/catch(Throwable)`，失败写 `crash.log`、
  置 `STARTUP_ERROR`、`finish()`，由 `player.js` 自动回退到旧的 WebGL 播放器。
- 边下边看暂未切原生：`/stream/` 水位不足返回 503，ExoPlayer 会当硬错误，需要先改阻塞语义或加重试策略。

每个阶段独立构建 APK 真机验证，不合并成一次大改。

---

## 9. 风险与已知不确定项

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| Media3 `GlEffect` 标 `@UnstableApi` | 升级版本可能改签名 | 锁定 media3 版本；effect 代码集中在单一包内，隔离改动面 |
| 中端机 720p→1440p 跑不满 60 | 超分在中端不可用 | 已按设备分档；不承诺全设备可用，只承诺原画不退化 |
| `mediacodec` 输出 10bit 时色彩空间未知 | HDR 源偏色 | 源为 HDR 时强制原画并跳过 effect（`Format.colorInfo.colorSpace` 判定） |
| 中间纹理内存峰值 | 低端机 OOM | 1080p Restore 需 > 4GB RAM 才开放；`onTrimMemory` 释放 |
| 工坊存在非 faststart MP4 | 首帧慢 | 由统一字节缓存层解决，与超分解耦 |
| Anime4K 上游 2024-08 后停更 | shader 无新版本 | 已打包为本地资产，不依赖远端；MIT 允许自由修改 |

---

## 10. 许可证清单

| 组件 | 许可证 | 义务 |
| --- | --- | --- |
| Media3（exoplayer / effect / ui / common） | Apache-2.0 | 保留 NOTICE |
| Anime4K GLSL（bloc97/Anime4K） | MIT | 保留版权声明 |
| FidelityFX FSR1（RCAS pass） | MIT | 保留版权声明 |
| ~~libmpv / libmpv-kotlin-multiplatform~~ | ~~GPL-3.0-or-later~~ | **不引入** |

Animeko 的实现（`Anime4kQualityEffects.kt`）为 AGPL-3.0，**只作结构参考，不复制代码**。本方案按同一结构自行实现，shader 来源为 MIT 的 Anime4K 原始 GLSL。

---

## 11. 与旧实现的对照表（迁移检查用）

| 旧 `assets/sr/` | 新方案 |
| --- | --- |
| `controller.js` 固定 `targetFps: 30` | 删除，帧率由解码器与显示器决定 |
| `renderer.js` `texSubImage2D` 逐帧拷贝 | 删除，GPU 纹理直通 |
| `maxDimension 2560×1440` 上限 | 删除，档位按源分辨率放行 |
| `scale ≥ 1.2` 可用判定 | 改为显式档位表 + 输出尺寸校验 |
| 5 个引擎（含未验证实验档） | 3 个已验证档位（锐化/恢复/超分 x2） |
| 无默认引擎设置 | 三层设置模型 |
| 无性能回退 | 自动降档状态机 + 设备画像 |

---

## 附录 A：上游 shader 结构实测

对本地打包的 Anime4K v4.0 / FSR1 SDK v2.0.0 GLSL 文件逐行解析 `//!DESC` 与 `//!WIDTH`，结果如下（可作为移植时的断言基准）：

| 文件 | pass 数 | 末 pass 输出 |
| --- | --- | --- |
| `Anime4K_Upscale_CNN_x2_M.glsl` | **9** | `//!WIDTH conv2d_last_tf.w 2 *` → 2 倍 |
| `Anime4K_Upscale_CNN_x2_S.glsl` | **5** | `//!WIDTH conv2d_last_tf.w 2 *` → 2 倍 |
| `Anime4K_Upscale_CNN_x2_VL.glsl` | **18** | 双路卷积（`conv2d_tf` + `conv2d_tf1` 并行），2 倍 |
| `Anime4K_Restore_CNN_M.glsl` | **8** | `//!WIDTH conv2d_tf.w` → 原尺寸 |
| `Anime4K_Restore_CNN_S.glsl` | **4** | `//!WIDTH conv2d_2_tf.w` → 原尺寸 |
| `Anime4K_Upscale_Denoise_CNN_x2_M.glsl` | **9** | 2 倍（带降噪，暂不采用） |
| `AMD_FSR1_RCAS_RT.glsl` | **1** | 无 SAVE，直接写 MAIN；受 `//!WHEN SHARP 4.0 <` 控制 |
| `AMD_FSR1_EASU.glsl` | 1 | `//!WIDTH OUTPUT.w` → 尺寸由显示目标决定 |

两条结构性事实：

1. **M 档 Upscale 的前 7 个 pass 全部 `//!HOOK MAIN`**，即每次卷积都从原始帧重新采样，输出各自的 feature map；第 8 个 pass 把 7 张 feature map 加权合并；第 9 个 pass 做 depth-to-space 得到 2 倍输出。这解释了为什么中间纹理必须 7 张并存、不能 ping-pong。
2. **EASU 的尺寸由 `OUTPUT` 决定**，属于"跟随显示尺寸"的 window 级 shader，与"固定输出尺寸的 effect 链"模型不兼容。因此 FSR1 在本方案里**只用 RCAS 做锐化，不用 EASU 做放大**——放大交给 Anime4K 的固定 2x。

### A.1 官方 mpv 配置为什么不能照搬

上游自带三份 conf，Fast 模式（低端 GPU）的实际链路是：

```text
Clamp_Highlights → Restore_CNN_M → Upscale_CNN_x2_M
→ AutoDownscalePre_x2 → AutoDownscalePre_x4 → Upscale_CNN_x2_S
```

按附录 A 的 pass 数累加，这条链在 720p 源上、条件全部满足时要跑 **8 + 9 + 1 + 1 + 5 = 24 个 pass**（上界），而且是"放大→可能降回→再放大"的桌面思路。手机上照搬必然 10 帧。

三处不能照搬的地方：

| 上游做法 | 为什么不能照搬 | 本方案 |
| --- | --- | --- |
| 多 shader 串联 | 24 pass，手机 GPU 无法承担 | 单档位，一次只跑一条链（与 Animeko 的选择一致） |
| `AutoDownscalePre` 的 `//!WHEN` 用 `OUTPUT` / `NATIVE` 比值判断 | 依赖 mpv 的 window 尺寸语义，Media3 无此概念 | 直接按源高度分档，等价效果由档位表实现 |
| `Clamp_Highlights` 防高光溢出 | 实测是 **3 个 pass**，且第 3 个 `//!HOOK PREKERNEL` + 单通道 `STATSMAX` 统计纹理，需要"先统计后 clamp"两阶段 | **第一版不移植**。真机若出现高光裁切再补，不在没验证的情况下增加 3 个 pass |

最后一条尤其重要：如果为了"和官方一致"先把 Clamp 加进来，9 pass 的 Upscale 链路会变成 12 pass，凭空多出三分之一开销，却没人确认过是否真的需要。

---

## 附录 B：`Anime4kProgram` 移植骨架（供实施参考）

```kotlin
// 1) 剥掉 mpv 指令，只留 GLSL 主体
private fun readPassBodies(ctx: Context, asset: String): List<String> =
    ctx.assets.open(asset).bufferedReader().readText()
        .split(Regex("(?m)^//!DESC "))
        .drop(1)
        .map { sec -> sec.lineSequence().drop(1).filterNot { it.startsWith("//!") }.joinToString("\n") }

// 2) 断言 pass 数与上游一致（附录 A 的数字）
require(passBodies.size == EXPECTED_PASSES[asset])

// 3) 7 张 feature map 纹理 + 各自 FBO，尺寸 = 输入
repeat(convPassCount) {
    tex[it] = GlUtil.createTexture(w, h, /* highPrecision = */ false)
    fbo[it] = GlUtil.createFboForTexture(tex[it])
}

// 4) 逐 pass 绘制，conv 全部 HOOK MAIN → 源纹理恒为 inputTexId
convPrograms.forEachIndexed { i, p -> draw(p, src = inputTexId, dst = fbo[i], w, h) }

// 5) combine：采样 7 张 feature map
drawCombine(tex, dst = if (upscale) fbo[7] else currentFramebuffer, w, h)

// 6) upscale 时 depth-to-space：输出 2w × 2h
if (upscale) drawDepthToSpace(tex[7], inputTexId, w * 2, h * 2)

// 7) 计数器：实际执行 pass 数 != 期望值 → 抛 VideoFrameProcessingException
```
