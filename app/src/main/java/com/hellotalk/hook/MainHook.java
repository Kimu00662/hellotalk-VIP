package com.hellotalk.hook;

import android.app.Activity;
import android.content.Context;
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

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private static final Object LOCK = new Object();

    private static final AtomicBoolean PENDING =
            new AtomicBoolean(false);

    private static WeakReference<Activity> pendingActivity =
            new WeakReference<>(null);

    private static WeakReference<Object> nameSearchView =
            new WeakReference<>(null);

    private static volatile String pendingUsername;
    private static volatile long pendingStartTime;

    private static final long TIMEOUT_MS = 15000L;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        /*
         * 每个功能单独保护。
         * 即使搜索桥接 hook 失败，也不能影响假 VIP 和翻译。
         */
        try {
            hookVip();
        } catch (Throwable t) {
            log("hookVip outer fail: " + t);
        }

        try {
            hookTranslate();
        } catch (Throwable t) {
            log("hookTranslate outer fail: " + t);
        }

        try {
            hookUserNameSearchView();
        } catch (Throwable t) {
            log("hookUserNameSearchView outer fail: " + t);
        }

        try {
            hookFilterProfileClick();
        } catch (Throwable t) {
            log("hookFilterProfileClick outer fail: " + t);
        }

        try {
            hookResolvedUserCapture();
        } catch (Throwable t) {
            log("hookResolvedUserCapture outer fail: " + t);
        }

        log("=== HT stable + profile bridge loaded ===");
    }

    // ---------------------------------------------------------------------
    // 假 VIP
    // ---------------------------------------------------------------------

    private static void hookVip() {
        try {
            Class<?> vipClass =
                    XposedHelpers.findClass("xt.h", sCl);

            XposedHelpers.findAndHookMethod(
                    vipClass,
                    "j",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(
                                MethodHookParam param
                        ) {
                            return 100;
                        }
                    }
            );

            log("假VIP OK");
        } catch (Throwable t) {
            log("假VIP FAIL: " + t);
            XposedBridge.log(t);
        }
    }

    // ---------------------------------------------------------------------
    // 无限翻译
    // ---------------------------------------------------------------------

    private static void hookTranslate() {
        try {
            Class<?> translateClass =
                    XposedHelpers.findClass("lx.o", sCl);

            XposedHelpers.findAndHookMethod(
                    translateClass,
                    "h",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(
                                MethodHookParam param
                        ) {
                            return true;
                        }
                    }
            );

            log("翻译 OK");
        } catch (Throwable t) {
            log("翻译 FAIL: " + t);
            XposedBridge.log(t);
        }
    }

    // ---------------------------------------------------------------------
    // 记录 UserNameSearchView
    //
    // SearchFilterActivityV2$e.a() 会调用 UserNameSearchView.L(int)
    // 创建当前高级搜索页自己的 SearchIDViewV2。
    // ---------------------------------------------------------------------

    private static void hookUserNameSearchView() {
        try {
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
                            try {
                                nameSearchView =
                                        new WeakReference<>(param.thisObject);

                                log("UserNameSearchView.L OK，已记录控件");
                            } catch (Throwable t) {
                                log("记录 UserNameSearchView 失败: " + t);
                            }
                        }
                    }
            );

            log("UserNameSearchView.L hook OK");
        } catch (Throwable t) {
            log("UserNameSearchView.L hook FAIL: " + t);
            XposedBridge.log(t);
        }
    }

    // ---------------------------------------------------------------------
    // 高级搜索点击入口
    //
    // 只 hook SearchUserViewModel：
    // - 高级自定义搜索使用它；
    // - 首页 recommend 使用 SearchListViewModel，不会被影响。
    // ---------------------------------------------------------------------

    private static void hookFilterProfileClick() {
        try {
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

                                /*
                                 * 真实 ID 完全放行。
                                 * 国籍、年龄、新用户等正常结果不受影响。
                                 */
                                if (uid != 0) {
                                    return;
                                }

                                if (isBlank(username)) {
                                    log("userid=0 且 username 为空，放行");
                                    return;
                                }

                                synchronized (LOCK) {
                                    if (PENDING.get()) {
                                        log("已有 pending，放行本次点击");
                                        return;
                                    }

                                    PENDING.set(true);
                                    pendingActivity =
                                            new WeakReference<>(activity);
                                    pendingUsername = username;
                                    pendingStartTime =
                                            System.currentTimeMillis();
                                }

                                log("拦截 userid=0: " + username);

                                /*
                                 * 原方法继续执行会使用 user_id=0，
                                 * 因此必须阻止原方法。
                                 */
                                param.setResult(null);

                                Object view =
                                        findCurrentNameSearchView(activity);

                                if (view == null) {
                                    log("找不到 UserNameSearchView");
                                    clearPending();
                                    return;
                                }

                                final Object finalView = view;
                                final String finalUsername = username;

                                /*
                                 * M() 内部会查找 SearchIDViewV2。
                                 * 如果当前 Fragment 尚未挂载，先调用 L()，
                                 * 再延迟调用 M()。
                                 */
                                MAIN_HANDLER.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            if (!PENDING.get()) {
                                                return;
                                            }

                                            Object fragment =
                                                    XposedHelpers.callMethod(
                                                            finalView,
                                                            "G"
                                                    );

                                            if (fragment == null) {
                                                /*
                                                 * 0x7f0a181e 是已确认的
                                                 * search_user_name_container。
                                                 */
                                                XposedHelpers.callMethod(
                                                        finalView,
                                                        "L",
                                                        0x7f0a181e
                                                );
                                            }

                                            MAIN_HANDLER.postDelayed(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            try {
                                                                if (!PENDING.get()) {
                                                                    return;
                                                                }

                                                                log("调用原生 M(): "
                                                                        + finalUsername);

                                                                XposedHelpers
                                                                        .callMethod(
                                                                                finalView,
                                                                                "M",
                                                                                finalUsername
                                                                        );

                                                                scheduleTimeout();
                                                            } catch (Throwable t) {
                                                                log("调用 M() 失败: " + t);
                                                                clearPending();
                                                            }
                                                        }
                                                    },
                                                    250L
                                            );
                                        } catch (Throwable t) {
                                            log("准备原生用户名搜索失败: " + t);
                                            clearPending();
                                        }
                                    }
                                });

                            } catch (Throwable t) {
                                log("goToProfile hook error: " + t);
                                clearPending();
                            }
                        }
                    }
            );

            log("SearchUserViewModel.goToProfile hook OK");
        } catch (Throwable t) {
            log("SearchUserViewModel.goToProfile hook FAIL: " + t);
            XposedBridge.log(t);
        }
    }

    // ---------------------------------------------------------------------
    // 捕获原生用户名搜索返回的真实用户
    //
    // 原生 Paging 结果进入 adapter 后，会读取 rl0.e.T()。
    // 只在 pending 状态下匹配同名、非零 userid。
    // ---------------------------------------------------------------------

    private static void hookResolvedUserCapture() {
        try {
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

                                Object result =
                                        param.getResult();

                                if (!(result instanceof Integer)) {
                                    return;
                                }

                                int uid = (Integer) result;

                                if (uid <= 0) {
                                    return;
                                }

                                String username =
                                        readUsername(item);

                                String wanted =
                                        pendingUsername;

                                if (isBlank(username)
                                        || wanted == null
                                        || !wanted.equals(username)) {
                                    return;
                                }

                                Activity activity =
                                        pendingActivity.get();

                                if (activity == null
                                        || activity.isFinishing()
                                        || isDestroyed(activity)) {
                                    log("匹配到真实用户，但 Activity 已失效");
                                    clearPending();
                                    return;
                                }

                                /*
                                 * 只允许一个结果触发跳转。
                                 */
                                if (!PENDING.compareAndSet(true, false)) {
                                    return;
                                }

                                pendingUsername = null;
                                pendingActivity =
                                        new WeakReference<>(null);
                                pendingStartTime = 0L;

                                log("匹配到真实用户: "
                                        + username
                                        + " -> "
                                        + uid);

                                final Activity finalActivity = activity;
                                final Object finalItem = item;

                                MAIN_HANDLER.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        invokeNativeProfileEntry(
                                                finalActivity,
                                                finalItem
                                        );
                                    }
                                });

                            } catch (Throwable t) {
                                log("捕获真实用户失败: " + t);
                            }
                        }
                    }
            );

            log("rl0.e.T capture hook OK");
        } catch (Throwable t) {
            log("rl0.e.T capture hook FAIL: " + t);
            XposedBridge.log(t);
        }
    }

    // ---------------------------------------------------------------------
    // 调用原生最终主页入口
    // ---------------------------------------------------------------------

    private static void invokeNativeProfileEntry(
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
             * UserNameSearchFragment.goToProfile() 的默认参数：
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

            log("已调用原生 sl0.c.e()");
        } catch (Throwable t) {
            log("调用 sl0.c.e() 失败: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 找到当前 Activity 中的 UserNameSearchView
    // ---------------------------------------------------------------------

    private static Object findCurrentNameSearchView(
            Activity activity
    ) {
        try {
            Object cached = nameSearchView.get();

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
                nameSearchView =
                        new WeakReference<>(found);
            }

            return found;
        } catch (Throwable t) {
            log("查找 UserNameSearchView 异常: " + t);
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
                Context base =
                        ((ContextWrapper) context).getBaseContext();

                return base == activity;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    // ---------------------------------------------------------------------
    // pending 超时与字段读取
    // ---------------------------------------------------------------------

    private static void scheduleTimeout() {
        final long requestTime = pendingStartTime;

        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!PENDING.get()) {
                    return;
                }

                if (pendingStartTime != requestTime) {
                    return;
                }

                if (System.currentTimeMillis() - requestTime
                        >= TIMEOUT_MS) {
                    log("原生用户名搜索超时: " + pendingUsername);
                    clearPending();
                }
            }
        }, TIMEOUT_MS + 300L);
    }

    private static void clearPending() {
        synchronized (LOCK) {
            PENDING.set(false);
            pendingUsername = null;
            pendingActivity =
                    new WeakReference<>(null);
            pendingStartTime = 0L;
        }
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

    private static boolean isBlank(String value) {
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
