package com.hellotalk.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

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

    private static final AtomicBoolean PENDING =
            new AtomicBoolean(false);

    private static final AtomicLong TOKEN_COUNTER =
            new AtomicLong(0L);

    private static volatile long pendingToken;
    private static volatile long fragmentRequestToken;

    private static volatile String pendingUsername;

    /*
     * 发生点击的原始 UserSearchActivity。
     * 最终主页要使用它作为跳转上下文。
     */
    private static WeakReference<Activity> sourceActivity =
            new WeakReference<>(null);

    /*
     * 中间的 IDSearchActivity。
     */
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

        /*
         * 不在静态字段初始化阶段创建 Handler。
         * 避免 LSPosed 加载模块时主 Looper 尚未准备好。
         */
        try {
            sMainHandler =
                    new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            log("Handler 初始化失败: " + t);
        }

        /*
         * 每个 hook 独立保护。
         * 搜索桥接失败不能影响假 VIP 和翻译。
         */
        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookVip();
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
                hookTranslate();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookFilterProfileClick();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookIdSearchActivityInit();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookUsernameFragmentInit();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookResolvedUser();
            }
        });

        log("=== HT FULL VISIBLE ID SEARCH BRIDGE LOADED ===");
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

    private static Handler mainHandler() {
        Handler handler = sMainHandler;

        if (handler != null) {
            return handler;
        }

        synchronized (MainHook.class) {
            handler = sMainHandler;

            if (handler == null) {
                handler =
                        new Handler(Looper.getMainLooper());

                sMainHandler = handler;
            }
        }

        return handler;
    }

    // ============================================================
    // 假 VIP
    // ============================================================

    private static void hookVip() throws Throwable {
        Class<?> vipClass =
                XposedHelpers.findClass(
                        "xt.h",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                vipClass,
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

    /*
     * 高级筛选自己的 ViewModel 再加一层保险。
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
                XposedHelpers.findClass(
                        "lx.o",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                translateClass,
                "h",
                XC_MethodReplacement.returnConstant(true)
        );

        log("翻译 hook OK");
    }

    // ============================================================
    // 拦截高级搜索 userid=0 的点击
    // ============================================================

    private static void hookFilterProfileClick()
            throws Throwable {
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
                XposedHelpers.findClass(
                        "rl0.e",
                        sCl
                );

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

                            Object item =
                                    param.args[1];

                            int uid =
                                    readUid(item);

                            String username =
                                    readUsername(item);

                            log("[BRIDGE] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 原本就有真实 userid 的用户，
                             * 完全放行原始逻辑。
                             */
                            if (uid != 0) {
                                return;
                            }

                            /*
                             * 没有 username 就无法反查，
                             * 放行原始逻辑。
                             */
                            if (isBlank(username)) {
                                log("[BRIDGE] userid=0且username为空，放行");
                                return;
                            }

                            /*
                             * 防止同时启动多个中间搜索页。
                             */
                            if (!PENDING.compareAndSet(
                                    false,
                                    true
                            )) {
                                log("[BRIDGE] 已有反查请求，放行本次点击");
                                return;
                            }

                            long token =
                                    TOKEN_COUNTER.incrementAndGet();

                            pendingToken = token;
                            fragmentRequestToken = 0L;
                            pendingUsername = username;

                            sourceActivity =
                                    new WeakReference<>(activity);

                            helperActivity =
                                    new WeakReference<>(null);

                            /*
                             * 启动已确认存在的旧版 IDSearchActivity。
                             */
                            Class<?> idSearchClass =
                                    XposedHelpers.findClass(
                                            "com.hellotalk.search.v2.view.IDSearchActivity",
                                            sCl
                                    );

                            Intent intent =
                                    new Intent(
                                            activity,
                                            idSearchClass
                                    );

                            activity.startActivity(intent);

                            /*
                             * 去掉 Activity 进入动画。
                             * 页面本身保持正常可见，不再黑屏。
                             */
                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            /*
                             * 只有启动成功后才阻止原始
                             * user_id=0 跳转。
                             */
                            param.setResult(null);

                            log("[BRIDGE] IDSearchActivity launched");

                            scheduleTimeout(token);

                        } catch (Throwable t) {
                            log("[BRIDGE] 启动ID搜索页失败: " + t);
                            XposedBridge.log(t);

                            /*
                             * 启动失败时清理状态。
                             * 不让后续点击被旧 pending 卡住。
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
    // BaseBindingActivity 已经完成 binding 和 setContentView，
    // BaseActivity.setContentView() 随后调用子类 init()。
    // ============================================================

    private static void hookIdSearchActivityInit()
            throws Throwable {
        Class<?> idSearchClass =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.view.IDSearchActivity",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                idSearchClass,
                "init",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!PENDING.get()) {
                                return;
                            }

                            Activity activity =
                                    (Activity) param.thisObject;

                            helperActivity =
                                    new WeakReference<>(activity);

                            /*
                             * 这里不隐藏整个页面，
                             * 只隐藏输入框和返回按钮。
                             */
                            prepareVisibleHelperPage(activity);

                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            log("[BRIDGE] IDSearchActivity.init()");
                        } catch (Throwable t) {
                            log("[BRIDGE] IDSearchActivity.init处理失败: "
                                    + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!PENDING.get()) {
                                return;
                            }

                            Activity activity =
                                    (Activity) param.thisObject;

                            helperActivity =
                                    new WeakReference<>(activity);

                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            log("[BRIDGE] IDSearchActivity.init完成");
                        } catch (Throwable t) {
                            log("[BRIDGE] IDSearchActivity.init after失败: "
                                    + t);
                        }
                    }
                }
        );

        log("IDSearchActivity.init hook OK");
    }

    /*
     * 保持正常页面背景和 Fragment 容器，
     * 只隐藏用户不需要操作的输入框和返回按钮。
     */
    private static void prepareVisibleHelperPage(
            Activity activity
    ) {
        try {
            /*
             * BaseBindingActivity 已确认有 public 字段 A：
             * A = 当前 ActivitySearchIdSearchBinding。
             */
            Object binding =
                    XposedHelpers.getObjectField(
                            activity,
                            "A"
                    );

            if (binding == null) {
                log("[BRIDGE] IDSearchActivity binding为空");
                return;
            }

            Object searchView =
                    XposedHelpers.getObjectField(
                            binding,
                            "searchView"
                    );

            if (searchView instanceof View) {
                ((View) searchView).setVisibility(
                        View.INVISIBLE
                );
            }

            Object back =
                    XposedHelpers.getObjectField(
                            binding,
                            "ivBack"
                    );

            if (back instanceof View) {
                ((View) back).setVisibility(
                        View.INVISIBLE
                );
            }

            /*
             * overlay 是找回 ID 的提示层。
             * 反查模式下不需要显示，避免遮挡 Fragment。
             */
            try {
                Object overlay =
                        XposedHelpers.getObjectField(
                                binding,
                                "overlay"
                        );

                Object overlayRoot =
                        XposedHelpers.callMethod(
                                overlay,
                                "getRoot"
                        );

                if (overlayRoot instanceof View) {
                    ((View) overlayRoot).setVisibility(
                            View.INVISIBLE
                    );
                }
            } catch (Throwable ignored) {
                /*
                 * overlay 处理失败不影响搜索。
                 */
            }

            log("[BRIDGE] helper page visible, controls hidden");

        } catch (Throwable t) {
            log("[BRIDGE] helper页面处理失败: " + t);
        }
    }

    // ============================================================
    // UserNameSearchFragment.initViewData()
    //
    // 这里已经完成：
    // BaseUserPagingFragment.initViewData()
    // UserNameSearchFragment 的 Flow collector 建立
    //
    // 直接调用 private loadUser()，
    // 绕过 requestUser() 内部的 500ms debounce。
    // ============================================================

    private static void hookUsernameFragmentInit()
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
                            if (!PENDING.get()) {
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

                            /*
                             * 只处理我们启动的旧版
                             * IDSearchActivity。
                             */
                            if (!"com.hellotalk.search.v2.view.IDSearchActivity"
                                    .equals(
                                            activity.getClass().getName()
                                    )) {
                                return;
                            }

                            long token =
                                    pendingToken;

                            if (fragmentRequestToken == token) {
                                return;
                            }

                            String username =
                                    pendingUsername;

                            if (isBlank(username)) {
                                return;
                            }

                            fragmentRequestToken = token;

                            helperActivity =
                                    new WeakReference<>(activity);

                            log("[BRIDGE] UserNameSearchFragment ready");
                            log("[BRIDGE] pending username="
                                    + username);

                            /*
                             * 等 initViewData() 自己返回，
                             * 再启动 Fragment lifecycleScope。
                             */
                            mainHandler().post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (!PENDING.get()
                                                        || pendingToken != token) {
                                                    return;
                                                }

                                                /*
                                                 * loadUser 是 private，
                                                 * XposedHelpers.callMethod()
                                                 * 仍可反射调用。
                                                 */
                                                XposedHelpers.callMethod(
                                                        fragment,
                                                        "loadUser",
                                                        username
                                                );

                                                log("[BRIDGE] loadUser() called");

                                            } catch (Throwable t) {
                                                log("[BRIDGE] loadUser调用失败: "
                                                        + t);
                                                XposedBridge.log(t);
                                                clearAndFinishHelper();
                                            }
                                        }
                                    }
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] Fragment初始化处理失败: "
                                    + t);
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

    private static void hookResolvedUser()
            throws Throwable {
        Class<?> itemClass =
                XposedHelpers.findClass(
                        "rl0.e",
                        sCl
                );

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
                                    || !username.equals(wanted)) {
                                return;
                            }

                            Activity source =
                                    sourceActivity.get();

                            if (source == null
                                    || source.isFinishing()
                                    || isDestroyed(source)) {
                                log("[BRIDGE] 原始搜索 Activity 已失效");
                                clearAndFinishHelper();
                                return;
                            }

                            /*
                             * 防止 Paging/Adapter 多次读取
                             * 同一个对象导致重复跳转。
                             */
                            if (!PENDING.compareAndSet(
                                    true,
                                    false
                            )) {
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
                            fragmentRequestToken = 0L;

                            log("[BRIDGE] resolved username="
                                    + username
                                    + " uid="
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
                                            /*
                                             * 先调用原生主页入口。
                                             */
                                            callNativeProfile(
                                                    finalSource,
                                                    finalItem
                                            );

                                            /*
                                             * 再关闭中间加载页，
                                             * 并去掉退出动画。
                                             */
                                            if (finalHelper != null
                                                    && !finalHelper.isFinishing()) {
                                                try {
                                                    finalHelper.finish();

                                                    finalHelper
                                                            .overridePendingTransition(
                                                                    0,
                                                                    0
                                                            );
                                                } catch (Throwable t) {
                                                    log("[BRIDGE] 关闭辅助页失败: "
                                                            + t);
                                                }
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
             * UserNameSearchFragment 原生参数：
             *
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

            log("[BRIDGE] sl0.c.e() called");

        } catch (Throwable t) {
            log("[BRIDGE] sl0.c.e()失败: " + t);
            XposedBridge.log(t);
        }
    }

    // ============================================================
    // 超时和清理
    // ============================================================

    private static void scheduleTimeout(
            final long token
    ) {
        mainHandler().postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!PENDING.get()) {
                            return;
                        }

                        if (pendingToken != token) {
                            return;
                        }

                        log("[BRIDGE] 反查超时: "
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
                helper.overridePendingTransition(
                        0,
                        0
                );
            } catch (Throwable ignored) {
            }
        }
    }

    private static void clearPending() {
        PENDING.set(false);

        pendingUsername = null;

        sourceActivity =
                new WeakReference<>(null);

        helperActivity =
                new WeakReference<>(null);

        pendingToken = 0L;
        fragmentRequestToken = 0L;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static int readUid(Object item) {
        try {
            Object value =
                    XposedHelpers.callMethod(
                            item,
                            "T"
                    );

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
                    XposedHelpers.getObjectField(
                            item,
                            "Y"
                    );

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

    private static boolean isDestroyed(
            Activity activity
    ) {
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
