package com.github.catvod.crawler;

import android.util.Log;

/** TVBox 爬虫调试日志（兼容层，spider jar 内部调用） */
public class SpiderDebug {

    public static void log(String msg) {
        Log.d("JaySpider", msg);
    }

    public static void log(Throwable t) {
        Log.w("JaySpider", t);
    }
}
