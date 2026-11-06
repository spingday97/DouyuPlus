package io.github.ysl_ovo.douyuplus.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 平板/宽屏模式强制解锁 Hook (PadModeHook)
 * 彻底优化平板启动体验：
 * 1. 禁用 SoraActivity / MainActivity / DYLauncherActivity 的 enableRecreateOnWidthChange()，
 *    禁止窗口尺寸变动时触发自我销毁重建 (recreate)，从根源消灭启动时的反复闪烁重置。
 * 2. 对 Activity.recreate() 增加全局 5000ms 冷启动硬核拦截与跨实例 3000ms 防抖，彻底掐死连环闪烁。
 * 3. 提前在 Activity.attachBaseContext 阶段注入横屏与 840dp 配置，杜绝开屏初期的窄屏拉伸现象。
 * 4. Hook DYDeviceUtils 全套设备判断 (u0/t0/s0/w0/l0/x0/r0) 与 DYWideScreenUtilKt 全套宽屏判定。
 * 5. Hook DYWindowUtils 横竖屏判断 (X/Y -> true, Z/V -> false)，全 App 统一为宽屏横屏分支。
 * 6. 对启动中转 Activity 注入 overridePendingTransition(0, 0)，实现零动画无缝秒进首页。
 * 7. 拦截 WideActivityStyle.h/l：禁止二级页面压缩为 375dp 窄条，强制全宽铺满。
 * 8. 直播间横屏时自动隐藏竖屏冗余覆盖层 (mLivePortraitView / fjb)。
 */
public class PadModeHook {
    private static final String TAG = "DouyuPlus_PadMode";

