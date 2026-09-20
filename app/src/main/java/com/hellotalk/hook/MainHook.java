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

    private static ClassLoader cl;

    private static final Handler MAIN =
            new Handler(Looper.getMainLooper());

    private static final AtomicBoolean pending =
            new AtomicBoolean(false);

    private static WeakReference<Activity> pendingActivity =
            new WeakReference<>(null);

    private static WeakReference<Object> searchView =
            new WeakReference<>(null);

    private static volatile String pendingUsername;
    private static volatile long pendingTime;

    private static final long TIMEOUT = 15000L;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        cl = lpparam.classLoader;

        safe(MainHook::hookVip);
        safe(MainHook::hookTranslate);
        safe(MainHook::hookFilterVip);
        safe(MainHook::hookSearchView);
        safe(MainHook::hookProfileClick);
        safe(MainHook::hookResolvedItem);

        log("=== HT FULL VERSION LOADED ===");
    }

    private interface Task {
        void run() throws Throwable;
    }

    private static void safe(Task task) {
        try {
            task.run();
        } catch (Throwable t) {
            log("hook exception: " + t);
            XposedBridge.log(t);
        }
    }

    // ------------------------------------------------------------------
    // 假 VIP：双保险
    // ------------------------------------------------------------------

    private static void hookVip() throws Throwable {
        Class<?> cls = XposedHelpers.findClass("xt.h", cl);

        XposedHelpers.findAndHookMethod(
                cls,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        log("[VIP] xt.h.j called");
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        log("[VIP] xt.h.j result -> 100");
                        p.setResult(100);
                    }
                }
        );

        log("假VIP hook OK");
    }

    /*
     * 高级搜索自己的 ViewModel 也强制返回 true。
     * 如果 xt.h.j() 没有被调用，这一层仍然能让客户端 UI 放行。
     */
    private static void hookFilterVip() throws Throwable {
        Class<?> cls = XposedHelpers.findClass(
                "com.hellotalk.search.v2.logic.controller.searchuser.SearchFilterViewModelV2",
                cl
        );

        XposedHelpers.findAndHookMethod(
                cls,
                "isVip",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        log("[VIP] SearchFilterViewModelV2.isVip -> true");
                        p.setResult(true);
                    }
                }
        );

        log("SearchFilterViewModelV2.isVip hook OK");
    }

    // ------------------------------------------------------------------
    // 无限翻译
    // ------------------------------------------------------------------

    private static void hookTranslate() throws Throwable {
        Class<?> cls = XposedHelpers.findClass("lx.o", cl);

        XposedHelpers.findAndHookMethod(
                cls,
                "h",
                XC_MethodReplacement.returnConstant(true)
        );

        log("翻译 hook OK");
    }

    // ------------------------------------------------------------------
    // 记录 UserNameSearchView
    // ------------------------------------------------------------------

    private static void hookSearchView() throws Throwable {
        Class<?> cls = XposedHelpers.findClass(
                "com.hellotalk.search.v2.widget.UserNameSearchView",
                cl
        );

        XposedHelpers.findAndHookMethod(
                cls,
                "L",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        searchView =
                                new WeakReference<>(p.thisObject);
                        log("[BRIDGE] UserNameSearchView.L captured");
                    }
                }
        );

        log("UserNameSearchView.L hook OK");
    }

    // ------------------------------------------------------------------
    // 拦截高级搜索 userid=0 的点击
    // ------------------------------------------------------------------

    private static void hookProfileClick() throws Throwable {
        Class<?> vm = XposedHelpers.findClass(
                "com.hellotalk.search.v2.viewmodel.SearchUserViewModel",
                cl
        );

        Class<?> activity = XposedHelpers.findClass(
                "android.app.Activity",
                cl
        );

        Class<?> item = XposedHelpers.findClass("rl0.e", cl);

        XposedHelpers.findAndHookMethod(
                vm,
                "goToProfile",
                activity,
                item,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Activity a = (Activity) p.args[0];
                            Object user = p.args[1];

                            int uid = readUid(user);
                            String name = readName(user);

                            log("[BRIDGE] click uid="
                                    + uid + " name=" + name);

                            // 真实 ID 直接放行
                            if (uid != 0) {
                                return;
                            }

                            // 无 username 无法反查，放行原逻辑
                            if (blank(name)) {
                                return;
                            }

                            // 防止多个异步请求同时运行
                            if (!pending.compareAndSet(false, true)) {
                                log("[BRIDGE] another request pending");
                                return;
                            }

                            pendingActivity =
                                    new WeakReference<>(a);
                            pendingUsername = name;
                            pendingTime =
                                    System.currentTimeMillis();

                            // 阻止原方法使用 user_id=0
                            p.setResult(null);

                            Object view =
                                    findSearchView(a);

                            if (view == null) {
                                log("[BRIDGE] UserNameSearchView not found");
                                clearPending();
                                return;
                            }

                            final Object finalView = view;
                            final String finalName = name;

                            MAIN.postDelayed(() -> {
                                try {
                                    if (!pending.get()) {
                                        return;
                                    }

                                    /*
                                     * UserNameSearchView.M() 会调用当前
                                     * SearchIDViewV2 的 requestUser()。
                                     */
                                    log("[BRIDGE] call native M: "
                                            + finalName);

                                    XposedHelpers.callMethod(
                                            finalView,
                                            "M",
                                            finalName
                                    );

                                    setTimeout();
                                } catch (Throwable t) {
                                    log("[BRIDGE] call M failed: " + t);
                                    clearPending();
                                }
                            }, 300L);

                        } catch (Throwable t) {
                            log("[BRIDGE] click error: " + t);
                            clearPending();
                        }
                    }
                }
        );

        log("SearchUserViewModel.goToProfile hook OK");
    }

    // ------------------------------------------------------------------
    // 捕获原生用户名搜索返回的真实 rl0.e
    // ------------------------------------------------------------------

    private static void hookResolvedItem() throws Throwable {
        Class<?> cls = XposedHelpers.findClass("rl0.e", cl);

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

                            Object user = p.thisObject;
                            Object result = p.getResult();

                            if (!(result instanceof Integer)) {
                                return;
                            }

                            int uid = (Integer) result;
                            String name = readName(user);

                            if (uid <= 0
                                    || blank(name)
                                    || pendingUsername == null
                                    || !pendingUsername.equals(name)) {
                                return;
                            }

                            Activity a = pendingActivity.get();

                            if (a == null
                                    || a.isFinishing()
                                    || destroyed(a)) {
                                clearPending();
                                return;
                            }

                            if (!pending.compareAndSet(true, false)) {
                                return;
                            }

                            pendingUsername = null;
                            pendingActivity =
                                    new WeakReference<>(null);

                            log("[BRIDGE] resolved "
                                    + name + " -> " + uid);

                            final Activity finalActivity = a;
                            final Object finalUser = user;

                            MAIN.post(() -> {
                                callNativeProfile(
                                        finalActivity,
                                        finalUser
                                );
                            });

                        } catch (Throwable t) {
                            log("[BRIDGE] capture error: " + t);
                        }
                    }
                }
        );

        log("rl0.e.T capture hook OK");
    }

    // ------------------------------------------------------------------
    // 调用原生 sl0.c.e()
    // ------------------------------------------------------------------

    private static void callNativeProfile(
            Activity activity,
            Object user
    ) {
        try {
            Class<?> cls =
                    XposedHelpers.findClass("sl0.c", cl);

            Object singleton =
                    XposedHelpers.getStaticObjectField(cls, "a");

            XposedHelpers.callMethod(
                    singleton,
                    "e",
                    activity,
                    user,
                    "user_filter_word",
                    "SearchService",
                    0
            );

            log("[BRIDGE] native profile entry called");
        } catch (Throwable t) {
            log("[BRIDGE] profile entry failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 查找当前 Activity 中的 UserNameSearchView
    // ------------------------------------------------------------------

    private static Object findSearchView(Activity activity) {
        try {
            Object cached = searchView.get();

            if (cached != null && belongsTo(cached, activity)) {
                return cached;
            }

            View root =
                    activity.getWindow().getDecorView();

            Object found = findView(
                    root,
                    "com.hellotalk.search.v2.widget.UserNameSearchView"
            );

            if (found != null) {
                searchView =
                        new WeakReference<>(found);
            }

            return found;
        } catch (Throwable t) {
            log("[BRIDGE] find view failed: " + t);
            return null;
        }
    }

    private static Object findView(View view, String className) {
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

    private static boolean belongsTo(
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

    // ------------------------------------------------------------------
    // 状态与工具
    // ------------------------------------------------------------------

    private static void setTimeout() {
        final long start = pendingTime;

        MAIN.postDelayed(() -> {
            if (pending.get()
                    && pendingTime == start
                    && System.currentTimeMillis() - start >= TIMEOUT) {
                log("[BRIDGE] timeout: " + pendingUsername);
                clearPending();
            }
        }, TIMEOUT + 500L);
    }

    private static void clearPending() {
        pending.set(false);
        pendingUsername = null;
        pendingActivity =
                new WeakReference<>(null);
        pendingTime = 0L;
    }

    private static int readUid(Object user) {
        try {
            Object value =
                    XposedHelpers.callMethod(user, "T");

            return value instanceof Integer
                    ? (Integer) value
                    : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readName(Object user) {
        try {
            Object value =
                    XposedHelpers.getObjectField(user, "Y");

            return value == null
                    ? null
                    : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean destroyed(Activity activity) {
        try {
            return android.os.Build.VERSION.SDK_INT >= 17
                    && activity.isDestroyed();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[HT] " + msg);
    }
}
