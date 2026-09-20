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

        // Hook 1: 假VIP（等效你改 smali 的 xt.h.j() 恒返回 100）
        try {
            XposedHelpers.findAndHookMethod("xt.h", cl, "j",
                XC_MethodReplacement.returnConstant(100));
            XposedBridge.log("[HelloTalkHook] 假VIP OK");
        } catch (Throwable t) {
            XposedBridge.log("[HelloTalkHook] 假VIP FAIL: " + t);
        }

        // Hook 2: 无限翻译
        try {
            XposedHelpers.findAndHookMethod("lx.o", cl, "h",
                XC_MethodReplacement.returnConstant(true));
            XposedBridge.log("[HelloTalkHook] 翻译可用判断 OK");
        } catch (Throwable t) {
            XposedBridge.log("[HelloTalkHook] 翻译hook1 FAIL: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("lx.o", cl, "d",
                XC_MethodReplacement.returnConstant(0));
            XposedBridge.log("[HelloTalkHook] 今日已用次数清零 OK");
        } catch (Throwable t) {
            XposedBridge.log("[HelloTalkHook] 翻译hook2 FAIL: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("lx.o", cl, "b",
                XC_MethodReplacement.returnConstant(9999));
            XposedBridge.log("[HelloTalkHook] 免费翻译次数拉满 OK");
        } catch (Throwable t) {
            XposedBridge.log("[HelloTalkHook] 翻译hook3 FAIL: " + t);
        }
    }
}
