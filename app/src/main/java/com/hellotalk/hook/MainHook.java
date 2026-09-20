package com.hellotalk.hook;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;
    private static volatile Handler sMainHandler;

    private static final AtomicBoolean pending =
            new AtomicBoolean(false);

    private static final AtomicLong tokenCounter =
            new AtomicLong(0L);

    private static volatile long pendingToken;
    private static volatile long launchedToken;

    private static volatile String pendingUsername;

    private static WeakReference<Activity> sourceActivity =
            new WeakReference<>(null);

    private static WeakReference<Activity> helperActivity =
            new WeakReference<>(null);

    private static final long TIMEOUT_MS = 20000L;

    @Override
    public void handleLoadPackage(
            final LoadPackageParam lpparam
    ) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        try {
            sMainHandler = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            log("Handler 初始化失败: " + t);
        }

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookVip();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookFilterVip();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookTranslate();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookFilterClick();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookIdSearchInit();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookUsernameFragmentInit();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookResolvedUser();
            }
        });

        log("=== HT FINAL HIDDEN ID SEARCH LOADED ===");
    }

    private interface Task {
        void run() throws Throwable;
    }

    private static void safe(Task task) {
        try {
            task.run();
        } catch (Throwable t) {
            log("Hook异常: " + t);
            XposedBridge.log(t);
        }
    }

    private static Handler mainHandler() {
        Handler handler = sMainHandler;

        if (handler != null) {
            return handler;
        }

        synchronized (MainHook.class) {
            handler = sMainHandler;

            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
                sMainHandler = handler;
            }
        }

        return handler;
    }

    // ============================================================
    // 假 VIP
    // ============================================================

    private static void hookVip() throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass("xt.h", sCl);

        XposedHelpers.findAndHookMethod(
                cls,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        param.setResult(100);
                    }
                }
        );

        log("假VIP hook OK");
    }

    private static void hookFilterVip() throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.SearchFilterViewModelV2",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                cls,
                "isVip",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
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
        Class<?> cls =
                XposedHelpers.findClass("lx.o", sCl);

        XposedHelpers.findAndHookMethod(
                cls,
                "h",
                XC_MethodReplacement.returnConstant(true)
        );

        log("翻译 hook OK");
    }

    // ============================================================
    // 拦截高级搜索 userid=0 的点击
    // ============================================================

    private static void hookFilterClick() throws Throwable {
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
                        Activity activity = null;

                        try {
                            activity =
                                    (Activity) param.args[0];

                            Object item = param.args[1];

                            int uid = readUid(item);
                            String username =
                                    readUsername(item);

                            log("[BRIDGE] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 正常真实 ID 完全放行。
                             */
                            if (uid != 0) {
                                return;
                            }

                            if (isBlank(username)) {
                                log("[BRIDGE] userid=0无username，放行");
                                return;
                            }

                            /*
                             * 同时只处理一个脱敏用户。
                             */
                            if (!pending.compareAndSet(false, true)) {
                                log("[BRIDGE] 已有反查请求，放行");
                                return;
                            }

                            long token =
                                    tokenCounter.incrementAndGet();

                            pendingToken = token;
                            launchedToken = 0L;
                            pendingUsername = username;
                            sourceActivity =
                                    new WeakReference<>(activity);
                            helperActivity =
                                    new WeakReference<>(null);

                            /*
                             * 先启动辅助 ID 搜索 Activity。
                             * 启动成功后才阻止 user_id=0 原跳转。
                             */
                            Class<?> idClass =
                                    XposedHelpers.findClass(
                                            "com.hellotalk.search.v2.view.IDSearchActivity",
                                            sCl
                                    );

                            Intent intent =
                                    new Intent(activity, idClass);

                            activity.startActivity(intent);

                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            param.setResult(null);

                            log("[BRIDGE] IDSearchActivity launched");

                            scheduleTimeout(token);

                        } catch (Throwable t) {
                            log("[BRIDGE] 启动辅助搜索失败: " + t);
                            XposedBridge.log(t);

                            /*
                             * 只有辅助 Activity 启动失败时，
                             * 才恢复原方法，不让点击彻底失效。
                             */
                            clearPending();
                        }
                    }
                }
        );

        log("SearchUserViewModel.goToProfile hook OK");
    }

    // ============================================================
    // IDSearchActivity.init()
    //
    // BaseActivity.setContentView() 会同步调用 init()，
    // 此时 binding/root 已经准备好。
    // ============================================================

    private static void hookIdSearchInit() throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.view.IDSearchActivity",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                cls,
                "init",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!pending.get()) {
                                return;
                            }

                            Activity activity =
                                    (Activity) param.thisObject;

                            hideHelperActivity(activity);

                            helperActivity =
                                    new WeakReference<>(activity);

                            log("[BRIDGE] IDSearchActivity.init()");
                        } catch (Throwable t) {
                            log("[BRIDGE] 隐藏辅助 Activity 失败: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!pending.get()) {
                                return;
                            }

                            Activity activity =
                                    (Activity) param.thisObject;

                            helperActivity =
                                    new WeakReference<>(activity);

                            log("[BRIDGE] IDSearchActivity.init()完成");
                        } catch (Throwable t) {
                            log("[BRIDGE] IDSearchActivity.init after失败: "
                                    + t);
                        }
                    }
                }
        );

        log("IDSearchActivity.init hook OK");
    }

    private static void hideHelperActivity(
            Activity activity
    ) {
        try {
            Window window =
                    activity.getWindow();

            if (window != null) {
                window.setBackgroundDrawable(
                        new ColorDrawable(Color.TRANSPARENT)
                );

                WindowManager.LayoutParams attrs =
                        window.getAttributes();

                attrs.alpha = 0.0f;
                attrs.dimAmount = 0.0f;

                window.setAttributes(attrs);
            }

            activity.overridePendingTransition(0, 0);

            /*
             * IDSearchActivity 根布局由 BaseBindingActivity
             * 保存到 BaseActivity.y。
             */
            Object root =
                    XposedHelpers.getObjectField(
                            activity,
                            "y"
                    );

            if (root instanceof View) {
                View rootView = (View) root;
                rootView.setAlpha(0.0f);
                rootView.setVisibility(View.INVISIBLE);
            }

        } catch (Throwable t) {
            log("[BRIDGE] hide helper window error: " + t);
        }
    }

    // ============================================================
    // UserNameSearchFragment.initViewData()
    //
    // 此时 BaseUserPagingFragment 已经创建 RecyclerView adapter，
    // UserNameSearchFragment 已经建立 Flow collector。
    // 直接调用 private loadUser()，绕过500ms debounce。
    // ============================================================

    private static void hookUsernameFragmentInit()
            throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.UserNameSearchFragment",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                cls,
                "initViewData",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!pending.get()) {
                                return;
                            }

                            Object fragment =
                                    param.thisObject;

                            Object activityObject =
                                    XposedHelpers.callMethod(
                                            fragment,
                                            "getActivity"
                                    );

                            if (!(activityObject
                                    instanceof Activity)) {
                                return;
                            }

                            Activity activity =
                                    (Activity) activityObject;

                            if (!"com.hellotalk.search.v2.view.IDSearchActivity"
                                    .equals(activity.getClass().getName())) {
                                return;
                            }

                            long token = pendingToken;

                            if (launchedToken == token) {
                                return;
                            }

                            String username =
                                    pendingUsername;

                            if (isBlank(username)) {
                                return;
                            }

                            launchedToken = token;
                            helperActivity =
                                    new WeakReference<>(activity);

                            log("[BRIDGE] UserNameSearchFragment ready");
                            log("[BRIDGE] direct loadUser: "
                                    + username);

                            /*
                             * 延后一个主线程消息：
                             * 确保 initViewData() 自身调用返回，
                             * 再进入 Fragment lifecycleScope。
                             */
                            mainHandler().post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (!pending.get()
                                                        || pendingToken != token) {
                                                    return;
                                                }

                                                XposedHelpers.callMethod(
                                                        fragment,
                                                        "loadUser",
                                                        username
                                                );

                                                log("[BRIDGE] loadUser() called");
                                            } catch (Throwable t) {
                                                log("[BRIDGE] loadUser()失败: "
                                                        + t);
                                                XposedBridge.log(t);
                                                clearAndFinishHelper();
                                            }
                                        }
                                    }
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] Fragment init处理失败: " + t);
                            XposedBridge.log(t);
                            clearAndFinishHelper();
                        }
                    }
                }
        );

        log("UserNameSearchFragment.initViewData hook OK");
    }

    // ============================================================
    // 捕获真实搜索结果
    // ============================================================

    private static void hookResolvedUser() throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass("rl0.e", sCl);

        XposedHelpers.findAndHookMethod(
                cls,
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

                            Object item =
                                    param.thisObject;

                            Object result =
                                    param.getResult();

                            if (!(result instanceof Integer)) {
                                return;
                            }

                            int uid =
                                    (Integer) result;

                            if (uid <= 0) {
                                return;
                            }

                            String username =
                                    readUsername(item);

                            if (isBlank(username)
                                    || isBlank(pendingUsername)
                                    || !username.equals(
                                    pendingUsername
                            )) {
                                return;
                            }

                            Activity source =
                                    sourceActivity.get();

                            if (source == null
                                    || source.isFinishing()
                                    || isDestroyed(source)) {
                                log("[BRIDGE] 原始 Activity已失效");
                                clearAndFinishHelper();
                                return;
                            }

                            if (!pending.compareAndSet(true, false)) {
                                return;
                            }

                            Activity helper =
                                    helperActivity.get();

                            pendingUsername = null;
                            sourceActivity =
                                    new WeakReference<>(null);
                            helperActivity =
                                    new WeakReference<>(null);
                            pendingToken = 0L;
                            launchedToken = 0L;

                            log("[BRIDGE] resolved "
                                    + username
                                    + " -> "
                                    + uid);

                            final Activity finalSource =
                                    source;

                            final Activity finalHelper =
                                    helper;

                            final Object finalItem =
                                    item;

                            mainHandler().post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            callNativeProfile(
                                                    finalSource,
                                                    finalItem
                                            );

                                            if (finalHelper != null
                                                    && !finalHelper.isFinishing()) {
                                                finalHelper.finish();
                                                finalHelper
                                                        .overridePendingTransition(
                                                                0,
                                                                0
                                                        );
                                            }
                                        }
                                    }
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] 捕获真实用户失败: " + t);
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
            Class<?> cls =
                    XposedHelpers.findClass(
                            "sl0.c",
                            sCl
                    );

            Object singleton =
                    XposedHelpers.getStaticObjectField(
                            cls,
                            "a"
                    );

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
            log("[BRIDGE] sl0.c.e()失败: " + t);
            XposedBridge.log(t);
        }
    }

    // ============================================================
    // 超时与清理
    // ============================================================

    private static void scheduleTimeout(
            final long token
    ) {
        mainHandler().postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!pending.get()) {
                            return;
                        }

                        if (pendingToken != token) {
                            return;
                        }

                        log("[BRIDGE] timeout: "
                                + pendingUsername);

                        clearAndFinishHelper();
                    }
                },
                TIMEOUT_MS
        );
    }

    private static void clearAndFinishHelper() {
        Activity helper =
                helperActivity.get();

        clearPending();

        if (helper != null
                && !helper.isFinishing()) {
            try {
                helper.finish();
                helper.overridePendingTransition(0, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void clearPending() {
        pending.set(false);
        pendingUsername = null;
        sourceActivity =
                new WeakReference<>(null);
        helperActivity =
                new WeakReference<>(null);
        pendingToken = 0L;
        launchedToken = 0L;
    }

    // ============================================================
    // 工具
    // ============================================================

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
