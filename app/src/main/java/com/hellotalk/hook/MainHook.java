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

    private void hookVip() {
        try {
            XposedHelpers.findAndHookMethod("xt.h", sCl, "j",
                    XC_MethodReplacement.returnConstant(100));
            log("假VIP OK");
        } catch (Throwable t) { log("假VIP FAIL: " + t); }
    }

    private void hookTranslate() {
        try {
            XposedHelpers.findAndHookMethod("lx.o", sCl, "h",
                    XC_MethodReplacement.returnConstant(true));
            log("翻译 OK");
        } catch (Throwable t) { log("翻译 FAIL: " + t); }
    }

    private void hookProfile() {
        try {
            Class<?> rl0e = XposedHelpers.findClass("rl0.e", sCl);
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

    static class ProfileHook extends XC_MethodHook {
        static ThreadLocal<Boolean> inResolve = new ThreadLocal<>();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (Boolean.TRUE.equals(inResolve.get())) {
                    inResolve.remove();
                    return;
                }

                Object item = param.args[1];
                int uid = getUid(item);
                if (uid != 0) return;

                String username = (String) XposedHelpers.getObjectField(item, "Y");
                final Object[] args = param.args;
                final Method method = (Method) param.method;
                final Object thiz = param.thisObject;
                final Activity activity = (Activity) param.args[0];

                log("检测到脱敏用户: " + username + "，反查中...");
                param.setResult(null);

                new Thread(() -> {
                    try {
                        int realUid = resolveUidByUsername(username);
                        if (realUid > 0) {
                            injectUid(item, realUid);
                            log("反查成功: " + username + " -> " + realUid);
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

    static int getUid(Object item) {
        try {
            Object o = XposedHelpers.getObjectField(item, "T");
            return (o == null) ? 0 : ((Integer) o);
        } catch (Throwable t) { return 0; }
    }

    static void injectUid(Object item, int uid) {
        try {
            XposedHelpers.setObjectField(item, "T", Integer.valueOf(uid));
        } catch (Throwable t) {}
    }

    static int resolveUidByUsername(String username) {
        try {
            Class<?> ql0c = XposedHelpers.findClass("ql0.c", sCl);
            Class<?> m41f0 = XposedHelpers.findClass("m41.f0", sCl);
            Class<?> qh0a = XposedHelpers.findClass("qh0.a", sCl);

            Object wrapped = XposedHelpers.callStaticMethod(m41f0, "b", ql0c);
            Object api = XposedHelpers.callStaticMethod(qh0a, "a", wrapped);

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
            if (gMethod == null) { log("找不到 g 方法"); return 0; }
            gMethod.setAccessible(true);

            final Object emptyContext = getEmptyCoroutineContext();

            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] resultHolder = new Object[1];

            Object continuation = Proxy.newProxyInstance(sCl,
                    new Class[]{ d41d },
                    (proxy, method, args) -> {
                        if ("resumeWith".equals(method.getName())) {
                            resultHolder[0] = args[0];
                            latch.countDown();
                            return null;
                        }
                        if ("getContext".equals(method.getName())) {
                            return emptyContext;
                        }
                        return null;
                    });

            log("调用 ql0.c.g...");
            gMethod.invoke(api, 1, 20, username, continuation);

            if (!latch.await(10, TimeUnit.SECONDS)) {
                log("反查超时");
                return 0;
            }

            Object result = resultHolder[0];
            if (result == null) { log("结果(result) null"); return 0; }
            log("result类型: " + result.getClass().getName());

            // 直接读 n91.s 的字段 b（真正的数据体）
            Object lcResp = null;
            try {
                lcResp = XposedHelpers.getObjectField(result, "b");
            } catch (Throwable t) {
                log("读字段b失败, 尝试.a()");
                lcResp = XposedHelpers.callMethod(result, "a");
            }
            if (lcResp == null) { log("lcResp(null)=null"); return 0; }
            log("lcResp类型: " + lcResp.getClass().getName());

            // 读 LCResponse 的 code 和 data
            Object code = XposedHelpers.callMethod(lcResp, "getCode");
            Object data = XposedHelpers.callMethod(lcResp, "getData");
            log("code=" + code + ", data=" + data);
            if (data == null) { log("data null, 可能code!=0"); return 0; }

            // data 是 rl0.h
            Object rl0h = data;
            List list = (List) XposedHelpers.callMethod(rl0h, "b");
            if (list == null || list.isEmpty()) { log("列表为空"); return 0; }

            int realUid = getUid(list.get(0));
            log("反查结果 userid=" + realUid);
            return realUid;

        } catch (Throwable t) {
            log("resolveUid FAIL: " + t);
            Throwable real = t;
            while (real instanceof java.lang.reflect.InvocationTargetException
                    && real.getCause() != null) {
                real = real.getCause();
            }
            log("  ★真正原因: " + real);
            for (StackTraceElement e : real.getStackTrace()) {
                log("    at " + e);
            }
            return 0;
        }
    }

    static Object getEmptyCoroutineContext() {
        try {
            Class<?> e = XposedHelpers.findClass("d41.g", sCl);
            return XposedHelpers.getStaticObjectField(e, "n");
        } catch (Throwable t) {
            log("getEmptyCoroutineContext 失败: " + t);
            return null;
        }
    }

    static void log(String msg) {
        XposedBridge.log("[HT] " + msg);
    }
}
