package com.hellotalk.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader cl;

    private static final Handler MAIN =
            new Handler(Looper.getMainLooper());

    private static final Object LOCK = new Object();

    private static WeakReference<Object> lastNameSearchView =
            new WeakReference<>(null);

    private static WeakReference<Activity> pendingActivity =
            new WeakReference<>(null);

    private static volatile String pendingUsername;
    private static volatile long pendingSince;
    private static final AtomicBoolean pending = new AtomicBoolean(false);

    private static final long PENDING_TIMEOUT_MS = 15000L;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        cl = lpparam.classLoader;

        hookVip();
        hookTranslate();

        // 当前高级搜索页面中的顶部用户名搜索控件
        hookNameSearchView();

        // 高级搜索列表点击入口
        hookFilterProfileClick();

        // 捕获原生用户名搜索返回的真实对象
        hookUserIdGetter();

        log("=== HT final bridge module loaded ===");
    }

    // ---------------------------------------------------------------------
    // 假 VIP
    // ---------------------------------------------------------------------

    private void hookVip() {
        try {
            XposedHelpers.findAndHookMethod(
                    "xt.h",
                    cl,
                    "j",
                    XC_MethodReplacement.returnConstant(100)
            );
            log("假VIP OK");
        } catch (Throwable t) {
            log("假VIP FAIL: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 无限翻译
    // ---------------------------------------------------------------------

    private void hookTranslate() {
        try {
            XposedHelpers.findAndHookMethod(
                    "lx.o",
                    cl,
                    "h",
                    XC_MethodReplacement.returnConstant(true)
            );
            log("翻译 OK");
        } catch (Throwable t) {
            log("翻译 FAIL: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 记录高级搜索页的 UserNameSearchView
    //
    // L(int) 会创建并挂载 SearchIDViewV2。
    // ---------------------------------------------------------------------

    private void hookNameSearchView() {
        try {
            Class<?> viewClass = XposedHelpers.findClass(
                    "com.hellotalk.search.v2.widget.UserNameSearchView",
                    cl
            );

            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "L",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object view = param.thisObject;
                                lastNameSearchView =
                                        new WeakReference<>(view);

                                log("记录 UserNameSearchView: "
                                        + view.getClass().getName());
                            } catch (Throwable t) {
                                log("记录 UserNameSearchView 失败: " + t);
                            }
                        }
                    }
            );

            log("UserNameSearchView.L hook OK");
        } catch (Throwable t) {
            log("UserNameSearchView.L hook FAIL: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 拦截高级搜索列表点击
    //
    // 只 hook SearchUserViewModel，不碰：
    // - SearchListViewModel
    // - recommend 首页
    // - 已经有真实 userid 的用户
    // ---------------------------------------------------------------------

    private void hookFilterProfileClick() {
        try {
            Class<?> vmClass = XposedHelpers.findClass(
                    "com.hellotalk.search.v2.viewmodel.SearchUserViewModel",
                    cl
            );

            Class<?> activityClass =
                    XposedHelpers.findClass(
                            "android.app.Activity",
                            cl
                    );

            Class<?> itemClass =
                    XposedHelpers.findClass(
                            "rl0.e",
                            cl
                    );

            XposedHelpers.findAndHookMethod(
                    vmClass,
                    "goToProfile",
                    activityClass,
                    itemClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity =
                                        (Activity) param.args[0];

                                Object item = param.args[1];

                                int uid = readUid(item);
                                String username = readUsername(item);

                                log("高级搜索点击: uid=" + uid
                                        + ", username=" + username);

                                // 正常用户完全放行
                                if (uid != 0) {
                                    return;
                                }

                                // 没有 username 无法走原生反查，放行原逻辑
                                if (isBlank(username)) {
                                    log("userid=0 且 username 为空，放行原逻辑");
                                    return;
                                }

                                // 避免同时处理多个点击
                                synchronized (LOCK) {
                                    if (pending.get()) {
                                        log("已有 pending 请求，放行本次点击");
                                        return;
                                    }

                                    pendingActivity =
                                            new WeakReference<>(activity);
                                    pendingUsername = username;
                                    pendingSince =
                                            System.currentTimeMillis();
                                    pending.set(true);
                                }

                                log("拦截 userid=0，转入原生用户名搜索: "
                                        + username);

                                // 原方法会把 userid=0 交给 sl0.c，
                                // 必然打开错误页面，所以阻止它。
                                param.setResult(null);

                                final Object view =
                                        getUsableNameSearchView(activity);

                                if (view == null) {
                                    log("找不到当前 UserNameSearchView");
                                    clearPending();
                                    return;
                                }

                                // 等待 FragmentTransaction 完成后再调用 M()
                                MAIN.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            if (!pending.get()) {
                                                return;
                                            }

                                            Activity a =
                                                    pendingActivity.get();

                                            if (a == null
                                                    || a.isFinishing()
                                                    || isDestroyed(a)) {
                                                log("Activity 已失效");
                                                clearPending();
                                                return;
                                            }

                                            String name =
                                                    pendingUsername;

                                            log("调用原生 UserNameSearchView.M("
                                                    + name + ")");

                                            XposedHelpers.callMethod(
                                                    view,
                                                    "M",
                                                    name
                                            );

                                            scheduleTimeout();
                                        } catch (Throwable t) {
                                            log("调用原生 M() 失败: " + t);
                                            clearPending();
                                        }
                                    }
                                }, 150L);

                            } catch (Throwable t) {
                                log("goToProfile hook 异常: " + t);
                            }
                        }
                    }
            );

            log("SearchUserViewModel.goToProfile hook OK");
        } catch (Throwable t) {
            log("SearchUserViewModel.goToProfile hook FAIL: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 捕获原生用户名搜索返回的真实 rl0.e
    //
    // 注意：这里不自动反查。
    // 只有 pendingUsername 存在时才匹配。
    // ---------------------------------------------------------------------

    private void hookUserIdGetter() {
        try {
            Class<?> itemClass = XposedHelpers.findClass(
                    "rl0.e",
                    cl
            );

            XposedHelpers.findAndHookMethod(
                    itemClass,
                    "T",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!pending.get()) {
                                    return;
                                }

                                Object item = param.thisObject;
                                int uid = readUidFromResult(param);
                                String username = readUsername(item);

                                if (uid <= 0 || isBlank(username)) {
                                    return;
                                }

                                String wanted = pendingUsername;

                                if (wanted == null
                                        || !wanted.equals(username)) {
                                    return;
                                }

                                Activity activity =
                                        pendingActivity.get();

                                if (activity == null
                                        || activity.isFinishing()
                                        || isDestroyed(activity)) {
                                    log("真实结果匹配，但 Activity 已失效");
                                    clearPending();
                                    return;
                                }

                                // 防止同一个对象被 Paging/Adapter 多次读取
                                if (!pending.compareAndSet(true, false)) {
                                    return;
                                }

                                pendingUsername = null;
                                pendingActivity =
                                        new WeakReference<>(null);

                                log("匹配到真实用户: username="
                                        + username
                                        + ", userid=" + uid);

                                final Activity finalActivity = activity;
                                final Object finalItem = item;

                                MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            invokeNativeProfileEntry(
                                                    finalActivity,
                                                    finalItem
                                            );
                                        } catch (Throwable t) {
                                            log("原生主页跳转失败: " + t);
                                        }
                                    }
                                });

                            } catch (Throwable t) {
                                log("T() 捕获真实对象异常: " + t);
                            }
                        }
                    }
            );

            log("rl0.e.T hook OK");
        } catch (Throwable t) {
            log("rl0.e.T hook FAIL: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 调用 App 原生最终主页出口 sl0.c.e()
    //
    // UserNameSearchFragment 原生使用：
    // source     = "user_filter_word"
    // filterType = "SearchService"
    // position   = 0
    // ---------------------------------------------------------------------

    private static void invokeNativeProfileEntry(
            Activity activity,
            Object item
    ) {
        try {
            Class<?> sl0c = XposedHelpers.findClass(
                    "sl0.c",
                    cl
            );

            Object singleton =
                    XposedHelpers.getStaticObjectField(sl0c, "a");

            XposedHelpers.callMethod(
                    singleton,
                    "e",
                    activity,
                    item,
                    "user_filter_word",
                    "SearchService",
                    0
            );

            log("已调用 sl0.c.e()，userid="
                    + readUid(item));
        } catch (Throwable t) {
            log("调用 sl0.c.e() 失败: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 获取当前可用的 UserNameSearchView
    // ---------------------------------------------------------------------

    private static Object getUsableNameSearchView(Activity activity) {
        Object view = lastNameSearchView.get();

        if (view != null && belongsToActivity(view, activity)) {
            return view;
        }

        // 兜底：从 Activity 的 View 树递归查找
        try {
            View root = activity.getWindow().getDecorView();

            Object found = findViewByClass(
                    root,
                    "com.hellotalk.search.v2.widget.UserNameSearchView"
            );

            if (found != null) {
                lastNameSearchView =
                        new WeakReference<>(found);
            }

            return found;
        } catch (Throwable t) {
            log("查找 UserNameSearchView 失败: " + t);
            return null;
        }
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

            if (context instanceof android.content.ContextWrapper) {
                Context base =
                        ((android.content.ContextWrapper) context)
                                .getBaseContext();

                return base == activity;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static Object findViewByClass(
            View view,
            String className
    ) {
        if (view == null) {
            return null;
        }

        if (view.getClass().getName().equals(className)) {
            return view;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            for (int i = 0; i < group.getChildCount(); i++) {
                Object result =
                        findViewByClass(group.getChildAt(i), className);

                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    // ---------------------------------------------------------------------
    // pending 管理
    // ---------------------------------------------------------------------

    private static void scheduleTimeout() {
        final long started = pendingSince;

        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pending.get()) {
                    return;
                }

                if (pendingSince != started) {
                    return;
                }

                if (System.currentTimeMillis() - started
                        >= PENDING_TIMEOUT_MS) {
                    log("原生用户名搜索超时，清理 pending: "
                            + pendingUsername);
                    clearPending();
                }
            }
        }, PENDING_TIMEOUT_MS + 300L);
    }

    private static void clearPending() {
        pending.set(false);
        pendingUsername = null;
        pendingActivity =
                new WeakReference<>(null);
        pendingSince = 0L;
    }

    // ---------------------------------------------------------------------
    // 字段读取
    // ---------------------------------------------------------------------

    private static int readUid(Object item) {
        try {
            Object result =
                    XposedHelpers.callMethod(item, "T");

            if (result instanceof Integer) {
                return (Integer) result;
            }

            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int readUidFromResult(
            XC_MethodHook.MethodHookParam param
    ) {
        try {
            Object result = param.getResult();

            if (result instanceof Integer) {
                return (Integer) result;
            }

            return 0;
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
