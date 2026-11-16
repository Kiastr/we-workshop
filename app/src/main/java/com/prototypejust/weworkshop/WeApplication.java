package com.prototypejust.weworkshop;

import android.app.Application;

/**
 * 进程入口。
 *
 * <p>目前只做一件事：安装崩溃记录器。放在 Application 而不是 MainActivity，
 * 是因为播放页、下载服务都可能在没有主界面参与的情况下出问题，越早装越不容易漏。
 */
public class WeApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
