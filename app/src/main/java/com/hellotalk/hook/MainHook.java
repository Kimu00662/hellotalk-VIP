package com.hellotalk.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

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
    private static volatile Handler mainHandler;

    private static final AtomicBoolean pending =
            new AtomicBoolean(false);

    private static final AtomicLong tokenCounter =
            new AtomicLong(0L);

    private static volatile long pendingToken;
    private static volatile long requestedToken;

    private static volatile String pendingUsername;

    private static WeakReference<Activity> originalActivity =
            new WeakReference<>(null);

    private static WeakReference<Activity> idSearchActivity =
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
            mainHandler = new Handler(Looper.getMainLooper());
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
                hookFilterProfileClick();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookIdSearchFragmentInit();
            }
        });

        safe(new Task() {
            @Override
            public void run() throws Throwable {
                hookResolvedUser();
            }
        });

        log("=== HT ID SEARCH BRIDGE LOADED ===");
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

    private static Handler handler() {
        Handler result = mainHandler;

        if (result != null) {
            return result;
        }

        synchronized (MainHook.class) {
            result = mainHandler;

            if (result == null) {
                result = new Handler(Looper.getMainLooper());
                mainHandler = result;
            }
        }

        return result;
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
                        log("[VIP] xt.h.j() -> 100");
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
    // 拦截高级搜索 userid=0 点击
    // ============================================================

    private static void hookFilterProfileClick() throws Throwable {
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
                            String username =
                                    readUsername(item);

                            log("[BRIDGE] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 真实 ID 完全放行。
                             */
                            if (uid != 0) {
                                return;
                            }

                            if (isBlank(username)) {
                                log("[BRIDGE] userid=0 且 username为空，放行");
                                return;
                            }

                            /*
                             * 同一时间只处理一次脱敏点击。
                             */
                            if (!pending.compareAndSet(false, true)) {
                                log("[BRIDGE] 已有反查请求，放行本次点击");
                                return;
                            }

                            final long token =
                                    tokenCounter.incrementAndGet();

                            pendingToken = token;
                            requestedToken = 0L;
                            pendingUsername = username;
                            originalActivity =
                                    new WeakReference<>(activity);
                            idSearchActivity =
                                    new WeakReference<>(null);

                            /*
                             * 先启动官方已有的 IDSearchActivity。
                             * 启动成功后才阻止原来的 user_id=0 跳转。
                             */
                            try {
                                Class<?> idActivityClass =
                                        XposedHelpers.findClass(
                                                "com.hellotalk.search.v2.view.IDSearchActivity",
                                                sCl
                                        );

                                Intent intent =
                                        new Intent(
                                                activity,
                                                idActivityClass
                                        );

                                activity.startActivity(intent);

                                param.setResult(null);

                                log("[BRIDGE] 已启动原生 IDSearchActivity");

                                scheduleTimeout(token);

                            } catch (Throwable launchError) {
                                log("[BRIDGE] 启动 IDSearchActivity 失败: "
                                        + launchError);

                                /*
                                 * 启动失败时不吞掉原始点击。
                                 */
                                clearPending();
                            }

                        } catch (Throwable t) {
                            log("[BRIDGE] 点击处理失败: " + t);
                            clearPending();
                        }
                    }
                }
        );

        log("SearchUserViewModel.goToProfile hook OK");
    }

    // ============================================================
    // 原生 IDSearchActivity 中的 UserNameSearchFragment
    //
    // Fragment initViewData 完成后，collector 已建立，
    // 此时调用 requestUser 不会丢失事件。
    // ============================================================

    private static void hookIdSearchFragmentInit()
            throws Throwable {
        Class<?> fragmentClass =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.UserNameSearchFragment",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                fragmentClass,
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

                            String activityName =
                                    activity.getClass().getName();

                            if (!"com.hellotalk.search.v2.view.IDSearchActivity"
                                    .equals(activityName)) {
                                return;
                            }

                            long token = pendingToken;

                            if (requestedToken == token) {
                                return;
                            }

                            String username =
                                    pendingUsername;

                            if (isBlank(username)) {
                                return;
                            }

                            requestedToken = token;

                            idSearchActivity =
                                    new WeakReference<>(activity);

                            log("[BRIDGE] IDSearchActivity Fragment初始化完成");
                            log("[BRIDGE] 调用 requestUser(): "
                                    + username);

                            XposedHelpers.callMethod(
                                    fragment,
                                    "requestUser",
                                    username
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] Fragment初始化处理失败: " + t);
                            XposedBridge.log(t);
                            clearPending();
                        }
                    }
                }
        );

        log("UserNameSearchFragment.initViewData hook OK");
    }

    // ============================================================
    // 捕获原生用户名搜索返回的真实 rl0.e
    // ============================================================

    private static void hookResolvedUser() throws Throwable {
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

                            String wanted =
                                    pendingUsername;

                            if (isBlank(username)
                                    || isBlank(wanted)
                                    || !wanted.equals(username)) {
                                return;
                            }

                            Activity sourceActivity =
                                    originalActivity.get();

                            Activity idActivity =
                                    idSearchActivity.get();

                            if (sourceActivity == null
                                    || sourceActivity.isFinishing()
                                    || isDestroyed(sourceActivity)) {
                                log("[BRIDGE] 原始 Activity 已失效");
                                clearPending();
                                return;
                            }

                            /*
                             * 只允许一个匹配结果触发跳转。
                             */
                            if (!pending.compareAndSet(true, false)) {
                                return;
                            }

                            pendingUsername = null;
                            originalActivity =
                                    new WeakReference<>(null);
                            idSearchActivity =
                                    new WeakReference<>(null);
                            pendingToken = 0L;
                            requestedToken = 0L;

                            log("[BRIDGE] 找到真实用户: "
                                    + username
                                    + " -> "
                                    + uid);

                            final Activity finalSourceActivity =
                                    sourceActivity;

                            final Activity finalIdActivity =
                                    idActivity;

                            final Object finalItem =
                                    item;

                            handler().post(new Runnable() {
                                @Override
                                public void run() {
                                    /*
                                     * 使用原始 UserSearchActivity 作为
                                     * profile 跳转上下文。
                                     */
                                    callNativeProfile(
                                            finalSourceActivity,
                                            finalItem
                                    );

                                    /*
                                     * 主页跳转请求已经发出后关闭中间
                                     * IDSearchActivity。
                                     */
                                    if (finalIdActivity != null
                                            && !finalIdActivity.isFinishing()) {
                                        finalIdActivity.finish();
                                    }
                                }
                            });

                        } catch (Throwable t) {
                            log("[BRIDGE] 真实用户捕获失败: " + t);
                            XposedBridge.log(t);
                        }
                    }
                }
        );

        log("rl0.e.T capture hook OK");
    }

    // ============================================================
    // 使用原生最终主页入口
    // ============================================================

    private static void callNativeProfile(
            Activity activity,
            Object item
    ) {
        try {
            Class<?> cls =
                    XposedHelpers.findClass("sl0.c", sCl);

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

            log("[BRIDGE] sl0.c.e() 已调用");

        } catch (Throwable t) {
            log("[BRIDGE] sl0.c.e() 调用失败: " + t);
            XposedBridge.log(t);
        }
    }

    // ============================================================
    // 超时
    // ============================================================

    private static void scheduleTimeout(
            final long token
    ) {
        handler().postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!pending.get()) {
                            return;
                        }

                        if (pendingToken != token) {
                            return;
                        }

                        log("[BRIDGE] ID 搜索超时: "
                                + pendingUsername);

                        Activity idActivity =
                                idSearchActivity.get();

                        clearPending();

                        if (idActivity != null
                                && !idActivity.isFinishing()) {
                            idActivity.finish();
                        }
                    }
                },
                TIMEOUT_MS
        );
    }

    private static void clearPending() {
        pending.set(false);
        pendingUsername = null;
        originalActivity =
                new WeakReference<>(null);
        idSearchActivity =
                new WeakReference<>(null);
        pendingToken = 0L;
        requestedToken = 0L;
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
