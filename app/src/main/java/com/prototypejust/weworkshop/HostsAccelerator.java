package com.prototypejust.weworkshop;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Dns;

/**
 * App 内 hosts 加速引擎 —— 参考 Watt Toolkit 的「域名 → 优选 IP」思路。
 *
 * <h3>为什么不是 VpnService / 改系统 hosts</h3>
 * Watt Toolkit 在 PC 上靠改系统 hosts 或本地反向代理生效。Android 上等价做法有两种，
 * 但都与本项目冲突：
 * <ul>
 *   <li>改 /etc/hosts：需要 root，绝大多数用户没有。</li>
 *   <li>VpnService 接管 DNS：Android 同时只允许一个 VPN，会顶掉用户为了访问 Steam
 *       已经开启的 VPN/TUN（见 README「开着 VPN 但 Steam 登录失败」），反而让 App 彻底连不上。</li>
 * </ul>
 * 因此这里把 hosts 映射放进 <b>App 自己的 DNS 解析层</b>（okhttp3.Dns）：
 * 只影响本 App 的 Java 层请求，不申请任何权限、不抢占 VPN、不需要 root。
 *
 * <h3>TLS 为什么不会坏</h3>
 * OkHttp 的 Dns 只决定「连到哪个 IP」，SNI 与证书校验依旧使用 URL 里的原始域名。
 * 所以映射 IP 必须是真的部署了目标域名证书的 CDN 边缘节点。为此本引擎的测速
 * 不是简单 ping，而是 <b>TCP connect + 带 SNI 的 TLS 握手 + HostnameVerifier 校验</b>，
 * 只有整套通过的候选才会被选为优选 IP。
 *
 * <h3>三重安全网</h3>
 * <ol>
 *   <li>默认关闭：开启前不会改动任何解析行为。</li>
 *   <li>lookup() 把「映射 IP」放在首位、把系统 DNS 解析结果作为后续备选地址，
 *       映射 IP 连不上时 OkHttp 会自动尝试下一个 route，不会把请求打死。</li>
 *   <li>内置候选 IP 只是起点：不同地区/运营商的最优边缘不同，且 IP 会变，
 *       必须以 App 内「一键测速优选」的结果为准，或由用户填写自定义条目。</li>
 * </ol>
 *
 * <h3>内置候选 IP 的采集方法（便于日后自行维护）</h3>
 * 采集于 2026-11-15，来源为开发机本地 DNS 解析（getent ahostsv4）与 TCP/TLS 连通性实测：
 * <pre>
 *   steamcommunity.com              162.220.12.226
 *   api.steampowered.com            104.119.189.147 / .161 / .170   (Akamai)
 *   store.steampowered.com          2.19.102.132                     (Akamai)
 *   steamuserimages-a.akamaihd.net  2.18.244.199 / .221              (Akamai)
 *   github.com                      20.27.177.113 / 140.82.114.4     (实测 HTTP 200)
 * </pre>
 * 更新方式：在开发机执行 {@code getent ahostsv4 <域名>}，把结果里能完成
 * TLS 握手的 IP 填进 {@link #SERVICES} 的 candidates 即可；不要写死为唯一值。
 * 项目实际访问的域名清单见 README「数据源与原理」。
 *
 * <h3>作用范围（如实标注，不要夸大）</h3>
 * 生效：HttpClient 的列表/详情请求、javaSteam 的 Steam CDN 下载（两者都挂了本 Dns）。
 * 不生效：WebView 自行加载的远程缩略图、javaSteam 的 Steam CM 长连接（不经 OkHttp 的 Dns）。
 */
public final class HostsAccelerator {

    private static final String PREFS = "weworkshop";
    private static final String K_ENABLED = "accel_enabled";
    private static final String K_DISABLED = "accel_disabled_services";
    private static final String K_CUSTOM = "accel_custom";
    private static final String K_PICKS = "accel_picks";

    private static final int PROBE_TIMEOUT_MS = 4000;
    private static final int PROBE_PORT = 443;

    /** 一组可独立开关的加速服务（对应 UI 上的卡片）。 */
    public static final class Service {
        public final String id;
        public final String name;
        public final String desc;
        /** 命中判据：精确相等或以 "." + domain 结尾（覆盖子域）。 */
        public final String[] domains;
        /** 候选 IP，仅作测速起点，不是固定答案。 */
        public final String[] candidates;

