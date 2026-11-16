package com.prototypejust.weworkshop;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把未捕获异常的堆栈写进内部存储，供下次启动时展示。
 *
 * <p>为什么需要它：播放器这类新页面出问题时，用户能给的只有"闪退"两个字，
 * 而 logcat 需要连电脑。写一份 crash.log 到应用私有目录，下次启动由 WebView 读出来，
 * 用户截图就能定位，不用再靠猜。
 *
 * <p>只记录，不吞异常：写完之后仍然交给系统默认处理器，进程该结束还是结束。
 * 否则应用会停在一个半死不活的状态，比闪退更难排查。
 */
public final class CrashLog {

    private static final String TAG = "WECrash";
    private static final String FILE_NAME = "crash.log";
    /** 最多保留的 crash.log 字节数；只留最近几次，避免无限增长。 */
    private static final long MAX_BYTES = 192 * 1024L;

    private CrashLog() {
    }

    /** 在 Application.onCreate 里调用一次。 */
    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(app, thread, error);
            } catch (Throwable ignored) {
                // 连崩溃都写不进去时，不能让日志逻辑再抛一次
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** 手动记录一次（用于 catch 住的启动失败，不一定是未捕获异常）。 */
    public static void write(Context context, Thread thread, Throwable error) {
        String text = format(thread, error);
        Log.e(TAG, text);
        try {
            File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
            if (file.exists() && file.length() > MAX_BYTES) {
                // 简单截断：超限就重写，不保留历史
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
            // 磁盘写失败不影响主流程
        }
    }

    /** 读取上次记录的崩溃信息，没有则返回空串。 */
    public static String read(Context context) {
        File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
        if (!file.exists() || file.length() == 0) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            // 只回传最后一段，避免一次塞爆 WebView
            byte[] all = bos.toByteArray();
            int from = Math.max(0, all.length - 6000);
            return new String(all, from, all.length - from, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context context) {
        File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String format(Thread thread, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        pw.println();
        pw.println("=== crash " + time + " ===");
        pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("thread: " + (thread == null ? "-" : thread.getName()));
        if (error != null) {
            error.printStackTrace(pw);
        } else {
            pw.println("(no throwable)");
        }
        pw.flush();
        return sw.toString();
    }
}
