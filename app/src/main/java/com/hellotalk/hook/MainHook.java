package com.hellotalk.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;
    private static volatile boolean resolving = false;

    /*
     * vq.a.d() 本身没有 URL 参数。
     * vq.a.intercept() 和 d() 通常在同一线程执行，
     * 用 ThreadLocal 把当前 URL 关联起来。
     */
    private static final ThreadLocal<String> CURRENT_URL =
            new ThreadLocal<>();

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        hookVip();
        hookTranslate();

        // 真正的加密/解密诊断
        hookSecretData();
        hookVqInterceptor();
        hookVqDecrypt();

        // 只观察对象，不拦截跳转、不写回 userid
        hookItem();

        log("=== HT diagnostic module loaded ===");
    }

    // ------------------------------------------------------------------------
    // 假 VIP
    // ------------------------------------------------------------------------

    private void hookVip() {
        try {
            XposedHelpers.findAndHookMethod(
                    "xt.h",
                    sCl,
                    "j",
                    XC_MethodReplacement.returnConstant(100)
            );
            log("假VIP OK");
        } catch (Throwable t) {
            log("假VIP FAIL: " + t);
        }
    }

    // ------------------------------------------------------------------------
    // 无限翻译
    // ------------------------------------------------------------------------

    private void hookTranslate() {
        try {
            XposedHelpers.findAndHookMethod(
                    "lx.o",
                    sCl,
                    "h",
                    XC_MethodReplacement.returnConstant(true)
            );
            log("翻译 OK");
        } catch (Throwable t) {
            log("翻译 FAIL: " + t);
        }
    }

    // ------------------------------------------------------------------------
    // SecretDataModel：只打印长度和哈希，不打印密钥原文
    // ------------------------------------------------------------------------

    private void hookSecretData() {
        try {
            Class<?> companion = XposedHelpers.findClass(
                    "com.hellotalk.ht.base.configure.entity.SecretDataModel$Companion",
                    sCl
            );

            hookSecretMethod(companion, "readPub");
            hookSecretMethod(companion, "readPublicKey");
            hookSecretMethod(companion, "readSharedSecret");

            log("SecretData hook OK");
        } catch (Throwable t) {
            log("SecretData hook FAIL: " + t);
        }
    }

    private void hookSecretMethod(Class<?> cls, final String methodName) {
        XposedHelpers.findAndHookMethod(
                cls,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            String value = result == null
                                    ? ""
                                    : String.valueOf(result);

                            log("[Secret] " + methodName
                                    + " len=" + value.length()
                                    + " sha256=" + sha256(value));
                        } catch (Throwable t) {
                            log("[Secret] " + methodName
                                    + " log failed: " + t);
                        }
                    }
                }
        );
    }

    // ------------------------------------------------------------------------
    // vq.a：真实 network interceptor
    // ------------------------------------------------------------------------

    private void hookVqInterceptor() {
        try {
            Class<?> vqa = XposedHelpers.findClass("vq.a", sCl);
            Class<?> chain = XposedHelpers.findClass(
                    "okhttp3.Interceptor$Chain",
                    sCl
            );

            XposedHelpers.findAndHookMethod(
                    vqa,
                    "intercept",
                    chain,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object chainObj = param.args[0];
                                Object request = XposedHelpers.callMethod(
                                        chainObj,
                                        "request"
                                );

                                Object url = XposedHelpers.callMethod(
                                        request,
                                        "url"
                                );

                                String urlString = String.valueOf(url);

                                if (!urlString.contains(
                                        "/go_user_search/v2/universal"
                                )) {
                                    return;
                                }

                                CURRENT_URL.set(urlString);

                                Object contentType =
                                        XposedHelpers.callMethod(
                                                request,
                                                "header",
                                                "ht-content-type"
                                        );

                                Object pub =
                                        XposedHelpers.callMethod(
                                                request,
                                                "header",
                                                "x-ht-pub"
                                        );

                                Object body =
                                        XposedHelpers.callMethod(
                                                request,
                                                "body"
                                        );

                                log("[vq.a request]"
                                        + "\nurl=" + urlString
                                        + "\nht-content-type=" + contentType
                                        + "\nx-ht-pub="
                                        + summarizeString(pub)
                                        + "\nbody="
                                        + summarizeRequestBody(body));

                            } catch (Throwable t) {
                                log("[vq.a request] error: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (CURRENT_URL.get() != null) {
                                    log("[vq.a response] url="
                                            + CURRENT_URL.get());
                                }
                            } catch (Throwable t) {
                                log("[vq.a response] error: " + t);
                            } finally {
                                CURRENT_URL.remove();
                            }
                        }
                    }
            );

            log("vq.a intercept hook OK");
        } catch (Throwable t) {
            log("vq.a intercept hook FAIL: " + t);
        }
    }

    // ------------------------------------------------------------------------
    // vq.a.d：真实响应解密函数
    // d([B, String contentType, String encoding) -> [B
    // ------------------------------------------------------------------------

    private void hookVqDecrypt() {
        try {
            Class<?> vqa = XposedHelpers.findClass("vq.a", sCl);

            XposedHelpers.findAndHookMethod(
                    vqa,
                    "d",
                    byte[].class,
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                byte[] raw = (byte[]) param.args[0];
                                String contentType =
                                        String.valueOf(param.args[1]);
                                String encoding =
                                        String.valueOf(param.args[2]);

                                if (!isUniversalThread()) {
                                    return;
                                }

                                log("[vq.a decrypt input]"
                                        + "\nurl=" + CURRENT_URL.get()
                                        + "\ncontentType=" + contentType
                                        + "\nencoding=" + encoding
                                        + "\nrawLen="
                                        + (raw == null ? -1 : raw.length)
                                        + "\nrawSha256="
                                        + sha256(raw));
                            } catch (Throwable t) {
                                log("[vq.a decrypt input] error: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isUniversalThread()) {
                                    return;
                                }

                                byte[] plain = (byte[]) param.getResult();

                                log("[vq.a decrypt output]"
                                        + "\nurl=" + CURRENT_URL.get()
                                        + "\nplainLen="
                                        + (plain == null ? -1 : plain.length)
                                        + "\nplainSha256="
                                        + sha256(plain)
                                        + "\nplainPreview="
                                        + previewBytes(plain, 5000));

                            } catch (Throwable t) {
                                log("[vq.a decrypt output] error: " + t);
                            }
                        }
                    }
            );

            log("vq.a decrypt hook OK");
        } catch (Throwable t) {
            log("vq.a decrypt hook FAIL: " + t);
        }
    }

    private static boolean isUniversalThread() {
        String url = CURRENT_URL.get();
        return url != null
                && url.contains("/go_user_search/v2/universal");
    }

    // ------------------------------------------------------------------------
    // rl0.e：只打印 userid，不反查、不拦截、不写回
    // ------------------------------------------------------------------------

    private void hookItem() {
        try {
            XposedHelpers.findAndHookMethod(
                    "rl0.e",
                    sCl,
                    "T",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.thisObject;
                                Object uidObject = param.getResult();

                                int uid = uidObject == null
                                        ? 0
                                        : ((Integer) uidObject);

                                Object username =
                                        XposedHelpers.getObjectField(item, "Y");

                                log("[item]"
                                        + " userid=" + uid
                                        + " username=" + username);
                            } catch (Throwable t) {
                                log("[item] error: " + t);
                            }
                        }
                    }
            );

            log("Item hook OK");
        } catch (Throwable t) {
            log("Item hook FAIL: " + t);
        }
    }

    // ------------------------------------------------------------------------
    // 保留旧的反查诊断，但默认不自动触发
    // 这版先不自动反查，避免测试时制造额外变量。
    // ------------------------------------------------------------------------

    static int resolveUidByUsername(String username) {
        if (username == null || username.isEmpty()) {
            return 0;
        }

        String nickname = username.startsWith("@")
                ? username.substring(1)
                : username;

        log("[manual resolve] nickname=" + nickname);

        try {
            Class<?> apiClass = XposedHelpers.findClass(
                    "ql0.c",
                    sCl
            );

            Class<?> factory = XposedHelpers.findClass(
                    "m41.f0",
                    sCl
            );

            Class<?> serviceFactory = XposedHelpers.findClass(
                    "qh0.a",
                    sCl
            );

            Object wrapper = XposedHelpers.callStaticMethod(
                    factory,
                    "b",
                    apiClass
            );

            Object api = XposedHelpers.callStaticMethod(
                    serviceFactory,
                    "a",
                    wrapper
            );

            Class<?> continuationClass = XposedHelpers.findClass(
                    "d41.d",
                    sCl
            );

            Method universal = null;

            for (Method method : apiClass.getDeclaredMethods()) {
                if ("g".equals(method.getName())
                        && method.getParameterTypes().length == 4
                        && method.getParameterTypes()[0] == int.class) {
                    universal = method;
                    break;
                }
            }

            if (universal == null) {
                log("[manual resolve] g not found");
                return 0;
            }

            universal.setAccessible(true);

            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] holder = new Object[1];

            Object context = getEmptyCoroutineContext();

            Object continuation = Proxy.newProxyInstance(
                    sCl,
                    new Class[]{continuationClass},
                    (proxy, method, args) -> {
                        if ("resumeWith".equals(method.getName())) {
                            holder[0] = args == null
                                    ? null
                                    : args[0];
                            latch.countDown();
                            return null;
                        }

                        if ("getContext".equals(method.getName())) {
                            return context;
                        }

                        return null;
                    }
            );

            universal.invoke(
                    api,
                    1,
                    15,
                    nickname,
                    continuation
            );

            if (!latch.await(15, TimeUnit.SECONDS)) {
                log("[manual resolve] timeout");
                return 0;
            }

            Object result = holder[0];

            if (result == null) {
                log("[manual resolve] null result");
                return 0;
            }

            Object lcResponse =
                    XposedHelpers.getObjectField(result, "b");

            Object code =
                    XposedHelpers.callMethod(lcResponse, "getCode");

            Object data =
                    XposedHelpers.callMethod(lcResponse, "getData");

            log("[manual resolve] code=" + code
                    + " data=" + data);

            if (data == null) {
                return 0;
            }

            List<?> list = (List<?>) XposedHelpers.callMethod(
                    data,
                    "b"
            );

            if (list == null) {
                return 0;
            }

            for (Object item : list) {
                Object uid =
                        XposedHelpers.getObjectField(item, "T");

                Object name =
                        XposedHelpers.getObjectField(item, "Y");

                log("[manual resolve candidate]"
                        + " userid=" + uid
                        + " username=" + name);

                if (uid instanceof Integer
                        && ((Integer) uid) != 0) {
                    return (Integer) uid;
                }
            }

        } catch (Throwable t) {
            log("[manual resolve] failed: " + t);
        }

        return 0;
    }

    private static Object getEmptyCoroutineContext() {
        try {
            Class<?> contextClass =
                    XposedHelpers.findClass("d41.g", sCl);

            return XposedHelpers.getStaticObjectField(
                    contextClass,
                    "n"
            );
        } catch (Throwable t) {
            log("EmptyCoroutineContext error: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------------

    private static String summarizeString(Object value) {
        if (value == null) {
            return "null";
        }

        String text = String.valueOf(value);

        return "len=" + text.length()
                + ", sha256=" + sha256(text)
                + ", prefix="
                + (text.length() <= 24
                ? text
                : text.substring(0, 24) + "...");
    }

    private static String summarizeRequestBody(Object body) {
        if (body == null) {
            return "null";
        }

        try {
            Object contentType =
                    XposedHelpers.callMethod(body, "contentType");

            Object length =
                    XposedHelpers.callMethod(body, "contentLength");

            return "contentType=" + contentType
                    + ", length=" + length;
        } catch (Throwable t) {
            return "body=" + body.getClass().getName();
        }
    }

    private static String previewBytes(byte[] bytes, int maxChars) {
        if (bytes == null) {
            return "null";
        }

        try {
            String text = new String(
                    bytes,
                    StandardCharsets.UTF_8
            );

            if (text.length() > maxChars) {
                return text.substring(0, maxChars) + "...";
            }

            return text;
        } catch (Throwable t) {
            return "<not utf8>";
        }
    }

    private static String sha256(String value) {
        if (value == null) {
            return "null";
        }

        try {
            return sha256(
                    value.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Throwable t) {
            return "error";
        }
    }

    private static String sha256(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] result = digest.digest(bytes);
            StringBuilder builder = new StringBuilder();

            for (byte b : result) {
                builder.append(String.format("%02x", b));
            }

            return builder.toString();
        } catch (Throwable t) {
            return "error";
        }
    }

    private static void log(String message) {
        XposedBridge.log("[HT] " + message);
    }
}
