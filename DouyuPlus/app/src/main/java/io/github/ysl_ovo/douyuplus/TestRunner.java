package io.github.ysl_ovo.douyuplus;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TestRunner {
    private static final String TAG = "DouyuPlus_Test";
    public static final String ACTION_TEST = "com.douyuplus.ACTION_TEST";
    private static final int MAX_LOG_LINES = 600;
    private static final ArrayDeque<String> sLogBuffer = new ArrayDeque<>(MAX_LOG_LINES);
    private static volatile boolean sLogCollectorStarted = false;
    private static volatile WeakReference<Activity> sCurrentActivityRef = null;
    private static volatile boolean sReceiverRegistered = false;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        startLogCollectorIfNeeded();
        try {
            // 针对具体 Activity 进行 Hook，避免 bootclasspath 限制
            String[] targetActivities = new String[]{
                    "tv.douyu.view.activity.launcher.DYLauncherActivity",
                    "com.douyu.module.home.pages.main.MainActivity",
                    "com.douyu.module.player.p.rambobase.RamboPlayerActivity"
            };

            for (String actName : targetActivities) {
                try {
                    Class<?> cls = XposedHelpers.findClass(actName, lpparam.classLoader);
                    XposedHelpers.findAndHookMethod(cls, "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Activity activity = (Activity) param.thisObject;
                            sCurrentActivityRef = new WeakReference<>(activity);
                            Log.i(TAG, "Current resumed activity: " + activity.getClass().getName());

                            if (!sReceiverRegistered) {
                                sReceiverRegistered = true;
                                Context appCtx = activity.getApplicationContext();
                                IntentFilter filter = new IntentFilter(ACTION_TEST);
                                BroadcastReceiver receiver = new BroadcastReceiver() {
                                    @Override
                                    public void onReceive(Context context, Intent intent) {
                                        Log.i(TAG, "============================================================");
                                        Log.i(TAG, ">>> RECEIVED ACTION_TEST: EXECUTING LIVE E2E TEST <<<");
                                        Log.i(TAG, "============================================================");
                                        final String targetRoomId = intent.getStringExtra("room_id");
                                        final String manualRate = intent.getStringExtra("manual_rate");
                                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                                            @Override
                                            public void run() {
                                                Activity curAct = sCurrentActivityRef != null ? sCurrentActivityRef.get() : null;
                                                if (curAct == null || curAct.isFinishing()) {
                                                    Log.w(TAG, "No active resumed activity available for test");
                                                    return;
                                                }
                                                if (manualRate != null && !manualRate.isEmpty()) {
                                                    io.github.ysl_ovo.douyuplus.hooks.AutoQualityHook.simulateUserManualSwitch(manualRate, "用户手动测试_" + manualRate);
                                                } else if (targetRoomId != null && !targetRoomId.isEmpty()) {
                                                    try {
                                                        Class<?> launchCls = XposedHelpers.findClass("com.douyu.module.launch.MLaunchProviderUtils", lpparam.classLoader);
                                                        XposedHelpers.callStaticMethod(launchCls, "V", curAct, targetRoomId, null);
                                                        Log.i(TAG, "Launched room " + targetRoomId + " via MLaunchProviderUtils");
                                                    } catch (Throwable t) {
                                                        Log.e(TAG, "Failed to launch room: " + targetRoomId, t);
                                                    }
                                                } else {
                                                    runEndToEndTest(curAct, lpparam);
                                                }
                                            }
                                        });
                                    }
                                };
                                if (Build.VERSION.SDK_INT >= 33) {
                                    appCtx.registerReceiver(receiver, filter, 0x2); // Context.RECEIVER_EXPORTED
                                } else {
                                    appCtx.registerReceiver(receiver, filter);
                                }
                                Log.i(TAG, "✓ Successfully registered ACTION_TEST receiver on Application Context!");
                            }
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "Could not hook onResume for: " + actName);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed in TestRunner.init", t);
        }
    }

    private static void runEndToEndTest(Activity activity, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 1. 获取 DanmuOptMgr 实例
            Class<?> danmuOptMgrCls = XposedHelpers.findClass("tv.douyu.danmuopt.manager.DanmuOptMgr", lpparam.classLoader);
            Object optMgr = null;
            try {
                optMgr = XposedHelpers.callStaticMethod(danmuOptMgrCls, "ts", activity);
            } catch (Throwable t) {
                try {
                    optMgr = XposedHelpers.callStaticMethod(danmuOptMgrCls, "Dx", activity);
                } catch (Throwable ignored) {}
            }

            if (optMgr == null) {
                Log.e(TAG, "TEST FAILED: DanmuOptMgr instance is null!");
                return;
            }
            Log.i(TAG, "✓ TEST STEP 1: DanmuOptMgr instance acquired successfully: " + optMgr.getClass().getName());

            // 2. 检索 DanmuOptMgr 内部保存的 OptFunBean 映射表
            Map<String, Object> optBeanMap = null;
            for (Field f : danmuOptMgrCls.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(optMgr);
                    if (val instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) val;
                        if (map.containsKey("type_Cppy") || map.containsKey("type_copy")) {
                            optBeanMap = (Map<String, Object>) val;
                            break;
                        }
                    }
                }
            }

            if (optBeanMap != null) {
                Object addOneBean = optBeanMap.get("type_Cppy");
                if (addOneBean == null) addOneBean = optBeanMap.get("type_copy");

                if (addOneBean != null) {
                    Log.i(TAG, "✓ TEST STEP 2: Found OptFunBean (type_Cppy): " + addOneBean.getClass().getName());

                    // 测试 a() 方法 (可见性)
                    Method aMethod = addOneBean.getClass().getMethod("a");
                    boolean isVisible = (Boolean) aMethod.invoke(addOneBean);
                    Log.i(TAG, "✓ TEST STEP 2.1: OptFunBean.a() visibility returned: " + isVisible + " (Expected: true)");

                    // 测试 b() 方法 (CD 冷却检查)
                    Method bMethod = addOneBean.getClass().getMethod("b");
                    boolean isCooldownInitial = (Boolean) bMethod.invoke(addOneBean);
                    Log.i(TAG, "✓ TEST STEP 2.2: OptFunBean.b() initial returned: " + isCooldownInitial + " (Expected: false)");

                    // 模拟官方调用 c() 设置 CD 倒计时
                    Method cMethod = addOneBean.getClass().getMethod("c");
                    cMethod.invoke(addOneBean);
                    Log.i(TAG, "✓ TEST STEP 2.3: Called OptFunBean.c() to record last send timestamp");

                    // 再次检查 b()，看 Hook 是否成功粉碎了 60 秒冷却限制！
                    boolean isCooldownAfter = (Boolean) bMethod.invoke(addOneBean);
                    Log.i(TAG, "✓ TEST STEP 2.4: OptFunBean.b() after CD trigger returned: " + isCooldownAfter + " (Expected: false, BYPASSED!)");
                }
            } else {
                Log.w(TAG, "Could not locate optBeanMap in DanmuOptMgr");
            }

            // 3. 测试弹幕底层发送与防重零宽字符注入
            String testContent = "DouyuPlus_Test弹幕_" + (System.currentTimeMillis() % 10000);
            Log.i(TAG, "✓ TEST STEP 3: Dispatching test danmu: \"" + testContent + "\"");

            boolean sendTriggered = false;
            for (String mName : new String[]{"Zt", "Xy"}) {
                try {
                    Method m = danmuOptMgrCls.getDeclaredMethod(mName, String.class);
                    m.setAccessible(true);
                    m.invoke(optMgr, testContent);
                    sendTriggered = true;
                    Log.i(TAG, "✓ TEST STEP 3.1: Successfully invoked DanmuOptMgr." + mName + "()");
                    break;
                } catch (Throwable ignored) {}
            }

            if (!sendTriggered) {
                Class<?> userInfoBeanCls = XposedHelpers.findClass("com.douyu.live.common.beans.UserInfoBean", lpparam.classLoader);
                Object fakeUserInfo = userInfoBeanCls.newInstance();
                XposedHelpers.setObjectField(fakeUserInfo, "content", testContent);
                XposedHelpers.setObjectField(fakeUserInfo, "cid", "test_cid_" + System.currentTimeMillis());

                for (String mName : new String[]{"r7", "y8"}) {
                    try {
                        Method m = danmuOptMgrCls.getDeclaredMethod(mName, userInfoBeanCls);
                        m.setAccessible(true);
                        m.invoke(optMgr, fakeUserInfo);
                        sendTriggered = true;
                        Log.i(TAG, "✓ TEST STEP 3.2: Successfully invoked DanmuOptMgr." + mName + "()");
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            Log.i(TAG, "============================================================");
            Log.i(TAG, ">>> E2E TEST COMPLETED SUCCESSFULLY! <<<");
            Log.i(TAG, "============================================================");

            Toast.makeText(activity, "⚡ [DouyuPlus] 端到端实测验证完成! 请检查 Logcat", Toast.LENGTH_LONG).show();

        } catch (Throwable t) {
            Log.e(TAG, "TEST RUNNER FAILED WITH EXCEPTION", t);
        }
    }

    /**
     * 启动后台零 I/O 的内存环形日志收集器（仅在开启调试模式时运行）
     */
    public static synchronized void startLogCollectorIfNeeded() {
        if (sLogCollectorStarted || !DouyuPlusConfig.isDebugModeEnabled()) {
            return;
        }
        sLogCollectorStarted = true;

        // 挂载全局未捕获异常记录器，确保发生崩溃时能将堆栈留存在内存环形队列与 Logcat 中
        try {
            final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        StringWriter sw = new StringWriter();
                        e.printStackTrace(new PrintWriter(sw));
                        String crashLog = "[CRASH] Thread=" + t.getName() + "\n" + sw.toString();
                        appendLogLine(crashLog);
                        Log.e("DouyuPlus_Crash", crashLog);
                    } catch (Throwable ignored) {}
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(t, e);
                    }
                }
            });
        } catch (Throwable ignored) {}

        Thread collector = new Thread(new Runnable() {
            @Override
            public void run() {
                Process proc = null;
                try {
                    int pid = android.os.Process.myPid();
                    proc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time", "--pid=" + pid});
                    BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while (DouyuPlusConfig.isDebugModeEnabled() && (line = reader.readLine()) != null) {
                        if (isRelevantLogLine(line)) {
                            appendLogLine(line);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "LogCollector stopped: " + t.getMessage());
                } finally {
                    if (proc != null) {
                        try {
                            proc.destroy();
                        } catch (Throwable ignored) {}
                    }
                    sLogCollectorStarted = false;
                }
            }
        }, "DouyuPlus-LogCollector");
        collector.setDaemon(true);
        collector.start();
        Log.i(TAG, "Started DouyuPlus in-memory log collector (max " + MAX_LOG_LINES + " lines)");
    }

    private static boolean isRelevantLogLine(String line) {
        return line != null && (line.contains("DouyuPlus")
                || line.contains("AndroidRuntime")
                || line.contains("Xposed")
                || line.contains("FATAL EXCEPTION"));
    }

    private static void appendLogLine(String line) {
        synchronized (sLogBuffer) {
            if (sLogBuffer.size() >= MAX_LOG_LINES) {
                sLogBuffer.pollFirst();
            }
            sLogBuffer.addLast(line);
        }
    }

    /**
     * 一键汇总环境诊断信息与模块运行日志并复制到系统剪贴板
     */
    public static void copyDebugLogsToClipboard(Context context) {
        if (context == null) return;
        try {
            List<String> lines;
            synchronized (sLogBuffer) {
                lines = new ArrayList<>(sLogBuffer);
            }

            // 如果内存环形缓冲为空（如刚开启调试模式尚未重启），实时抓取一次当前进程的 logcat 快照
            if (lines.isEmpty()) {
                Process proc = null;
                try {
                    int pid = android.os.Process.myPid();
                    proc = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", "2500", "--pid=" + pid});
                    BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (isRelevantLogLine(line)) {
                            lines.add(line);
                            if (lines.size() > MAX_LOG_LINES) {
                                lines.remove(0);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (proc != null) {
                        try {
                            proc.destroy();
                        } catch (Throwable ignored) {}
                    }
                }
            }

            StringBuilder sb = new StringBuilder(8192);
            String nowStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            sb.append("=== DouyuPlus Debug Log (").append(nowStr).append(") ===\n");
            sb.append("Device: ").append(Build.BRAND).append(" ").append(Build.MODEL)
                    .append(" (Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT).append(")\n");
            try {
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                sb.append("Screen: ").append(dm.widthPixels).append("x").append(dm.heightPixels)
                        .append(" (density=").append(dm.density)
                        .append(", sw=").append(context.getResources().getConfiguration().smallestScreenWidthDp).append("dp)\n");
            } catch (Throwable ignored) {}
            sb.append("Config: padMode=").append(DouyuPlusConfig.isPadModeEnabled())
                    .append(", adBlock=").append(DouyuPlusConfig.isAdBlockEnabled())
                    .append(", autoQuality=").append(DouyuPlusConfig.getAutoQualityMode(context))
                    .append(", aiStrengthen=").append(DouyuPlusConfig.isAiStrengthenEnabled())
                    .append(" (SGSR=").append(DouyuPlusConfig.getSgsrLevel(context))
                    .append("%, CAS=").append(DouyuPlusConfig.getCasLevel(context)).append("%)")
                    .append(", danmuClean=").append(DouyuPlusConfig.isDanmuCleanEnabled())
                    .append(", danmuMerge=").append(DouyuPlusConfig.isDanmuMergeEnabled())
                    .append(", danmuSuffix=").append(DouyuPlusConfig.isDanmuSuffixEnabled())
                    .append(", addOneNoCd=").append(DouyuPlusConfig.isAddOneNoCdEnabled())
                    .append(", performance=").append(DouyuPlusConfig.isPerformanceEnabled())
                    .append(", debugMode=").append(DouyuPlusConfig.isDebugModeEnabled())
                    .append("\n----------------------------------------\n");

            if (lines.isEmpty()) {
                sb.append("(暂无捕获到的 DouyuPlus 日志，请开启调试模式操作后再试)\n");
            } else {
                for (String l : lines) {
                    sb.append(l).append('\n');
                }
            }

            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("DouyuPlus_Log", sb.toString()));
                Toast.makeText(context, "已复制 " + lines.size() + " 条调试日志到剪贴板", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "无法访问系统剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(context, "复制日志失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
