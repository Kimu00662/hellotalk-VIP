package com.hellotalk.hook;

import android.app.Activity;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;

    /*
     * 不能写成：
     * new Handler(Looper.getMainLooper())
     * 放在静态字段里。
     *
     * LSPosed 加载模块类时主 Looper 可能还没准备好，
     * 会导致 ExceptionInInitializerError。
     */
    private static volatile Handler MAIN_HANDLER;

    private static final AtomicBoolean PENDING =
            new AtomicBoolean(false);

    private static WeakReference<Activity> pendingActivity =
            new WeakReference<>(null);

    private static WeakReference<Object> cachedSearchView =
            new WeakReference<>(null);

    private static volatile String pendingUsername;
    private static volatile long pendingStartTime;

    private static final long PENDING_TIMEOUT_MS = 15000L;

    @Override
    public void handleLoadPackage(
            final LoadPackageParam lpparam
    ) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        /*
         * 这里已经进入目标 App 进程后再初始化，
         * 不再放在类的静态初始化阶段。
         */
        try {
            MAIN_HANDLER = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            log("主线程 Handler 初始化失败: " + t);
        }

        // 每个功能独立保护，搜索 hook 失败不能影响 VIP 和翻译
        safe(MainHook::hookVip);
        safe(MainHook::hookTranslate);
        safe(MainHook::hookFilterVip);

        // 最终点击桥接
        safe(MainHook::hookSearchView);
        safe(MainHook::hookProfileClick);
        safe(MainHook::hookResolvedItem);

        log("=== HT FULL STABLE VERSION LOADED ===");
    }

    private interface HookTask {
        void run() throws Throwable;
    }

    private static void safe(HookTask task) {
        try {
            task.run();
        } catch (Throwable t) {
            log("Hook 异常: " + t);
            XposedBridge.log(t);
        }
    }

    private static Handler mainHandler() {
        Handler handler = MAIN_HANDLER;

        if (handler != null) {
            return handler;
        }

        synchronized (MainHook.class) {
            handler = MAIN_HANDLER;

            if (handler == null) {
                Looper looper = Looper.getMainLooper();

                if (looper == null) {
                    throw new IllegalStateException(
                            "Main Looper 尚未准备好"
                    );
                }

                handler = new Handler(looper);
                MAIN_HANDLER = handler;
            }
        }

        return handler;
    }

    // ============================================================
    // 假 VIP
    // ============================================================

    private static void hookVip() throws Throwable {
        Class<?> vipClass =
                XposedHelpers.findClass("xt.h", sCl);

        XposedHelpers.findAndHookMethod(
                vipClass,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        log("[VIP] xt.h.j() 被调用");
                    }

                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        log("[VIP] xt.h.j() 返回值强制为100");
                        param.setResult(100);
                    }
                }
        );

        log("假VIP hook OK");
    }

    /*
     * 高级搜索页面自己的 VIP 判断再加一层保险。
     */
    private static void hookFilterVip() throws Throwable {
        Class<?> filterVm =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.SearchFilterViewModelV2",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                filterVm,
                "isVip",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        log("[VIP] SearchFilterViewModelV2.isVip() -> true");
                        param.setResult(true);
                    }
                }
        );

        log("SearchFilterViewModelV2.isVip hook OK");
    }

    // ============================================================
    // 无限翻译
    // ============================================================

    private static void hookTranslate() throws Throwable {
        Class<?> translateClass =
                XposedHelpers.findClass("lx.o", sCl);

        XposedHelpers.findAndHookMethod(
                translateClass,
                "h",
                XC_MethodReplacement.returnConstant(true)
        );

        log("翻译 hook OK");
    }

    // ============================================================
    // 保存高级搜索页的 UserNameSearchView
    // ============================================================

    private static void hookSearchView() throws Throwable {
        Class<?> viewClass =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.widget.UserNameSearchView",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                viewClass,
                "L",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        cachedSearchView =
                                new WeakReference<>(param.thisObject);

                        log("[BRIDGE] UserNameSearchView.L() 已捕获");
                    }
                }
        );

        log("UserNameSearchView.L hook OK");
    }

    // ============================================================
    // 拦截高级搜索 userid=0 的点击
    // ============================================================

    private static void hookProfileClick() throws Throwable {
        Class<?> vmClass =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.viewmodel.SearchUserViewModel",
                        sCl
                );

        Class<?> activityClass =
                XposedHelpers.findClass(
                        "android.app.Activity",
                        sCl
                );

        Class<?> itemClass =
                XposedHelpers.findClass("rl0.e", sCl);

        XposedHelpers.findAndHookMethod(
                vmClass,
                "goToProfile",
                activityClass,
                itemClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            Activity activity =
                                    (Activity) param.args[0];

                            Object item = param.args[1];

                            int uid = readUid(item);
                            String username = readUsername(item);

                            log("[BRIDGE] 点击 uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 正常真实 ID 直接放行。
                             * 国籍、年龄、新用户等不受影响。
                             */
                            if (uid != 0) {
                                return;
                            }

                            if (isBlank(username)) {
                                log("[BRIDGE] userid=0 但 username为空，放行");
                                return;
                            }

                            /*
                             * 同时只处理一个 pending 点击。
                             */
                            if (!PENDING.compareAndSet(false, true)) {
                                log("[BRIDGE] 已有待处理请求，放行本次点击");
                                return;
                            }

                            pendingActivity =
                                    new WeakReference<>(activity);
                            pendingUsername = username;
                            pendingStartTime =
                                    System.currentTimeMillis();

                            /*
                             * 阻止原方法使用 user_id=0。
                             */
                            param.setResult(null);

                            Object searchView =
                                    findCurrentSearchView(activity);

                            if (searchView == null) {
                                log("[BRIDGE] 找不到 UserNameSearchView");
                                clearPending();
                                return;
                            }

                            final Object finalSearchView =
                                    searchView;

                            final String finalUsername =
                                    username;

                            /*
                             * 等待主线程当前点击事件结束，
                             * 再调用原生 M(username)。
                             */
                            mainHandler().postDelayed(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (!PENDING.get()) {
                                                    return;
                                                }

                                                log("[BRIDGE] 调用原生 M("
                                                        + finalUsername
                                                        + ")");

                                                XposedHelpers.callMethod(
                                                        finalSearchView,
                                                        "M",
                                                        finalUsername
                                                );

                                                scheduleTimeout();
                                            } catch (Throwable t) {
                                                log("[BRIDGE] 调用原生 M()失败: "
                                                        + t);
                                                clearPending();
                                            }
                                        }
                                    },
                                    300L
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] 点击处理异常: " + t);
                            clearPending();
                        }
                    }
                }
        );

        log("SearchUserViewModel.goToProfile hook OK");
    }

    // ============================================================
    // 捕获原生用户名搜索返回的真实对象
    // ============================================================

    private static void hookResolvedItem() throws Throwable {
        Class<?> itemClass =
                XposedHelpers.findClass("rl0.e", sCl);

        XposedHelpers.findAndHookMethod(
                itemClass,
                "T",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!PENDING.get()) {
                                return;
                            }

                            Object item = param.thisObject;
                            Object result = param.getResult();

                            if (!(result instanceof Integer)) {
                                return;
                            }

                            int uid = (Integer) result;

                            if (uid <= 0) {
                                return;
                            }

                            String username =
                                    readUsername(item);

                            if (isBlank(username)
                                    || pendingUsername == null
                                    || !pendingUsername.equals(username)) {
                                return;
                            }

                            Activity activity =
                                    pendingActivity.get();

                            if (activity == null
                                    || activity.isFinishing()
                                    || isDestroyed(activity)) {
                                log("[BRIDGE] Activity已失效");
                                clearPending();
                                return;
                            }

                            /*
                             * 防止 Paging/Adapter 重复读取同一对象，
                             * 导致重复跳转。
                             */
                            if (!PENDING.compareAndSet(true, false)) {
                                return;
                            }

                            pendingUsername = null;
                            pendingActivity =
                                    new WeakReference<>(null);
                            pendingStartTime = 0L;

                            log("[BRIDGE] 找到真实用户: "
                                    + username
                                    + " -> "
                                    + uid);

                            final Activity finalActivity =
                                    activity;

                            final Object finalItem =
                                    item;

                            mainHandler().post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            callNativeProfile(
                                                    finalActivity,
                                                    finalItem
                                            );
                                        }
                                    }
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] 捕获真实对象异常: " + t);
                        }
                    }
                }
        );

        log("rl0.e.T capture hook OK");
    }

    // ============================================================
    // 调用原生主页跳转出口
    // ============================================================

    private static void callNativeProfile(
            Activity activity,
            Object item
    ) {
        try {
            Class<?> sl0Class =
                    XposedHelpers.findClass("sl0.c", sCl);

            Object singleton =
                    XposedHelpers.getStaticObjectField(
                            sl0Class,
                            "a"
                    );

            /*
             * UserNameSearchFragment 原生使用的参数：
             * source     = user_filter_word
             * filterType = SearchService
             * position   = 0
             */
            XposedHelpers.callMethod(
                    singleton,
                    "e",
                    activity,
                    item,
                    "user_filter_word",
                    "SearchService",
                    0
            );

            log("[BRIDGE] 已调用原生 sl0.c.e()");
        } catch (Throwable t) {
            log("[BRIDGE] 原生主页跳转失败: " + t);
        }
    }

    // ============================================================
    // 查找当前 Activity 中的 UserNameSearchView
    // ============================================================

    private static Object findCurrentSearchView(
            Activity activity
    ) {
        try {
            Object cached = cachedSearchView.get();

            if (cached != null
                    && belongsToActivity(cached, activity)) {
                return cached;
            }

            View root =
                    activity.getWindow().getDecorView();

            Object found =
                    findViewByClass(
                            root,
                            "com.hellotalk.search.v2.widget.UserNameSearchView"
                    );

            if (found != null) {
                cachedSearchView =
                        new WeakReference<>(found);
            }

            return found;
        } catch (Throwable t) {
            log("[BRIDGE] 查找搜索控件失败: " + t);
            return null;
        }
    }

    private static Object findViewByClass(
            View view,
            String className
    ) {
        if (view == null) {
            return null;
        }

        if (className.equals(view.getClass().getName())) {
            return view;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            for (int i = 0; i < group.getChildCount(); i++) {
                Object result =
                        findViewByClass(
                                group.getChildAt(i),
                                className
                        );

                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    private static boolean belongsToActivity(
            Object view,
            Activity activity
    ) {
        try {
            Object context =
                    XposedHelpers.callMethod(view, "getContext");

            if (context == activity) {
                return true;
            }

            if (context instanceof ContextWrapper) {
                return ((ContextWrapper) context)
                        .getBaseContext() == activity;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    // ============================================================
    // pending 超时
    // ============================================================

    private static void scheduleTimeout() {
        final long start = pendingStartTime;

        mainHandler().postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!PENDING.get()) {
                            return;
                        }

                        if (pendingStartTime != start) {
                            return;
                        }

                        if (System.currentTimeMillis() - start
                                >= PENDING_TIMEOUT_MS) {
                            log("[BRIDGE] 搜索超时: "
                                    + pendingUsername);
                            clearPending();
                        }
                    }
                },
                PENDING_TIMEOUT_MS + 500L
        );
    }

    private static void clearPending() {
        PENDING.set(false);
        pendingUsername = null;
        pendingActivity =
                new WeakReference<>(null);
        pendingStartTime = 0L;
    }

    // ============================================================
    // 工具函数
    // ============================================================

    private static int readUid(Object item) {
        try {
            Object result =
                    XposedHelpers.callMethod(item, "T");

            return result instanceof Integer
                    ? (Integer) result
                    : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readUsername(Object item) {
        try {
            Object result =
                    XposedHelpers.getObjectField(item, "Y");

            return result == null
                    ? null
                    : String.valueOf(result);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null
                || value.trim().isEmpty();
    }

    private static boolean isDestroyed(Activity activity) {
        try {
            return android.os.Build.VERSION.SDK_INT >= 17
                    && activity.isDestroyed();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String message) {
        XposedBridge.log("[HT] " + message);
    }
}
