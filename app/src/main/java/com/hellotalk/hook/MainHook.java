package com.hellotalk.hook;

import android.app.Activity;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) return;
        sCl = lpparam.classLoader;

        hookVip();
        hookTranslate();
        hookProfile();
    }

    // ============ Hook 1: 假VIP ============
    private void hookVip() {
        try {
            XposedHelpers.findAndHookMethod("xt.h", sCl, "j",
                    XC_MethodReplacement.returnConstant(100));
            log("假VIP OK");
        } catch (Throwable t) { log("假VIP FAIL: " + t); }
    }

    // ============ Hook 2: 无限翻译 ============
    private void hookTranslate() {
        try {
            XposedHelpers.findAndHookMethod("lx.o", sCl, "h",
                    XC_MethodReplacement.returnConstant(true));
            log("翻译 OK");
        } catch (Throwable t) { log("翻译 FAIL: " + t); }
    }

    // ============ Hook 3: 搜索打开主页 ============
    private void hookProfile() {
        try {
            Class<?> rl0e = XposedHelpers.findClass("rl0.e", sCl);
            // 注意：原始 smali 里是 virtual call（非 static），实际调用点是 static variant
            // 我们 hook 调用点更稳定的 sl0/c;->e (public static synthetic)
            // 但为保险，同时 hook SearchListViewModel.startToProfile
            XposedHelpers.findAndHookMethod(
                    "com.hellotalk.search.v2.viewmodel.SearchListViewModel",
                    sCl, "startToProfile",
                    Activity.class, rl0e, int.class,
                    new ProfileHook());
            XposedHelpers.findAndHookMethod(
                    "com.hellotalk.search.v2.viewmodel.SearchUserViewModel",
                    sCl, "goToProfile",
                    Activity.class, rl0e,
                    new ProfileHook());
            log("搜索hook OK");
        } catch (Throwable t) {
            log("搜索hook FAIL: " + t);
        }
    }

    // 共用 hook 逻辑
    static class ProfileHook extends XC_MethodHook {
        static volatile ThreadLocal<Boolean> inResolve = new ThreadLocal<>();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                // 防止递归回调
                if (Boolean.TRUE.equals(inResolve.get())) {
                    inResolve.remove();
                    return;
                }

                Object item = param.args[1]; // rl0.e
                int uid = XposedHelpers.getIntField(item, "T");
                if (uid != 0) return; // 真实 ID，直接放行

                String username = (String) XposedHelpers.getObjectField(item, "Y");
                final Object[] args = param.args;
                final Method method = (Method) param.method;
                final Object thiz = param.thisObject;
                final Activity activity = (Activity) param.args[0];

                log("检测到脱敏用户: " + username + "，开始反查...");

                // 阻止原方法执行
                param.setResult(null);

                // 后台线程反查
                new Thread(() -> {
                    try {
                        int realUid = resolveUidByUsername(username);
                        if (realUid > 0) {
                            XposedHelpers.setIntField(item, "T", realUid);
                            log("反查成功: " + username + " -> " + realUid);
                            // 回到主线程重新执行原方法
                            activity.runOnUiThread(() -> {
                                try {
                                    inResolve.set(true);
                                    method.invoke(thiz, args);
                                } catch (Throwable t) {
                                    log("重调失败: " + t);
                                }
                            });
                        } else {
                            log("反查失败: " + username);
                        }
                    } catch (Throwable t) {
                        log("反查Error: " + t);
                    }
                }).start();

            } catch (Throwable t) {
                log("ProfileHook FAIL: " + t);
            }
        }
    }

    // ============ 反查真实 userid（同步调用 suspend 接口） ============
    private static int resolveUidByUsername(String username) {
        try {
            // 1. 获取 ql0/c Retrofit proxy
            Class<?> ql0c = XposedHelpers.findClass("ql0.c", sCl);
            Class<?> m41f0 = XposedHelpers.findClass("m41.f0", sCl);
            Class<?> qh0a = XposedHelpers.findClass("qh0.a", sCl);
            Class<?> t41d = XposedHelpers.findClass("t41.d", sCl);

            Object wrapped = XposedHelpers.callStaticMethod(m41f0, "b", ql0c);
            Object api = XposedHelpers.callStaticMethod(qh0a, "a", wrapped);

            // 2. 找到 g 方法
            Class<?> d41d = XposedHelpers.findClass("d41.d", sCl);
            Method gMethod = null;
            for (Method m : ql0c.getDeclaredMethods()) {
                if ("g".equals(m.getName())
                        && m.getParameterCount() == 4
                        && m.getParameterTypes()[0] == int.class) {
                    gMethod = m;
                    break;
                }
            }
            if (gMethod == null) {
                log("找不到 ql0.c.g 方法");
                return 0;
            }
            gMethod.setAccessible(true);

            // 3. CountDownLatch + Continuation proxy
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] resultHolder = new Object[1];
            final Exception[] errorHolder = new Exception[1];

            Object continuation = Proxy.newProxyInstance(sCl,
                    new Class[]{ d41d },
                    (proxy, method, args) -> {
                        if ("resumeWith".equals(method.getName())) {
                            resultHolder[0] = args[0];
                            latch.countDown();
                        }
                        return null;
                    });

            // 4. 反射调用 Retrofit proxy
            log("调用 ql0.c.g...");
            gMethod.invoke(api, 1, 20, username, continuation);

            // 5. 等待结果（最多 10 秒）
            if (!latch.await(10, TimeUnit.SECONDS)) {
                log("反查超时");
                return 0;
            }

            // 6. 解析结果: n91.s → .a() → LCResponse → .getData() → rl0.h → .b() → list → first → .T()
            Object result = resultHolder[0]; // n91.s
            if (result == null) {
                log("n91.s 为 null");
                return 0;
            }
            Object lcResp = XposedHelpers.callMethod(result, "a"); // .a() = body
            if (lcResp == null) {
                log("LCResponse 为 null");
                return 0;
            }
            Method getData = null;
            for (Method m : lcResp.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0
                        && !"getClass".equals(m.getName())
                        && !"toString".equals(m.getName())
                        && !"hashCode".equals(m.getName())) {
                    getData = m;
                    break;
                }
            }
            if (getData == null) { log("找不到 getData"); return 0; }
            getData.setAccessible(true);
            Object rl0h = getData.invoke(lcResp); // rl0.h
            if (rl0h == null) { log("rl0.h 为 null"); return 0; }

            List list = (List) XposedHelpers.callMethod(rl0h, "b"); // ArrayList
            if (list == null || list.isEmpty()) {
                log("搜索结果为空");
                return 0;
            }
            Object firstUser = list.get(0);
            int realUid = XposedHelpers.getIntField(firstUser, "T");
            log("反查结果: userid=" + realUid);
            return realUid;

        } catch (Throwable t) {
            log("resolveUid FAIL: " + t + "\n"
                    + "  cause: " + t.getCause() + "\n"
                    + "  msg:   " + t.getMessage());
            for (StackTraceElement e : t.getStackTrace()) {
                log("    at " + e);
            }
            return 0;
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[HT] " + msg);
    }
}
