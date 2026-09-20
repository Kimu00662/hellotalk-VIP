package com.hellotalk.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!"com.hellotalk".equals(lpparam.packageName)) return;

        final ClassLoader cl = lpparam.classLoader;

        // 假VIP
        try {
            XposedHelpers.findAndHookMethod("xt.h", cl, "j",
                    XC_MethodReplacement.returnConstant(100));
            XposedBridge.log("[HT] 假VIP OK");
        } catch (Throwable t) {
            XposedBridge.log("[HT] 假VIP FAIL: " + t);
        }

        // 无限翻译
        try {
            XposedHelpers.findAndHookMethod("lx.o", cl, "h",
                    XC_MethodReplacement.returnConstant(true));
            XposedBridge.log("[HT] 翻译 OK");
        } catch (Throwable t) {
            XposedBridge.log("[HT] 翻译 FAIL: " + t);
        }
    }
}
