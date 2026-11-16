<div align="center">

<h1>WE 工坊（WE Workshop）</h1>

<a href="https://github.com/deepseek-ai/deepseek-harness"><img src="https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white" alt="powered by dsh"></a>

</div>

一个 Android 客户端，用来在手机上浏览并下载 **Wallpaper Engine**（壁纸引擎）的 Steam 创意工坊内容，并**直接播放已下载的视频、实时超分**。

Wallpaper Engine 没有手机端，创意工坊也只能在 PC 客户端里看。这个 App 把工坊搬到了 Android：列表、筛选、搜索、详情、GIF 预览、**源文件下载**（保存到手机 `Download/Wallpaper WE/`），以及本地视频播放与真超分渲染。

## 功能

- 浏览创意工坊列表：缩略图、标题、作者、订阅数
- 排序：趋势 / 最新 / 最高评分
- 筛选：类型（场景/视频/应用/网页）、分类（壁纸/预设/资源）、年龄分级（所有人/问题内容/成人）
- 搜索（支持中文）
- 分页浏览，支持直接输入页码跳转
- 详情页：GIF 大图、标签、描述、订阅/收藏/浏览数、文件大小、跳转 Steam
- 记住上一次的筛选状态（排序/类型/分类/分级/搜索词）
- **下载源文件**：登录 Steam 后把壁纸源文件（视频等）直接下载到手机 `Download/Wallpaper WE/`
- 下载列表：进度、取消、失败重试、打开所在文件夹
- 断点续传 + 失败自动重试（最多 3 次，间隔 3s / 6s / 12s）
- **原生播放**：点击即进入 Media3 原生播放页，4K60 硬解直出，拖动跟手，真实横屏全屏，鼠标/手势全图标控件
- **边下边看**：下载过程中即可预览（不必等下载完成），索引在文件尾部的视频会自动禁用并提示
- **超分**：7 档 WebGL2 渲染（Anime4K / FSR / FSRCNNX / AnimeJaNai），带生效确认、性能看门狗与阶梯降档 —— 当前仍在 WebView 链路，正向原生 `GlEffect` 迁移，见 `docs/NATIVE_SUPER_RESOLUTION.md`
- **网络加速（App 内 hosts）**：顶栏 ⚡，5 组服务可独立开关，一键 TLS 测速优选 IP，失败自动回退系统解析

## 数据源与原理

- **列表**：登录后通过 Steam 统一消息服务 `PublishedFileService.QueryFiles` 查询（数据源与 WE 客户端一致）；未登录时回退到 `steamcommunity.com/workshop/browse` 网页解析（匿名可见条目会更少）
- **详情**：`ISteamRemoteStorage/GetPublishedFileDetails` 接口 + Steam 社区页面
- **下载**：`javaSteam` 建立 Steam 连接 → 获取 depot 密钥 / CDN 节点 / manifest 授权码 → 从 Steam CDN 下载 manifest 与分块 → AES 解密 + zstd/xz 解压 → 写入 `Download/Wallpaper WE/`
- **播放**：`LocalVideoServer` 在 `127.0.0.1` 起一个仅本机可访问的 HTTP 服务，把 `Download/Wallpaper WE/` 下的视频以 Range/206 流喂给隐藏的 `<video>`；视频帧上传为 WebGL 纹理，超分后画到 `<canvas>`
- **边下边看**：`StreamRegistry` 逐 chunk 登记已写入区间、算出「连续可读水位」，`LocalVideoServer` 的 `/stream/<itemId>` 把 Range 裁剪到水位并阻塞等待下载推进；下载完成后按文件名切到 MediaStore 成品续播
- **网络**：需要能访问 Steam（国内需代理或 VPN，手机开全局代理 / TUN 模式即可）；播放与超分全程离线，不联网

## 播放：原生内核与超分（迁移中）

**0.6.0 起，点击视频直接进入原生播放页**（`PlayerActivity`），不再经过 WebView 的 `<video>` 弹窗。

### 原生内核（当前默认）

| 项 | 实现 |
| --- | --- |
| 内核 | Media3 ExoPlayer 1.8.1（只用 `media3-exoplayer`，不引 `media3-ui`） |
| 输出 | `SurfaceView` + 解码器直出，帧数据全程在 GPU，不经过 JavaScript / Bitmap / `texSubImage2D` |
| 画面比例 | 自写 `AspectFrameLayout` 按视频宽高比居中，支持适应 / 铺满裁切 / 拉伸三种模式 |
| 视频效果 | 默认不挂任何 effect，**不创建 GL 处理链**，原画零额外开销 |
| 拖动 | 拖动中 `SeekParameters.CLOSEST_SYNC` + `setScrubbingModeEnabled(true)`（只解关键帧、解码器超实时运行），松手切回 `EXACT` 精确落点 |
| 全屏 | 真实 `SENSOR_LANDSCAPE` + 沉浸式隐藏状态栏/导航栏，旋转不重建播放会话 |
| 控件 | 全部 Material 图标按钮，无文字按钮；单击显隐，双击左右 ±10s，左滑亮度、右滑音量 |
| 失败兜底 | 启动过程任何一步抛异常（含 `Error`）都写入崩溃日志并自动切回旧的 WebGL 播放器，**不闪退** |

