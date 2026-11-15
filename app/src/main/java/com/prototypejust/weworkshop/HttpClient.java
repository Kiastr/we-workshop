package com.prototypejust.weworkshop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * App 内 Java 层 HTTP 客户端。
 *
 * 原先基于 HttpURLConnection；为了支持 hosts 加速迁移到 OkHttp。
 * 原因：OkHttp 的 Dns 接口允许在「URL 保持原域名」的前提下指定解析到的 IP，
 * SNI 与证书校验依旧按原域名进行；而 HttpURLConnection 想把域名换成 IP，
 * 只能改 URL，会直接导致 HTTPS 证书域名校验失败。
 *
 * 对外 get/post 签名、UA、请求头、以及「响应体强制按 UTF-8 解码」的行为与旧实现一致，
 * 所有调用方（Bridge.browse / WorkshopParser.fetchDetails 等）无需改动。
 * gzip 由 OkHttp 自动协商并解压，因此不再手动设置 Accept-Encoding。
 *
 * 注意：若用户在设置里填了 HTTP 代理，OkHttp 会让代理服务器负责 DNS，
 * 此时自定义 Dns 不会被调用 —— hosts 加速与「网络代理」是互斥的两条路径。
 */
public final class HttpClient {
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36 WEWorkshop/0.1";

    private static final MediaType FORM =
            MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8");

    /** 手动设置的 HTTP 代理（来自 App 设置）；null 表示直连。 */
    private static volatile Proxy httpProxy = null;
    private static volatile OkHttpClient client = null;

    private HttpClient() {
    }

    private static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (HttpClient.class) {
                c = client;
                if (c == null) {
                    OkHttpClient.Builder b = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .dns(HostsAccelerator.dns());
                    Proxy p = httpProxy;
                    if (p != null) b.proxy(p);
                    c = b.build();
                    client = c;
                }
            }
        }
        return c;
    }

    public static void setProxy(Proxy proxy) {
        httpProxy = proxy;
        client = null; // 下次请求按新代理重建连接池
    }

    public static Proxy getProxy() {
        return httpProxy;
    }

    public static void setProxyHostPort(String hostPort) {
        if (hostPort == null || hostPort.trim().isEmpty()) {
            setProxy(null);
            return;
        }
        String v = hostPort.trim();
        int idx = v.lastIndexOf(':');
        if (idx <= 0 || idx == v.length() - 1) {
            setProxy(null);
            return;
        }
        String host = v.substring(0, idx).trim();
        int port;
        try {
            port = Integer.parseInt(v.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            setProxy(null);
            return;
        }
        setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
    }

    public static String get(String url) throws IOException {
        return request(url, null);
    }

    public static String post(String url, String body) throws IOException {
        return request(url, body);
    }

    private static String request(String urlStr, String postBody) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (postBody != null) {
            rb.post(RequestBody.create(postBody, FORM));
        }
        Response resp = client().newCall(rb.build()).execute();
        try {
            ResponseBody b = resp.body();
            if (b == null) {
                throw new IOException("HTTP " + resp.code() + " (empty body)");
            }
            // bytes() 会消费并关闭 body；与原实现一样强制 UTF-8，不受服务端 charset 影响。
            byte[] bytes = b.bytes();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            return new String(bytes, "UTF-8");
        } finally {
            resp.close();
        }
    }
}
