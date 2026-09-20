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
    private static volatile long pendingStartTime;

    private static final long TIMEOUT_MS = 15000L;

    // search_user_name_container
    private static final int SEARCH_USER_NAME_CONTAINER =
            0x7f0a181e;

    @Override
    public void handleLoadPackage(
            final LoadPackageParam lpparam
    ) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        try {
            mainHandler = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            log("Handler init failed: " + t);
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
            log("Hook exception: " + t);
            XposedBridge.log(t);
        }
    }

    private static Handler handler() {
        Handler result = mainHandler;

        if (result != null) {
            return result;
        }

        synchronized (MainHook.class) {
            result = mainHandler;

            if (result == null) {
                Looper looper = Looper.getMainLooper();
                result = new Handler(looper);
                mainHandler = result;
            }
        }

        return result;
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
                        log("[VIP] xt.h.j() called");
                    }

                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        param.setResult(100);
                        log("[VIP] xt.h.j() -> 100");
                    }
                }
        );

        log("假VIP hook OK");
    }

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
                        param.setResult(true);
                        log("[VIP] SearchFilterViewModelV2.isVip() -> true");
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
    // 记录 UserNameSearchView.L()
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

                        log("[BRIDGE] UserNameSearchView.L() completed");
                    }
                }
        );

        log("UserNameSearchView.L hook OK");
    }

    // ============================================================
    // 高级搜索点击入口
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

                            log("[BRIDGE] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 有真实 ID 的条目完全放行。
                             */
                            if (uid != 0) {
                                return;
                            }

                            if (isBlank(username)) {
                                log("[BRIDGE] userid=0 but username empty");
                                return;
                            }

                            /*
                             * 同时只处理一个待跳转项目。
                             */
                            if (!pending.compareAndSet(false, true)) {
                                log("[BRIDGE] another request pending");
                                return;
                            }

                            pendingActivity =
                                    new WeakReference<>(activity);
                            pendingUsername = username;
                            pendingStartTime =
                                    System.currentTimeMillis();

                            /*
                             * 阻止原方法继续使用 user_id=0。
                             */
                            param.setResult(null);

                            Object view =
                                    findCurrentSearchView(activity);

                            if (view == null) {
                                log("[BRIDGE] UserNameSearchView not found");
                                clearPending();
                                return;
                            }

                            final Object finalView = view;
                            final String finalUsername = username;

                            handler().post(new Runnable() {
                                @Override
                                public void run() {
                                    prepareAndSearch(
                                            finalView,
                                            finalUsername
                                    );
                                }
                            });

                        } catch (Throwable t) {
                            log("[BRIDGE] profile click error: " + t);
                            XposedBridge.log(t);
                            clearPending();
                        }
                    }
                }
        );

        log("SearchUserViewModel.goToProfile hook OK");
    }

    // ============================================================
    // 通过 SearchFilterActivityV2.Y4() 获取 binding 中的控件
    // ============================================================

    private static Object findCurrentSearchView(
            Activity activity
    ) {
        try {
            Object cached = cachedSearchView.get();

            if (cached != null
                    && belongsToActivity(cached, activity)) {
                log("[BRIDGE] use cached UserNameSearchView");
                return cached;
            }

            Class<?> activityClass =
                    XposedHelpers.findClass(
                            "com.hellotalk.search.v2.logic.controller.searchuser.SearchFilterActivityV2",
                            sCl
                    );

            /*
             * APK 已确认：
             * Y4(SearchFilterActivityV2)
             * 返回 SearchFilterActivityV2Binding。
             */
            Object binding =
                    XposedHelpers.callStaticMethod(
                            activityClass,
                            "Y4",
                            activity
                    );

            if (binding == null) {
                log("[BRIDGE] SearchFilterActivityV2.Y4() returned null");
                return null;
            }

            Object view =
                    XposedHelpers.getObjectField(
                            binding,
                            "searchFilterSearchView"
                    );

            if (view == null) {
                log("[BRIDGE] binding.searchFilterSearchView is null");
                return null;
            }

            cachedSearchView =
                    new WeakReference<>(view);

            log("[BRIDGE] found UserNameSearchView from binding");
            return view;

        } catch (Throwable t) {
            log("[BRIDGE] get UserNameSearchView failed: " + t);
            XposedBridge.log(t);
            return null;
        }
    }

    // ============================================================
    // 确保 SearchIDViewV2 存在后再调用 M()
    // ============================================================

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
                log("[BRIDGE] SearchIDViewV2 absent, call L()");

                XposedHelpers.callMethod(
                        view,
                        "L",
                        SEARCH_USER_NAME_CONTAINER
                );

                handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        invokeNativeSearch(
                                view,
                                username
                        );
                    }
                }, 500L);

            } else {
                log("[BRIDGE] SearchIDViewV2 already exists");
                invokeNativeSearch(view, username);
            }

        } catch (Throwable t) {
            log("[BRIDGE] prepare search failed: " + t);
            XposedBridge.log(t);
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
                log("[BRIDGE] SearchIDViewV2 still absent after L()");
                clearPending();
                return;
            }

            log("[BRIDGE] call native M(): " + username);

            /*
             * M() -> 当前 SearchIDViewV2.requestUser(username)
             * -> 原生 Flow/Paging/universal 链。
             */
            XposedHelpers.callMethod(
                    view,
                    "M",
                    username
            );

            scheduleTimeout();

        } catch (Throwable t) {
            log("[BRIDGE] call M() failed: " + t);
            XposedBridge.log(t);
            clearPending();
        }
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
                            if (!pending.get()) {
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
                                log("[BRIDGE] Activity invalid");
                                clearPending();
                                return;
                            }

                            /*
                             * Paging/Adapter 可能重复读取 T()，
                             * 这里只允许第一个匹配结果触发跳转。
                             */
                            if (!pending.compareAndSet(true, false)) {
                                return;
                            }

                            pendingUsername = null;
                            pendingActivity =
                                    new WeakReference<>(null);
                            pendingStartTime = 0L;

                            log("[BRIDGE] resolved username="
                                    + username
                                    + " uid="
                                    + uid);

                            final Activity finalActivity =
                                    activity;

                            final Object finalItem =
                                    item;

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
                            log("[BRIDGE] capture resolved item failed: " + t);
                            XposedBridge.log(t);
                        }
                    }
                }
        );

        log("rl0.e.T capture hook OK");
    }

    // ============================================================
    // 原生主页入口
    // ============================================================

    private static void callNativeProfile(
            Activity activity,
            Object item
    ) {
        try {
            Class<?> sl0Class =
                    XposedHelpers.findClass(
                            "sl0.c",
                            sCl
                    );

            Object singleton =
                    XposedHelpers.getStaticObjectField(
                            sl0Class,
                            "a"
                    );

            /*
             * UserNameSearchFragment.goToProfile() 的真实参数：
             * source = user_filter_word
             * filterType = SearchService
             * position = 0
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

            log("[BRIDGE] sl0.c.e() called");

        } catch (Throwable t) {
            log("[BRIDGE] sl0.c.e() failed: " + t);
            XposedBridge.log(t);
        }
    }

    // ============================================================
    // Activity 归属检查
    // ============================================================

    private static boolean belongsToActivity(
            Object view,
            Activity activity
    ) {
        try {
            Object context =
                    XposedHelpers.callMethod(
                            view,
                            "getContext"
                    );

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
    // 超时与字段工具
    // ============================================================

    private static void scheduleTimeout() {
        final long start = pendingStartTime;

        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pending.get()) {
                    return;
                }

                if (pendingStartTime != start) {
                    return;
                }

                if (System.currentTimeMillis() - start
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
        pendingStartTime = 0L;
    }

    private static int readUid(Object item) {
        try {
            Object value =
                    XposedHelpers.callMethod(item, "T");

            return value instanceof Integer
                    ? (Integer) value
                    : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readUsername(Object item) {
        try {
            Object value =
                    XposedHelpers.getObjectField(item, "Y");

            return value == null
                    ? null
                    : String.valueOf(value);
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
