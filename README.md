<div align="center">

<h1>WE 工坊（WE Workshop）</h1>

<p>Android 上的 Wallpaper Engine 创意工坊下载器</p>

</div>

> **仓库状态：收尾维护，功能冻结。**
>
> 本仓库是这一代实现的最后一版，只接受收尾范围内的改动（构建失败、崩溃、文案与事实性错误）。
> 下一轮重构会在**独立仓库**中进行，与本仓库及上游源仓库**分家**：不再合并上游，也不再沿用这里的
> WebView 播放器与 JS 超分运行时。收尾边界逐条列在 `docs/REPO_SCOPE.md`，架构决策见
> `docs/PLAYER_ARCHITECTURE.md`。

---

## 产品定位

**这是一个下载器。播放只是已下载视频的一个动作，不是产品中心。**

- 未下载：详情页主按钮是**下载**。
- 下载中：同一位置显示进度、速度与取消。
- 已下载：主按钮变为**播放**；也可以在「下载 → 已下载」里一键直接播。
- 非视频资源：只提供打开目录与删除，不伪装成可播放。
- 不做视频库、不做推荐流、不维护第二套播放列表。

浏览 → 下载 → 播放，只有这一条主线。

---

## 快速开始

1. 安装 APK（`we-workshop-v0.6.3.apk`）。
2. 右上角 ⚙ → Steam 登录（需要拥有 Wallpaper Engine）。
3. 工坊列表里挑一个条目 → 详情 → **下载**。
4. 下载完成后，详情页或 ⤓ 下载页里点 **播放**，进入原生播放器。

不登录也能浏览（匿名网页态），但下载需要登录态。

---

## 分类与筛选

**当前实现**（这就是全部，没有隐藏的高级筛选）：

| 位置 | 选项 |
| --- | --- |
| 排序 | 趋势、最新、最高评分 |
| 内容形式 | 全部、场景、视频、应用、网页 |
| 工坊类别 | 全部类型、壁纸、预设、资源 |
| 年龄分级 | 全部分级、所有人、问题内容、成人 |

**未实现**（完整分类模型留给新仓库）：热门时间窗（今日/本周/本月/近三月/今年）、分辨率体系（宽屏/超宽屏/双屏/三屏/竖屏）、题材标签全集、排除标签（`excludedtags[]`）、同组 OR / 跨组 AND 的统一 `FilterSpec`。

做这些之前，界面不会出现对应的按钮——宁可不给，也不给一个点了没反应的筛选。

---

## 下载与存储

- 源文件保存到 `Download/Wallpaper WE/`，不改码率、不转码、不降清晰度。
- 支持断点续传；中断后从下载页点「重试」继续。
- 「清除已完成」只清记录，不删文件。
- 下载期间由前台服务保活，避免后台被回收。

**边下边看**：保留，但**仍走旧的 WebGL 回退链路**（见下节），帧率上限受该链路限制。原生播放器暂不接管流式地址，因为 `/stream/` 在水位不足时返回 `503`，ExoPlayer 会当作硬错误。这一项在收尾范围内**不修**。

---

## 播放

默认走**原生播放器**（`PlayerActivity`），不是 WebView。

| 项 | 实现 |
| --- | --- |
| 内核 | Media3 ExoPlayer 1.8.1 |
| 输出 | 解码器 → `SurfaceView` 直出，帧不进 JavaScript / Bitmap / `texSubImage2D` |
| 视频效果 | 默认 `setVideoEffects(emptyList())`，不创建 GL 链，原画零额外开销 |
| 拖动 | 拖动中 `CLOSEST_SYNC` + scrubbing，松手切 `EXACT` 精确落点 |
| 全屏 | 真实 `SENSOR_LANDSCAPE` + 沉浸式，旋转不重建播放会话 |
| 控件 | 全图标按钮；单击显隐、双击左右 ±10s、左滑亮度、右滑音量、横向拖动带时间气泡 |

播放信息与 `weworkshop.log` 会输出同一组数据：解码器名、首帧耗时、丢帧数。**丢帧 > 0.5% 就说明这台设备这一档没跑住**，不用凭手感判断。

### 回退机制

原生播放器启动失败时，`player.js` 会自动切回旧的 WebGL 播放器并提示原因，同时把堆栈写进 `crash.log`。下次启动 WebView 顶部会出现可复制的崩溃横幅。

这条回退链路很慢（每帧一次 `texSubImage2D`），只保证「不白屏」，不保证流畅。

---

## 超分

**原生内核不支持超分。** 超分只存在于回退的 WebGL 播放器里，属于实验功能。

