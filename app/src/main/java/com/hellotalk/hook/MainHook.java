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
    private static volatile Handler mainHandler;

    private static final AtomicBoolean pending =
            new AtomicBoolean(false);

    private static WeakReference<Activity> pendingActivity =
            new WeakReference<>(null);

    private static WeakReference<Object> cachedSearchView =
            new WeakReference<>(null);

    private static volatile String pendingUsername;
    private static volatile long pendingTime;

    private static final long TIMEOUT_MS = 15000L;

    /*
     * 已确认的资源 ID：
     * com.hellotalk.search.R$id.search_user_name_container
     */
    private static final int SEARCH_USER_NAME_CONTAINER =
            0x7f0a181e;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        try {
            mainHandler = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            log("Handler 初始化失败: " + t);
        }

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookVip();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookTranslate();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookFilterVip();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookSearchView();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookProfileClick();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookResolvedItem();
            }
        });

        log("=== HT FULL STABLE BRIDGE LOADED ===");
    }

    private interface HookTask {
        void run() throws Throwable;
    }

    private static void safe(HookTask task) {
        try {
            task.run();
        } catch (Throwable t) {
            log("Hook异常: " + t);
            XposedBridge.log(t);
        }
    }

    private static Handler handler() {
        Handler h = mainHandler;

        if (h != null) {
            return h;
        }

        synchronized (MainHook.class) {
            h = mainHandler;

            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                mainHandler = h;
            }
        }

        return h;
    }

    // =========================================================
    // VIP
    // =========================================================

    private static void hookVip() throws Throwable {
        Class<?> cls = XposedHelpers.findClass("xt.h", sCl);

        XposedHelpers.findAndHookMethod(
                cls,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        log("[VIP] xt.h.j() called");
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(100);
                        log("[VIP] xt.h.j() -> 100");
                    }
                }
        );

        log("假VIP hook OK");
    }

    private static void hookFilterVip() throws Throwable {
        Class<?> cls = XposedHelpers.findClass(
                "com.hellotalk.search.v2.logic.controller.searchuser.SearchFilterViewModelV2",
                sCl
        );

        XposedHelpers.findAndHookMethod(
                cls,
                "isVip",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(true);
                        log("[VIP] SearchFilterViewModelV2.isVip() -> true");
                    }
                }
        );

        log("SearchFilterViewModelV2.isVip hook OK");
    }

    // =========================================================
    // 翻译
    // =========================================================

    private static void hookTranslate() throws Throwable {
        Class<?> cls = XposedHelpers.findClass("lx.o", sCl);

        XposedHelpers.findAndHookMethod(
                cls,
                "h",
                XC_MethodReplacement.returnConstant(true)
        );

        log("翻译 hook OK");
    }

    // =========================================================
    // 保存 UserNameSearchView
    // =========================================================

    private static void hookSearchView() throws Throwable {
        Class<?> cls = XposedHelpers.findClass(
                "com.hellotalk.search.v2.widget.UserNameSearchView",
                sCl
        );

        XposedHelpers.findAndHookMethod(
                cls,
                "L",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        cachedSearchView =
                                new WeakReference<>(p.thisObject);
                        log("[BRIDGE] UserNameSearchView.L()完成");
                    }
                }
        );

        log("UserNameSearchView.L hook OK");
    }

    // =========================================================
    // 拦截 userid=0 的高级搜索点击
    // =========================================================

    private static void hookProfileClick() throws Throwable {
        Class<?> vmClass = XposedHelpers.findClass(
                "com.hellotalk.search.v2.viewmodel.SearchUserViewModel",
                sCl
        );

        Class<?> activityClass =
                XposedHelpers.findClass("android.app.Activity", sCl);

        Class<?> itemClass =
                XposedHelpers.findClass("rl0.e", sCl);

        XposedHelpers.findAndHookMethod(
                vmClass,
                "goToProfile",
                activityClass,
                itemClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Activity activity = (Activity) p.args[0];
                            Object item = p.args[1];

                            int uid = readUid(item);
                            String username = readUsername(item);

                            log("[BRIDGE] click uid=" + uid
                                    + " username=" + username);

                            // 正常真实 ID 完全放行
                            if (uid != 0) {
                                return;
                            }

                            if (blank(username)) {
                                log("[BRIDGE] userid=0且无username，放行");
                                return;
                            }

                            if (!pending.compareAndSet(false, true)) {
                                log("[BRIDGE] 已有请求处理中，放行");
                                return;
                            }

                            pendingActivity =
                                    new WeakReference<>(activity);
                            pendingUsername = username;
                            pendingTime =
                                    System.currentTimeMillis();

                            // 阻止原方法跳转 user_id=0
                            p.setResult(null);

                            final Object view =
                                    findSearchView(activity);

                            if (view == null) {
                                log("[BRIDGE] 找不到 UserNameSearchView");
                                clearPending();
                                return;
                            }

                            final String name = username;

                            handler().post(new Runnable() {
                                @Override
                                public void run() {
                                    prepareAndSearch(view, name);
                                }
                            });

                        } catch (Throwable t) {
                            log("[BRIDGE] click error: " + t);
                            clearPending();
                        }
                    }
                }
        );

        log("SearchUserViewModel.goToProfile hook OK");
    }

    /*
     * 关键修复：
     * M() 内部依赖 G() 找 SearchIDViewV2。
     * 如果 Fragment 尚未挂载，先调用 L(containerId)，
     * 等 transaction 完成后再调用 M(username)。
     */
    private static void prepareAndSearch(
            final Object view,
            final String username
    ) {
        try {
            if (!pending.get()) {
                return;
            }

            Object fragment =
                    XposedHelpers.callMethod(view, "G");

            if (fragment == null) {
                log("[BRIDGE] SearchIDViewV2不存在，先调用L()");
                XposedHelpers.callMethod(
                        view,
                        "L",
                        SEARCH_USER_NAME_CONTAINER
                );

                handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        invokeNativeSearch(view, username);
                    }
                }, 400L);
            } else {
                invokeNativeSearch(view, username);
            }

        } catch (Throwable t) {
            log("[BRIDGE] prepare search error: " + t);
            clearPending();
        }
    }

    private static void invokeNativeSearch(
            Object view,
            String username
    ) {
        try {
            if (!pending.get()) {
                return;
            }

            Object fragment =
                    XposedHelpers.callMethod(view, "G");

            if (fragment == null) {
                log("[BRIDGE] L()后仍找不到SearchIDViewV2");
                clearPending();
                return;
            }

            log("[BRIDGE] 调用原生 M(): " + username);

            XposedHelpers.callMethod(
                    view,
                    "M",
                    username
            );

            scheduleTimeout();

        } catch (Throwable t) {
            log("[BRIDGE] M() failed: " + t);
            clearPending();
        }
    }

    // =========================================================
    // 捕获原生搜索返回的真实 rl0.e
    // =========================================================

    private static void hookResolvedItem() throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass("rl0.e", sCl);

        XposedHelpers.findAndHookMethod(
                cls,
                "T",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!pending.get()) {
                                return;
                            }

                            Object item = p.thisObject;
                            Object result = p.getResult();

                            if (!(result instanceof Integer)) {
                                return;
                            }

                            int uid = (Integer) result;
                            String username = readUsername(item);

                            if (uid <= 0
                                    || blank(username)
                                    || pendingUsername == null
                                    || !pendingUsername.equals(username)) {
                                return;
                            }

                            Activity activity =
                                    pendingActivity.get();

                            if (activity == null
                                    || activity.isFinishing()
                                    || isDestroyed(activity)) {
                                clearPending();
                                return;
                            }

                            if (!pending.compareAndSet(true, false)) {
                                return;
                            }

                            pendingUsername = null;
                            pendingActivity =
                                    new WeakReference<>(null);
                            pendingTime = 0L;

                            log("[BRIDGE] 匹配真实用户: "
                                    + username + " -> " + uid);

                            final Activity finalActivity = activity;
                            final Object finalItem = item;

                            handler().post(new Runnable() {
                                @Override
                                public void run() {
                                    callNativeProfile(
                                            finalActivity,
                                            finalItem
                                    );
                                }
                            });

                        } catch (Throwable t) {
                            log("[BRIDGE] capture error: " + t);
                        }
                    }
                }
        );

        log("rl0.e.T capture hook OK");
    }

    // =========================================================
    // 原生主页入口
    // =========================================================

    private static void callNativeProfile(
            Activity activity,
            Object item
    ) {
        try {
            Class<?> cls =
                    XposedHelpers.findClass("sl0.c", sCl);

            Object singleton =
                    XposedHelpers.getStaticObjectField(cls, "a");

            XposedHelpers.callMethod(
                    singleton,
                    "e",
                    activity,
                    item,
                    "user_filter_word",
                    "SearchService",
                    0
            );

            log("[BRIDGE] sl0.c.e() called");

        } catch (Throwable t) {
            log("[BRIDGE] profile jump failed: " + t);
        }
    }

    // =========================================================
    // 查找当前 UserNameSearchView
    // =========================================================

    private static Object findSearchView(Activity activity) {
        try {
            Object cached = cachedSearchView.get();

            if (cached != null
                    && belongsToActivity(cached, activity)) {
                return cached;
            }

            View root =
                    activity.getWindow().getDecorView();

            Object found = findView(
                    root,
                    "com.hellotalk.search.v2.widget.UserNameSearchView"
            );

            if (found != null) {
                cachedSearchView =
                        new WeakReference<>(found);
            }

            return found;

        } catch (Throwable t) {
            log("[BRIDGE] find view failed: " + t);
            return null;
        }
    }

    private static Object findView(
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
                        findView(group.getChildAt(i), className);

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

    // =========================================================
    // 超时与工具
    // =========================================================

    private static void scheduleTimeout() {
        final long start = pendingTime;

        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pending.get()
                        && pendingTime == start
                        && System.currentTimeMillis() - start
                        >= TIMEOUT_MS) {
                    log("[BRIDGE] search timeout: "
                            + pendingUsername);
                    clearPending();
                }
            }
        }, TIMEOUT_MS + 500L);
    }

    private static void clearPending() {
        pending.set(false);
        pendingUsername = null;
        pendingActivity =
                new WeakReference<>(null);
        pendingTime = 0L;
    }

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

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
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