播放页刻意**不用 media3-ui 的 `PlayerView`**：它自带一整套控制器布局和几十个 `exo_*` 资源，而本页控件全是自绘的，那套东西一个都用不上。只用 `SurfaceView` + `media3-exoplayer`，inflate 面小、包体也小。

播放器会把实测数据写进日志（`Android/data/com.prototypejust.weworkshop/files/weworkshop.log`）：

```text
[Player] input 3840x2160@60 video/avc container=video/mp4
[Player] decoder=c2.qti.hevc.decoder hw=true initMs=48
[Player] firstFrame=210ms
[Player] [perf] 3840x2160@60 video/avc | 渲染 3840x2160 @ 60fps | 解码器 ...（硬解） | 丢帧 12 / 约 3600 帧（0.33%）
```

「播放信息」菜单项可随时查看同一组数据。**丢帧 > 0.5% 就说明这台设备这一档没跑住**，不要凭手感判断。

### 崩溃日志

未捕获异常由 `WeApplication` → `CrashLog` 写进应用私有目录的 `crash.log`（设备型号 + Android 版本 + 完整堆栈）。
下次启动时 WebView 会把它显示成一条覆盖横幅，**长按可复制**，点「关闭并清除」才会删掉。

播放页自己的启动失败也会走同一条记录路径，另外还有一份简版原因会经由 `Bridge.nativePlayerError()`
交给 `player.js`，用于判定是否回退到兼容播放器。

超分尚未迁到原生内核。下面的 WebGL 链路是 0.5.x 的旧实现，仅作为迁移参考保留；新方案见 `docs/NATIVE_SUPER_RESOLUTION.md`（Media3 `GlEffect` + Anime4K/FSR1，原画基线不受影响）。

播放内核为什么是 Media3 而不是 libmpv、边下边看怎么并入同一个内核、什么条件下才改判，见 `docs/PLAYER_ARCHITECTURE.md`（ADR-001）。

### 旧 WebGL 超分链路（0.5.x）

### 三条硬规则

来自 `android-true-super-resolution` skill，逐条落地在代码里，违反任意一条都是「假超分」（shader 编译成功但画面还是原画）：

| 规则 | 落地位置 | 说明 |
| --- | --- | --- |
| 渲染目标必须大于源尺寸约 **1.2×** | `sr/surface.js` `FixedSurfacePolicy` | 达不到时 Anime4K/FSRCNNX 的 `//!WHEN` 会整段跳过，UI 明确提示而不是白跑 |
| 渲染目标尺寸**创建期一次性锁定**，之后永不 resize | `sr/surface.js` + `sr/renderer.js` | `canvas.width/height` 只在 `create` 写一次；CSS 负责缩放显示。切视频 = 销毁重建新管线，不算 resize |
| 必须验证**渲染链真的跑了**，而不是只验证注入成功 | `sr/renderer.js` + `sr/controller.js` | 每帧返回实际执行的 pass 描述列表；状态栏显示「N pass」；连续 2 秒 0 pass 判为假超分并降档 |

### 档位（声明顺序 = 算力升序 = 自动降档顺序）

| 档位 | 类型 | pass | 说明 |
| --- | --- | ---: | --- |
| 原画 | off | 0 | 不做后处理，功耗最低 |
| FSR EASU + RCAS | 空间放大 | 2 | AMD FSR1，最省电，非神经超分 |
| Anime4K 官方 · Fast | GLSL CNN | 27 | 官方 Low-end 模板，M 级重建 + S 级放大，**默认档** |
| Anime4K 3DGraphics | GLSL CNN | 7 | 3D 渲染内容的硬边/纹理/锯齿 |
| Anime4K 3DGraphics AA | GLSL CNN | 7 | 3D 链 + 抗锯齿训练权重 |
| FSRCNNX x2 8-0-4-1 | GLSL CNN | 14 | 轻量效果好，只增强亮度、色度按 `Y'/Y` 近似保留 |
| AnimeJaNai SuperUltraCompact | ONNX → GLSL | 13 | 10 层 Conv3x3 + PReLU + CRD + 残差，实验重档，建议 ≤720p |

Anime4K / FSR / FSRCNNX 官方发布的是 **mpv `//!HOOK` 语法**（不是标准 GLSL），由 `sr/hook.js` 翻译成 WebGL2 GLSL ES 3.00；AnimeJaNai 是 ONNX 权重，由 `sr/animejanai.js` 转写成 GLSL 卷积链，两者统一进 `sr/renderer.js` 的 FBO ping-pong。

### 性能护栏