| 档位 | 说明 |
| --- | --- |
| 原画 | 关闭后处理 |
| FSR EASU + RCAS | 2 pass，最省电，非神经超分 |
| Anime4K Fast | 官方 Low-end 模板，弱 GPU 首选 |
| Anime4K 3DGraphics / 3DGraphics AA | 3D 内容的硬边与锯齿 |
| FSRCNNX x2 8-0-4-1 | 14 pass，只增强亮度 |
| Anime4K 标准 (HQ) | VL 级重建，开销最大，建议 ≤720p |

生效条件与限制（如实）：

- 输出必须大于源约 **1.2×**，达不到时放大链不执行，状态栏显示 `0 pass`。
- 输出被限制在约 **2560×1440**，2K/4K 源基本用不上放大链，只能锐化。
- 渲染尺寸在管线创建时锁定，播放中不 resize。
- 卡顿会逐档降级，降到底仍不达标则回原画。
- AnimeJaNai 已从档位列表移出（未验证的 ONNX 转写重档，留着只会增加「不可用」选项）。

---

## 网络与登录

- **App 内 hosts 加速**：内置候选 IP，映射只发生在解析层，OkHttp 仍用原域名做 SNI 与证书校验，不破坏 HTTPS。
- **Steam 登录**：支持令牌验证码与手机确认；冷启动会自动恢复会话，失败重试 3 次，只有 `Expired` / `Revoked` / `AccessDenied` 立即放弃。
- 杀后台后若仍提示重新登录，先看日志里的 `restore session` 段落，而不是直接重输密码。

---

## 权限与隐私

- 需要网络、存储（下载目录）、前台服务（下载保活）。
- Steam 凭据与 refresh token 只存在应用私有目录，不写日志、不上传。
- 崩溃日志只写到 `Android/data/com.prototypejust.weworkshop/files/crash.log`，展示在本地，需手动清除。

---

## 架构

```text
WebView：工坊列表 / 详情 / 下载管理 / 设置
                 │  Bridge（@JavascriptInterface）
                 ▼
Native PlayerActivity（Media3 + SurfaceView）
                 │  启动失败
                 ▼
WebView 回退播放器（video → texSubImage2D → WebGL2，实验超分）
```

为什么是单个 Media3 内核而不是 libmpv，见 `docs/PLAYER_ARCHITECTURE.md`。核心一句话：mpv 在 Android 上要么零拷贝但强制 RGB、10bit 降 8bit，要么保色彩但每帧拷回内存、4K60 必掉帧。

---

## 构建

```bash
./gradlew assembleRelease
```

- JDK 17，Android SDK 35 / build-tools 35.0.0。
- push 到 `main`（仅改文档或 `tools/` 不触发）时，CI 执行 `assembleRelease` → `apksigner verify` → 生成 `we-workshop-v<版本>.apk` 与 `.sha256` → 上传 Release。

> **签名现状**：`release` 变体沿用 `signingConfigs.debug`。只要不是用同一份 keystore 构建，覆盖安装都会被系统拒绝，需要先卸载旧版。这个 APK 适合自用，不适合上架。

---

## 第三方许可

| 组件 | 许可 | 来源 |
| --- | --- | --- |
| AndroidX Media3 | Apache-2.0 | [androidx/media](https://developer.android.com/jetpack/androidx/releases/media3) |
| Anime4K GLSL shaders | MIT | [bloc97/Anime4K](https://github.com/bloc97/Anime4K) |
| AMD FidelityFX FSR1 (EASU/RCAS) | MIT | [FidelityFX-SDK](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK) |
| FSRCNNX_x2_8_0_4_1 | LGPL-3.0 | [hooke007/mpv_PlayKit](https://github.com/hooke007/mpv_PlayKit) |
| 2x AnimeJaNai SuperUltraCompact | CC-BY-NC-SA-4.0 | [openmodeldb](https://openmodeldb.info/models/2x-AnimeJaNai-Standard-v1-SuperUltraCompact) |
| javaSteam | MIT | [dragonbra/javasteam](https://github.com/dragonbra/javasteam) |

AnimeJaNai 的权重仍在包内（`assets/sr/`），但已从档位列表移出；其许可为非商业，本应用不商用。

---

## 故障排查

| 现象 | 先看什么 |
| --- | --- |
| 播放闪退 / 黑屏 | 下次启动的崩溃横幅，或 `files/crash.log` |
| 4K60 掉帧 | 播放信息里的解码器名与丢帧率；`hw=false` 表示该编码没有硬解，任何软件降级都救不了 |
| 登录反复要求输密码 | 日志里 `restore session` 与 `poll` 段落 |
| 下载卡在 0% | 先确认 CDN 可达；开加速后仍不行，看 `SocketTimeoutException` 的目标地址 |
| 超分显示 0 pass | 输出尺寸没到源的 1.2×，或超过 2560×1440 上限 |

日志路径：`Android/data/com.prototypejust.weworkshop/files/weworkshop.log`
