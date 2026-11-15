package com.prototypejust.weworkshop;

import android.content.Context;
import android.content.ContentUris;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.database.Cursor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 本地视频服务：把 Download/Wallpaper WE/ 下已下载的视频以 HTTP 流提供给 WebView 播放。
 *
 * ---------------------------------------------------------------------------
 * 为什么是「本地 HTTP 服务器」而不是 shouldInterceptRequest（重要，别改回去）
 *   WebViewClient.shouldInterceptRequest 对普通子资源（js/css/img/xhr）有效，但
 *   HTML5 <video> 的媒体请求在 Android WebView 里走 MediaPlayer 的独立数据通道，
 *   历史上不经过该回调（见 StackOverflow 8999712 / 42216685）。若靠拦截喂视频流，
 *   某些 WebView 版本会直接播放失败且无任何回调，排查成本极高。
 *   起一个只绑 127.0.0.1 的轻量 HTTP 服务，media 请求就是真实网络请求，行为稳定，
 *   并且天然支持 Range/206（HTML5 video 拖动进度条必需）。
 *
 * 为什么页面是 file:// 却用 http:// 资源
 *   index.html 仍由 file:///android_asset/index.html 加载（保持现状，最小改动）。
 *   视频用 http://127.0.0.1:<port>/... ，前端给 <video> 设 crossorigin="anonymous"，
 *   本服务回 Access-Control-Allow-Origin: * ，于是 WebGL 用 texImage2D(video)
 *   把帧上传成纹理时不会污染 canvas —— 这是超分能工作的前提。
 *   AndroidManifest 已声明 usesCleartextTraffic=true 且 network_security_config
 *   允许明文，loopback HTTP 不会被系统拦截。
 *
 * 安全约束（逐条勿删）
 *   1. ServerSocket 只绑 InetAddress.getByName("127.0.0.1")，不监听 0.0.0.0，
 *      设备外部无法访问。
 *   2. 端口由系统分配（new ServerSocket(0, ...)），避免固定端口冲突/被其他 app 抢占。
 *   3. 每次进程启动生成随机 token 作为 URL 路径前缀，不匹配的请求一律 403，
 *      防止本机其他进程/网页猜到地址。
 *   4. 只服务 listJson() 枚举出来的白名单 id（"ms"+MediaStore._ID / "fs"+hash），
 *      URL 里不含任何文件路径，也不解析 ../ ，杜绝路径穿越与任意文件读取。
 *      流式端点 /stream/<itemId> 的 id 是纯数字，来自 StreamRegistry 的活跃下载，
 *      同样不含路径。
 *   5. 只接受 GET / HEAD / OPTIONS，其他 405。
 *
 * ---------------------------------------------------------------------------
 * 边下边看（/stream/<itemId>）—— 与成品播放的区别
 *   成品播放读已完整落盘的文件；流式播放读「正在被 4 路并行稀疏写入」的 staging 文件，
 *   绝不能按文件长度判断可读范围（尾部可能先写满、中间是空洞，见
 *   WorkshopDownloader 的注释）。改由 StreamRegistry 逐 chunk 登记的「连续可读水位」
 *   决定：把 Range 的 end 裁剪到 waterline-1，超出部分 await() 等待下载推进或超时。
 *   Content-Range 的 total 始终是完整文件大小，播放器据此显示总时长。
 *
 *   阻塞式等待会长期占用线程，所以 /stream/ 走独立的 streamPool，不占死成品播放的
 *   pool（4 线程）。下载完成后 staging 会被 deleteRecursively() 删除，此时按文件名
 *   从 MediaStore 找到成品 Entry，后续 Range 直接复用 serveVideo() 走成品，播放不中断。
 *
 *   限制：mp4 的 moov 若在文件尾部（非 faststart），播放器必须先读到尾部才能起播，
 *   边下边看对该文件不成立。StreamRegistry 运行时扫描 box 顺序探测，前端据
 *   statusJson().ready 决定按钮是否可用（见 Bridge.streamingVideo）。
 *
 * ---------------------------------------------------------------------------
 * 导入方法（未来自主维护）
 *   Bridge.localVideos() 是成品列表入口：懒启动本服务 -> 重新枚举视频 -> 返回 JSON。
 *   Bridge.streamingVideo(itemId) 是边下边看状态入口，返回 {ok,ready,url,reason,...}。
 *   前端拿 items[].url 直接给 <video src>，拿 base 做其他同源用途。
 *   新增视频格式：在 VIDEO_EXTS / mimeOf() 里登记即可。
 *   服务是进程级单例、daemon 线程，无需在 Activity 生命周期里显式 stop。
 * ---------------------------------------------------------------------------
 */