看门狗只认真实卡顿信号（p95 帧间隔 > 1.5× 目标、或掉帧比例每秒增速 > 1%），需**持续 10 秒**才逐档降级；缓冲中、A/B 对比中、倍速 ≠1、换档后 3 秒内不采样；降到原画仍卡则熔断。源分辨率过高时重档会在下拉框里被提前禁用（x2 中间纹理有显存上限），避免 OOM。

### 已知限制

- 视频格式取决于 WebView 解码能力（`mp4` / `webm` 最稳，`mkv` / `mov` / `avi` 可能不支持）
- 需要 WebGL2 + `EXT_color_buffer_float`；不满足时自动只保留「原画 / FSR」
- 神经超分建议 ≤720p 源，1080p 及以上会受限或禁用
- `<video crossorigin="anonymous">` 必须保留：本地 HTTP 流靠 CORS 头避免 WebGL 取帧污染 canvas
- 边下边看只对 **moov 在文件头部的 MP4**（faststart）可用；索引在尾部的视频必须下载完才能播，UI 会明确说明。运行时扫描文件头的 box 顺序来判定，不依赖任何格式预设，也不会对视频做转码或压缩

## 边下边看（边下载边预览）

详情页下载进度条下方出现「▶ **边看边下**」即可在下载过程中预览；顶栏 ▶ 播放列表里也会列出「下载中」条目。播放页与成品播放完全一致（档位 / 倍速 / 全屏 / A/B 都可用），因为流式 URL 只是换了一个数据源，仍走同一套 `<video>` → WebGL2 超分管线。

### 为什么不影响下载速度

**下载模式原样保留**：仍是 manifest 里 chunk 按 offset 排序后分 4 路并行、断点续传（`.chunks` 逐 chunk 记录）、SHA-1 校验、`copyToDownloads` 落盘。`WorkshopDownloader` 只多了四处登记钩子（`begin` / 每 chunk 后 `mark` / `finish` / `abort`），不改分组策略、不改并行度、不改顺序。

关键点：4 路是**交错分配**（`groups[i % 4]`），每组内部按 offset 递增串行，因此「从 0 起连续可读的前缀」几乎跟随总完成量推进（模拟 92%~100%），不需要改成顺序下载。水位 = 所有已登记区间串起来的连续前缀，**不能用文件长度判断**——稀疏写入时尾部可能先写满、中间还是空洞，按长度读会花屏。这和下载器里断点续传不能只看文件长度是同一个坑。

### 三层机制

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 水位登记 | `StreamRegistry.java` | 逐 chunk 记录 `[offset, length)`，合并算连续前缀；`await()` / `notifyAll()` 让 HTTP 请求等数据而不是报错 |
| 流式端点 | `LocalVideoServer.java` `/stream/<itemId>` | 把 Range 的 end 裁剪到 `waterline-1`；`Content-Range` 的 total 仍是完整文件大小（播放器据此显示总时长）；等待上限 12s，超时返回 `503 + Retry-After` 让播放器重发 Range。**独立线程池**，避免阻塞占死成品播放的 4 线程 |
| 前端 | `app.js` / `player.js` / `index.html` | 进度回调里轮询 `Bridge.streamingVideo(itemId)`，`ready=true` 才亮按钮；首次可用给一次性引导；播放列表里未就绪条目禁用点击并给出原因 |

### 格式限制（重要）

Steam 官方商店页与 Wiki 一致说明：**创意工坊仅放行 `.mp4`**，本地文件才支持 webm/avi/mov/m4v/wmv。所以下载到的基本都是 MP4。但「MP4」不保证能边下边看——若 `moov` 原子在文件尾部（非 faststart），播放器必须先读到尾部索引才能起播，此时边下边看在原理上不成立。

处理方式是**运行时探测**：`StreamRegistry` 扫描 staging 文件头的 MP4 box 顺序（`ftyp` → `moov` 即 faststart；`mdat` 先出现则说明 moov 在后面），只需每个 box 的 8 字节头可读、几百 KB 就能出结论。非 faststart 或非 MP4 一律保守禁用按钮并提示「需下载完才能播放」。**不对视频做任何压缩或转码**——Steam CDN 的 `compressedLength` 只是传输层压缩，`DepotChunk.process` 已解密解压还原成原始字节，落盘文件与作者上传的字节级一致；转码要引入 ffmpeg，会破坏既定 `WebView + WebGL2` 架构。

### 下载完成后不中断

下载完成后 `staging.deleteRecursively()` 会删掉正在播放的源文件。因此 `finish()` 之后，`LocalVideoServer` 会重新枚举 MediaStore、按文件名找到已落盘的成品（此时 `IS_PENDING` 已置 0），后续 Range 直接复用 `serveVideo()` 走成品，播放无缝续上，磁盘上也不会多留一份副本。

### 已知限制

