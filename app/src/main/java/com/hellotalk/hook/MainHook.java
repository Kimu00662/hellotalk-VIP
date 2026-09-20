package com.hellotalk.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Field;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) return;
        sCl = lpparam.classLoader;

        hookVip();
        hookTranslate();
        hookGson();      // 抓 SearchResp 的原始 JSON
        hookItem();      // 抓每个 rl0.e 的 userid + 字段是否真实存在
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

    // 抓 Gson.fromJson(String, Class)，当目标是 SearchResp(rl0.h) 时打印原始 JSON
    private void hookGson() {
        try {
            Class<?> gsonCls = XposedHelpers.findClass("com.google.gson.Gson", sCl);
            Class<?> respCls = XposedHelpers.findClass("rl0.h", sCl);

            XposedHelpers.findAndHookMethod(gsonCls, "fromJson",
                    String.class, Class.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Class<?> target = (Class<?>) param.args[1];
                                if (target != null && target.getName().equals("rl0.h")) {
                                    String json = (String) param.args[0];
                                    log("========= SearchResp 原始JSON =========");
                                    if (json != null && json.length() > 3000) {
                                        log(json.substring(0, 3000));
                                    } else {
                                        log(String.valueOf(json));
                                    }
                                    log("========= 原始JSON END =========");
                                }
                            } catch (Throwable t) { log("gson hook err: " + t); }
                        }
                    });
            log("Gson hook OK");
        } catch (Throwable t) {
            log("Gson hook FAIL: " + t);
        }
    }

    // 抓 rl0.e.T() 被调用时打印 userid；并 dump 该对象所有字段值
    private void hookItem() {
        try {
            XposedHelpers.findAndHookMethod("rl0.e", sCl, "T",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.thisObject;
                                Object uid = param.getResult();
                                Object y = XposedHelpers.getObjectField(item, "Y");
                                StringBuilder sb = new StringBuilder();
                                sb.append("item userid=").append(uid)
                                  .append(", username=").append(y);
                                // dump 部分关键字段
                                sb.append(", sex=").append(safeField(item, "N"));
                                sb.append(", country=").append(safeField(item, "B"));
                                log(sb.toString());
                            } catch (Throwable t) {}
                        }
                    });
            log("Item hook OK");
        } catch (Throwable t) {
            log("Item hook FAIL: " + t);
        }
    }

    private static Object safeField(Object obj, String name) {
        try { return XposedHelpers.getObjectField(obj, name); }
        catch (Throwable t) { return "?"; }
    }

    static void log(String msg) {
        XposedBridge.log("[HT] " + msg);
    }
}