public final class LocalVideoServer {
    private static final String TAG = "LocalVideoServer";
    private static final String HOST = "127.0.0.1";
    private static final int BUF = 64 * 1024;
    /** 流式请求等待新数据的最长时间。够覆盖一次慢 chunk，又不至于让播放器一直挂着。 */
    private static final long STREAM_WAIT_MS = 12000L;
    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "webm", "mov", "mkv", "avi", "m4v", "wmv"));

    private static volatile LocalVideoServer INSTANCE;

    /** 一个可播放条目。uri 是 content:// 或绝对路径；绝不会把路径拼进 URL。 */
    private static final class Entry {
        final String name, mime, title, img, uri;
        final long size;
        final boolean content;

        Entry(String name, String mime, String title, String img, String uri,
              long size, boolean content) {
            this.name = name; this.mime = mime; this.title = title;
            this.img = img; this.uri = uri; this.size = size; this.content = content;
        }
    }

    private final Context app;
    private final String token;
    private final int port;
    private final ServerSocket server;
    private final ExecutorService pool;
    /** 流式请求专用线程池：会阻塞等待下载水位，不能占用成品播放的 pool。 */
    private final ExecutorService streamPool;
    /** volatile 整体替换，避免 refresh 与请求处理竞争时读到半更新状态。 */
    private volatile Map<String, Entry> entries = Collections.emptyMap();
    /** 已解析的「下载完成 → 成品」映射，避免每个 Range 都重扫 MediaStore。 */
    private final ConcurrentHashMap<Long, Entry> finishedCache =
            new ConcurrentHashMap<Long, Entry>();

    public static synchronized LocalVideoServer get(Context ctx) {
        if (INSTANCE == null) {
            try {
                INSTANCE = new LocalVideoServer(ctx);
            } catch (IOException e) {
                Log.e(TAG, "启动本地视频服务失败", e);
                return null;
            }
        }
        return INSTANCE;
    }

    private LocalVideoServer(Context ctx) throws IOException {
        app = ctx.getApplicationContext();
        token = UUID.randomUUID().toString().replace("-", "");
        // 只绑定回环地址；backlog 8 足够（一个页面通常 1~2 个媒体连接）
        server = new ServerSocket(0, 8, InetAddress.getByName(HOST));
        port = server.getLocalPort();
        pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "LocalVideo");
                t.setDaemon(true);
                return t;
            }
        });
        // 流式请求可能阻塞等待下载，单独开池；最多 3 路并发预览足够，且不会饿死成品播放。
        streamPool = Executors.newFixedThreadPool(3, new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "LocalVideoStream");
                t.setDaemon(true);
                return t;
            }
        });
        Thread acceptor = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "LocalVideoAccept");
        acceptor.setDaemon(true);
        acceptor.start();
        Log.i(TAG, "listening on " + baseUrl());
    }

    public String baseUrl() {
        return "http://" + HOST + ":" + port + "/" + token + "/";
    }

    /** 重新枚举并列出所有可播放视频。Bridge.localVideos() 调它。 */
    public synchronized String listJson() {
        try {
            refresh();
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("base", baseUrl());
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                Entry e = me.getValue();
                JSONObject o = new JSONObject();
                o.put("id", me.getKey());
                o.put("name", e.name);
                o.put("title", e.title);
                o.put("img", e.img);
                o.put("size", e.size);
                o.put("mime", e.mime);
                o.put("url", baseUrl() + "video/" + me.getKey());
                arr.put(o);
            }
            // 边下边看：把「正在下载」的条目也列进播放列表，url 指向流式端点。
            // 字段与成品 item 对齐，另带 streaming/ready/waterline/total/reason 供前端标注状态。
            for (StreamRegistry.Session ses : StreamRegistry.activeSessions()) {
                JSONObject st = new JSONObject(
                        StreamRegistry.statusJson(ses.itemId, baseUrl()));
                JSONObject o = new JSONObject();
                o.put("id", "stream_" + ses.itemId);
                o.put("name", st.optString("name"));
                o.put("title", st.optString("title"));
                o.put("img", st.optString("img"));
                o.put("size", st.optLong("total"));
                o.put("mime", st.optString("mime"));
                o.put("url", st.optString("url"));
                o.put("streaming", true);
                o.put("ready", st.optBoolean("ready"));
                o.put("faststart", st.optInt("faststart"));
                o.put("waterline", st.optLong("waterline"));
                o.put("total", st.optLong("total"));
                o.put("reason", st.optString("reason"));
                arr.put(o);
            }
            root.put("items", arr);
            return root.toString();
        } catch (Throwable t) {
            Log.e(TAG, "枚举视频失败", t);
            try {
                return new JSONObject().put("ok", false)
                        .put("error", String.valueOf(t.getMessage())).toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":\"list failed\"}";
            }
        }
    }

    /** 枚举 Download/Wallpaper WE/ 下的视频文件。
     *  Android Q+ 走 MediaStore（下载是 WorkshopDownloader 用 MediaStore 写入的）；
     *  26~28 走旧的公共 Download 目录直接扫描（WRITE_EXTERNAL_STORAGE 已声明到 28）。 */
    private void refresh() {
        DownloadStore.init(app);
        JSONArray downloads = DownloadStore.list();
        LinkedHashMap<String, Entry> next = new LinkedHashMap<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.MIME_TYPE,
                    MediaStore.Downloads.DATE_MODIFIED
            };
            String sel = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
            String[] args = {"Download/Wallpaper WE%"};
            Cursor c = null;
            try {
                c = app.getContentResolver().query(collection, proj, sel, args,
                        MediaStore.Downloads.DATE_MODIFIED + " DESC");
                if (c == null) return;
                int iId = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                int iName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                int iSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                int iMime = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);
                int iDate = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
                while (c.moveToNext()) {
                    String name = c.getString(iName);
                    String mime = c.getString(iMime);
                    if (!isVideo(name, mime)) continue;
                    long id = c.getLong(iId);
                    long when = c.getLong(iDate) * 1000L; // DATE_MODIFIED 单位是秒
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    String[] meta = softMeta(downloads, when, name);
                    next.put("ms" + id, new Entry(name, mimeOf(name, mime), meta[0], meta[1],
                            uri.toString(), c.getLong(iSize), true));
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore 查询失败: " + t.getMessage());
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) { }
            }
        } else {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Wallpaper WE");
            File[] fs = dir.listFiles();
            if (fs != null) {
                // 按修改时间倒序，最近的排前面
                Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : fs) {
                    if (!f.isFile() || !isVideo(f.getName(), null)) continue;
                    String key = "fs" + Integer.toHexString(f.getName().hashCode());
                    String[] meta = softMeta(downloads, f.lastModified(), f.getName());
                    next.put(key, new Entry(f.getName(), mimeOf(f.getName(), null), meta[0], meta[1],
                            f.getAbsolutePath(), f.length(), false));
                }
            }
        }
        entries = Collections.unmodifiableMap(next);
        Log.i(TAG, "refresh: " + next.size() + " videos");
    }

    /** 软关联下载记录：视频文件本身不带工坊 id，用「落盘时间 ±5 分钟」找最近的
     *  已完成记录，借它的 title/img 展示。找不到就退回文件名，不影响播放。 */
    private static String[] softMeta(JSONArray downloads, long when, String fileName) {
        String title = stripExt(fileName), img = "";
        long best = Long.MAX_VALUE;
        for (int i = 0; i < downloads.length(); i++) {
            JSONObject o = downloads.optJSONObject(i);
            if (o == null || !"done".equals(o.optString("status"))) continue;
            long d = Math.abs(o.optLong("time") - when);
            if (d < best && d < 5L * 60 * 1000) {
                best = d;
                String t = o.optString("title", "");
                if (!t.isEmpty()) title = t;
                img = o.optString("img", "");
            }
        }
        return new String[]{title, img};
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    private static boolean isVideo(String name, String mime) {
        if (mime != null && mime.startsWith("video/")) return true;
        if (name == null) return false;
        int d = name.lastIndexOf('.');
        if (d < 0) return false;
        return VIDEO_EXTS.contains(name.substring(d + 1).toLowerCase(Locale.US));
    }

    private static String mimeOf(String name, String declared) {
        if (declared != null && declared.startsWith("video/") && !declared.contains("*")) {
            return declared;
        }
        int d = name == null ? -1 : name.lastIndexOf('.');
        String ext = d >= 0 ? name.substring(d + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "webm": return "video/webm";
            case "mkv":  return "video/x-matroska";
            case "mov":  return "video/quicktime";
            case "avi":  return "video/x-msvideo";
            case "wmv":  return "video/x-ms-wmv";
            case "m4v":
            case "mp4":
            default:     return "video/mp4";
        }
    }

    // ---------------- HTTP 层 ----------------

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() {
                    @Override public void run() { handle(s); }
                });
            } catch (IOException e) {
                if (!server.isClosed()) Log.w(TAG, "accept: " + e.getMessage());
                break;
            }
        }
    }

    private void handle(Socket s) {
        InputStream raw = null;
        OutputStream out = null;
        boolean handedOff = false;
        try {
            s.setSoTimeout(15000);
            raw = s.getInputStream();
            out = s.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(raw, StandardCharsets.ISO_8859_1), 2048);
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];

            // 读请求头（尤其 Range）
            Map<String, String> headers = new LinkedHashMap<>();
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) {
                    headers.put(h.substring(0, c).trim().toLowerCase(Locale.US),
                            h.substring(c + 1).trim());
                }
            }

            String prefix = "/" + token + "/";
            if (!path.startsWith(prefix)) {
                writeStatus(out, 403, "Forbidden", "text/plain", "forbidden");
                return;
            }
            if ("OPTIONS".equals(method)) {
                // <video crossorigin> 带 Range 头会触发 CORS 预检，必须放行
                writeOptions(out);
                return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeStatus(out, 405, "Method Not Allowed", "text/plain", "method not allowed");
                return;
            }
            String sub = path.substring(prefix.length());
            if (sub.startsWith("stream/")) {
                // 边下边看：交给独立线程池（可能阻塞等待下载水位）。
                String sid = sub.substring("stream/".length());
                int sq = sid.indexOf('?');
                if (sq >= 0) sid = sid.substring(0, sq);
                try { sid = URLDecoder.decode(sid, "UTF-8"); } catch (Exception ignored) { }
                final long itemId;
                try {
                    itemId = Long.parseLong(sid);
                } catch (NumberFormatException nfe) {
                    writeStatus(out, 404, "Not Found", "text/plain", "bad stream id");
                    return;
                }
                final String fMethod = method;
                final String fRange = headers.get("range");
                final OutputStream fOut = out;
                final Socket fSocket = s;
                handedOff = true;   // socket 所有权转交 streamPool，finally 不再关闭
                streamPool.execute(new Runnable() {
                    @Override public void run() {
                        serveStream(fOut, fSocket, fMethod, itemId, fRange);
                    }
                });
                return;
            }
            if (!sub.startsWith("video/")) {
                writeStatus(out, 404, "Not Found", "text/plain", "not found");
                return;
            }
            String id = sub.substring("video/".length());
            int q = id.indexOf('?');
            if (q >= 0) id = id.substring(0, q);
            try { id = URLDecoder.decode(id, "UTF-8"); } catch (Exception ignored) { }
            Entry e = entries.get(id);
            if (e == null) {
                writeStatus(out, 404, "Not Found", "text/plain", "unknown video id");
                return;
            }
            serveVideo(out, s, method, e, headers.get("range"));
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t.getMessage());
        } finally {
            if (!handedOff) {
                try { if (raw != null) raw.close(); } catch (Throwable ignored) { }
                try { if (out != null) out.close(); } catch (Throwable ignored) { }
                try { s.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 边下边看：/stream/&lt;itemId&gt; 的处理，运行在 streamPool 线程。
     *  与 serveVideo 的区别只有两点：数据源是「正在稀疏写入」的 staging 文件；
     *  可读上界不是文件长度而是 StreamRegistry 的连续水位。 */
    private void serveStream(final OutputStream out, final Socket sock, final String method,
                             final long itemId, final String rangeHeader) {
        // socket 所有权已从 handle() 转交过来，这里必须自己收尾（handle 的 finally 被跳过）。
        try {
            StreamRegistry.Session ses = StreamRegistry.get(itemId);
            if (ses == null) {
                writeStatus(out, 404, "Not Found", "text/plain", "no streaming session");
                return;
            }
            if (ses.failed) {
                writeStatus(out, 410, "Gone", "text/plain", "download cancelled or failed");
                return;
            }
            // 下载完成：staging 已被 deleteRecursively() 删掉，按文件名找到 MediaStore 成品，
            // 直接复用 serveVideo 的完整 Range/206/CORS 逻辑，播放无缝续上。
            if (ses.done) {
                Entry fin = resolveFinished(itemId, ses.name, ses.total);
                if (fin != null) {
                    serveVideo(out, sock, method, fin, rangeHeader);
                    return;
                }
                // 极端情况：MediaStore 尚未可见。若 staging 恰好还在就继续读，否则报错。
                if (ses.staging == null || !ses.staging.isFile()) {
                    writeStatus(out, 503, "Service Unavailable", "text/plain",
                            "成品尚未就绪，请稍后重试");
                    return;
                }
            }

            long total = ses.total;
            if (total <= 0) {
                writeStatus(out, 503, "Service Unavailable", "text/plain", "size unknown");
                return;
            }

            // ---- 解析 Range（与 serveVideo 保持一致） ----
            long start = 0, end = total - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String spec = rangeHeader.substring(6).trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash > 0) start = Long.parseLong(spec.substring(0, dash).trim());
                    if (dash >= 0 && dash < spec.length() - 1) {
                        end = Long.parseLong(spec.substring(dash + 1).trim());
                    }
                } catch (NumberFormatException ignored) { }
                if (start < 0) start = 0;
                if (end >= total) end = total - 1;
                partial = true;
            }
            if (start > end || start >= total) {
                writeStatus(out, 416, "Range Not Satisfiable", "text/plain", "bytes */" + total);
                return;
            }

            // ---- 等数据：目标是一段有意义的连续字节（最多 256KB），不是整个文件 ----
            // 若等到整个 end 才返回，open-ended 的 "bytes=0-" 会退化成「下完才播」。
            if (!ses.done) {
                long want = Math.min(total, start + 256L * 1024);
                StreamRegistry.await(ses, want, STREAM_WAIT_MS);
            }
            if (ses.failed && !ses.done) {
                writeStatus(out, 410, "Gone", "text/plain", "download cancelled or failed");
                return;
            }
            long water = ses.done ? total : ses.waterline;
            long realEnd = Math.min(end, water - 1);
            if (realEnd < start || water <= start) {
                // 该区间还没下到（常见于拖动进度条到未下载区域）：让播放器稍后重试。
                writeRetry(out);
                return;
            }

            File f = ses.staging;
            if (f == null || !f.isFile()) {
                writeStatus(out, 503, "Service Unavailable", "text/plain", "staging missing");
                return;
            }

            long len = realEnd - start + 1;
            StringBuilder sb = new StringBuilder();
            sb.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(mimeOf(ses.name, null)).append("\r\n");
            sb.append("Content-Length: ").append(len).append("\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            if (partial) {
                // total 必须是【完整文件大小】，播放器据此显示总时长；返回区间则被裁剪到水位。
                sb.append("Content-Range: bytes ").append(start).append('-')
                        .append(realEnd).append('/').append(total).append("\r\n");
            }
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: Range\r\n");
            sb.append("Access-Control-Expose-Headers: Content-Range, Accept-Ranges, Content-Length\r\n");
            sb.append("Cache-Control: no-store\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));

            if (!"HEAD".equals(method)) {
                // 稀疏文件：用 RandomAccessFile 精确定位，且只读到 water 以内，
                // 绝不触碰中间尚未写入的空洞（否则就是花屏 / 全 0 数据）。
                RandomAccessFile raf = null;
                try {
                    raf = new RandomAccessFile(f, "r");
                    raf.seek(start);
                    byte[] buf = new byte[BUF];
                    long left = len;
                    while (left > 0) {
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    if (raf != null) try { raf.close(); } catch (Throwable ignored) { }
                }
            }
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "serveStream: " + t.getMessage());
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
            try { sock.close(); } catch (Throwable ignored) { }
        }
    }

    /** 下载完成后按文件名从 MediaStore 成品里找到对应 Entry，缓存以避免每个 Range 都重扫。
     *  缓存必须用 size 校验：同一 itemId 可能被重试下载，新成品的 _ID 与大小都可能变化，
     *  直接用旧 Entry 会读到已删除/旧文件。 */
    private Entry resolveFinished(long itemId, String name, long expectSize) {
        Entry cached = finishedCache.get(itemId);
        if (cached != null && (expectSize <= 0 || cached.size == expectSize)) return cached;
        refresh();   // 重新枚举：成品此时 IS_PENDING 已置 0，可见
        String target = baseName(name).toLowerCase(Locale.US);
        Entry fallback = null;
        for (Entry e : entries.values()) {
            if (!baseName(e.name).toLowerCase(Locale.US).equals(target)) continue;
            if (expectSize <= 0 || e.size == expectSize) {
                finishedCache.put(itemId, e);
                return e;
            }
            if (fallback == null) fallback = e;   // 同名但大小不符：留作兜底
        }
        if (fallback != null) finishedCache.put(itemId, fallback);
        return fallback;
    }

    private static String baseName(String n) {
        if (n == null) return "";
        int i = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        return i >= 0 ? n.substring(i + 1) : n;
    }

    /** 区间尚未下载到：503 + Retry-After，让 <video> 稍后重发 Range，而不是直接报错退出。 */
    private static void writeRetry(OutputStream out) throws IOException {
        String body = "waiting for download";
        out.write(("HTTP/1.1 503 Service Unavailable\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Retry-After: 1\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void serveVideo(OutputStream out, Socket s, String method,
                           Entry e, String rangeHeader) throws IOException {
        long total = e.size;
        InputStream in = null;
        ParcelFileDescriptor pfd = null;
        try {
            if (e.content) {
                // openFileDescriptor + FileChannel.position() 做精确 seek，
                // 比 InputStream.skip() 可靠（skip 不保证一次跳够）。
                pfd = app.getContentResolver().openFileDescriptor(Uri.parse(e.uri), "r");
                if (pfd == null) {
                    writeStatus(out, 404, "Not Found", "text/plain", "file missing");
                    return;
                }
                if (total <= 0) total = pfd.getStatSize();
                in = new FileInputStream(pfd.getFileDescriptor());
            } else {
                File f = new File(e.uri);
                if (!f.isFile()) {
                    writeStatus(out, 404, "Not Found", "text/plain", "file missing");
                    return;
                }
                if (total <= 0) total = f.length();
                in = new FileInputStream(f);
            }

            long start = 0, end = total - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String spec = rangeHeader.substring(6).trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash > 0) start = Long.parseLong(spec.substring(0, dash).trim());
                    if (dash >= 0 && dash < spec.length() - 1) {
                        end = Long.parseLong(spec.substring(dash + 1).trim());
                    }
                } catch (NumberFormatException ignored) { }
                if (start < 0) start = 0;
                if (end >= total) end = total - 1;
                if (start > end || start >= total) {
                    writeStatus(out, 416, "Range Not Satisfiable", "text/plain",
                            "bytes */" + total);
                    return;
                }
                partial = true;
            }
            long len = end - start + 1;
            if (start > 0) {
                ((FileInputStream) in).getChannel().position(start);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(e.mime).append("\r\n");
            sb.append("Content-Length: ").append(len).append("\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            if (partial) {
                sb.append("Content-Range: bytes ").append(start).append('-')
                        .append(end).append('/').append(total).append("\r\n");
            }
            // CORS：file:// 页面的 Origin 是 null，通配 * 可匹配；WebGL 才能安全取帧
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: Range\r\n");
            sb.append("Access-Control-Expose-Headers: Content-Range, Accept-Ranges, Content-Length\r\n");
            sb.append("Cache-Control: no-store\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));

            if (!"HEAD".equals(method)) {
                byte[] buf = new byte[BUF];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
            out.flush();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            // FileInputStream 关闭后 fd 已释放；pfd 再 close 一次做兜底
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        }
    }

    private static void writeOptions(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 204 No Content\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Range\r\n"
                + "Access-Control-Max-Age: 86400\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void writeStatus(OutputStream out, int code, String reason,
                                    String mime, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + mime + "; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
