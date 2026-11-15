package com.prototypejust.weworkshop;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「边下边看」的下载水位登记表（进程内单例，纯 Java，不依赖 Android Context）。
 *
 * ---------------------------------------------------------------------------
 * 这个文件解决什么问题
 *   WorkshopDownloader 把 Steam CDN 的 depot chunk 解密后按 {@code chunk.offset}
 *   用 RandomAccessFile 稀疏写入 cacheDir/workshop/&lt;itemId&gt;/ 下的 staging 文件。
 *   4 路并行写不同 offset，文件中间可能是「空洞」（尾巴先写满、长度已等于最终大小），
 *   因此 **不能用 outFile.length() 判断可读范围**——这和下载器里断点续传不能只看
 *   文件长度是同一个坑（见 WorkshopDownloader.verifyOrThrow 上方的注释）。
 *
 *   本类改为逐 chunk 登记区间 [offset, offset+length)，再算出「从 0 起连续可读的
 *   字节数」= waterline。LocalVideoServer 的流式端点只读 waterline 以内的字节，
 *   超出部分阻塞等待，于是天然形成「下载多快、播多快」的渐进式播放。
 *
 * 为什么不改下载顺序
 *   WorkshopDownloader 用 sortedBy(offset) 后 groups[i % 4] 交错分配，每组内部
 *   按 offset 递增串行下载。模拟验证：连续前缀几乎跟随总完成量推进（92%~100%），
 *   所以 **4 路并行、断点续传、SHA-1 校验、copyToDownloads 全部原样保留**，
 *   只在 raf.write 之后加一行 mark()，下载速度不变。
 *
 * 为什么需要 moov 探测
 *   mp4 的元数据 moov 若在文件尾部（非 faststart），&lt;video&gt; 必须读到文件尾
 *   才能起播，边下边看对该文件本质上不成立。这是容器格式决定的，代码绕不过。
 *   所以运行时扫一遍文件头的 box 顺序：先遇到 moov → 可流式；先遇到 mdat →
 *   不可流式，UI 直接禁用按钮并提示「需下载完再播」。
 *   Steam 创意工坊对视频壁纸只放行 .mp4（商店页与官方 Wiki 一致），本地文件才
 *   支持 webm/avi 等；本实现对非 mp4 一律保守判为不可流式。
 *
 * 为什么需要成品源切换
 *   下载完成后 WorkshopDownloader 会 staging.deleteRecursively()，正在播放的
 *   staging 文件会被删掉。所以 finish() 会把源切到 copyToDownloads 落盘的成品
 *   （Q+ 是 content:// Uri，26~28 是绝对路径），此后流式端点改读成品，
 *   播放不中断、也不会重复占用一份磁盘。
 *
 * ---------------------------------------------------------------------------
 * 导入方法（未来自主维护）
 *   下载侧（WorkshopDownloader.kt）：
 *     begin(itemId, stagingFile, total, name, mime, title, img)   开始下载时
 *     mark(itemId, offset, length)                                 每个 chunk 写完后
 *     finish(itemId, finalSource, content)                         落盘完成、删 staging 前
 *     abort(itemId)                                                 取消 / 最终失败
 *   服务侧（LocalVideoServer.java）：
 *     get(itemId) / await(session, wantEnd, timeoutMs) / statusJson(itemId, base)
 *   前端：Bridge.streamingVideo(itemId) 返回 {ok,ready,url,reason,...}。
 * ---------------------------------------------------------------------------
 */
public final class StreamRegistry {

    /** 起播所需的最小连续可读字节数：moov + 一点 mdat。避免刚开头就起播反复缓冲。 */
    public static final long MIN_START = 512L * 1024;

    /** moov 探测结果：未定 / 不可流式 / 可流式。 */
    public static final int FAST_UNKNOWN = -1;
    public static final int FAST_NO = 0;
    public static final int FAST_YES = 1;

    private static final ConcurrentHashMap<Long, Session> SESSIONS =
            new ConcurrentHashMap<Long, Session>();

    private StreamRegistry() { }

    /** 一个正在（或刚刚）下载的条目。字段同包可见，供 LocalVideoServer 直接读。 */
    public static final class Session {
        final long itemId;
        final String name, mime, title, img;
        volatile long total;
        volatile File staging;
        volatile long waterline;
        volatile int faststart = FAST_UNKNOWN;
        volatile boolean done;
        volatile boolean failed;

        /** offset -> length。只在 lock 内访问，避免 4 路并发写坏 TreeMap。 */
        final TreeMap<Long, Long> ranges = new TreeMap<Long, Long>();
        /** wait/notify 的监视器：下载线程 notifyAll，HTTP 线程 wait。 */
        final Object lock = new Object();