    // 全局启动时间戳，用于冷启动保护
    private static final long APP_START_TIME = System.currentTimeMillis();
    // 全局静态上次 recreate 时间戳，彻底防止任何实例绕过防抖
    private static long sGlobalLastRecreateTime = 0L;

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 0. 禁用 enableRecreateOnWidthChange：彻底禁止 Activity 在屏幕宽度变化时调用 recreate() 自我销毁重建
        String[] soraClasses = new String[]{
                "com.douyu.module.base.SoraActivity",
                "com.douyu.module.home.pages.main.MainActivity",
                "tv.douyu.view.activity.launcher.DYLauncherActivity",
                "tv.douyu.view.activity.launcher.DYSplashLauncherActivity",
                "com.douyu.module.follow.p.live.page.myfollow.FollowFansActivity"
        };
        for (String clsName : soraClasses) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(cls, "enableRecreateOnWidthChange", XC_MethodReplacement.returnConstant(false));
            } catch (Throwable ignored) {}
        }

        // 0b. 拦截 Activity.recreate()：全局冷启动前 10000ms 严禁任何 recreate，且跨实例 3000ms 防抖，彻底消除反复闪烁
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "recreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    String actName = act.getClass().getName();

                    // 启动中转 Activity (Launcher / Splash) 绝对不准自我重建！
                    if (actName.contains("LauncherActivity")) {
                        Log.i(TAG, "Activity.recreate permanently blocked for launcher: " + actName);
                        param.setResult(null);
                        return;
                    }

                    long now = System.currentTimeMillis();
                    // 1. 冷启动前 10000ms 内，全面硬核拦截任何 Activity 销毁重建！
                    long timeSinceStart = now - APP_START_TIME;
                    if (timeSinceStart < 10000L) {
                        Log.i(TAG, "Activity.recreate suppressed during cold launch window: " + act.getClass().getSimpleName() + " (" + timeSinceStart + "ms)");
                        param.setResult(null);
                        return;
                    }

                    // 2. 全局（跨任何实例）3000ms 严格防抖
                    if (now - sGlobalLastRecreateTime < 3000L) {
                        Log.i(TAG, "Activity.recreate suppressed by global debounce: " + act.getClass().getSimpleName() + " (" + (now - sGlobalLastRecreateTime) + "ms)");
                        param.setResult(null);
                        return;
                    }
                    sGlobalLastRecreateTime = now;
                }
            });
            Log.i(TAG, "Hooked Activity.recreate with global cold launch protection");
        } catch (Throwable ignored) {}

        // 0c. Hook MainActivity.onConfigurationChanged：屏蔽由 uiMode 或屏幕旋转引起的内置 recreate()
        try {
            Class<?> mainActCls = XposedHelpers.findClass(
                    "com.douyu.module.home.pages.main.MainActivity",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(mainActCls, "onConfigurationChanged", Configuration.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        Configuration cfg = (Configuration) param.args[0];
                        if (cfg != null) {
                            cfg.orientation = Configuration.ORIENTATION_LANDSCAPE;
                            if (cfg.smallestScreenWidthDp < 840) cfg.smallestScreenWidthDp = 840;
                            if (cfg.screenWidthDp < 840) cfg.screenWidthDp = 840;
                            // 将 cfg.uiMode 同步为当前斗鱼内部主题，从根源掐死 MainActivity.onConfigurationChanged 内部硬编码的 recreate() 触发条件
                            try {
                                Class<?> btuCls = XposedHelpers.findClass("com.douyu.lib.theme.BaseThemeUtils", lpparam.classLoader);
                                boolean isDark = (Boolean) XposedHelpers.callStaticMethod(btuCls, "g", param.thisObject);
                                cfg.uiMode = (cfg.uiMode & ~48) | (isDark ? 32 : 16);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 0d. Hook SoraActivity.onResume：同步 this.isDark 消除主题异步读取差异导致的内置 recreate()
        try {
            Class<?> soraCls = XposedHelpers.findClass(
                    "com.douyu.module.base.SoraActivity",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(soraCls, "onResume", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        try {
                            Class<?> btuCls = XposedHelpers.findClass("com.douyu.lib.theme.BaseThemeUtils", lpparam.classLoader);
                            boolean isDark = (Boolean) XposedHelpers.callStaticMethod(btuCls, "i");
                            XposedHelpers.setBooleanField(param.thisObject, "isDark", isDark);
                        } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 1. Hook DYWideScreenUtilKt: 强制返回大屏平板模式判定
        try {
            Class<?> wideUtilKtCls = XposedHelpers.findClass(
                    "com.douyu.lib.utils.DYWideScreenUtilKt",
                    lpparam.classLoader
            );

            // a(Context) -> smallestScreenWidthDp >= 840
            XposedHelpers.findAndHookMethod(wideUtilKtCls, "a", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        param.setResult(840);
                    }
                }
            });

            // c(Context) -> screenWidthDp >= 840
            XposedHelpers.findAndHookMethod(wideUtilKtCls, "c", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        param.setResult(840);
                    }
                }
            });

            // d, e, g, i, k(Context) -> true (大宽屏 4 列模式)
            String[] trueMethods = new String[]{"d", "e", "g", "i", "k"};
            for (String mName : trueMethods) {
                try {
                    XposedHelpers.findAndHookMethod(wideUtilKtCls, mName, Context.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (DouyuPlusConfig.isPadModeEnabled()) {
                                param.setResult(true);
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // f(Context) -> false (不用中等 3 列，直接用大屏 e() 的 4 列)
            try {
                XposedHelpers.findAndHookMethod(wideUtilKtCls, "f", Context.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) {
                            param.setResult(false);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // h(Context) -> false (非窄屏手机)
            try {
                XposedHelpers.findAndHookMethod(wideUtilKtCls, "h", Context.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) {
                            param.setResult(false);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // j(Context, int) -> false (禁止二级页面被判定为需要缩窄成 375dp 悬浮卡片)
            try {
                XposedHelpers.findAndHookMethod(wideUtilKtCls, "j", Context.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) {
                            param.setResult(false);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            Log.i(TAG, "Hooked DYWideScreenUtilKt for Pad mode");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DYWideScreenUtilKt", t);
        }

        // 2. Hook DYDeviceUtils: 全套平板判定 (u0/t0/s0/w0/l0/x0/r0) -> true
        try {
            Class<?> devUtilsCls = XposedHelpers.findClass(
                    "com.douyu.lib.utils.DYDeviceUtils",
                    lpparam.classLoader
            );
            String[] tabletCheckMethods = new String[]{"u0", "t0", "s0", "w0", "l0", "x0", "r0"};
            for (String mName : tabletCheckMethods) {
                try {
                    XposedHelpers.findAndHookMethod(devUtilsCls, mName, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (DouyuPlusConfig.isPadModeEnabled()) {
                                param.setResult(true);
                            }
                        }
                    });
                } catch (Throwable ignored) {}
                try {
                    XposedHelpers.findAndHookMethod(devUtilsCls, mName, Context.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (DouyuPlusConfig.isPadModeEnabled()) {
                                param.setResult(true);
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 3. Hook DYWindowUtils: TYPE_WIDTH_SIZE_LARGE 以及横屏判定 X/Y -> true, 竖屏判定 Z/V -> false
        try {
            Class<?> winUtilsCls = XposedHelpers.findClass(
                    "com.douyu.lib.utils.DYWindowUtils",
                    lpparam.classLoader
            );
            Class<?> widthTypeCls = XposedHelpers.findClass(
                    "com.douyu.lib.utils.DYWindowUtils$WidthSizeType",
                    lpparam.classLoader
            );
            final Object largeType = Enum.valueOf((Class<Enum>) widthTypeCls, "TYPE_WIDTH_SIZE_LARGE");
            if (largeType != null) {
                XposedHelpers.findAndHookMethod(winUtilsCls, "B", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) {
                            param.setResult(largeType);
                        }
                    }
                });
                XposedHelpers.findAndHookMethod(winUtilsCls, "F", Context.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) {
                            param.setResult(largeType);
                        }
                    }
                });
            }
            // X(Activity) & Y(Context) -> true (强制横屏)
            try {
                XposedHelpers.findAndHookMethod(winUtilsCls, "X", Activity.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) param.setResult(true);
                    }
                });
                XposedHelpers.findAndHookMethod(winUtilsCls, "Y", Context.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) param.setResult(true);
                    }
                });
            } catch (Throwable ignored) {}
            // Z(Activity) & V(Context) -> false (禁止识别为竖屏)
            try {
                XposedHelpers.findAndHookMethod(winUtilsCls, "Z", Activity.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) param.setResult(false);
                    }
                });
                XposedHelpers.findAndHookMethod(winUtilsCls, "V", Context.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (DouyuPlusConfig.isPadModeEnabled()) param.setResult(false);
                    }
                });
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        // 4. Hook WideCompatUtil: 解除竖屏锁定，强制使用传感器横屏铺满平板
        try {
            Class<?> wideCompatUtilCls = XposedHelpers.findClass(
                    "com.douyu.lib.utils.WideCompatUtil",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(wideCompatUtilCls, "b", Activity.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        Activity act = (Activity) param.args[0];
                        if (act != null) {
                            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                        }
                        param.setResult(null);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(wideCompatUtilCls, "c", Activity.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        Activity act = (Activity) param.args[0];
                        if (act != null) {
                            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                        }
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 5. Hook WideActivityStyle.h & l: 禁止将二级页面压缩为 375dp 窄卡片或设置半透明窄窗主题，强制全宽铺满
        try {
            Class<?> wideStyleCls = XposedHelpers.findClass(
                    "tv.douyu.lib.ui.activity.WideActivityStyle",
                    lpparam.classLoader
            );
            Class<?> fragActCls = XposedHelpers.findClass(
                    "androidx.fragment.app.FragmentActivity",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(wideStyleCls, "l", fragActCls, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        param.setResult(null);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(wideStyleCls, "h", fragActCls, ViewGroup.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        Object fragAct = param.args[0];
                        ViewGroup contentView = (ViewGroup) param.args[1];
                        if (fragAct != null && contentView != null) {
                            Object parent = contentView.getParent();
                            if (parent instanceof View) {
                                try {
                                    XposedHelpers.callMethod(param.thisObject, "t", fragAct, parent, contentView);
                                } catch (Throwable ignored) {}
                            }
                        }
                        param.setResult(null);
                    }
                }
            });
            Log.i(TAG, "Hooked WideActivityStyle.h & l -> full width on Pad mode");
        } catch (Throwable ignored) {}

        // 6. Hook RamboPlayerActivity.MB / WB / OB / yB: 平板横屏直入直播间时隐藏竖屏专用层 (mLivePortraitView / jhk / fjb)
        try {
            Class<?> ramboActCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.rambobase.RamboPlayerActivity",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(ramboActCls, "MB", View.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    View portraitView = (View) param.args[0];
                    if (portraitView != null) {
                        portraitView.setVisibility(View.GONE);
                    }
                    hidePortraitLayersInPadMode((Activity) param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(ramboActCls, "WB", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    hidePortraitLayersInPadMode((Activity) param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(ramboActCls, "VB", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    hidePortraitLayersInPadMode((Activity) param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(ramboActCls, "OB", Configuration.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    hidePortraitLayersInPadMode((Activity) param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(ramboActCls, "yB", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    final Activity act = (Activity) param.thisObject;
                    hidePortraitLayersInPadMode(act);
                    if (act != null && act.getWindow() != null && act.getWindow().getDecorView() != null) {
                        act.getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                hidePortraitLayersInPadMode(act);
                            }
                        }, 300);
                    }
                }
            });
            Log.i(TAG, "Hooked RamboPlayerActivity -> hide portrait layers (mLivePortraitView/fjb) in landscape");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RamboPlayerActivity for Pad mode", t);
        }

        // 6b. Hook ListChatViewNeuron & RamboTabNeuron: 禁止在平板全屏横屏直播间异步膨胀左上角竖版「聊天/热议」悬浮窗 (id/fjb = 2131370344)
        try {
            Class<?> listChatNeuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.listchatview.ListChatViewNeuron",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(listChatNeuronCls, "v", ViewGroup.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    ViewGroup root = (ViewGroup) param.args[0];
                    if (root != null) {
                        View fjb = root.findViewById(2131370344);
                        if (fjb != null) {
                            fjb.setVisibility(View.GONE);
                        }
                    }
                    param.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(listChatNeuronCls, "PE", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        param.args[0] = false;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(listChatNeuronCls, "Vl", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled()) {
                        param.args[0] = false;
                    }
                }
            });
            Log.i(TAG, "Hooked ListChatViewNeuron -> disabled floating portrait chat box (fjb) in Pad landscape");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook ListChatViewNeuron for Pad mode", t);
        }

        // 7. Hook Activity 生命周期与方向：提前锁定横屏，杜绝窄屏拉伸与启动黑白闪烁
        try {
            // 拦截 setRequestedOrientation：去重并强制替换为 SENSOR_LANDSCAPE
            XposedHelpers.findAndHookMethod(Activity.class, "setRequestedOrientation", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    int orientation = (Integer) param.args[0];

                    // 启动中转 Activity (Launcher / Splash) 绝对不准向 WMS 发送旋转请求，消除 WMS 队列震荡
                    if (act != null && act.getClass().getName().contains("LauncherActivity")) {
                        param.setResult(null);
                        return;
                    }

                    if (act != null) {
                        int cur = act.getRequestedOrientation();
                        // 如果已经是横屏，坚决不要重复向系统发请求，防止引发系统级旋转和 Taskbar 重建
                        if (cur == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                || cur == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                            param.setResult(null);
                            return;
                        }
                    }
                    if (orientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            && orientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        param.args[0] = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                    }
                }
            });

            // 提前在 attachBaseContext 阶段注入横屏与平板 Configuration（不发系统请求，仅配置）
            XposedHelpers.findAndHookMethod(Activity.class, "attachBaseContext", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Context baseCtx = (Context) param.args[0];
                    if (baseCtx != null) {
                        applyPadConfiguration(baseCtx);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    applyPadConfiguration(act);
                }
            });

            // 在 onCreate 阶段确保宽屏方向生效，并消除启动中转 Activity 的过渡闪烁
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    applyPadConfiguration(act);
                    try {
                        String actName = act.getClass().getName();
                        // 启动中转 Activity 绝不向系统申请旋转，杜绝排队旋转引发三次闪烁
                        if (!actName.contains("LauncherActivity")) {
                            int cur = act.getRequestedOrientation();
                            if (cur != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    && cur != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    // 对启动中转 Activity 和 MainActivity 全部关闭切换动画，实现零露底、零黑屏、无缝直入
                    String actName = act.getClass().getName();
                    if (actName.contains("LauncherActivity") || actName.contains("MainActivity")) {
                        try {
                            act.overridePendingTransition(0, 0);
                        } catch (Throwable ignored) {}
                    }
                    if (act.getWindow() != null) {
                        // 强制窗口全宽全高铺满
                        try {
                            act.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                        } catch (Throwable ignored) {}
                    }
                }
            });

            // 拦截启动中转 Activity 的 startActivity，消灭启动 MainActivity 时的转场黑屏露底
            XposedHelpers.findAndHookMethod(Activity.class, "startActivity", android.content.Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    if (act != null && act.getClass().getName().contains("LauncherActivity")) {
                        try {
                            act.overridePendingTransition(0, 0);
                        } catch (Throwable ignored) {}
                    }
                }
            });

            // 当启动中转 Activity finish() 时消除退出动画，实现零缝隙直入首页
            XposedHelpers.findAndHookMethod(Activity.class, "finish", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    if (act != null && (act.getClass().getName().contains("LauncherActivity") || act.getClass().getName().contains("MainActivity"))) {
                        try {
                            act.overridePendingTransition(0, 0);
                        } catch (Throwable ignored) {}
                    }
                }
            });

            // Hook Resources.getConfiguration：保证全 App 任何地方获取的 Configuration 始终保持横屏与 sw840dp
            XposedHelpers.findAndHookMethod(Resources.class, "getConfiguration", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!DouyuPlusConfig.isPadModeEnabled()) return;
                    Configuration config = (Configuration) param.getResult();
                    if (config != null) {
                        if (config.smallestScreenWidthDp < 840) {
                            config.smallestScreenWidthDp = 840;
                        }
                        if (config.screenWidthDp < 840) {
                            config.screenWidthDp = 840;
                        }
                        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
                        config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_SIZE_MASK)
                                | Configuration.SCREENLAYOUT_SIZE_XLARGE;
                    }
                }
            });

            // 兜底保护：若在非 sw600dp 设备上强制开启平板模式，个别仅存在于 sw600dp 的 dimen 资源找不到时返回安全默认值
            XposedHelpers.findAndHookMethod(Resources.class, "getDimensionPixelSize", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled() && param.getThrowable() instanceof Resources.NotFoundException) {
                        param.setResult(24);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Resources.class, "getDimension", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (DouyuPlusConfig.isPadModeEnabled() && param.getThrowable() instanceof Resources.NotFoundException) {
                        param.setResult(24.0f);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Activity for Pad mode", t);
        }
    }

    private static void applyPadConfiguration(Context context) {
        if (context == null) return;
        try {
            Resources res = context.getResources();
            if (res == null) return;
            Configuration config = res.getConfiguration();
            if (config != null) {
                config.smallestScreenWidthDp = Math.max(config.smallestScreenWidthDp, 840);
                config.screenWidthDp = Math.max(config.screenWidthDp, 840);
                config.orientation = Configuration.ORIENTATION_LANDSCAPE;
                config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_SIZE_MASK)
                        | Configuration.SCREENLAYOUT_SIZE_XLARGE;
                // 绝不调用 res.updateConfiguration，避免激起配置广播风暴和全应用 View 树重置
            }
        } catch (Throwable ignored) {}
    }

    private static void hidePortraitLayersInPadMode(Activity act) {
        if (act == null || !DouyuPlusConfig.isPadModeEnabled()) return;
        try {
            View portraitView = (View) XposedHelpers.getObjectField(act, "y");
            if (portraitView != null) {
                portraitView.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
        try {
            Resources res = act.getResources();
            String pkg = act.getPackageName();
            // 仅隐藏宽屏模式下叠加在视频左上角的竖版聊天/热议悬浮窗 (fjb)，保留横屏上下控制栏 (jhk / g0y / jjl)
            int fjbId = res.getIdentifier("fjb", "id", pkg);
            if (fjbId != 0) {
                View fjbView = act.findViewById(fjbId);
                if (fjbView != null && fjbView.getVisibility() != View.GONE) {
                    fjbView.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {}
    }
}
