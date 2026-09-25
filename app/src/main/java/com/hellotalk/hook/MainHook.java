package com.hellotalk.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;
    private static volatile Handler sMainHandler;

    // true = 6.0.90（f4.h 不存在、s8.h 存在）；false = 5.7.0
    private static volatile boolean isHt6090 = false;

    private static final AtomicBoolean PENDING =
            new AtomicBoolean(false);

    private static final AtomicLong TOKEN_COUNTER =
            new AtomicLong(0L);

    private static volatile long pendingToken;
    private static volatile long fragmentToken;
    private static volatile long emptyCheckToken;

    private static volatile String pendingUsername;

    private static WeakReference<Activity> sourceActivity =
            new WeakReference<>(null);

    private static WeakReference<Activity> helperActivity =
            new WeakReference<>(null);

    private static final Map<String, Integer> UID_CACHE =
            new ConcurrentHashMap<>();

    private static final long TIMEOUT_MS = 20000L;
    private static final long EMPTY_RECHECK_MS = 800L;

    private interface HookTask {
        void run() throws Throwable;
    }

    @Override
    public void handleLoadPackage(
            final LoadPackageParam lpparam
    ) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        isHt6090 = XposedHelpers.findClassIfExists("f4.h", sCl) == null
                && XposedHelpers.findClassIfExists("s8.h", sCl) != null;

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                if (isHt6090) {
                    hookFilterClick6090();
                } else {
                    hookFilterClick();
                }
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookIdSearchInit();
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
                if (isHt6090) {
                    hookRefreshState6090();
                } else {
                    hookRefreshState();
                }
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookFakeVip();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                startPerfDiag();
            }
        });

        log("=== HelloTalk Hook loaded (ht6090=" + isHt6090 + ") ===");
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
        Handler h = sMainHandler;

        if (h != null) {
            return h;
        }

        synchronized (MainHook.class) {
            h = sMainHandler;

            if (h == null) {
                try {
                    h = new Handler(Looper.getMainLooper());
                    sMainHandler = h;
                } catch (Throwable t) {
                    log("Handler懒加载失败: " + t);
                    return null;
                }
            }
        }

        return h;
    }

    private static boolean post(Runnable task) {
        try {
            Handler h = handler();
            return h != null && h.post(task);
        } catch (Throwable t) {
            log("Handler post失败: " + t);
            return false;
        }
    }

    private static boolean postDelayed(
            Runnable task,
            long delayMs
    ) {
        try {
            Handler h = handler();
            return h != null && h.postDelayed(task, delayMs);
        } catch (Throwable t) {
            log("Handler postDelayed失败: " + t);
            return false;
        }
    }

    // ============================================================
    // 高级筛选 userid=0 点击
    // ============================================================

    private static void hookFilterClick()
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
                        try {
                            Activity activity =
                                    (Activity) param.args[0];

                            Object item =
                                    param.args[1];

                            int uid =
                                    readUid(item);

                            String username =
                                    normalize(
                                            readUsername(item)
                                    );

                            log("[BRIDGE] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 真实 userid 原样放行。
                             */
                            if (uid > 0) {
                                return;
                            }

                            /*
                             * 无 username 无法反查，原样放行。
                             */
                            if (isBlank(username)) {
                                return;
                            }

                            /*
                             * 反查期间吞掉后续 userid=0 点击。
                             */
                            if (PENDING.get()) {
                                log("[BRIDGE] pending期间阻止userid=0点击");
                                param.setResult(null);
                                return;
                            }

                            /*
                             * 成功结果缓存。
                             */
                            Integer cached =
                                    UID_CACHE.get(username);

                            if (cached != null
                                    && cached > 0) {
                                param.setResult(null);

                                final Activity finalActivity =
                                        activity;

                                final int finalUid =
                                        cached;

                                post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                openProfileByUid(
                                                        finalActivity,
                                                        finalUid
                                                );
                                            }
                                        }
                                );

                                return;
                            }

                            if (!PENDING.compareAndSet(
                                    false,
                                    true
                            )) {
                                param.setResult(null);
                                return;
                            }

                            long token =
                                    TOKEN_COUNTER.incrementAndGet();

                            pendingToken = token;
                            fragmentToken = 0L;
                            emptyCheckToken = 0L;
                            pendingUsername = username;

                            sourceActivity =
                                    new WeakReference<>(
                                            activity
                                    );

                            helperActivity =
                                    new WeakReference<>(
                                            null
                                    );

                            try {
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
                                activity.overridePendingTransition(
                                        0,
                                        0
                                );

                                /*
                                 * 启动成功后拦截原始 userid=0 跳转。
                                 */
                                param.setResult(null);

                                log("[BRIDGE] IDSearchActivity launched");

                                scheduleTimeout(token);

                            } catch (Throwable launchError) {
                                log("[BRIDGE] 启动ID搜索页失败: "
                                        + launchError);

                                /*
                                 * 启动失败时不阻断原始逻辑。
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
    // IDSearchActivity
    // ============================================================

    private static void hookIdSearchInit()
            throws Throwable {
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
                        if (!PENDING.get()) {
                            return;
                        }

                        try {
                            Activity activity =
                                    (Activity) param.thisObject;

                            helperActivity =
                                    new WeakReference<>(
                                            activity
                                    );

                            prepareHelperPage(activity);

                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            log("[BRIDGE] IDSearchActivity.init");

                        } catch (Throwable t) {
                            log("[BRIDGE] helper init处理失败: "
                                    + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        if (!PENDING.get()) {
                            return;
                        }

                        helperActivity =
                                new WeakReference<>(
                                        (Activity) param.thisObject
                                );

                        log("[BRIDGE] IDSearchActivity.init完成");
                    }
                }
        );

        log("IDSearchActivity.init hook OK");
    }

    private static void prepareHelperPage(
            Activity activity
    ) {
        try {
            /*
             * BaseBindingActivity.A 是 binding。
             */
            Object binding =
                    XposedHelpers.getObjectField(
                            activity,
                            isHt6090 ? "B" : "A"
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

            try {
                Object overlay =
                        XposedHelpers.getObjectField(
                                binding,
                                "overlay"
                        );

                Object root =
                        XposedHelpers.callMethod(
                                overlay,
                                "getRoot"
                        );

                if (root instanceof View) {
                    ((View) root).setVisibility(
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
    // UserNameSearchFragment
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

                            if (!(activityObject instanceof Activity)) {
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
                                    pendingToken;

                            if (fragmentToken == token) {
                                return;
                            }

                            String username =
                                    pendingUsername;

                            if (isBlank(username)) {
                                return;
                            }

                            fragmentToken = token;

                            helperActivity =
                                    new WeakReference<>(
                                            activity
                                    );

                            final Object finalFragment =
                                    fragment;

                            final String finalUsername =
                                    username;

                            log("[BRIDGE] UserNameSearchFragment ready");

                            post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (!PENDING.get()
                                                        || pendingToken != token) {
                                                    return;
                                                }

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
    // Paging 状态
    // ============================================================

    private static void hookRefreshState()
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

                            if (!isOurFragment(fragment)) {
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

                            String stateClass =
                                    state.getClass().getName();

                            /*
                             * f4.w$a = Error
                             */
                            if ("f4.w$a".equals(stateClass)) {
                                log("[BRIDGE] Paging refresh error");
                                clearAndFinishHelper();
                                return;
                            }

                            /*
                             * f4.w$c = NotLoading
                             */
                            if (!"f4.w$c".equals(stateClass)) {
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
                                    pendingUsername;

                            if (isBlank(wanted)) {
                                return;
                            }

                            Object match =
                                    findMatchingUser(
                                            list,
                                            wanted
                                    );

                            if (match != null) {
                                int uid =
                                        readUid(match);

                                if (uid > 0) {
                                    resolveUser(match, uid);
                                }

                                return;
                            }

                            if (list.isEmpty()) {
                                scheduleEmptyRecheck(fragment);
                            }

                        } catch (Throwable t) {
                            log("[BRIDGE] Paging状态处理失败: "
                                    + t);
                        }
                    }
                }
        );

        log("BaseUserPagingFragment.onRefreshLoadState hook OK");
    }

    private static void scheduleEmptyRecheck(
            final Object fragment
    ) {
        final long token =
                pendingToken;

        if (emptyCheckToken == token) {
            return;
        }

        emptyCheckToken = token;

        postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!PENDING.get()
                                    || pendingToken != token) {
                                return;
                            }

                            Object adapter =
                                    XposedHelpers.getObjectField(
                                            fragment,
                                            "userListAdapter"
                                    );

                            if (adapter == null) {
                                clearAndFinishHelper();
                                return;
                            }

                            List<?> list =
                                    readAdapterList(adapter);

                            if (list == null) {
                                clearAndFinishHelper();
                                return;
                            }

                            Object match =
                                    findMatchingUser(
                                            list,
                                            pendingUsername
                                    );

                            if (match != null) {
                                int uid =
                                        readUid(match);

                                if (uid > 0) {
                                    resolveUser(match, uid);
                                    return;
                                }
                            }

                            log("[BRIDGE] 二次检查仍为空，结束反查");
                            clearAndFinishHelper();

                        } catch (Throwable t) {
                            log("[BRIDGE] 空结果二次检查失败: "
                                    + t);
                            clearAndFinishHelper();
                        }
                    }
                },
                EMPTY_RECHECK_MS
        );
    }

    private static boolean isOurFragment(
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
        if (list == null
                || isBlank(wanted)) {
            return null;
        }

        for (Object item : list) {
            if (item == null) {
                continue;
            }

            String username =
                    normalize(
                            readUsername(item)
                    );

            if (wanted.equals(username)
                    && readUid(item) > 0) {
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
        if (!PENDING.compareAndSet(
                true,
                false
        )) {
            return;
        }

        String username =
                normalize(
                        readUsername(item)
                );

        if (!isBlank(username)) {
            UID_CACHE.put(
                    username,
                    uid
            );
        }

        Activity source =
                sourceActivity.get();

        Activity helper =
                helperActivity.get();

        clearPendingFields();

        if (source == null
                || source.isFinishing()
                || isDestroyed(source)) {
            finishActivity(helper);
            return;
        }

        final Activity finalSource =
                source;

        final Activity finalHelper =
                helper;

        final Object finalItem =
                item;

        log("[BRIDGE] resolved "
                + username
                + " -> "
                + uid);

        post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (isHt6090) {
                            openProfileByUid6090(
                                    finalSource,
                                    uid
                            );
                        } else {
                            callNativeProfile(
                                    finalSource,
                                    finalItem
                            );
                        }

                        finishActivity(
                                finalHelper
                        );
                    }
                }
        );
    }

    private static void openProfileByUid(
            Activity source,
            int uid
    ) {
        try {
            Class<?> routerClass =
                    XposedHelpers.findClass(
                            "com.hellotalk.ht.base.router.RouterManager",
                            sCl
                    );

            Object router =
                    XposedHelpers.callStaticMethod(
                            routerClass,
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
            /*
             * 缓存路径失败时不影响模块运行。
             */
            log("[BRIDGE] 缓存跳转失败: " + t);
        }
    }

    private static void callNativeProfile(
            Activity source,
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
        postDelayed(
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
        finishActivity(helper);
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

    private static void clearPendingFields() {
        pendingUsername = null;

        sourceActivity =
                new WeakReference<>(null);

        helperActivity =
                new WeakReference<>(null);

        pendingToken = 0L;
        fragmentToken = 0L;
        emptyCheckToken = 0L;
    }

    private static void clearPending() {
        PENDING.set(false);
        clearPendingFields();
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static int readUid(Object item) {
        try {
            if (isHt6090) {
                Object v = XposedHelpers.getObjectField(item, "Y");
                return v instanceof Integer ? (Integer) v : 0;
            }

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

    private static String readUsername(
            Object item
    ) {
        try {
            Object value =
                    XposedHelpers.getObjectField(
                            item,
                            isHt6090 ? "Z" : "Y"
                    );

            return value == null
                    ? null
                    : String.valueOf(value);

        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalize(
            String value
    ) {
        if (value == null) {
            return null;
        }

        value = value.trim();

        if (value.startsWith("@")) {
            value = value.substring(1);
        }

        return value;
    }

    private static boolean isBlank(
            String value
    ) {
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

    private static void log(
            String message
    ) {
        XposedBridge.log(
                "[HT] " + message
        );
    }

    // ============================================================
    // 6.0.90 假 VIP
    // 开关由 SettingsActivity 经 root 写入 /data/local/tmp/htvip_config.txt，
    // 这里直接读该文件（模块私有 prefs 在 hook 进程不可读，故不用 XSharedPreferences）。
    // 关闭时不注册任何 hook，对 HT 零修改。
    // VipInfoUtils.r.c() 是整条 VIP 判定的总出口（r.f()/r.h() 优先采纳其返回值），
    // 返回 100 即解锁高级筛选等全部 VIP 判定。
    // ============================================================

    private static void hookFakeVip() {
        if (!readFakeVipConfig()) {
            log("假VIP: 开关关闭，不注册 hook");
            return;
        }

        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.hellotalk.ht.base.data.utils.r",
                    sCl
            );

            if (cls == null) {
                log("假VIP: 未找到 VipInfoUtils(r)");
                return;
            }

            XposedHelpers.findAndHookMethod(
                    cls,
                    "c",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param
                        ) {
                            param.setResult(100);
                        }
                    }
            );

            log("假VIP: Hook VipInfoUtils.r.c() 注册成功（返回100）");

        } catch (Throwable t) {
            log("假VIP Hook 失败: " + t);
        }
    }

    private static boolean readFakeVipConfig() {
        try {
            java.io.File f = new java.io.File(
                    SettingsActivity.CONFIG_PATH
            );
            if (!f.exists()) {
                return false;
            }

            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f)
            );
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(SettingsActivity.KEY_FAKE_VIP + "=")) {
                    r.close();
                    return "true".equalsIgnoreCase(
                            line.substring(SettingsActivity.KEY_FAKE_VIP.length() + 1).trim()
                    );
                }
            }
            r.close();
        } catch (Throwable t) {
            log("假VIP: 读取配置失败: " + t);
        }

        return false;
    }

    // ============================================================
    // 6.0.90 专用：高级筛选 userid=0 点击桥接
    // 与 5.7.0 差异：入口=UserSearchFragment.onClickUserItem(View,int,ax0.f)；
    //   条目 uid=字段 ax0.f.Y，username=字段 ax0.f.Z；
    //   分页回调参数类型=s8.h（快照=s8.u，列表=s8.u.d()）；
    //   跳主页=OtherProfileActivity.a9(Context, g1[uid])。
    // ============================================================

    private static void hookFilterClick6090()
            throws Throwable {
        Class<?> fragClass =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.UserSearchFragment",
                        sCl
                );

        Class<?> viewClass =
                XposedHelpers.findClass(
                        "android.view.View",
                        sCl
                );

        Class<?> itemClass =
                XposedHelpers.findClass(
                        "ax0.f",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                fragClass,
                "onClickUserItem",
                viewClass,
                int.class,
                itemClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            View view =
                                    (View) param.args[0];

                            Object item =
                                    param.args[2];

                            int uid =
                                    readUid(item);

                            String username =
                                    normalize(
                                            readUsername(item)
                                    );

                            log("[BRIDGE6090] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            if (uid > 0) {
                                return;
                            }

                            if (isBlank(username)) {
                                return;
                            }

                            if (PENDING.get()) {
                                param.setResult(null);
                                return;
                            }

                            Integer cached =
                                    UID_CACHE.get(username);

                            if (cached != null
                                    && cached > 0) {
                                param.setResult(null);

                                final Activity finalActivity =
                                        activityFromView(view);

                                final int finalUid =
                                        cached;

                                if (finalActivity != null) {
                                    post(
                                            new Runnable() {
                                                @Override
                                                public void run() {
                                                    openProfileByUid6090(
                                                            finalActivity,
                                                            finalUid
                                                    );
                                                }
                                            }
                                    );
                                }

                                return;
                            }

                            Activity activity =
                                    activityFromView(view);

                            if (activity == null) {
                                return;
                            }

                            if (!PENDING.compareAndSet(
                                    false,
                                    true
                            )) {
                                param.setResult(null);
                                return;
                            }

                            long token =
                                    TOKEN_COUNTER.incrementAndGet();

                            pendingToken = token;
                            fragmentToken = 0L;
                            emptyCheckToken = 0L;
                            pendingUsername = username;

                            sourceActivity =
                                    new WeakReference<>(
                                            activity
                                    );

                            helperActivity =
                                    new WeakReference<>(
                                            null
                                    );

                            try {
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
                                activity.overridePendingTransition(
                                        0,
                                        0
                                );

                                param.setResult(null);

                                log("[BRIDGE6090] IDSearchActivity launched");

                                scheduleTimeout(token);

                            } catch (Throwable launchError) {
                                log("[BRIDGE6090] 启动ID搜索页失败: "
                                        + launchError);

                                clearPending();
                            }

                        } catch (Throwable t) {
                            log("[BRIDGE6090] 点击处理失败: " + t);
                            clearPending();
                        }
                    }
                }
        );

        log("UserSearchFragment.onClickUserItem hook OK (6.0.90)");
    }

    private static void hookRefreshState6090()
            throws Throwable {
        Class<?> baseFragment =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.BaseUserPagingFragment",
                        sCl
                );

        Class<?> loadStates =
                XposedHelpers.findClass(
                        "s8.h",
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

                            if (!isOurFragment(fragment)) {
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

                            String stateClass =
                                    state.getClass().getName();

                            if ("s8.w$a".equals(stateClass)) {
                                log("[BRIDGE6090] Paging refresh error");
                                clearAndFinishHelper();
                                return;
                            }

                            if (!"s8.w$c".equals(stateClass)) {
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

                            List<?> list =
                                    readAdapterList(adapter);

                            if (list == null) {
                                return;
                            }

                            String wanted =
                                    pendingUsername;

                            if (isBlank(wanted)) {
                                return;
                            }

                            Object match =
                                    findMatchingUser(
                                            list,
                                            wanted
                                    );

                            if (match != null) {
                                int uid =
                                        readUid(match);

                                if (uid > 0) {
                                    resolveUser(match, uid);
                                }

                                return;
                            }

                            if (list.isEmpty()) {
                                scheduleEmptyRecheck(fragment);
                            }

                        } catch (Throwable t) {
                            log("[BRIDGE6090] Paging状态处理失败: "
                                    + t);
                        }
                    }
                }
        );

        log("BaseUserPagingFragment.onRefreshLoadState hook OK (6.0.90)");
    }

    private static List<?> readAdapterList(
            Object adapter
    ) {
        try {
            Object snapshot =
                    XposedHelpers.callMethod(
                            adapter,
                            isHt6090 ? "t" : "r"
                    );

            if (snapshot == null) {
                return null;
            }

            Object listObject =
                    XposedHelpers.callMethod(
                            snapshot,
                            "d"
                    );

            if (listObject instanceof List) {
                return (List<?>) listObject;
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Activity activityFromView(
            View view
    ) {
        try {
            android.content.Context ctx =
                    view.getContext();

            while (ctx instanceof android.content.ContextWrapper) {
                if (ctx instanceof Activity) {
                    return (Activity) ctx;
                }

                ctx = ((android.content.ContextWrapper) ctx)
                        .getBaseContext();
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    private static void openProfileByUid6090(
            Activity source,
            int uid
    ) {
        try {
            Class<?> g1Class =
                    XposedHelpers.findClass(
                            "com.hellotalk.ht.base.common.g1",
                            sCl
                    );

            Object g1 =
                    XposedHelpers.newInstance(
                            g1Class
                    );

            XposedHelpers.setIntField(
                    g1,
                    "a",
                    uid
            );

            Class<?> profileClass =
                    XposedHelpers.findClass(
                            "com.hellotalk.profile.mvvm.view.activity.OtherProfileActivity",
                            sCl
                    );

            XposedHelpers.callStaticMethod(
                    profileClass,
                    "a9",
                    source,
                    g1
            );

            log("[BRIDGE6090] openProfileByUid " + uid);

        } catch (Throwable t) {
            log("[BRIDGE6090] 跳转失败: " + t);
        }
    }

    // ============================================================
    // 性能诊断（临时，默认关）：定位“响应慢 / 滑动卡 / 发热耗电”
    // 开关 perf_diag：开时启用
    //   ① 主线程看门狗：每 300ms 查主线程是否卡住，卡住则抓主线程栈
    //   ② CPU 采样：每 5s 统计各线程 CPU，输出 top 8 线程栈
    // 关闭时不启动任何线程，对 HT 零影响。定位后删除。
    // ============================================================

    private static void startPerfDiag() {
        if (!readPerfDiagConfig()) {
            log("perfDiag: 开关关闭");
            return;
        }

        log("perfDiag: 启动（主线程看门狗 + CPU采样）");

        Thread wd = new Thread(new Runnable() {
            @Override
            public void run() {
                mainThreadWatchdog();
            }
        }, "HT_AI_PerfWD");
        wd.setDaemon(true);
        wd.start();

        Thread cpu = new Thread(new Runnable() {
            @Override
            public void run() {
                cpuSampler();
            }
        }, "HT_AI_PerfCPU");
        cpu.setDaemon(true);
        cpu.start();
    }

    private static boolean readPerfDiagConfig() {
        try {
            java.io.File f = new java.io.File(SettingsActivity.CONFIG_PATH);
            if (!f.exists()) {
                return false;
            }

            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(SettingsActivity.KEY_PERF_DIAG + "=")) {
                    r.close();
                    return "true".equalsIgnoreCase(
                            line.substring(SettingsActivity.KEY_PERF_DIAG.length() + 1).trim());
                }
            }
            r.close();
        } catch (Throwable t) {
            log("perfDiag: 读取配置失败: " + t);
        }

        return false;
    }

    private static void mainThreadWatchdog() {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

        while (true) {
            final java.util.concurrent.atomic.AtomicBoolean done =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final long postTime = System.currentTimeMillis();

            h.post(new Runnable() {
                @Override
                public void run() {
                    done.set(true);
                }
            });

            try {
                Thread.sleep(300);
            } catch (Throwable ignored) {
            }

            if (!done.get()) {
                long blockedMs = System.currentTimeMillis() - postTime;
                log("perfDiag: 主线程阻塞 " + blockedMs + "ms\n" + mainThreadStack());
            }
        }
    }

    private static String mainThreadStack() {
        try {
            Thread main = android.os.Looper.getMainLooper().getThread();
            StackTraceElement[] st = main.getStackTrace();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn == null || cn.startsWith("java.lang") || cn.startsWith("android.os")) {
                    continue;
                }
                sb.append("\n    ").append(cn).append(".").append(e.getMethodName());
                if (++shown >= 12) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(取主线程栈失败: " + t + ")";
        }
    }

    private static void cpuSampler() {
        java.util.Map<Long, Long> prev = new java.util.HashMap<>();

        while (true) {
            try {
                Thread.sleep(5000);
            } catch (Throwable ignored) {
            }

            java.util.Map<Long, String> names = new java.util.HashMap<>();
            java.util.Map<Long, Long> now = new java.util.HashMap<>();
            java.util.Map<Long, Long> delta = new java.util.HashMap<>();
            java.util.Map<Long, Thread> threads = new java.util.HashMap<>();

            try {
                java.util.Map<Thread, StackTraceElement[]> all =
                        Thread.getAllStackTraces();
                for (java.util.Map.Entry<Thread, StackTraceElement[]> en : all.entrySet()) {
                    Thread th = en.getKey();
                    long id = th.getId();
                    names.put(id, th.getName());
                    threads.put(id, th);
                    long cpu = cpuTimeOf(id);
                    if (cpu < 0) {
                        continue;
                    }
                    now.put(id, cpu);
                    Long p = prev.get(id);
                    if (p != null) {
                        delta.put(id, cpu - p);
                    }
                }
            } catch (Throwable t) {
                log("perfDiag: CPU采样失败: " + t);
                prev = now;
                continue;
            }

            prev = now;

            if (delta.isEmpty()) {
                continue;
            }

            List<java.util.Map.Entry<Long, Long>> sorted =
                    new ArrayList<>(delta.entrySet());
            Collections.sort(sorted, new java.util.Comparator<java.util.Map.Entry<Long, Long>>() {
                @Override
                public int compare(java.util.Map.Entry<Long, Long> a,
                                   java.util.Map.Entry<Long, Long> b) {
                    return Long.compare(b.getValue(), a.getValue());
                }
            });

            StringBuilder sb = new StringBuilder("perfDiag: 5s CPU top:\n");
            int n = 0;
            for (java.util.Map.Entry<Long, Long> e : sorted) {
                long id = e.getKey();
                long ms = e.getValue() / 1000L;
                if (ms < 50) {
                    break;
                }
                sb.append("  [").append(ms).append("ms] ")
                        .append(names.get(id))
                        .append(" (tid=").append(id).append(")\n")
                        .append(topFrames(threads.get(id)))
                        .append("\n");
                if (++n >= 8) {
                    break;
                }
            }

            if (n > 0) {
                log(sb.toString());
            }
        }
    }

    private static long cpuTimeOf(long tid) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/task/" + tid + "/stat"));
            String line = r.readLine();
            r.close();
            if (line == null) {
                return -1;
            }
            int end = line.lastIndexOf(')');
            if (end < 0) {
                return -1;
            }
            String[] f = line.substring(end + 2).trim().split("\\s+");
            // 字段 14/15（0-based 11/12）= utime/stime，单位 tick
            long utime = Long.parseLong(f[11]);
            long stime = Long.parseLong(f[12]);
            return (utime + stime) * 10000L;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String topFrames(Thread th) {
        if (th == null) {
            return "";
        }

        try {
            StackTraceElement[] st = th.getStackTrace();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn == null || cn.startsWith("java.lang") || cn.startsWith("android.os")) {
                    continue;
                }
                sb.append("      ").append(cn).append(".").append(e.getMethodName()).append("\n");
                if (++shown >= 6) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
