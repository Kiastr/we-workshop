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
- **本地视频播放**：顶栏 ▶ 打开列表，播放 / 暂停、拖动进度、倍速、全屏、循环
- **真超分**：Anime4K / FSR / FSRCNNX / AnimeJaNai 共 7 档，实时 WebGL2 渲染，带生效确认、性能看门狗、阶梯降档与 A/B 对比

## 数据源与原理

- **列表**：登录后通过 Steam 统一消息服务 `PublishedFileService.QueryFiles` 查询（数据源与 WE 客户端一致）；未登录时回退到 `steamcommunity.com/workshop/browse` 网页解析（匿名可见条目会更少）
- **详情**：`ISteamRemoteStorage/GetPublishedFileDetails` 接口 + Steam 社区页面
- **下载**：`javaSteam` 建立 Steam 连接 → 获取 depot 密钥 / CDN 节点 / manifest 授权码 → 从 Steam CDN 下载 manifest 与分块 → AES 解密 + zstd/xz 解压 → 写入 `Download/Wallpaper WE/`
- **播放**：`LocalVideoServer` 在 `127.0.0.1` 起一个仅本机可访问的 HTTP 服务，把 `Download/Wallpaper WE/` 下的视频以 Range/206 流喂给隐藏的 `<video>`；视频帧上传为 WebGL 纹理，超分后画到 `<canvas>`
- **网络**：需要能访问 Steam（国内需代理或 VPN，手机开全局代理 / TUN 模式即可）；播放与超分全程离线，不联网

## 本地视频播放与真超分

点顶栏 **▶** 打开本地视频列表，选中即播放。播放页可切换档位、倍速、全屏，**按住「A/B 对比」显示原画**。

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

主要依赖：`in.dragonbra:javasteam`（Steam 协议）、OkHttp、protobuf-java、BouncyCastle、zstd-jni、xz、lz4。播放与超分**不新增任何第三方依赖**，纯 WebView + WebGL2 + Java 标准库。

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

## 验证工具（改动超分代码后请全部跑一遍）

| 命令 | 作用 | 通过标准 |
| --- | --- | --- |
| `python3 tools/verify_ajn.py` | 用 ONNX Runtime 作基准，逐层验证 AnimeJaNai GLSL 卷积链数值等价 | 10 层 + 最终输出 maxdiff < 1e-4 |
| `python3 tools/verify_shaders.py` | mesa EGL/GLES3 **真实编译 + link** 所有翻译后的 shader | `compile FAIL 0` 且 `link FAIL 0` |
| `python3 tools/gen_assets.py` | 重生成资源 | 与仓库内文件 byte-identical |

`verify_shaders.py` 需要 Linux + mesa 的 `libEGL` / `libGLESv2` + Node（WSL / Linux CI 均可）；它内部调用 `tools/export_shaders.js` 从 `hook.js` / `animejanai.js` / `renderer.js` 导出真正喂给 WebGL2 的源码，覆盖所有档位链与 MRT 变体。Android 真机仍需在 WebView 里做一次目视验收。

## 技术栈

- Java + Kotlin（仅 Steam 相关用 Kotlin）
- 单 Activity + WebView，前端为 `assets/` 下的 HTML/CSS/JS
- `javaSteam` 负责 Steam 登录（含 Steam Guard）与 CDN 下载
- 播放：`LocalVideoServer`（回环 HTTP + Range/206 + CORS）→ `<video>` → WebGL2 纹理
- 超分：`sr/hook.js`（mpv hook → GLSL ES 3.00 翻译）、`sr/animejanai.js`（ONNX → GLSL 卷积链）、`sr/renderer.js`（FBO ping-pong）、`sr/controller.js`（看门狗/降档）、`sr/surface.js`（尺寸锁定）

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
