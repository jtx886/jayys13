package com.github.catvod.crawler;

import android.text.TextUtils;
import android.util.Log;

/**
 * 影视仓爬虫调试日志（宿主层）
 * spider jar 内的爬虫普遍调用 SpiderDebug.log()，宿主必须提供同名类，
 * 否则触发 NoClassDefFoundError 导致整站搜索失败。
 */
public class SpiderDebug {

    private static final String TAG = "Spider";

    public static void log(Throwable th) {
        if (th != null) Log.w(TAG, Log.getStackTraceString(th));
    }

    public static void log(String msg) {
        if (!TextUtils.isEmpty(msg)) Log.d(TAG, msg);
    }

    public static void log(String tag, String msg, Object... args) {
        if (!TextUtils.isEmpty(msg)) Log.d(tag, String.format(msg, args));
    }
}