- 预览期间**不要把进度条拖到尚未下载的位置**：该区间会返回 503 让播放器重试，可能卡顿；等下载追上即可恢复
- moov 在尾部的视频会退化为「下载完再播」，这是容器格式决定，不是故障
- 断点续传时水位从 `.chunks` 记录重建，不影响已下载部分的预览

## 常见问题：开着 VPN 但 Steam 登录失败

App 里有两个不同的网络通道，它们**不共用代理设置**：

| 通道 | 用途 | 受什么影响 |
| --- | --- | --- |
| OkHttp / HttpURLConnection | 创意工坊列表、详情、GIF、CDN 下载 | 系统代理、App 内「网络代理」输入框 |
| javaSteam 长连接（WebSocket/TCP） | **Steam 登录**、depot 密钥、manifest | 只受系统层 VPN/TUN 接管 |

所以会出现：**列表能刷、图片能载、下载能跑，但一登录 Steam 就超时**。

解决办法（任选其一）：

1. 把 VPN 切换为 **TUN / 全局模式**（推荐）
2. 使用分流规则时，把 `WE 工坊`（`com.prototypejust.weworkshop`）或 Steam CM 网段加入代理
3. 设置页点「连接诊断」，确认「网络/VPN」一栏是否显示 VPN 已连接

如果诊断显示 VPN 已连接但依然超时，多半是节点被 Steam 风控，换节点再试。

## 常见问题：一直停在「等待 Steam 验证…」

Steam 对开了手机令牌的账号，会返回一组允许的确认方式（`allowedConfirmations`），javaSteam 按固定优先级只走**第一种**：

| 方式 | javaSteam 的行为 | 界面表现 |
| --- | --- | --- |
| `DeviceConfirmation` | 调 `acceptDeviceConfirmation()`，返回 true 后进入 `while(true)` 轮询，**没有任何回调，也没有超时** | 提示「已向手机 App 发送确认请求」+「改用验证码登录」 |
| `DeviceCode` | 调 `getDeviceCode()` 等界面给码 | 5 位令牌验证码输入框 |
| `EmailCode` | 调 `getEmailCode()`，带上收件邮箱 | 邮箱验证码输入框 |

旧版本对这三种情况都返回「接受手机确认」，却没有任何界面提示：手机没弹窗（未装 App、通知被折叠、不在同一网络）时，界面只会一直显示「等待 Steam 验证…」，10 分钟后退化成一句「登录失败」。

现在的处理：

1. 手机确认阶段立刻提示「打开手机 App 点允许」，并给出**改用验证码登录**按钮
2. 点该按钮会取消当前 poll、重新发起一次登录，这次强制走 `DeviceCode`（`forceCodeLogin`），一定拿得到输入框
3. 若账号只允许手机确认（`allowedConfirmations` 里没有 `DeviceCode`/`EmailCode`），降级按钮**直接隐藏**，避免点进死胡同
4. 登录成功时保存 Steam 下发的 `guardData`（JWT），下次登录少触发一次 2FA；主动登出会连同 token 一起清掉

### 手机批准了却显示「登录已取消」

这是 0.5.8 修掉的一个误报，原因是 JavaSteam 的轮询会被断线连带取消：

```
SteamClient.onClientDisconnected()
  → jobManager.cancelPendingJobs()
    → AsyncJobSingle.setFailed(dueToRemoteFailure = false)
      → future.cancel(true)          // poll 请求被 cancel
        → CancellationException      // 界面翻成「登录已取消」
```

切到手机 App 点「允许」时，本 App 退到后台，心跳/VPN/路由都可能让长连接断一次。断线本身不影响服务端的认证会话——`requestID` 还在 Steam 那边有效，手机上批准的结果也还在，重连后继续 poll 就能拿到 token。但 javaSteam 会把这次断线当成「取消」，用户什么都没取消却看到「登录已取消」。

0.5.8 起「手机确认」这条路自己轮询：断线后先重连，再用**同一个** `authSession` 继续 poll，手机上的批准不会白费；只有 `Expired` / `Revoked` / `Cancelled` 这类真正作废的结果才终止。

同一版还顺手堵了两个会加剧这个问题的口子：

- 连点登录按钮会并发发起两个认证会话（手机收到两条确认，批错一条就被判 `Cancelled`）——现在用 `claimLogin()` 串行化
- 启动时的「恢复会话」会和手动登录抢同一条连接和 `logonCallback`——手动登录会先取消恢复任务

排查用的日志在 `Android/data/com.prototypejust.weworkshop/files/weworkshop.log`，登录失败会记录完整的异常链和 `allowedConfirmations` 列表；断线重试会写 `poll: client disconnected, reconnecting` / `poll retry:`。

### 杀后台后要重新输账号密码（0.6.1）

`refreshToken` 一直是持久化的，问题出在「恢复中」这段状态没告诉前端：冷启动要连 CM 服务器，正常几秒、慢的时候十几秒，这段时间 `loggedIn` 仍是 `false`，前端就把账号密码表单亮了出来——看上去像掉登录，其实恢复还在跑，用户还没等完就重新输了一遍。

