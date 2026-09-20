package com.hellotalk.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static volatile long sPendingToken;
    private static volatile long sFragmentRequestToken;
    private static volatile String sPendingUsername;

    private static WeakReference<Activity> sSourceActivity =
            new WeakReference<>(null);

    private static WeakReference<Activity> sHelperActivity =
            new WeakReference<>(null);

    private static final long TIMEOUT_MS = 20000L;

    /*
     * 只保存成功结果，进程重启后自动清空。
     */
    private static final Map<String, Integer> UID_CACHE =
            new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(
            final LoadPackageParam lpparam
    ) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        try {
            sMainHandler =
                    new Handler(Looper.getMainLooper());
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
                hookFilterClick();
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
                hookPagingRefreshState();
            }
        });

        log("=== HT FINAL SEARCH BRIDGE LOADED ===");
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
                XposedHelpers.findClass("xt.h", sCl);

        XposedHelpers.findAndHookMethod(
                vipClass,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        param.setResult(100);
                        log("[VIP] xt.h.j -> 100");
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
                        log("[VIP] SearchFilterViewModelV2.isVip -> true");
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
    // 高级筛选点击
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
                        Activity source = null;

                        try {
                            source =
                                    (Activity) param.args[0];

                            Object item =
                                    param.args[1];

                            int uid =
                                    readUid(item);

                            String username =
                                    normalizeUsername(
                                            readUsername(item)
                                    );

                            log("[BRIDGE] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 原本就有真实 userid，完全放行。
                             */
                            if (uid > 0) {
                                return;
                            }

                            if (isBlank(username)) {
                                log("[BRIDGE] userid=0且username为空，放行");
                                return;
                            }

                            /*
                             * 如果同名已有缓存，直接进入主页。
                             */
                            Integer cached =
                                    UID_CACHE.get(username);

                            if (cached != null && cached > 0) {
                                log("[BRIDGE] cache hit "
                                        + username
                                        + " -> "
                                        + cached);

                                param.setResult(null);

                                final Activity finalSource =
                                        source;

                                final int finalUid =
                                        cached;

                                mainHandler().post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                openProfileByUid(
                                                        finalSource,
                                                        finalUid
                                                );
                                            }
                                        }
                                );

                                return;
                            }

                            /*
                             * 已有反查时，userid=0 点击必须吞掉。
                             * 不能再放行，否则会进入 user_id=0 错误页。
                             */
                            if (!PENDING.compareAndSet(
                                    false,
                                    true
                            )) {
                                log("[BRIDGE] pending期间阻止userid=0点击");
                                param.setResult(null);
                                return;
                            }

                            long token =
                                    TOKEN_COUNTER.incrementAndGet();

                            sPendingToken = token;
                            sFragmentRequestToken = 0L;
                            sPendingUsername = username;

                            sSourceActivity =
                                    new WeakReference<>(source);

                            sHelperActivity =
                                    new WeakReference<>(null);

                            try {
                                Class<?> idSearchClass =
                                        XposedHelpers.findClass(
                                                "com.hellotalk.search.v2.view.IDSearchActivity",
                                                sCl
                                        );

                                Intent intent =
                                        new Intent(
                                                source,
                                                idSearchClass
                                        );

                                source.startActivity(intent);

                                source.overridePendingTransition(
                                        0,
                                        0
                                );

                                /*
                                 * 只有辅助页启动成功后才阻止原始跳转。
                                 */
                                param.setResult(null);

                                log("[BRIDGE] IDSearchActivity launched");

                                scheduleTimeout(token);

                            } catch (Throwable launchError) {
                                log("[BRIDGE] 启动ID搜索页失败: "
                                        + launchError);

                                /*
                                 * 启动失败时不拦原始方法。
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
    // 辅助 IDSearchActivity 初始化
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

                            sHelperActivity =
                                    new WeakReference<>(activity);

                            prepareVisibleHelperPage(activity);

                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            log("[BRIDGE] IDSearchActivity.init");

                        } catch (Throwable t) {
                            log("[BRIDGE] IDSearchActivity.init异常: "
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

                            sHelperActivity =
                                    new WeakReference<>(activity);

                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            log("[BRIDGE] IDSearchActivity.init完成");

                        } catch (Throwable t) {
                            log("[BRIDGE] IDSearchActivity.init after异常: "
                                    + t);
                        }
                    }
                }
        );

        log("IDSearchActivity.init hook OK");
    }

    private static void prepareVisibleHelperPage(
            Activity activity
    ) {
        try {
            /*
             * BaseBindingActivity.A 是当前 binding。
             */
            Object binding =
                    XposedHelpers.getObjectField(
                            activity,
                            "A"
                    );

            if (binding == null) {
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
             * 隐藏找回 ID 的 overlay，但保留正常页面背景、
             * Fragment 容器和 RecyclerView。
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
            }

        } catch (Throwable t) {
            log("[BRIDGE] helper页面处理失败: " + t);
        }
    }

    // ============================================================
    // UserNameSearchFragment 初始化
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

                            if (!"com.hellotalk.search.v2.view.IDSearchActivity"
                                    .equals(
                                            activity.getClass().getName()
                                    )) {
                                return;
                            }

                            long token =
                                    sPendingToken;

                            if (sFragmentRequestToken == token) {
                                return;
                            }

                            String username =
                                    sPendingUsername;

                            if (isBlank(username)) {
                                return;
                            }

                            sFragmentRequestToken = token;

                            sHelperActivity =
                                    new WeakReference<>(activity);

                            log("[BRIDGE] UserNameSearchFragment ready");

                            final Object finalFragment =
                                    fragment;

                            final String finalUsername =
                                    username;

                            mainHandler().post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (!PENDING.get()
                                                        || sPendingToken != token) {
                                                    return;
                                                }

                                                /*
                                                 * 直接调用 private loadUser，
                                                 * 绕过 requestUser 内部的500ms debounce。
                                                 */
                                                XposedHelpers.callMethod(
                                                        finalFragment,
                                                        "loadUser",
                                                        finalUsername
                                                );

                                                log("[BRIDGE] loadUser called");

                                            } catch (Throwable t) {
                                                log("[BRIDGE] loadUser失败: "
                                                        + t);
                                                clearAndFinishHelper();
                                            }
                                        }
                                    }
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] Fragment init失败: " + t);
                            clearAndFinishHelper();
                        }
                    }
                }
        );

        log("UserNameSearchFragment.initViewData hook OK");
    }

    // ============================================================
    // Paging 刷新状态和 adapter 快照
    // ============================================================

    private static void hookPagingRefreshState()
            throws Throwable {
        Class<?> baseFragment =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.BaseUserPagingFragment",
                        sCl
                );

        Class<?> loadStates =
                XposedHelpers.findClass(
                        "f4.h",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                baseFragment,
                "onRefreshLoadState",
                loadStates,
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

                            if (!isOurUsernameFragment(fragment)) {
                                return;
                            }

                            Object state =
                                    XposedHelpers.callMethod(
                                            param.args[0],
                                            "b"
                                    );

                            if (state == null) {
                                return;
                            }

                            String stateName =
                                    state.getClass().getName();

                            /*
                             * f4.w$a = Error
                             */
                            if ("f4.w$a".equals(stateName)) {
                                log("[BRIDGE] ID搜索 Paging Error");
                                clearAndFinishHelper();
                                return;
                            }

                            /*
                             * f4.w$c = NotLoading
                             */
                            if (!"f4.w$c".equals(stateName)) {
                                return;
                            }

                            Object adapter =
                                    XposedHelpers.getObjectField(
                                            fragment,
                                            "userListAdapter"
                                    );

                            if (adapter == null) {
                                return;
                            }

                            Object snapshot =
                                    XposedHelpers.callMethod(
                                            adapter,
                                            "r"
                                    );

                            if (snapshot == null) {
                                return;
                            }

                            Object listObject =
                                    XposedHelpers.callMethod(
                                            snapshot,
                                            "d"
                                    );

                            if (!(listObject instanceof List)) {
                                return;
                            }

                            List<?> list =
                                    (List<?>) listObject;

                            String wanted =
                                    sPendingUsername;

                            if (isBlank(wanted)) {
                                return;
                            }

                            Object matched =
                                    findMatchingUser(
                                            list,
                                            wanted
                                    );

                            if (matched != null) {
                                int uid =
                                        readUid(matched);

                                if (uid > 0) {
                                    resolveUser(
                                            matched,
                                            uid
                                    );
                                }

                                return;
                            }

                            /*
                             * NotLoading 且列表为空/无匹配时，
                             * 不能立即把第一次 NotLoading 当失败，
                             * 因为 Paging 可能先发出一次初始状态。
                             * 后续仍由超时保护。
                             */
                            if (list.isEmpty()) {
                                log("[BRIDGE] ID搜索当前快照为空");
                            }

                        } catch (Throwable t) {
                            log("[BRIDGE] Paging状态处理失败: " + t);
                        }
                    }
                }
        );

        log("BaseUserPagingFragment.onRefreshLoadState hook OK");
    }

    private static boolean isOurUsernameFragment(
            Object fragment
    ) {
        if (fragment == null) {
            return false;
        }

        if (!"com.hellotalk.search.v2.logic.controller.searchuser.UserNameSearchFragment"
                .equals(
                        fragment.getClass().getName()
                )) {
            return false;
        }

        try {
            Object activity =
                    XposedHelpers.callMethod(
                            fragment,
                            "getActivity"
                    );

            return activity != null
                    && "com.hellotalk.search.v2.view.IDSearchActivity"
                    .equals(
                            activity.getClass().getName()
                    );

        } catch (Throwable t) {
            return false;
        }
    }

    private static Object findMatchingUser(
            List<?> list,
            String wanted
    ) {
        for (Object item : list) {
            if (item == null) {
                continue;
            }

            String username =
                    normalizeUsername(
                            readUsername(item)
                    );

            if (!wanted.equals(username)) {
                continue;
            }

            if (readUid(item) > 0) {
                return item;
            }
        }

        return null;
    }

    // ============================================================
    // 成功处理
    // ============================================================

    private static void resolveUser(
            Object item,
            int uid
    ) {
        if (!PENDING.compareAndSet(true, false)) {
            return;
        }

        String username =
                normalizeUsername(
                        readUsername(item)
                );

        if (!isBlank(username)) {
            UID_CACHE.put(username, uid);
        }

        Activity source =
                sSourceActivity.get();

        Activity helper =
                sHelperActivity.get();

        sPendingUsername = null;
        sSourceActivity =
                new WeakReference<>(null);
        sHelperActivity =
                new WeakReference<>(null);
        sPendingToken = 0L;
        sFragmentRequestToken = 0L;

        if (source == null
                || source.isFinishing()
                || isDestroyed(source)) {
            finishActivity(helper);
            return;
        }

        log("[BRIDGE] resolved "
                + username
                + " -> "
                + uid);

        mainHandler().post(
                new Runnable() {
                    @Override
                    public void run() {
                        callNativeProfile(
                                source,
                                item
                        );

                        finishActivity(helper);
                    }
                }
        );
    }

    private static void openProfileByUid(
            Activity source,
            int uid
    ) {
        try {
            Class<?> profileProvider =
                    XposedHelpers.findClass(
                            "com.hellotalk.ht.base.router.provider.IProfileProvider",
                            sCl
                    );

            Object router =
                    XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass(
                                    "com.hellotalk.ht.base.router.RouterManager",
                                    sCl
                            ),
                            "getInstance"
                    );

            Object provider =
                    XposedHelpers.callMethod(
                            router,
                            "getHTProfile"
                    );

            XposedHelpers.callMethod(
                    provider,
                    "E3",
                    source,
                    uid,
                    2
            );

        } catch (Throwable t) {
            log("[BRIDGE] cache profile跳转失败: " + t);
        }
    }

    private static void callNativeProfile(
            Activity source,
            Object item
    ) {
        try {
            Class<?> sl0 =
                    XposedHelpers.findClass(
                            "sl0.c",
                            sCl
                    );

            Object singleton =
                    XposedHelpers.getStaticObjectField(
                            sl0,
                            "a"
                    );

            XposedHelpers.callMethod(
                    singleton,
                    "e",
                    source,
                    item,
                    "user_filter_word",
                    "SearchService",
                    0
            );

            log("[BRIDGE] sl0.c.e called");

        } catch (Throwable t) {
            log("[BRIDGE] sl0.c.e失败: " + t);
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

                        if (sPendingToken != token) {
                            return;
                        }

                        log("[BRIDGE] 反查超时: "
                                + sPendingUsername);

                        clearAndFinishHelper();
                    }
                },
                TIMEOUT_MS
        );
    }

    private static void finishActivity(
            Activity activity
    ) {
        if (activity == null) {
            return;
        }

        try {
            if (!activity.isFinishing()) {
                activity.finish();
                activity.overridePendingTransition(
                        0,
                        0
                );
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearAndFinishHelper() {
        Activity helper =
                sHelperActivity.get();

        clearPending();
        finishActivity(helper);
    }

    private static void clearPending() {
        PENDING.set(false);

        sPendingUsername = null;

        sSourceActivity =
                new WeakReference<>(null);

        sHelperActivity =
                new WeakReference<>(null);

        sPendingToken = 0L;
        sFragmentRequestToken = 0L;
    }

    // ============================================================
    // 工具
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

    private static String normalizeUsername(
            String username
    ) {
        if (username == null) {
            return null;
        }

        username = username.trim();

        if (username.startsWith("@")) {
            username = username.substring(1);
        }

        return username;
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