        Session(long itemId, String name, String mime, String title, String img,
                long total, File staging) {
            this.itemId = itemId;
            this.name = name == null ? "" : name;
            this.mime = mime == null ? "video/mp4" : mime;
            this.title = title == null ? "" : title;
            this.img = img == null ? "" : img;
            this.total = total;
            this.staging = staging;
        }
    }

    /** 开始（或重试）一次下载。
     *  重试时【复用同一个 Session 对象】：已建立的 HTTP 连接正持有它的 lock 在 wait()，
     *  若换一个新对象，旧连接永远收不到 notifyAll，只能干等超时。复用时清空区间重新
     *  累计（staging 可能被 verifyOrThrow 删掉重建），并唤醒等待者，让它们重新按水位取数。 */
    public static Session begin(long itemId, File staging, long total,
                                String name, String mime, String title, String img) {
        Session s = SESSIONS.get(itemId);
        if (s != null && !s.done && !s.failed) {
            synchronized (s.lock) {
                s.ranges.clear();
                s.waterline = 0;
                s.faststart = FAST_UNKNOWN;
                s.total = total;
                s.staging = staging;
                s.lock.notifyAll();
            }
            return s;
        }
        s = new Session(itemId, name, mime, title, img, total, staging);
        SESSIONS.put(itemId, s);
        // 轻量清理：只保留最近 24 个条目，避免长时间使用后内存缓慢增长。
        if (SESSIONS.size() > 24) {
            for (Map.Entry<Long, Session> e : SESSIONS.entrySet()) {
                if (e.getValue().failed) SESSIONS.remove(e.getKey());
            }
        }
        return s;
    }

    public static Session get(long itemId) {
        return SESSIONS.get(itemId);
    }

    /** 供下载器在任务结束时判断是否需要 abort：已 finish 的 session 不能被误标为失败。
     *  （Session 的字段是包级可见，steam 子包无法直接读，故提供静态访问器。） */
    public static boolean isDone(long itemId) {
        Session s = SESSIONS.get(itemId);
        return s != null && s.done;
    }

    /** 供 LocalVideoServer.listJson() 把「下载中」条目附加到播放列表。
     *  已完成 / 已取消的不返回：成品会由 MediaStore 枚举出来，无需重复。 */
    public static java.util.List<Session> activeSessions() {
        java.util.List<Session> out = new java.util.ArrayList<Session>();
        for (Session s : SESSIONS.values()) {
            if (!s.done && !s.failed) out.add(s);
        }
        return out;
    }

    /** 登记一个已写入的区间并重算连续水位。由 WorkshopDownloader 在 raf.write 之后调用。 */
    public static void mark(long itemId, long offset, long length) {
        Session s = SESSIONS.get(itemId);
        if (s == null || length <= 0) return;
        synchronized (s.lock) {
            s.ranges.put(offset, length);
            recompute(s);
            s.lock.notifyAll();
        }
    }

    /** 从 0 起把重叠/相邻区间串起来，得到连续可读前缀。 */
    private static void recompute(Session s) {
        long w = 0;
        for (Map.Entry<Long, Long> e : s.ranges.entrySet()) {
            long start = e.getKey();
            if (start > w) break;                 // 出现空洞，后面再连续也没用
            long end = start + e.getValue();
            if (end > w) w = end;
        }
        s.waterline = Math.min(w, s.total);
    }

    /** 下载完成：水位拉满、唤醒所有等待中的 HTTP 连接。
     *  成品源不在这里登记 —— staging 会被 deleteRecursively() 删掉，
     *  LocalVideoServer 在 done 之后复用已有的 MediaStore 枚举按文件名找成品，
     *  这样 copyToDownloads 的签名和逻辑完全不用改。 */
    public static void finish(long itemId) {
        Session s = SESSIONS.get(itemId);
        if (s == null) return;
        synchronized (s.lock) {
            s.waterline = s.total;
            s.done = true;
            s.lock.notifyAll();
        }
    }


    /** 取消 / 最终失败：唤醒等待连接，让它们尽快返回错误而不是挂到超时。幂等。 */
    public static void abort(long itemId) {
        Session s = SESSIONS.get(itemId);
        if (s == null) return;
        synchronized (s.lock) {
            s.failed = true;
            s.lock.notifyAll();
        }
    }