0.6.1 的改动：

| 改动 | 原因 |
| --- | --- |
| 新增 `restoring` 状态并上报前端 | 恢复期间只显示「正在恢复 Steam 会话…」，不显示账号密码表单 |
| 恢复失败重试 3 次（1.5s / 3s 退避） | 冷启动那几秒 CM 常常还没连稳，一次失败不等于令牌失效 |
| 令牌先落盘、再连 CM | `logOnWithToken` 失败时令牌仍在，下次启动还能自动恢复 |
| 只有 `Expired` / `Revoked` / `AccessDenied` 等终态错误才立刻放弃 | 其余错误重试有意义，别让用户白等 |
| 恢复失败保留令牌 | 不再因为一次网络抖动就要求重新输密码 |

## 网络加速（App 内 hosts）

顶栏 **⚡** 打开。参考 Watt Toolkit 的「域名 → 优选 IP」思路，但按 Android 的实际约束重新落地。

### 为什么不是 VpnService / 改系统 hosts

| 做法 | 判定 |
| --- | --- |
| 改 `/etc/hosts` | 需要 root，排除 |
| `VpnService` 接管 DNS（最像 Watt Toolkit PC 版） | Android 同时只允许一个 VPN，会**顶掉你为了访问 Steam 已开的 VPN/TUN**，反而让 App 彻底连不上，排除 |
| **App 内自定义 DNS（`okhttp3.Dns`）** | 无需权限、不抢 VPN、不改系统，TLS 仍按原域名做 SNI 与证书校验，**采用** |

映射只发生在 App 自己的解析层：`lookup()` 返回「映射 IP」作为首选地址，OkHttp 仍用 URL 里的原域名做 SNI 和 `HostnameVerifier` 校验，所以不会破坏 HTTPS。为此 `HttpClient` 从 `HttpURLConnection` 迁移到 OkHttp（对外 `get/post` 签名与 UTF-8 解码行为不变），javaSteam 的 CDN client 也挂了同一个 `Dns`。

### 怎么用

1. 点「⚡ 一键测速优选」：并发对每个候选做 **TCP connect + 带 SNI 的 TLS 握手 + 证书域名校验**，只保留整套通过者，按延迟排序选出每个域名的最优 IP。只做 ping 是不够的 —— CDN 边缘 IP 可能能连上却没部署目标域名的证书。
2. 打开右上角总开关（**默认关闭**，开启前不改任何解析行为）。
3. 服务卡片可单独开关；「自定义 hosts 条目」优先级最高，可填你所在地区实测可用的 IP。
4. 若某张卡片显示 **「⚠ 测速无可用 IP」**，说明该域名所有候选都没通过校验 —— 在「自定义 hosts 条目」补一个本地区可用 IP，再点一次测速即可。自定义条目同样会参与 TLS/SNI + 证书校验，输出里能看到 `✓/✗`，不会连错服务器。

### 卡片三态（避免误判）

| 显示 | 含义 |
| --- | --- |
| `● 优选 x.x.x.x · N ms` | 该域名已选出可用 IP，映射生效 |
| `⚠ 测速无可用 IP · 请在下方补一条自定义 hosts` | **测速跑过但该组全失败**，不是「忘了测速」。多半是域名被 DNS 污染、候选 IP 全废 |
| 灰色「未测速 · …」 | 从未跑过测速（或点了「清除优选」） |

判据 `benched` 由 Java 层持久化到 SharedPreferences，重启 App 后仍准确。测速输出末尾会额外列出「一个可用 IP 都没选出」的域名。

### 三重安全网

- 默认关闭；未命中规则时 `lookup()` 行为与系统 DNS 完全一致。
- `lookup()` 把映射 IP 放首位、系统 DNS 解析结果作为后续备选 route，映射 IP 连不上时 OkHttp 自动尝试下一个地址，不会把请求打死。**但备选会过滤掉与映射 IP 不同协议族、以及非全局可达的地址**：国内 DNS 常把 `steamcommunity.com` 的 AAAA 投毒到 `2a03:2880::/29`（Meta/Facebook）等无关地址，Android 双栈下又优先尝试 IPv6，把它当 route 追加只会卡满 `connectTimeout(15s)` 并把脏地址报进异常，反而比不加速更慢。
- 内置候选 IP **只是测速起点，不是固定答案**（不同地区/运营商的最优 CDN 边缘不同，且 IP 会变）。测速失败就不写优选结果，保持系统解析；可随时「清除优选」回到内置候选。

### 跨组复用（自愈）

