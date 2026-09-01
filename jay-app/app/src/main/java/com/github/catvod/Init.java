package com.github.catvod;

import android.content.Context;

import java.lang.ref.WeakReference;

/** 影视仓全局 Context 持有（spider jar 的 Init / Path / Prefers 依赖此类） */
public class Init {

    private WeakReference<Context> context;

    private static Init get() {
        return Loader.INSTANCE;
    }

    public static void set(Context context) {
        get().context = new WeakReference<>(context);
    }

    public static Context context() {
        return get().context.get();
    }

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }
}