        Service(String id, String name, String desc, String[] domains, String[] candidates) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.domains = domains;
            this.candidates = candidates;
        }
    }

    /**
     * 内置规则表。顺序即 UI 展示顺序。
     * 注意：只放「候选 IP 归属明确」的域名；多个域名共用一个 IP 时由 TLS 校验兜底，
     * 证书不匹配就不会被选中，也不会被写入 picks。
     */
    private static final Service[] SERVICES = new Service[]{
            new Service("steam_community", "Steam 社区 / 创意工坊",
                    "列表网页解析、详情页、工坊浏览",
                    new String[]{"steamcommunity.com"},
                    new String[]{"162.220.12.226"}),
            new Service("steam_api", "Steam Web API",
                    "GetPublishedFileDetails 详情接口",
                    new String[]{"api.steampowered.com"},
                    new String[]{"104.119.189.147", "104.119.189.161", "104.119.189.170"}),
            new Service("steam_store", "Steam 商店",
                    "商店页面与跳转链接",
                    new String[]{"store.steampowered.com"},
                    new String[]{"2.19.102.132"}),
            new Service("steam_cdn", "Steam 图片 CDN",
                    "封面 / GIF 预览图域名（WebView 缩略图不走此层）",
                    new String[]{"steamuserimages-a.akamaihd.net"},
                    new String[]{"2.18.244.199", "2.18.244.221"}),
            new Service("github", "GitHub",
                    "项目更新、模型与 shader 资源来源",
                    new String[]{"github.com"},
                    new String[]{"20.27.177.113", "140.82.114.4"}),
    };

    private static final class Pick {
        final String ip;
        final long ms;
        final long ts;

        Pick(String ip, long ms) {
            this.ip = ip;
            this.ms = ms;
            this.ts = System.currentTimeMillis();
        }
    }

    private static final class Probe {
        final String domain;
        final String ip;
        final long ms;
        final boolean ok;
        final String why;

        Probe(String domain, String ip, long ms, boolean ok, String why) {
            this.domain = domain;
            this.ip = ip;
            this.ms = ms;
            this.ok = ok;
            this.why = why;
        }
    }

    private static volatile boolean enabled = false;
    private static final Set<String> disabled = Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, String> custom = Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private static final Map<String, Pick> picks = Collections.synchronizedMap(new LinkedHashMap<String, Pick>());
    private static final AtomicLong hits = new AtomicLong();
    private static SharedPreferences prefs;

    private HostsAccelerator() {
    }

    /** 在 MainActivity.onCreate 里、任何网络请求之前调用一次。 */
    public static synchronized void init(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean(K_ENABLED, false);
        disabled.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString(K_DISABLED, "[]"));
            for (int i = 0; i < a.length(); i++) disabled.add(a.optString(i));
        } catch (Exception ignored) {
        }
        custom.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString(K_CUSTOM, "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                custom.put(normalize(k), o.optString(k));
            }
        } catch (Exception ignored) {
        }
        picks.clear();
        try {
            JSONObject o = new JSONObject(prefs.getString(K_PICKS, "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = o.optJSONObject(k);
                if (v == null) continue;
                String ip = v.optString("ip", "");
                if (!ip.isEmpty()) picks.put(normalize(k), new Pick(ip, v.optLong("ms", -1)));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 唯一的 Dns 实例，同时挂到 HttpClient 与 javaSteam 的 OkHttpClient 上。
     * 未命中规则时行为与系统 DNS 完全一致。
     */
    private static final Dns DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            String ip = mappedIp(hostname);
            List<InetAddress> system;
            try {
                system = Dns.SYSTEM.lookup(hostname);
            } catch (UnknownHostException e) {
                system = Collections.emptyList();
            }
            if (ip == null) {
                if (system.isEmpty()) throw new UnknownHostException(hostname);
                return system;
            }
            // 命中：映射 IP 优先，系统解析结果作为后续备选 route（OkHttp 失败自动回退）。
            hits.incrementAndGet();
            List<InetAddress> out = new ArrayList<InetAddress>();
            out.add(InetAddress.getByName(ip));
            for (InetAddress a : system) {
                if (!ip.equals(a.getHostAddress())) out.add(a);
            }
            return out;
        }
    };

    public static Dns dns() {
        return DNS;
    }

    private static String mappedIp(String hostname) {
        if (!enabled || hostname == null) return null;
        final String h = normalize(hostname);
        // 1) 用户自定义条目优先级最高，独立于服务卡片开关。
        String c = custom.get(h);
        if (c != null && !c.isEmpty()) return c;
        // 2) 内置服务组：有测速结果用测速结果，否则用第一个候选（仍有系统 DNS 兜底）。
        for (Service s : SERVICES) {
            if (disabled.contains(s.id)) continue;
            for (String d : s.domains) {
                if (h.equals(d) || h.endsWith("." + d)) {
                    Pick p = picks.get(d);
                    if (p != null) return p.ip;
                    return s.candidates.length > 0 ? s.candidates[0] : null;
                }
            }
        }
        return null;
    }

    private static String normalize(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.US);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    // ---------------------------------------------------------------- 配置读写

    public static String configJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("enabled", enabled);
            root.put("hits", hits.get());
            root.put("note", "hosts 映射只作用于本 App 的 Java 层请求；"
                    + "WebView 远程缩略图与 Steam 长连接不受影响。"
                    + "建议先「一键测速优选」再开启，IP 失效会自动回退系统解析。");

            JSONArray arr = new JSONArray();
            for (Service s : SERVICES) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("desc", s.desc);
                o.put("domains", join(s.domains));
                o.put("candidates", join(s.candidates));
                o.put("on", !disabled.contains(s.id));
                Pick p = picks.get(s.domains[0]);
                o.put("picked", p == null ? "" : p.ip);
                o.put("ms", p == null ? -1 : p.ms);
                arr.put(o);
            }
            root.put("services", arr);

            JSONArray cr = new JSONArray();
            synchronized (custom) {
                for (Map.Entry<String, String> e : custom.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("domain", e.getKey());
                    o.put("ip", e.getValue());
                    cr.put(o);
                }
            }
            root.put("custom", cr);
            return root.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    public static String setEnabled(boolean on) {
        enabled = on;
        if (prefs != null) prefs.edit().putBoolean(K_ENABLED, on).apply();
        return "ok";
    }

    public static String setService(String id, boolean on) {
        boolean found = false;
        for (Service s : SERVICES) if (s.id.equals(id)) found = true;
        if (!found) return "err:未知服务";
        if (on) disabled.remove(id);
        else disabled.add(id);
        saveDisabled();
        return "ok";
    }

    public static String addRule(String domain, String ip) {
        String d = normalize(domain);
        String v = ip == null ? "" : ip.trim();
        if (!d.matches("[a-z0-9._-]+") || !d.contains(".")) return "err:域名格式不对";
        if (!isIpLiteral(v)) return "err:请填 IPv4 / IPv6 地址，不要填域名";
        custom.put(d, v);
        saveCustom();
        return "ok";
    }

    public static String removeRule(String domain) {
        custom.remove(normalize(domain));
        saveCustom();
        return "ok";
    }

    public static String clearPicks() {
        picks.clear();
        if (prefs != null) prefs.edit().putString(K_PICKS, "{}").apply();
        return "ok";
    }

    /** 纯字面量校验，刻意不调用 InetAddress.getByName：那会把非法字符串当主机名去查 DNS。 */
    private static boolean isIpLiteral(String v) {
        if (v.isEmpty()) return false;
        if (v.indexOf(':') < 0) {
            // IPv4：必须正好 4 段、每段 0-255
            String[] p = v.split("\\.", -1);
            if (p.length != 4) return false;
            for (String s : p) {
                if (s.isEmpty() || s.length() > 3 || !s.matches("\\d+")) return false;
                if (Integer.parseInt(s) > 255) return false;
            }
            return true;
        }
        // IPv6：只允许十六进制与冒号，且至少含一个冒号
        return v.matches("[0-9a-fA-F:]+");
    }

    private static void saveDisabled() {
        if (prefs == null) return;
        JSONArray a = new JSONArray();
        synchronized (disabled) {
            for (String s : disabled) {
                try {
                    a.put(s);
                } catch (Exception ignored) {
                    // org.json.JSONException 是受检异常，逐个吞掉即可（正常字符串不会抛）
                }
            }
        }
        prefs.edit().putString(K_DISABLED, a.toString()).apply();
    }

    private static void saveCustom() {
        if (prefs == null) return;
        JSONObject o = new JSONObject();
        synchronized (custom) {
            for (Map.Entry<String, String> e : custom.entrySet()) {
                try {
                    o.put(e.getKey(), e.getValue());
                } catch (Exception ignored) {
                }
            }
        }
        prefs.edit().putString(K_CUSTOM, o.toString()).apply();
    }

    private static void savePicks() {
        if (prefs == null) return;
        JSONObject o = new JSONObject();
        synchronized (picks) {
            for (Map.Entry<String, Pick> e : picks.entrySet()) {
                try {
                    JSONObject v = new JSONObject();
                    v.put("ip", e.getValue().ip);
                    v.put("ms", e.getValue().ms);
                    v.put("ts", e.getValue().ts);
                    o.put(e.getKey(), v);
                } catch (Exception ignored) {
                }
            }
        }
        prefs.edit().putString(K_PICKS, o.toString()).apply();
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(a[i]);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 测速优选

    /**
     * 对所有启用服务的候选 IP（含系统 DNS 解析结果）与自定义条目做并发 TLS 探测，
     * 每个域名取延迟最低且校验通过者写入 picks。同步方法，调用方必须在后台线程执行。
     */
    public static String benchmark() {
        long t0 = System.currentTimeMillis();
        final List<String[]> pairs = new ArrayList<String[]>();
        for (Service s : SERVICES) {
            if (disabled.contains(s.id)) continue;
            for (String d : s.domains) {
                for (String ip : candidatesFor(s, d)) pairs.add(new String[]{d, ip});
            }
        }
        synchronized (custom) {
            for (Map.Entry<String, String> e : custom.entrySet()) {
                pairs.add(new String[]{e.getKey(), e.getValue()});
            }
        }

        List<Callable<Probe>> tasks = new ArrayList<Callable<Probe>>();
        for (final String[] pair : pairs) {
            tasks.add(new Callable<Probe>() {
                @Override
                public Probe call() {
                    return probe(pair[0], pair[1]);
                }
            });
        }

        List<Probe> results = new ArrayList<Probe>();
        if (!tasks.isEmpty()) {
            ExecutorService ex = Executors.newFixedThreadPool(Math.min(8, Math.max(2, tasks.size())));
            try {
                List<Future<Probe>> fs = ex.invokeAll(tasks, 12, TimeUnit.SECONDS);
                for (Future<Probe> f : fs) {
                    try {
                        if (!f.isCancelled() && f.get() != null) results.add(f.get());
                    } catch (Exception ignored) {
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                ex.shutdownNow();
            }
        }

        // 每个域名只保留最快且通过校验的候选；全部失败则不写 picks（保持系统解析兜底）。
        Map<String, Probe> best = new LinkedHashMap<String, Probe>();
        for (Probe p : results) {
            if (!p.ok) continue;
            Probe old = best.get(p.domain);
            if (old == null || p.ms < old.ms) best.put(p.domain, p);
        }
        for (Map.Entry<String, Probe> e : best.entrySet()) {
            picks.put(normalize(e.getKey()), new Pick(e.getValue().ip, e.getValue().ms));
        }
        savePicks();

        try {
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("elapsed", System.currentTimeMillis() - t0);
            root.put("total", pairs.size());
            root.put("picked", best.size());
            JSONArray arr = new JSONArray();
            for (Probe p : results) {
                JSONObject o = new JSONObject();
                o.put("domain", p.domain);
                o.put("ip", p.ip);
                o.put("ms", p.ms);
                o.put("ok", p.ok);
                o.put("why", p.why);
                arr.put(o);
            }
            root.put("results", arr);
            return root.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static List<String> candidatesFor(Service s, String domain) {
        List<String> out = new ArrayList<String>();
        for (String ip : s.candidates) if (!out.contains(ip)) out.add(ip);
        // 把系统解析结果也纳入对比：如果直连本来就最快，测速结果会等于「无需加速」。
        try {
            for (InetAddress a : Dns.SYSTEM.lookup(domain)) {
                String ip = a.getHostAddress();
                if (ip != null && !out.contains(ip)) out.add(ip);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 单个候选探测：TCP connect → 带 SNI 的 TLS 握手 → 证书域名校验。
     * 只做 TCP connect 是不够的：CDN 边缘 IP 可能能连上却没有目标域名的证书，
     * 那样映射后会直接 SSLHandshakeException，比不加速更糟。
     */
    private static Probe probe(String domain, String ip) {
        long t0 = System.currentTimeMillis();
        SSLSocket sock = null;
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            sock = (SSLSocket) f.createSocket();
            try {
                SSLParameters p = sock.getSSLParameters();
                // 显式指定元素类型为 SNIServerName：Collections.singletonList 会推断成
                // List<SNIHostName>，而 setServerNames 要的是 List<SNIServerName>（泛型不协变）。
                p.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(domain)));
                sock.setSSLParameters(p);
            } catch (Throwable ignored) {
                // 极少数 ROM 不支持 SNIHostName，退化为不带 SNI 的握手（后续校验仍会失败）。
            }
            sock.connect(new InetSocketAddress(ip, PROBE_PORT), PROBE_TIMEOUT_MS);
            sock.setSoTimeout(PROBE_TIMEOUT_MS);
            sock.startHandshake();
            SSLSession sess = sock.getSession();
            boolean verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(domain, sess);
            long ms = System.currentTimeMillis() - t0;
            if (!verified) return new Probe(domain, ip, ms, false, "证书域名不匹配");
            return new Probe(domain, ip, ms, true, "ok");
        } catch (Throwable t) {
            return new Probe(domain, ip, System.currentTimeMillis() - t0, false,
                    t.getClass().getSimpleName());
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