`candidatesFor()` 会把**其它服务已通过整套校验的 `picks` IP** 也纳入本域名的测速候选。Steam 的 api / store / community / 图片 CDN 常落在同一批 Akamai 边缘，某组测速全失败、其它组成功时，再测一次就能让失败组复用那些已验证 IP；安全性由 `probe()` 保证 —— 它用**本域名**做 SNI 并校验证书，证书不匹配就判 `false`，不会把 GitHub 边缘误选成 `steamcommunity.com` 的可用 IP。候选变多后测速总预算从 12s 提到 20s，单个探测仍受 4s 超时限制。

### 内置候选 IP 与维护

采集于 2026-11-15，来自开发机 `getent ahostsv4 <域名>` 与 TCP/TLS 连通性实测，写在 `HostsAccelerator.java` 文件头注释与 `SERVICES` 常量里：Steam 社区 / Web API / 商店 / 图片 CDN / GitHub 共 5 组。更新方式：在开发机执行 `getent ahostsv4 <域名>`，挑出能完成 TLS 握手的 IP 填进 `candidates`，**不要写死为唯一值**。

> **`steamcommunity.com` 的内置候选刻意留空**：原值 `162.220.12.226` 实测 TCP `:443` 直接 `ConnectionRefused`。保留一个必然失败的 IP 更糟 —— `mappedIp()` 会返回它、命中计数照涨、UI 看起来「加速已生效」，实际请求秒拒后回退到被投毒的 IPv6 卡满 15s。清空后，未测速时该域名行为与系统 DNS 完全一致（安全网第 1 条），可用 IP 由「跨组复用」+「用户自定义条目」两条路径供给。

### 作用范围（如实标注）

| 生效 | 不生效 |
| --- | --- |
| 列表 / 详情（`HttpClient`）、Steam CDN 下载（javaSteam 的 `OkHttpClient`） | WebView 自行加载的远程缩略图、javaSteam 的 Steam CM 长连接（不经 OkHttp 的 `Dns`） |

另外：设置里填了「网络代理（host:port）」时，OkHttp 会让代理服务器负责 DNS，自定义 `Dns` 不会被调用 —— **hosts 加速与网络代理互斥**，两者不要同时开。

### 故障定位：`SocketTimeoutException: failed to connect … after 15000ms`

典型报错（未登录时抓 `steamcommunity.com/workshop/browse/` 的网页回退路径）：

```text
加载失败：SocketTimeoutException: failed to connect to
steamcommunity.com/2a03:2880:f136:83:face:b00c:0:25de (port 443)
from /2409:8962:... (port 43828) after 15000ms
```

| 观察点 | 判定 |
| --- | --- |
| `2a03:2880::/29` | 属 **AS32934 Meta/Facebook**（`face:b00c` 是其接口标识），Valve 在 AS32590/Akamai，`steamcommunity.com` 绝不该解析到这里 → **DNS 投毒** |
| `from /2409:8962:...` | 运营商 IPv6，说明系统 DNS 返回被投毒的 **AAAA**，Android 优先走 IPv6 撞上脏地址 |
| `after 15000ms` | 精确等于 `HttpClient` 的 `connectTimeout(15, SECONDS)`，说明是 TCP 连接阶段超时、请求确实经过 Java 层 |
| 目标是域名直连 :443 | 可排除「设置了 HTTP 代理」（走代理时目标应是代理地址） |

排查顺序：① 看 ⚡ 页「命中次数」是否 > 0（=0 说明总开关没开或该服务被关）；② 看卡片是否为「⚠ 测速无可用 IP」；③ 看「已优选」数量是否少于启用服务数；④ 在自定义条目填一个本地区可用 IP 后重测。注意**未登录**时列表只能走 `steamcommunity.com` 网页抓取这条最容易被污染的路径；登录后的 `QueryFiles` 走 Steam CM 长连接，不受本 `Dns` 影响，是另一条通道。

## 界面与图标

- UI 采用深空底 + 青→紫品牌渐变的覆盖式设计层（`style.css` 末尾「设计系统覆盖层」v2 / v3），保留原有全部 class/id 结构；加速页带首次使用引导横幅（app 内引导与本文成对交付）。
- 桌面图标由 `tools/gen_icon.py` **用代码绘制**（不依赖 AI 生成图片）：深空渐变底 + 渐变圆角块 + 播放三角，与顶栏品牌块、CSS 配色一致。产出 5 个密度的 `ic_launcher` / `ic_launcher_round` / adaptive 前后景，以及 `mipmap-anydpi-v26` 的自适应图标 XML。改配色需同时改 `style.css` 的 `--cyan` / `--violet` / `--grad` 与 `gen_icon.py` 的 `BRAND_C0` / `BRAND_C1`。

### v3 设计层（依据 Material 3 / Apple HIG 审查结果）

审查按 6 个维度做，v3 只针对查出的真实问题，**不重做视觉风格**。仍纯覆盖，不改 class/id、不改 JS 依赖的 DOM 结构。

