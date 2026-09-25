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

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookFilterClick();
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
                hookRefreshState();
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
                hookDiag6090();
            }
        });

        log("=== HelloTalk Hook loaded ===");
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

                            Object snapshot =
                                    XposedHelpers.callMethod(
                                            adapter,
                                            "r"
                                    );

                            if (snapshot == null) {
                                clearAndFinishHelper();
                                return;
                            }

                            Object listObject =
                                    XposedHelpers.callMethod(
                                            snapshot,
                                            "d"
                                    );

                            if (!(listObject instanceof List)) {
                                clearAndFinishHelper();
                                return;
                            }

                            List<?> list =
                                    (List<?>) listObject;

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
                        callNativeProfile(
                                finalSource,
                                finalItem
                        );

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
                            "Y"
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
    // 诊断（临时）：6.0.90 高级筛选 uid=0 点击链路
    // 目的：确认点击入口与条目字段（uid / 用户名 getter），定位后删除
    // ============================================================

    private static final String[] DIAG_GETTERS = {
            "W", "C", "E", "G", "I", "O", "P", "Q", "Y", "Z",
            "b", "b0", "g0", "h", "h0", "i0", "j", "j0", "k", "k0",
            "l", "p", "q0", "s", "D", "F", "K", "L", "M", "N",
            "T", "a0", "c0", "d0", "g", "i", "m", "n", "r", "v", "w", "z"
    };

    private static void hookDiag6090() {
        hookDiagMethod("com.hellotalk.search.v2.viewmodel.SearchUserViewModel", "goToProfile");
    }

    private static void hookDiagMethod(
            final String className,
            final String methodName
    ) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(className, sCl);
            if (c == null) {
                log("[DIAG] 类不存在: " + className);
                return;
            }
            XposedBridge.hookAllMethods(c, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    dumpDiag(className + "." + methodName, param.args);
                }
            });
            log("[DIAG] hook OK: " + className + "." + methodName);
        } catch (Throwable t) {
            log("[DIAG] hook 失败 " + className + "." + methodName + ": " + t);
        }
    }

    private static void dumpDiag(String where, Object[] args) {
        try {
            if (args == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) {
                    continue;
                }
                String cn = a.getClass().getName();
                if (!"ax0.f".equals(cn) && !"rl0.e".equals(cn)) {
                    continue;
                }
                log("[DIAG] " + where + " uid=" + safeGet(a, "W"));
                for (String m : DIAG_GETTERS) {
                    Object v = safeGet(a, m);
                    if (v != null) {
                        log("[DIAG]   " + m + " = " + v);
                    }
                }
            }
        } catch (Throwable t) {
            log("[DIAG] dump失败: " + t);
        }
    }

    private static Object safeGet(Object obj, String method) {
        try {
            return XposedHelpers.callMethod(obj, method);
        } catch (Throwable t) {
            return null;
        }
    }
}
