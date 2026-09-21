package com.hellotalk.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static ClassLoader sCl;
    private static volatile Handler sMainHandler;

    private static final AtomicBoolean PENDING =
            new AtomicBoolean(false);

    private static final AtomicLong TOKEN_COUNTER =
            new AtomicLong(0L);

    private static volatile long pendingToken;
    private static volatile long fragmentToken;
    private static volatile long emptyCheckToken;

    private static volatile String pendingUsername;

    private static WeakReference<Activity> sourceActivity =
            new WeakReference<>(null);

    private static WeakReference<Activity> helperActivity =
            new WeakReference<>(null);

    private static final Map<String, Integer> UID_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<Object, MomentFilterState> MOMENT_FILTERS =
            new WeakHashMap<>();

    private static final long TIMEOUT_MS = 20000L;
    private static final long EMPTY_RECHECK_MS = 800L;

    private interface HookTask {
        void run() throws Throwable;
    }

    private static final class MomentFilterState {
        final ArrayList<Integer> teach;
        final ArrayList<Integer> learn;

        MomentFilterState(
                ArrayList<Integer> teach,
                ArrayList<Integer> learn
        ) {
            this.teach = teach;
            this.learn = learn;
        }
    }

    @Override
    public void handleLoadPackage(
            final LoadPackageParam lpparam
    ) {
        if (!"com.hellotalk".equals(lpparam.packageName)) {
            return;
        }

        sCl = lpparam.classLoader;

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookVip();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookFilterVip();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookTranslate();
            }
        });

        /*
         * 动态专用 VIP 请求字段修复。
         * 失败时不会影响其他功能。
         */
        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookMomentVipType();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookMomentLatestCondition();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookMomentHistoryCondition();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookMomentFallbackRequest();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookMomentLocalResults();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookFilterClick();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookIdSearchInit();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookUsernameFragmentInit();
            }
        });

        safe(new HookTask() {
            @Override
            public void run() throws Throwable {
                hookRefreshState();
            }
        });

        log("=== HT FINAL BRIDGE WITH MOMENT VIP FIX LOADED ===");
    }

    private static void safe(HookTask task) {
        try {
            task.run();
        } catch (Throwable t) {
            log("Hook异常: " + t);
            XposedBridge.log(t);
        }
    }

    private static Handler handler() {
        Handler h = sMainHandler;

        if (h != null) {
            return h;
        }

        synchronized (MainHook.class) {
            h = sMainHandler;

            if (h == null) {
                try {
                    h = new Handler(Looper.getMainLooper());
                    sMainHandler = h;
                } catch (Throwable t) {
                    log("Handler懒加载失败: " + t);
                    return null;
                }
            }
        }

        return h;
    }

    private static boolean post(Runnable task) {
        try {
            Handler h = handler();
            return h != null && h.post(task);
        } catch (Throwable t) {
            log("Handler post失败: " + t);
            return false;
        }
    }

    private static boolean postDelayed(
            Runnable task,
            long delayMs
    ) {
        try {
            Handler h = handler();
            return h != null && h.postDelayed(task, delayMs);
        } catch (Throwable t) {
            log("Handler postDelayed失败: " + t);
            return false;
        }
    }

    // ============================================================
    // 假 VIP
    // ============================================================

    private static void hookVip() throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass(
                        "xt.h",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                cls,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        param.setResult(100);
                    }
                }
        );

        log("假VIP hook OK");
    }

    private static void hookFilterVip()
            throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.SearchFilterViewModelV2",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                cls,
                "isVip",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        param.setResult(true);
                    }
                }
        );

        log("SearchFilterViewModelV2.isVip hook OK");
    }

    // ============================================================
    // 无限翻译
    // ============================================================

    private static void hookTranslate()
            throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass(
                        "lx.o",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                cls,
                "h",
                XC_MethodReplacement.returnConstant(true)
        );

        log("翻译 hook OK");
    }

    // ============================================================
    // 动态 VIP 请求字段
    //
    // ze0.y.h(int) 会构造：
    // MomentPb.FeaturedCondition
    //
    // 原生逻辑：
    // UserPay -> xt.h.g(UserPay) -> vipType
    //
    // 假 VIP 只修改 xt.h.j()=100，
    // 因此这里把动态请求中的 vipType 改为完整 VIP 类型101。
    // ============================================================

    private static void hookMomentVipType()
            throws Throwable {
        Class<?> logicClass =
                XposedHelpers.findClass(
                        "ze0.y",
                        sCl
                );

        final Class<?> conditionClass =
                XposedHelpers.findClass(
                        "com.hellotalk.ht.base.pbModel.MomentPb$FeaturedCondition",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                logicClass,
                "h",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            /*
                             * 只在假 VIP 判断生效时修改。
                             * xt.h.j() 已由本模块返回100。
                             */
                            Object vipResult =
                                    XposedHelpers.callStaticMethod(
                                            XposedHelpers.findClass(
                                                    "xt.h",
                                                    sCl
                                            ),
                                            "j"
                                    );

                            if (!(vipResult instanceof Integer)
                                    || ((Integer) vipResult) <= 0) {
                                return;
                            }

                            Object condition =
                                    param.getResult();

                            int qtype =
                                    param.args.length > 0
                                            && param.args[0] instanceof Integer
                                            ? (Integer) param.args[0]
                                            : -1;

                            int vipBefore =
                                    readMomentVipType(conditionClass, condition);

                            if (condition == null
                                    || !conditionClass.isInstance(
                                    condition
                            )) {
                                return;
                            }

                            /*
                             * protobuf 对象不可直接修改。
                             * toBuilder 会保留所有原始字段：
                             *
                             * teach_lang
                             * learn_lang
                             * sex
                             * nationality
                             * location
                             * level_index
                             * default_index
                             */
                            Object builder =
                                    XposedHelpers.callMethod(
                                            condition,
                                            "toBuilder"
                                    );

                            XposedHelpers.callMethod(
                                    builder,
                                    "setVipType",
                                    101
                            );

                            Object modified =
                                    XposedHelpers.callMethod(
                                            builder,
                                            "build"
                                    );

                            if (modified != null
                                    && conditionClass.isInstance(
                                    modified
                            )) {
                                param.setResult(modified);
                                log("[MOMENT] h called qtype="
                                        + qtype
                                        + " vip before="
                                        + vipBefore
                                        + " vip after=101");
                            }

                        } catch (Throwable ignored) {
                            /*
                             * 动态字段修改失败时保留原始结果。
                             */
                        }
                    }
                }
        );

        log("Moment FeaturedCondition.vipType hook OK");
    }

    private static void hookMomentLatestCondition()
            throws Throwable {
        final Class<?> requestClass =
                XposedHelpers.findClass(
                        "ze0.i",
                        sCl
                );

        final Class<?> conditionClass =
                XposedHelpers.findClass(
                        "com.hellotalk.ht.base.pbModel.MomentPb$FeaturedCondition",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                requestClass,
                "e",
                conditionClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            Object condition = param.args[0];
                            int vip = readMomentVipType(
                                    conditionClass,
                                    condition
                            );
                            log("[MOMENT] latest condition vip=" + vip);
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );

        log("Moment latest condition hook OK");
    }

    private static void hookMomentHistoryCondition()
            throws Throwable {
        final Class<?> requestClass =
                XposedHelpers.findClass(
                        "ze0.h",
                        sCl
                );

        final Class<?> conditionClass =
                XposedHelpers.findClass(
                        "com.hellotalk.ht.base.pbModel.MomentPb$FeaturedCondition",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                requestClass,
                "f",
                conditionClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            Object condition = param.args[0];
                            int vip = readMomentVipType(
                                    conditionClass,
                                    condition
                            );
                            log("[MOMENT] history condition vip=" + vip);
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );

        log("Moment history condition hook OK");
    }

    private static int readMomentVipType(
            Class<?> conditionClass,
            Object condition
    ) {
        if (condition == null
                || conditionClass == null
                || !conditionClass.isInstance(condition)) {
            return -1;
        }

        try {
            Object value = XposedHelpers.callMethod(
                    condition,
                    "getVipType"
            );
            return value instanceof Integer
                    ? (Integer) value
                    : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void hookMomentFallbackRequest()
            throws Throwable {
        Class<?> presenterClass =
                XposedHelpers.findClass(
                        "kg0.e",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                presenterClass,
                "A",
                int.class,
                List.class,
                List.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            ArrayList<Integer> selectedTeach =
                                    copyLanguageList(param.args[1]);
                            ArrayList<Integer> selectedLearn =
                                    copyLanguageList(param.args[2]);

                            if (selectedTeach.isEmpty()
                                    || selectedLearn.isEmpty()) {
                                clearMomentFilter(param.thisObject);
                                return;
                            }

                            Object userLanguage = currentUserLanguage();
                            if (userLanguage == null) {
                                clearMomentFilter(param.thisObject);
                                return;
                            }

                            ArrayList<Integer> defaultTeach =
                                    copyLanguageList(
                                            XposedHelpers.callMethod(
                                                    userLanguage,
                                                    "getLearnLanguageList"
                                            )
                                    );
                            ArrayList<Integer> defaultLearn =
                                    copyLanguageList(
                                            XposedHelpers.callMethod(
                                                    userLanguage,
                                                    "getTeachLanguageList"
                                            )
                                    );

                            if (defaultTeach.isEmpty()
                                    || defaultLearn.isEmpty()
                                    || (sameLanguages(
                                    selectedTeach,
                                    defaultTeach
                            ) && sameLanguages(
                                    selectedLearn,
                                    defaultLearn
                            ))) {
                                clearMomentFilter(param.thisObject);
                                return;
                            }

                            putMomentFilter(
                                    param.thisObject,
                                    new MomentFilterState(
                                            selectedTeach,
                                            selectedLearn
                                    )
                            );

                            param.args[1] = defaultTeach;
                            param.args[2] = defaultLearn;

                            log("[MOMENT_LOCAL] fallback request active");
                        } catch (Throwable t) {
                            clearMomentFilter(param.thisObject);
                            log("[MOMENT_LOCAL] fallback request failed: "
                                    + t.getClass().getName());
                        }
                    }
                }
        );

        log("Moment local fallback request hook OK");
    }

    private static void hookMomentLocalResults()
            throws Throwable {
        Class<?> activityClass =
                XposedHelpers.findClass(
                        "com.hellotalk.moment.search.ui.MomentSearchResultActivity",
                        sCl
                );

        Class<?> resultClass =
                XposedHelpers.findClass(
                        "com.hellotalk.moment.common.model.MomentResultModel",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                activityClass,
                "showMomentList",
                resultClass,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            Object presenter =
                                    XposedHelpers.getObjectField(
                                            param.thisObject,
                                            "t"
                                    );
                            MomentFilterState state =
                                    getMomentFilter(presenter);
                            if (state == null || param.args[0] == null) {
                                return;
                            }

                            Object momentsObject =
                                    XposedHelpers.callMethod(
                                            param.args[0],
                                            "getMoments"
                                    );
                            if (!(momentsObject instanceof List)) {
                                XposedHelpers.callMethod(
                                        param.args[0],
                                        "setMoments",
                                        new ArrayList<Object>()
                                );
                                log("[MOMENT_LOCAL] candidates=0 matched=0");
                                return;
                            }

                            List<?> moments = (List<?>) momentsObject;
                            ArrayList<Object> filtered = new ArrayList<>();
                            for (Object moment : moments) {
                                if (matchesMomentLanguages(moment, state)) {
                                    filtered.add(moment);
                                }
                            }

                            XposedHelpers.callMethod(
                                    param.args[0],
                                    "setMoments",
                                    filtered
                            );

                            log("[MOMENT_LOCAL] candidates="
                                    + moments.size()
                                    + " matched="
                                    + filtered.size());
                        } catch (Throwable t) {
                            try {
                                if (param.args[0] != null) {
                                    XposedHelpers.callMethod(
                                            param.args[0],
                                            "setMoments",
                                            new ArrayList<Object>()
                                    );
                                }
                            } catch (Throwable ignored) {
                            }
                            log("[MOMENT_LOCAL] result filter failed: "
                                    + t.getClass().getName());
                        }
                    }
                }
        );

        log("Moment local result filter hook OK");
    }

    private static Object currentUserLanguage() {
        try {
            Class<?> userManagerClass =
                    XposedHelpers.findClass("xt.l", sCl);
            Object userManager =
                    XposedHelpers.callStaticMethod(
                            userManagerClass,
                            "r"
                    );

            Class<?> accountClass =
                    XposedHelpers.findClass("et.b", sCl);
            Object account =
                    XposedHelpers.callStaticMethod(
                            accountClass,
                            "e"
                    );
            int uid = XposedHelpers.getIntField(account, "b");

            Object user = XposedHelpers.callMethod(
                    userManager,
                    "s",
                    uid
            );
            if (user == null) {
                return null;
            }

            Class<?> userUtilsClass =
                    XposedHelpers.findClass("xt.s", sCl);
            return XposedHelpers.callStaticMethod(
                    userUtilsClass,
                    "k",
                    user
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ArrayList<Integer> copyLanguageList(
            Object value
    ) {
        ArrayList<Integer> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }

        for (Object item : (List<?>) value) {
            if (item instanceof Integer && (Integer) item > 0) {
                result.add((Integer) item);
            }
        }
        return result;
    }

    private static boolean sameLanguages(
            List<Integer> first,
            List<Integer> second
    ) {
        return first.size() == second.size()
                && first.containsAll(second)
                && second.containsAll(first);
    }

    private static boolean matchesMomentLanguages(
            Object moment,
            MomentFilterState state
    ) {
        if (moment == null || state == null) {
            return false;
        }

        try {
            Object owner = XposedHelpers.callMethod(moment, "H0");
            if (owner == null) {
                return false;
            }

            Object userLanguage =
                    XposedHelpers.callMethod(owner, "C");
            if (userLanguage == null) {
                return false;
            }

            ArrayList<Integer> teach = copyLanguageList(
                    XposedHelpers.callMethod(
                            userLanguage,
                            "getTeachLanguageList"
                    )
            );
            ArrayList<Integer> learn = copyLanguageList(
                    XposedHelpers.callMethod(
                            userLanguage,
                            "getLearnLanguageList"
                    )
            );

            return intersects(state.teach, teach)
                    && intersects(state.learn, learn);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean intersects(
            List<Integer> first,
            List<Integer> second
    ) {
        for (Integer value : first) {
            if (second.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static void putMomentFilter(
            Object presenter,
            MomentFilterState state
    ) {
        if (presenter == null || state == null) {
            return;
        }
        synchronized (MOMENT_FILTERS) {
            MOMENT_FILTERS.put(presenter, state);
        }
    }

    private static MomentFilterState getMomentFilter(
            Object presenter
    ) {
        if (presenter == null) {
            return null;
        }
        synchronized (MOMENT_FILTERS) {
            return MOMENT_FILTERS.get(presenter);
        }
    }

    private static void clearMomentFilter(Object presenter) {
        if (presenter == null) {
            return;
        }
        synchronized (MOMENT_FILTERS) {
            MOMENT_FILTERS.remove(presenter);
        }
    }

    // ============================================================
    // 高级筛选 userid=0 点击
    // ============================================================

    private static void hookFilterClick()
            throws Throwable {
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
                XposedHelpers.findClass(
                        "rl0.e",
                        sCl
                );

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

                            Object item =
                                    param.args[1];

                            int uid =
                                    readUid(item);

                            String username =
                                    normalize(
                                            readUsername(item)
                                    );

                            log("[BRIDGE] click uid="
                                    + uid
                                    + " username="
                                    + username);

                            /*
                             * 真实 userid 原样放行。
                             */
                            if (uid > 0) {
                                return;
                            }

                            /*
                             * 无 username 无法反查，原样放行。
                             */
                            if (isBlank(username)) {
                                return;
                            }

                            /*
                             * 反查期间吞掉后续 userid=0 点击。
                             */
                            if (PENDING.get()) {
                                log("[BRIDGE] pending期间阻止userid=0点击");
                                param.setResult(null);
                                return;
                            }

                            /*
                             * 成功结果缓存。
                             */
                            Integer cached =
                                    UID_CACHE.get(username);

                            if (cached != null
                                    && cached > 0) {
                                param.setResult(null);

                                final Activity finalActivity =
                                        activity;

                                final int finalUid =
                                        cached;

                                post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                openProfileByUid(
                                                        finalActivity,
                                                        finalUid
                                                );
                                            }
                                        }
                                );

                                return;
                            }

                            if (!PENDING.compareAndSet(
                                    false,
                                    true
                            )) {
                                param.setResult(null);
                                return;
                            }

                            long token =
                                    TOKEN_COUNTER.incrementAndGet();

                            pendingToken = token;
                            fragmentToken = 0L;
                            emptyCheckToken = 0L;
                            pendingUsername = username;

                            sourceActivity =
                                    new WeakReference<>(
                                            activity
                                    );

                            helperActivity =
                                    new WeakReference<>(
                                            null
                                    );

                            try {
                                Class<?> idSearchClass =
                                        XposedHelpers.findClass(
                                                "com.hellotalk.search.v2.view.IDSearchActivity",
                                                sCl
                                        );

                                Intent intent =
                                        new Intent(
                                                activity,
                                                idSearchClass
                                        );

                                activity.startActivity(intent);
                                activity.overridePendingTransition(
                                        0,
                                        0
                                );

                                /*
                                 * 启动成功后拦截原始 userid=0 跳转。
                                 */
                                param.setResult(null);

                                log("[BRIDGE] IDSearchActivity launched");

                                scheduleTimeout(token);

                            } catch (Throwable launchError) {
                                log("[BRIDGE] 启动ID搜索页失败: "
                                        + launchError);

                                /*
                                 * 启动失败时不阻断原始逻辑。
                                 */
                                clearPending();
                            }

                        } catch (Throwable t) {
                            log("[BRIDGE] 点击处理失败: " + t);
                            clearPending();
                        }
                    }
                }
        );

        log("SearchUserViewModel.goToProfile hook OK");
    }

    // ============================================================
    // IDSearchActivity
    // ============================================================

    private static void hookIdSearchInit()
            throws Throwable {
        Class<?> cls =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.view.IDSearchActivity",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                cls,
                "init",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                            MethodHookParam param
                    ) {
                        if (!PENDING.get()) {
                            return;
                        }

                        try {
                            Activity activity =
                                    (Activity) param.thisObject;

                            helperActivity =
                                    new WeakReference<>(
                                            activity
                                    );

                            prepareHelperPage(activity);

                            activity.overridePendingTransition(
                                    0,
                                    0
                            );

                            log("[BRIDGE] IDSearchActivity.init");

                        } catch (Throwable t) {
                            log("[BRIDGE] helper init处理失败: "
                                    + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        if (!PENDING.get()) {
                            return;
                        }

                        helperActivity =
                                new WeakReference<>(
                                        (Activity) param.thisObject
                                );

                        log("[BRIDGE] IDSearchActivity.init完成");
                    }
                }
        );

        log("IDSearchActivity.init hook OK");
    }

    private static void prepareHelperPage(
            Activity activity
    ) {
        try {
            /*
             * BaseBindingActivity.A 是 binding。
             */
            Object binding =
                    XposedHelpers.getObjectField(
                            activity,
                            "A"
                    );

            if (binding == null) {
                return;
            }

            Object searchView =
                    XposedHelpers.getObjectField(
                            binding,
                            "searchView"
                    );

            if (searchView instanceof View) {
                ((View) searchView).setVisibility(
                        View.INVISIBLE
                );
            }

            Object back =
                    XposedHelpers.getObjectField(
                            binding,
                            "ivBack"
                    );

            if (back instanceof View) {
                ((View) back).setVisibility(
                        View.INVISIBLE
                );
            }

            try {
                Object overlay =
                        XposedHelpers.getObjectField(
                                binding,
                                "overlay"
                        );

                Object root =
                        XposedHelpers.callMethod(
                                overlay,
                                "getRoot"
                        );

                if (root instanceof View) {
                    ((View) root).setVisibility(
                            View.INVISIBLE
                    );
                }
            } catch (Throwable ignored) {
            }

        } catch (Throwable t) {
            log("[BRIDGE] helper页面处理失败: " + t);
        }
    }

    // ============================================================
    // UserNameSearchFragment
    // ============================================================

    private static void hookUsernameFragmentInit()
            throws Throwable {
        Class<?> fragmentClass =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.UserNameSearchFragment",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                fragmentClass,
                "initViewData",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!PENDING.get()) {
                                return;
                            }

                            Object fragment =
                                    param.thisObject;

                            Object activityObject =
                                    XposedHelpers.callMethod(
                                            fragment,
                                            "getActivity"
                                    );

                            if (!(activityObject instanceof Activity)) {
                                return;
                            }

                            Activity activity =
                                    (Activity) activityObject;

                            if (!"com.hellotalk.search.v2.view.IDSearchActivity"
                                    .equals(
                                            activity.getClass().getName()
                                    )) {
                                return;
                            }

                            long token =
                                    pendingToken;

                            if (fragmentToken == token) {
                                return;
                            }

                            String username =
                                    pendingUsername;

                            if (isBlank(username)) {
                                return;
                            }

                            fragmentToken = token;

                            helperActivity =
                                    new WeakReference<>(
                                            activity
                                    );

                            final Object finalFragment =
                                    fragment;

                            final String finalUsername =
                                    username;

                            log("[BRIDGE] UserNameSearchFragment ready");

                            post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (!PENDING.get()
                                                        || pendingToken != token) {
                                                    return;
                                                }

                                                XposedHelpers.callMethod(
                                                        finalFragment,
                                                        "loadUser",
                                                        finalUsername
                                                );

                                                log("[BRIDGE] loadUser called");

                                            } catch (Throwable t) {
                                                log("[BRIDGE] loadUser失败: "
                                                        + t);
                                                clearAndFinishHelper();
                                            }
                                        }
                                    }
                            );

                        } catch (Throwable t) {
                            log("[BRIDGE] Fragment init失败: " + t);
                            clearAndFinishHelper();
                        }
                    }
                }
        );

        log("UserNameSearchFragment.initViewData hook OK");
    }

    // ============================================================
    // Paging 状态
    // ============================================================

    private static void hookRefreshState()
            throws Throwable {
        Class<?> baseFragment =
                XposedHelpers.findClass(
                        "com.hellotalk.search.v2.logic.controller.searchuser.BaseUserPagingFragment",
                        sCl
                );

        Class<?> loadStates =
                XposedHelpers.findClass(
                        "f4.h",
                        sCl
                );

        XposedHelpers.findAndHookMethod(
                baseFragment,
                "onRefreshLoadState",
                loadStates,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        try {
                            if (!PENDING.get()) {
                                return;
                            }

                            Object fragment =
                                    param.thisObject;

                            if (!isOurFragment(fragment)) {
                                return;
                            }

                            Object state =
                                    XposedHelpers.callMethod(
                                            param.args[0],
                                            "b"
                                    );

                            if (state == null) {
                                return;
                            }

                            String stateClass =
                                    state.getClass().getName();

                            /*
                             * f4.w$a = Error
                             */
                            if ("f4.w$a".equals(stateClass)) {
                                log("[BRIDGE] Paging refresh error");
                                clearAndFinishHelper();
                                return;
                            }

                            /*
                             * f4.w$c = NotLoading
                             */
                            if (!"f4.w$c".equals(stateClass)) {
                                return;
                            }

                            Object adapter =
                                    XposedHelpers.getObjectField(
                                            fragment,
                                            "userListAdapter"
                                    );

                            if (adapter == null) {
                                return;
                            }

                            Object snapshot =
                                    XposedHelpers.callMethod(
                                            adapter,
                                            "r"
                                    );

                            if (snapshot == null) {
                                return;
                            }

                            Object listObject =
                                    XposedHelpers.callMethod(
                                            snapshot,
                                            "d"
                                    );

                            if (!(listObject instanceof List)) {
                                return;
                            }

                            List<?> list =
                                    (List<?>) listObject;

                            String wanted =
                                    pendingUsername;

                            if (isBlank(wanted)) {
                                return;
                            }

                            Object match =
                                    findMatchingUser(
                                            list,
                                            wanted
                                    );

                            if (match != null) {
                                int uid =
                                        readUid(match);

                                if (uid > 0) {
                                    resolveUser(match, uid);
                                }

                                return;
                            }

                            if (list.isEmpty()) {
                                scheduleEmptyRecheck(fragment);
                            }

                        } catch (Throwable t) {
                            log("[BRIDGE] Paging状态处理失败: "
                                    + t);
                        }
                    }
                }
        );

        log("BaseUserPagingFragment.onRefreshLoadState hook OK");
    }

    private static void scheduleEmptyRecheck(
            final Object fragment
    ) {
        final long token =
                pendingToken;

        if (emptyCheckToken == token) {
            return;
        }

        emptyCheckToken = token;

        postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!PENDING.get()
                                    || pendingToken != token) {
                                return;
                            }

                            Object adapter =
                                    XposedHelpers.getObjectField(
                                            fragment,
                                            "userListAdapter"
                                    );

                            if (adapter == null) {
                                clearAndFinishHelper();
                                return;
                            }

                            Object snapshot =
                                    XposedHelpers.callMethod(
                                            adapter,
                                            "r"
                                    );

                            if (snapshot == null) {
                                clearAndFinishHelper();
                                return;
                            }

                            Object listObject =
                                    XposedHelpers.callMethod(
                                            snapshot,
                                            "d"
                                    );

                            if (!(listObject instanceof List)) {
                                clearAndFinishHelper();
                                return;
                            }

                            List<?> list =
                                    (List<?>) listObject;

                            Object match =
                                    findMatchingUser(
                                            list,
                                            pendingUsername
                                    );

                            if (match != null) {
                                int uid =
                                        readUid(match);

                                if (uid > 0) {
                                    resolveUser(match, uid);
                                    return;
                                }
                            }

                            log("[BRIDGE] 二次检查仍为空，结束反查");
                            clearAndFinishHelper();

                        } catch (Throwable t) {
                            log("[BRIDGE] 空结果二次检查失败: "
                                    + t);
                            clearAndFinishHelper();
                        }
                    }
                },
                EMPTY_RECHECK_MS
        );
    }

    private static boolean isOurFragment(
            Object fragment
    ) {
        if (fragment == null) {
            return false;
        }

        if (!"com.hellotalk.search.v2.logic.controller.searchuser.UserNameSearchFragment"
                .equals(
                        fragment.getClass().getName()
                )) {
            return false;
        }

        try {
            Object activity =
                    XposedHelpers.callMethod(
                            fragment,
                            "getActivity"
                    );

            return activity != null
                    && "com.hellotalk.search.v2.view.IDSearchActivity"
                    .equals(
                            activity.getClass().getName()
                    );

        } catch (Throwable t) {
            return false;
        }
    }

    private static Object findMatchingUser(
            List<?> list,
            String wanted
    ) {
        if (list == null
                || isBlank(wanted)) {
            return null;
        }

        for (Object item : list) {
            if (item == null) {
                continue;
            }

            String username =
                    normalize(
                            readUsername(item)
                    );

            if (wanted.equals(username)
                    && readUid(item) > 0) {
                return item;
            }
        }

        return null;
    }

    // ============================================================
    // 成功处理
    // ============================================================

    private static void resolveUser(
            Object item,
            int uid
    ) {
        if (!PENDING.compareAndSet(
                true,
                false
        )) {
            return;
        }

        String username =
                normalize(
                        readUsername(item)
                );

        if (!isBlank(username)) {
            UID_CACHE.put(
                    username,
                    uid
            );
        }

        Activity source =
                sourceActivity.get();

        Activity helper =
                helperActivity.get();

        clearPendingFields();

        if (source == null
                || source.isFinishing()
                || isDestroyed(source)) {
            finishActivity(helper);
            return;
        }

        final Activity finalSource =
                source;

        final Activity finalHelper =
                helper;

        final Object finalItem =
                item;

        log("[BRIDGE] resolved "
                + username
                + " -> "
                + uid);

        post(
                new Runnable() {
                    @Override
                    public void run() {
                        callNativeProfile(
                                finalSource,
                                finalItem
                        );

                        finishActivity(
                                finalHelper
                        );
                    }
                }
        );
    }

    private static void openProfileByUid(
            Activity source,
            int uid
    ) {
        try {
            Class<?> routerClass =
                    XposedHelpers.findClass(
                            "com.hellotalk.ht.base.router.RouterManager",
                            sCl
                    );

            Object router =
                    XposedHelpers.callStaticMethod(
                            routerClass,
                            "getInstance"
                    );

            Object provider =
                    XposedHelpers.callMethod(
                            router,
                            "getHTProfile"
                    );

            XposedHelpers.callMethod(
                    provider,
                    "E3",
                    source,
                    uid,
                    2
            );

        } catch (Throwable t) {
            /*
             * 缓存路径失败时不影响模块运行。
             */
            log("[BRIDGE] 缓存跳转失败: " + t);
        }
    }

    private static void callNativeProfile(
            Activity source,
            Object item
    ) {
        try {
            Class<?> sl0Class =
                    XposedHelpers.findClass(
                            "sl0.c",
                            sCl
                    );

            Object singleton =
                    XposedHelpers.getStaticObjectField(
                            sl0Class,
                            "a"
                    );

            XposedHelpers.callMethod(
                    singleton,
                    "e",
                    source,
                    item,
                    "user_filter_word",
                    "SearchService",
                    0
            );

            log("[BRIDGE] sl0.c.e called");

        } catch (Throwable t) {
            log("[BRIDGE] sl0.c.e失败: " + t);
        }
    }

    // ============================================================
    // 超时和清理
    // ============================================================

    private static void scheduleTimeout(
            final long token
    ) {
        postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!PENDING.get()) {
                            return;
                        }

                        if (pendingToken != token) {
                            return;
                        }

                        log("[BRIDGE] 反查超时: "
                                + pendingUsername);

                        clearAndFinishHelper();
                    }
                },
                TIMEOUT_MS
        );
    }

    private static void clearAndFinishHelper() {
        Activity helper =
                helperActivity.get();

        clearPending();
        finishActivity(helper);
    }

    private static void finishActivity(
            Activity activity
    ) {
        if (activity == null) {
            return;
        }

        try {
            if (!activity.isFinishing()) {
                activity.finish();
                activity.overridePendingTransition(
                        0,
                        0
                );
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearPendingFields() {
        pendingUsername = null;

        sourceActivity =
                new WeakReference<>(null);

        helperActivity =
                new WeakReference<>(null);

        pendingToken = 0L;
        fragmentToken = 0L;
        emptyCheckToken = 0L;
    }

    private static void clearPending() {
        PENDING.set(false);
        clearPendingFields();
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static int readUid(Object item) {
        try {
            Object value =
                    XposedHelpers.callMethod(
                            item,
                            "T"
                    );

            return value instanceof Integer
                    ? (Integer) value
                    : 0;

        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readUsername(
            Object item
    ) {
        try {
            Object value =
                    XposedHelpers.getObjectField(
                            item,
                            "Y"
                    );

            return value == null
                    ? null
                    : String.valueOf(value);

        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalize(
            String value
    ) {
        if (value == null) {
            return null;
        }

        value = value.trim();

        if (value.startsWith("@")) {
            value = value.substring(1);
        }

        return value;
    }

    private static boolean isBlank(
            String value
    ) {
        return value == null
                || value.trim().isEmpty();
    }

    private static boolean isDestroyed(
            Activity activity
    ) {
        try {
            return android.os.Build.VERSION.SDK_INT >= 17
                    && activity.isDestroyed();

        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(
            String message
    ) {
        XposedBridge.log(
                "[HT] " + message
        );
    }
}