| 审查发现 | 依据 | v3 的处理 |
| --- | --- | --- |
| 缩略图 `aspect-ratio: 1/1` 把壁纸裁成正方形 | 壁纸预览原生 16:9 | 改 `16 / 10`，兼顾真实比例与两列密度 |
| 加载只有一行「加载中…」 | 骨架屏降低感知等待 | 新增 `#skeleton` + `.skel-*` shimmer，6 张占位卡，网格参数与 `#list` 对齐避免跳动 |
| `.icon-btn` 32px 触控区偏小 | Apple HIG ≥44px、Material ≥48dp | `::before { inset: -6px }` 把热区扩到 44px，**视觉尺寸不变**，顶栏不会被挤 |
| 字号 10~17px 八级混用 | 类型系统应收敛 | 收成 `--fs-caption/secondary/body/title` 4 级 |
| 无焦点环、无入场、无图片淡入 | M3 状态层与动效规范 | 新增 `:focus-visible` 焦点环、卡片交错入场（前 8 张递增 30ms，之后固定 240ms）、缩略图 `onload` 淡入 |
| 语义色硬编码散落 | M3 语义角色 | 收进 `--success/--warning/--danger` 及对应 `-dim` 容器色；另补 `--surface-1/2/3`、`--sp-1..6`（4pt 网格）、`--ease-*` / `--dur-*` |

**对比度用 WCAG 官方公式实测**（不是估的）：

| 文本 | 对比度 | 判定 |
| --- | --- | --- |
| 搜索框占位文字 改前 `#6b7385` | **3.54:1** | ✗ 不达 AA（本次唯一真正不达标项，已修） |
| 搜索框占位文字 改后 `#93a0b8` | 6.39:1（v2 合成底上 6.73:1） | ✓ AA |
| 副标题 `.sub` 改前 `#7d859a` | 4.82:1 | ✓ AA（本来就达标，提亮属于优化而非修复） |
| 副标题 `.sub` 改后 `#9aa7c0` | 7.33:1 | ✓ AAA |
| 正文 `#eaf0ff` / 次要 `#98a4bd` | 17.01:1 / 7.74:1 | ✓ AAA |
| 错误 / 成功 / 警告语义色 | 8.94 / 12.18 / 13.53:1 | ✓ AAA |
| 焦点环 `--cyan`（UI 组件需 ≥3:1） | 11.09:1 | ✓ AAA |

两条工程约束：动画**只用 `transform` / `opacity`**（超分运行时 GPU 已经很忙，不做 blur / box-shadow 动画）；新增 `@media (prefers-reduced-motion: reduce)` 关掉全部动效并直接显示图片，尊重系统的「减少动态效果」设置。

图片加载失败时**不**加 `.is-loaded`，让 `.thumb` 的渐变占位底露出来——比 WebView 默认破图图标好看，国内网络下缩略图失败很常见。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 35、build-tools 35.0.0）。在根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

然后：

```bash
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

> 仓库里的 Gradle Wrapper 指向腾讯云镜像（`mirrors.cloud.tencent.com`），国内网络可直接拉取 Gradle 发行版；如需官方地址，改 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 即可。

主要依赖：`in.dragonbra:javasteam`（Steam 协议）、OkHttp、protobuf-java、BouncyCastle、zstd-jni、xz、lz4，以及原画播放内核 `androidx.media3:media3-exoplayer`。旧 WebGL 超分链路**不新增任何第三方依赖**，纯 WebView + WebGL2 + Java 标准库。

### GitHub Actions 自动出包

仓库内置 `.github/workflows/build-release.yml`，无需本地装 Android SDK 也能出包：

| 触发方式 | 行为 |
| --- | --- |
| push 到 `main`（仅改文档 / `tools/` 不触发） | 自动执行 `assembleRelease` 并发布 / 更新 Release |
| Actions 页手动 `Run workflow` | 可填版本号重跑；留空则取 `app/build.gradle` 的 `versionName` |

流水线：Temurin JDK 17 → Android SDK 35 / build-tools 35.0.0 → **仅 CI 内**把 wrapper 的腾讯云镜像地址换成官方源（不写回仓库，不影响本地 `./gradlew`）→ `assembleRelease` → `apksigner verify` 确认已签名 → 重命名为 `we-workshop-v<版本>.apk` 并生成 `.sha256` → 上传到 Release `v<版本>`（同名附件覆盖）→ 另存 14 天构建 artifact 便于排查。

产物在仓库 **Releases** 页下载。当前 `versionName = 0.5.7`，对应 tag `v0.5.7`；升级版本只需改 `app/build.gradle` 的 `versionCode` / `versionName` 后 push。

> **签名现状**：`app/build.gradle` 的 `release` 变体沿用 `signingConfigs.debug`（与仓库既有配置一致），CI 上会自动生成 debug keystore，`assembleRelease` 无需任何 Secrets 即可跑通。APK 可直接安装，但**不是正式发布签名**：每次 CI 生成的密钥不同，覆盖安装前需先卸载旧版本，也不适合上架应用商店。如需正式签名，需自行配置 `signingConfigs` 并把 keystore 以加密 Secrets 注入——`*.jks` / `*.keystore` 已被 `.gitignore` 排除，切勿把 keystore 或密码提交进仓库。

## 资源再生成

`app/src/main/assets/sr/` 下的 `shaders.js` / `weights.js` / `manifest.js` 是**自动生成文件，勿手改**。上游原始素材在 `tools/sources/`（GLSL + ONNX），生成脚本在 `tools/gen_assets.py`。

```bash
# 依赖（仅开发机需要，App 运行时离线）
python3 -m pip install --break-system-packages numpy onnx onnxruntime

