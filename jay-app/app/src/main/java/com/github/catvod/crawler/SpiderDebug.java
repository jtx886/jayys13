package com.github.catvod.crawler;

import android.util.Log;

/**
 * TVBox / 影视仓爬虫调试日志（兼容层）
 * spider jar 内的爬虫普遍调用 SpiderDebug.log()，宿主必须提供同名类，
 * 否则触发 NoClassDefFoundError 导致整站搜索失败。
 */
public class SpiderDebug {

    private static final String TAG = "Spider";

    public static void log(String msg) {
        Log.d(TAG, msg);
    }

    public static void log(Throwable th) {
        Log.w(TAG, th);
    }
}
