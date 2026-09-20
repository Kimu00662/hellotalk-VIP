package com.hellotalk.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;
    private static volatile boolean resolving = false;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) return;
        sCl = lpparam.classLoader;

        hookVip();
        hookTranslate();
        hookHeaderLog();
        hookFinalHeader();
        hookItem();
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

    private void hookHeaderLog() {
        try {
            Class<?> zh0a = XposedHelpers.findClass("zh0.a", sCl);
            Class<?> chainCls = XposedHelpers.findClass("okhttp3.Interceptor$Chain", sCl);
            XposedHelpers.findAndHookMethod(zh0a, "intercept", chainCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            dumpReq("zh0.a(加头前)", param);
                        }
                    });
            log("HeaderLog hook OK");
        } catch (Throwable t) {
            log("HeaderLog hook FAIL: " + t);
        }
    }

    private void hookFinalHeader() {
        Class<?> chainCls;
        try { chainCls = XposedHelpers.findClass("okhttp3.Interceptor$Chain", sCl); }
        catch (Throwable t) { log("找不到 Chain: " + t); return; }

        try {
            Class<?> ai0c = XposedHelpers.findClass("ai0.c", sCl);
            XposedHelpers.findAndHookMethod(ai0c, "intercept", chainCls,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { dumpReq("ai0.c", param); }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!isUniversal(param)) return;
                            Object resp = param.getResult();
                            if (resp != null) {
                                Object code = XposedHelpers.callMethod(resp, "code");
                                log("[ai0.c] response code=" + code);
                            }
                        } catch (Throwable t) {}
                    }
                });
            log("ai0.c hook OK");
        } catch (Throwable t) { log("ai0.c hook FAIL: " + t); }

        try {
            Class<?> zh0b = XposedHelpers.findClass("zh0.b", sCl);
            XposedHelpers.findAndHookMethod(zh0b, "intercept", chainCls,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { dumpReq("zh0.b", param); }
                });
            log("zh0.b hook OK");
        } catch (Throwable t) { log("zh0.b hook FAIL: " + t); }
    }

    private static void dumpReq(String tag, XC_MethodHook.MethodHookParam param) {
        try {
            Object chain = param.args[0];
            Object request = XposedHelpers.callMethod(chain, "request");
            Object url = XposedHelpers.callMethod(request, "url");
            String urlStr = url.toString();
            if (!urlStr.contains("/go_user_search/v2/universal")) return;
            Object headers = XposedHelpers.callMethod(request, "headers");
            log("=== [" + tag + "] universal 请求 ===\nURL: " + urlStr + "\nHeaders:\n" + headers.toString() + "\n=== END ===");
        } catch (Throwable t) {}
    }

    private static boolean isUniversal(XC_MethodHook.MethodHookParam param) {
        try {
            Object chain = param.args[0];
            Object request = XposedHelpers.callMethod(chain, "request");
            Object url = XposedHelpers.callMethod(request, "url");
            return url.toString().contains("/go_user_search/v2/universal");
        } catch (Throwable t) { return false; }
    }

    private void hookItem() {
        try {
            XposedHelpers.findAndHookMethod("rl0.e", sCl, "T",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.thisObject;
                                Object uidObj = param.getResult();
                                int uid = (uidObj == null) ? 0 : ((Integer) uidObj);
                                Object y = XposedHelpers.getObjectField(item, "Y");
                                String uname = (y == null) ? null : y.toString();

                                log("item userid=" + uid + ", username=" + uname);

                                if (uid == 0 && uname != null && !uname.isEmpty() && !resolving) {
                                    resolving = true;
                                    log(">>> 触发反查(慢速) username=" + uname);
                                    final String nick = uname;
                                    new Thread(() -> {
                                        try { resolveUidByUsername(nick); }
                                        finally { resolving = false; }
                                    }).start();
                                }
                            } catch (Throwable t) {}
                        }
                    });
            log("Item hook OK");
        } catch (Throwable t) { log("Item hook FAIL: " + t); }
    }

    static int resolveUidByUsername(String username) {
        if (username == null || username.isEmpty()) return 0;
        String nick = username.startsWith("@") ? username.substring(1) : username;
        log("反查传入 nickname=[" + nick + "] len=" + nick.length());
        try {
            // ★ 先等 3 秒，模拟人工节奏
            Thread.sleep(3000);
            long t0 = System.currentTimeMillis();

            Class<?> ql0c = XposedHelpers.findClass("ql0.c", sCl);
            Class<?> m41f0 = XposedHelpers.findClass("m41.f0", sCl);
            Class<?> qh0a = XposedHelpers.findClass("qh0.a", sCl);

            Object wrapped = XposedHelpers.callStaticMethod(m41f0, "b", ql0c);
            Object api = XposedHelpers.callStaticMethod(qh0a, "a", wrapped);
            log("反查 API 获取耗时=" + (System.currentTimeMillis() - t0) + "ms");

            Class<?> d41d = XposedHelpers.findClass("d41.d", sCl);
            Method gMethod = null;
            for (Method m : ql0c.getDeclaredMethods()) {
                if ("g".equals(m.getName()) && m.getParameterCount() == 4
                        && m.getParameterTypes()[0] == int.class) {
                    gMethod = m; break;
                }
            }
            if (gMethod == null) { log("找不到 g 方法"); return 0; }
            gMethod.setAccessible(true);

            final Object emptyContext = getEmptyCoroutineContext();
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] holder = new Object[1];

            Object continuation = Proxy.newProxyInstance(sCl,
                    new Class[]{ d41d },
                    (proxy, method, args) -> {
                        if ("resumeWith".equals(method.getName())) {
                            holder[0] = args[0]; latch.countDown(); return null;
                        }
                        if ("getContext".equals(method.getName())) return emptyContext;
                        return null;
                    });

            gMethod.invoke(api, 1, 15, nick, continuation);

            if (!latch.await(15, TimeUnit.SECONDS)) { log("反查超时"); return 0; }
            log("反查总耗时=" + (System.currentTimeMillis() - t0) + "ms");

            Object result = holder[0];
            if (result == null) { log("result null"); return 0; }

            Object lcResp = XposedHelpers.getObjectField(result, "b");
            Object code = XposedHelpers.callMethod(lcResp, "getCode");
            Object data = XposedHelpers.callMethod(lcResp, "getData");
            log("universal code=" + code + " data=" + data);
            if (data == null) { log("data null"); return 0; }

            java.util.List list = (java.util.List) XposedHelpers.callMethod(data, "b");
            if (list == null || list.isEmpty()) { log("列表空"); return 0; }

            int realUid = 0;
            for (Object it : list) {
                Object uid = XposedHelpers.getObjectField(it, "T");
                Object uy = XposedHelpers.getObjectField(it, "Y");
                log("  候选: userid=" + uid + " username=" + uy);
                int v = (uid == null) ? 0 : ((Integer) uid);
                if (v != 0) { realUid = v; break; }
            }
            log("反查最终 realUid=" + realUid);
            return realUid;

        } catch (Throwable t) {
            log("resolveUid FAIL: " + t);
            Throwable real = t;
            while (real instanceof java.lang.reflect.InvocationTargetException
                    && real.getCause() != null) real = real.getCause();
            log("  ★真正原因: " + real);
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

    static void log(String msg) { XposedBridge.log("[HT] " + msg); }
}