# 在仓库根目录执行
python3 tools/gen_assets.py
```

新增或替换 shader：把 `.glsl` 放进 `tools/sources/` 对应子目录 → 在 `gen_assets.py` 的 `FILES` 登记 → 在 `TIERS` 的 `chain` 引用 → 重跑脚本。`hook.js` 会自动按 `//!` 指令解析，通常无需改 JS。

### 桌面图标

`res/mipmap-*` 下的 PNG 由 `tools/gen_icon.py` 生成，**勿手改**：

```bash
python3 -m pip install --break-system-packages pillow numpy
python3 tools/gen_icon.py
```

脚本是确定性的，重复运行产出 byte-identical；配色常量与设计约定见「界面与图标」。

## 验证工具（改动超分代码后请全部跑一遍）

| 命令 | 作用 | 通过标准 |
| --- | --- | --- |
| `python3 tools/verify_ajn.py` | 用 ONNX Runtime 作基准，逐层验证 AnimeJaNai GLSL 卷积链数值等价 | 10 层 + 最终输出 maxdiff < 1e-4 |
| `python3 tools/verify_shaders.py` | mesa EGL/GLES3 **真实编译 + link** 所有翻译后的 shader | `compile FAIL 0` 且 `link FAIL 0` |
| `python3 tools/gen_assets.py` | 重生成资源 | 与仓库内文件 byte-identical |
| `python3 tools/gen_icon.py` | 重生成桌面图标 | 与仓库内 PNG byte-identical |

`verify_shaders.py` 需要 Linux + mesa 的 `libEGL` / `libGLESv2` + Node（WSL / Linux CI 均可）；它内部调用 `tools/export_shaders.js` 从 `hook.js` / `animejanai.js` / `renderer.js` 导出真正喂给 WebGL2 的源码，覆盖所有档位链与 MRT 变体。Android 真机仍需在 WebView 里做一次目视验收。

## 技术栈

- Java + Kotlin（仅 Steam 相关用 Kotlin）
- 单 Activity + WebView，前端为 `assets/` 下的 HTML/CSS/JS
- `javaSteam` 负责 Steam 登录（含 Steam Guard）与 CDN 下载
- 播放：`LocalVideoServer`（回环 HTTP + Range/206 + CORS）→ `<video>` → WebGL2 纹理
- 边下边看：`StreamRegistry`（逐 chunk 水位登记 / await-notify / MP4 box 顺序探测）+ `LocalVideoServer` 的 `/stream/<itemId>`（Range 裁剪 + 独立线程池）
- 超分：`sr/hook.js`（mpv hook → GLSL ES 3.00 翻译）、`sr/animejanai.js`（ONNX → GLSL 卷积链）、`sr/renderer.js`（FBO ping-pong）、`sr/controller.js`（看门狗/降档）、`sr/surface.js`（尺寸锁定）
- 加速：`HostsAccelerator`（okhttp3.Dns + TLS/SNI 测速优选）、`accel.js`（服务卡片 / 开关 / 自定义 hosts 条目）

## 第三方许可

| 组件 | 许可 | 来源 |
| --- | --- | --- |
| Anime4K GLSL shaders | MIT | [bloc97/Anime4K](https://github.com/bloc97/Anime4K) |
| AMD FidelityFX FSR1 (EASU/RCAS) | MIT | [FidelityFX-SDK](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK) |
| FSRCNNX_x2_8_0_4_1 | LGPL-3.0 | [hooke007/mpv_PlayKit](https://github.com/hooke007/mpv_PlayKit) |
| 2x AnimeJaNai SuperUltraCompact | CC-BY-NC-SA-4.0 | [OpenModelDB](https://openmodeldb.info/models/2x-AnimeJaNai-Standard-v1-SuperUltraCompact) |

完整清单（含每个文件的 sha256 / bytes / source）在 `app/src/main/assets/sr/manifest.js`，运行时也会在播放器列表页展示。

## 说明

- 仅供个人学习与自用；创意工坊内容版权归原作者所有，请勿二次分发
- 下载受保护的源文件需要用自己的 Steam 账号登录
- 本地视频服务只绑定 `127.0.0.1`、带随机 token 与白名单，不对外暴露、不上传任何数据
- 本项目与 Valve / Wallpaper Engine 官方无关

---

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