    /** 阻塞等待水位达到 wantEnd，或下载完成/失败/超时。不持有 socket 读锁，故
     *  s.setSoTimeout 不会影响这里。返回 true 表示水位足够或已结束。 */
    public static boolean await(Session s, long wantEnd, long timeoutMs) {
        if (s == null) return false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (s.lock) {
            while (s.waterline < wantEnd && !s.done && !s.failed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    s.lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** 每次状态查询时刷新 moov 探测；一旦得出确定结论就缓存，不再重复读文件。 */
    public static int faststartOf(Session s) {
        if (s == null) return FAST_NO;
        int known = s.faststart;
        if (known != FAST_UNKNOWN) return known;
        int r = probeFaststart(s);
        if (r != FAST_UNKNOWN) s.faststart = r;
        return r;
    }

    /**
     * 扫 mp4 顶层 box 顺序判断 moov 是否前置。
     * 只需每个 box 的 8 字节头可读即可推进，不要求 payload 全部到位，
     * 因此在下载很早期（几百 KB）就能得出结论。
     */
    private static int probeFaststart(Session s) {
        String n = s.name.toLowerCase(Locale.US);
        // 非 mp4 容器（webm/mkv/mov/avi...）：EBML/QuickTime 的索引也可能在尾部，
        // A 档保守处理，统一要求下载完再播，避免假可播。
        if (!n.endsWith(".mp4") && !n.endsWith(".m4v")) return FAST_NO;
        File f = s.staging;
        if (f == null || !f.isFile()) return FAST_UNKNOWN;
        long water = s.waterline;
        if (water < 8) return FAST_UNKNOWN;

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] h = new byte[16];
            long off = 0;
            for (int i = 0; i < 64; i++) {
                if (off + 8 > water) return FAST_UNKNOWN;      // 头部还没下够，待定
                raf.seek(off);
                raf.readFully(h, 0, 8);
                long size = u32(h, 0);
                String type = new String(h, 4, 4, StandardCharsets.US_ASCII);
                if (size == 1) {                              // 64 位 largesize
                    if (off + 16 > water) return FAST_UNKNOWN;
                    raf.seek(off + 8);
                    raf.readFully(h, 8, 8);
                    size = u64(h, 8);
                } else if (size == 0) {                       // box 延伸到文件尾
                    size = s.total - off;
                }
                if ("moov".equals(type)) return FAST_YES;     // moov 在 mdat 之前 → 可流式
                if ("mdat".equals(type)) return FAST_NO;      // 先到 mdat → moov 在尾部
                if (size < 8 || off + size > s.total) return FAST_NO;
                off += size;
                if (off >= s.total) return FAST_NO;
            }
            return FAST_NO;
        } catch (IOException e) {
            return FAST_UNKNOWN;
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) { }
        }
    }

    private static long u32(byte[] b, int p) {
        return ((long) (b[p] & 0xff) << 24) | ((long) (b[p + 1] & 0xff) << 16)
                | ((long) (b[p + 2] & 0xff) << 8) | (long) (b[p + 3] & 0xff);
    }

    private static long u64(byte[] b, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (long) (b[p + i] & 0xff);
        return v;
    }

    /** 供 Bridge.streamingVideo() 直接返回给前端的 JSON。base 为 Loopback 服务根 URL。 */
    public static String statusJson(long itemId, String base) {
        Session s = SESSIONS.get(itemId);
        JSONObject o = new JSONObject();
        try {
            if (s == null) {
                o.put("ok", false);
                o.put("reason", "该条目当前没有可预览的下载任务");
                return o.toString();
            }
            int fast = faststartOf(s);
            boolean ready = fast == FAST_YES && !s.failed && !s.done
                    && (s.waterline >= MIN_START || s.waterline >= s.total);
            String reason;
            if (s.failed) reason = "下载已取消或失败，无法预览";
            else if (s.done) reason = "已下载完成，请到 ▶ 播放列表观看";
            else if (fast == FAST_NO) reason = "该视频的索引在文件尾部，需下载完才能播放";
            else if (fast == FAST_UNKNOWN) reason = "正在探测视频结构…";
            else if (!ready) reason = "正在等待已下载数据达到起播阈值…";
            else reason = "可边下边播";

            o.put("ok", true);
            o.put("ready", ready);
            o.put("faststart", fast);
            o.put("done", s.done);
            o.put("failed", s.failed);
            o.put("itemId", s.itemId);
            o.put("name", s.name);
            o.put("title", s.title);
            o.put("img", s.img);
            o.put("mime", s.mime);
            o.put("total", s.total);
            o.put("waterline", s.waterline);
            o.put("reason", reason);
            o.put("url", base + "stream/" + itemId);
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false)
                        .put("reason", "状态读取失败：" + e.getMessage()).toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"reason\":\"status failed\"}";
            }
        }
        return o.toString();
    }
}
