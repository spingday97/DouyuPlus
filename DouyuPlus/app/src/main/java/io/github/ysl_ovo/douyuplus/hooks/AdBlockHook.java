package io.github.ysl_ovo.douyuplus.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 核心广告拦截与界面纯净 Hook (稳定零崩溃版)
 * 1. 秒进主页: 官方 show_ad = false 白名单
 * 2. 弹幕区推广广告与横幅拦截
 * 3. 直播间底栏强保快捷弹幕、四个小方块互动玩法、黄色礼物盒三键常驻显示，仅剔除劣质手游与带货小店
 * 4. 彻底铲除语音厅悬浮窗与挂件广告（玩机器 6657、冬瓜强 63136 等直播间）
 * 5. 彻底铲除直播间右下角劣质手游大卡片（烈焰觉醒、仙逆H5、中插广告 AdMidRoll）
 * 6. 彻底拔除全屏右下角活动大挂件（三团联动卡片大挂件、BigPendantView、EntriesGroup）
 * 7. 官方业务总开关 LiveRoomBizSwitch 精准管控
 * 8. 彻底隐藏竖屏直播间播放器下方「广告 / 来唠嗑 / 语音厅唠嗑」横幅 (jj2)
 */
public class AdBlockHook {
    private static final String TAG = "DouyuPlus_AdBlock";

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 冷启动开屏广告：设置官方 show_ad = false 白名单标志，直接秒进主页
        try {
            Class<?> launcherCls = XposedHelpers.findClass("tv.douyu.view.activity.launcher.DYLauncherActivity", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(launcherCls, "onCreate", "android.os.Bundle", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = activity.getIntent();
                    if (intent != null) {
                        intent.putExtra("show_ad", false);
                        Log.i(TAG, "DYLauncherActivity: forced show_ad = false (Instant Launch)");
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DYLauncherActivity", t);
        }

        // 2. 热启动开屏广告：拦截 HotStartSplashActivity / HotStartSplashAd
        try {
            Class<?> hotSplashAct = XposedHelpers.findClass("com.douyu.module.ad.launch.HotStartSplashActivity", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(hotSplashAct, "onCreate", "android.os.Bundle", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    ((Activity) param.thisObject).finish();
                    Log.i(TAG, "HotStartSplashActivity auto-finished");
                }
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> hotStartCls = XposedHelpers.findClass("com.douyu.module.ad.launch.HotStartSplashAd", lpparam.classLoader);
            for (Method m : hotStartCls.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 1 && Context.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
        } catch (Throwable ignored) {}

        // 3. 弹幕区推广广告：拦截 DanmuAdNeuron
        try {
            Class<?> danmuAdCls = XposedHelpers.findClass("com.douyu.module.player.p.danmuad.DanmuAdNeuron", lpparam.classLoader);
            for (Method m : danmuAdCls.getDeclaredMethods()) {
                Class<?>[] pTypes = m.getParameterTypes();
                if (pTypes.length == 1 && List.class.isAssignableFrom(pTypes[0])) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                } else if (m.getName().equals("OE") && pTypes.length == 0) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
            Log.i(TAG, "Hooked DanmuAdNeuron");
        } catch (Throwable ignored) {}

        // 4. 业务横幅与悬浮广告：拦截 MADProvider
        try {
            Class<?> madProviderCls = XposedHelpers.findClass("com.douyu.module.ad.MADProvider", lpparam.classLoader);
            for (Method m : madProviderCls.getDeclaredMethods()) {
                if (m.getName().equals("zj") || m.getName().equals("z7")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                }
            }
        } catch (Throwable ignored) {}

        // 5. 主播专有横幅 (LiveBannerNeuron & LiveBannerSdk & LiveBannerView)
        try {
            Class<?> liveBannerNeuronCls = XposedHelpers.findClass("com.douyu.module.player.p.livebanner.LiveBannerNeuron", lpparam.classLoader);
            for (Method m : liveBannerNeuronCls.getDeclaredMethods()) {
                if (m.getName().equals("BE")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> liveBannerSdkCls = XposedHelpers.findClass("com.douyu.sdk.livebanner.LiveBannerSdk", lpparam.classLoader);
            for (Method m : liveBannerSdkCls.getDeclaredMethods()) {
                if (m.getName().equals("b")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
        } catch (Throwable ignored) {}

        hookViewToGone(lpparam, "com.douyu.module.player.p.livebanner.LiveBannerView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.livebanner.LiveBannerWidget");

        // 6. 彻底铲除中插劣质游戏卡片广告 (AdMidRollNeuron, AdMidRollView)
        hookAdMidRoll(lpparam);

        // 7. 彻底拔除星海带货与弹幕卡片广告（劣质游戏卡片推广如烈焰觉醒、底栏粉色小手提袋）
        hookAnimatedAdAndDanmuCard(lpparam);

        // 8. 彻底铲除新版斗鱼小店带货系统（底栏粉色小手提袋、商品气泡：RamboShoppingNeuron / RamboNewShoppingViewController）
        hookRamboNewShopping(lpparam);

        // 9. 彻底拔除全屏右下角活动大挂件 (BigPendantView)
        hookBigPendant(lpparam);

        // 10. 彻底拔除全屏活动大卡片（三团联动大挂件 TribeUserPendant / TribeCallingFlagView）
        hookTribePendant(lpparam);

        // 11. 拔除其他已知悬浮广告与推广 Function
        hookMiscAdFunctions(lpparam);

        // 12. 彻底铲除穿山甲/PTG 原生广告与联运劣质手游大卡片（烈焰觉醒、仙逆）
        hookPtgAdSdk(lpparam);

        // 13. 彻底拔除全屏右下角活动大卡片（三团联动大卡片、ActiveEntryPresenter）
        hookActiveEntryPendant(lpparam);

        // 14. 彻底铲除语音厅悬浮窗与挂件广告（玩机器 6657、冬瓜强 63136 等直播间）
        hookVoiceAccompany(lpparam);

        // 15. 直播间底栏功能按键保活恢复（快捷弹幕、四个小方块互动玩法、黄色礼物盒常驻显示）
        hookRestoreBottomBarButtons(lpparam);

        // 16. 彻底铲除发现页（推荐流）、动态页等信息流广告卡片
        hookFeedAds(lpparam);

        // 17. 彻底隐藏竖屏直播间播放器下方中间广告横幅（广告角标 + 来唠嗑/语音厅唠嗑按钮）
        hookPortraitMidAdBanner(lpparam);
    }

    /**
     * 彻底隐藏竖屏直播间播放器下方「广告 / 来唠嗑 / 语音厅唠嗑」横幅 (air.tv.douyu.android:id/jj2)
     */
    private static void hookPortraitMidAdBanner(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> linearCompatCls = XposedHelpers.findClassIfExists(
                    "androidx.appcompat.widget.LinearLayoutCompat",
                    lpparam.classLoader
            );
            if (linearCompatCls == null) return;

            try {
                XposedHelpers.findAndHookMethod(linearCompatCls, "onMeasure", int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        View view = (View) param.thisObject;
                        if (isPortraitMidAdBanner(view)) {
                            collapsePortraitMidAdBanner(view);
                            try {
                                Method setMeasured = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
                                setMeasured.setAccessible(true);
                                setMeasured.invoke(view, 0, 0);
                            } catch (Throwable ignored) {}
                            param.setResult(null);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook LinearLayoutCompat.onMeasure", t);
            }

            try {
                XposedHelpers.findAndHookMethod(linearCompatCls, "onLayout", boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View view = (View) param.thisObject;
                        if (isPortraitMidAdBanner(view)) {
                            collapsePortraitMidAdBanner(view);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            Log.i(TAG, "Hooked LinearLayoutCompat for portrait mid ad banner (jj2)");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hookPortraitMidAdBanner", t);
        }
    }

    private static void collapsePortraitMidAdBanner(View view) {
        if (view.getVisibility() != View.GONE) {
            view.setVisibility(View.GONE);
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            view.setLayoutParams(lp);
        }

        // 同时折叠外层父容器 ig_ 以及同级占位视图 kqz，彻底消除中间黑色空隙
        android.view.ViewParent parent = view.getParent();
        if (parent instanceof View) {
            View parentView = (View) parent;
            try {
                Resources res = parentView.getResources();
                if (parentView.getId() != View.NO_ID
                        && "ig_".equals(res.getResourceEntryName(parentView.getId()))) {
                    parentView.setMinimumHeight(0);
                    if (parentView.getVisibility() != View.GONE) {
                        parentView.setVisibility(View.GONE);
                    }
                    ViewGroup.LayoutParams plp = parentView.getLayoutParams();
                    if (plp != null && plp.height != 0) {
                        plp.height = 0;
                        parentView.setLayoutParams(plp);
                    }

                    // 折叠 jhl 下的同级占位 View (kqz)
                    android.view.ViewParent grandParent = parentView.getParent();
                    if (grandParent instanceof ViewGroup) {
                        String pkg = parentView.getContext().getPackageName();
                        int kqzId = res.getIdentifier("kqz", "id", pkg);
                        if (kqzId != 0) {
                            View kqzView = ((ViewGroup) grandParent).findViewById(kqzId);
                            if (kqzView != null) {
                                kqzView.setMinimumHeight(0);
                                if (kqzView.getVisibility() != View.GONE) {
                                    kqzView.setVisibility(View.GONE);
                                }
                                ViewGroup.LayoutParams klp = kqzView.getLayoutParams();
                                if (klp != null && klp.height != 0) {
                                    klp.height = 0;
                                    kqzView.setLayoutParams(klp);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isPortraitMidAdBanner(View view) {
        if (view == null || view.getId() == View.NO_ID) return false;
        try {
            Resources res = view.getResources();
            if (res == null) return false;
            String entryName = res.getResourceEntryName(view.getId());
            if (!"jj2".equals(entryName)) return false;

            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                String pkg = view.getContext().getPackageName();
                int closeId = res.getIdentifier("edf", "id", pkg);
                int btnId = res.getIdentifier("byg", "id", pkg);
                return closeId != 0 && btnId != 0
                        && vg.findViewById(closeId) != null
                        && vg.findViewById(btnId) != null;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 彻底铲除语音厅悬浮窗与右侧活动挂件广告
     * 覆盖主播：玩机器(6657)、冬瓜强(63136)等各大语音厅/陪玩直播间
     */
    private static void hookVoiceAccompany(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 全局业务总开关 LiveRoomBizSwitch 精准拦截 ACCOMPANY_GANG、RN_HAPPY_GANG 开关
        try {
            Class<?> bizSwitchCls = XposedHelpers.findClass(
                    "com.douyu.sdk.liveroombizswitch.LiveRoomBizSwitch",
                    lpparam.classLoader
            );
            Class<?> bizKeyCls = XposedHelpers.findClass(
                    "com.douyu.sdk.liveroombizswitch.BizSwitchKey",
                    lpparam.classLoader
            );

            XC_MethodHook switchHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] != null) {
                        String keyName = param.args[0].toString();
                        if (keyName.contains("ACCOMPANY") || keyName.equals("VOICE_DANMU")
                                || keyName.contains("HAPPY_GANG") || keyName.equals("RAMBO_PENDENT")) {
                            param.setResult(false); // 强制关闭语音厅、陪玩与右侧大挂件业务
                        } else if (keyName.equals("RAMBO_BOTTOM_FUNCTION") || keyName.equals("INPUT_FRAME_INTERACTIVE_ENTRANCES")) {
                            param.setResult(true);  // 强保底栏正常功能区不被隐藏
                        }
                    }
                }
            };

            XposedHelpers.findAndHookMethod(bizSwitchCls, "i", bizKeyCls, switchHook);
            XposedHelpers.findAndHookMethod(bizSwitchCls, "j", bizKeyCls, boolean.class, switchHook);
            Log.i(TAG, "Hooked LiveRoomBizSwitch -> disabled ACCOMPANY, protected RAMBO_BOTTOM_FUNCTION");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook LiveRoomBizSwitch for voice accompany", t);
        }

        // 2. 拦截 AudioPendantNeuron (语音厅 RN 挂件神经元)，阻断其加载
        try {
            Class<?> audioPendantCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.audiopendant.AudioPendantNeuron",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(audioPendantCls, "zE", XC_MethodReplacement.DO_NOTHING);
            for (Method m : audioPendantCls.getDeclaredMethods()) {
                if (m.getName().equals("EE")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
            Log.i(TAG, "Hooked AudioPendantNeuron -> blocked voice accompany RN container");
        } catch (Throwable ignored) {}

        // 3. 拦截 AudioVAFloatPresenter (语音厅悬浮球/浮窗业务逻辑调度)
        try {
            Class<?> presenterCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.audiolive.mvp.presenter.AudioVAFloatPresenter",
                    lpparam.classLoader
            );
            for (Method m : presenterCls.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
            }
            Log.i(TAG, "Hooked AudioVAFloatPresenter -> disabled float popup");
        } catch (Throwable ignored) {}

        // 4. 拦截 VAPendantManager (右侧语音厅活动大挂件)
        try {
            Class<?> vaPendantCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.voiceaccompany.VAPendantManager",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(vaPendantCls, "A", XC_MethodReplacement.returnConstant(false));
            for (Method m : vaPendantCls.getDeclaredMethods()) {
                if (m.getName().equals("S")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
            Log.i(TAG, "Hooked VAPendantManager -> disabled entry");
        } catch (Throwable ignored) {}

        // 5. 拦截 VAFloatView 业务弹窗触发 yb 方法
        try {
            Class<?> vaFloatCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.voiceaccompany.view.VAFloatView",
                    lpparam.classLoader
            );
            for (Method m : vaFloatCls.getDeclaredMethods()) {
                if (m.getName().equals("yb")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
        } catch (Throwable ignored) {}

        // 6. 彻底隐藏语音厅悬浮窗与所有相关视图组件
        hookViewToGone(lpparam, "com.douyu.module.player.p.voiceaccompany.view.VAFloatView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.voiceaccompany.view.VaRankView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.voiceaccompany.view.VAFunctionContainer");
        hookViewToGone(lpparam, "com.douyu.module.player.p.voiceaccompany.view.VAGiftTiper");
        hookViewToGone(lpparam, "com.douyu.module.player.p.voiceaccompany.view.VASwitchButton");
        hookViewToGone(lpparam, "com.douyu.module.player.p.audiolive.mvp.view.AudioInteractionView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.audiolive.rank.view.AudioRankEnteranceView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.audiolive.mvp.widget.AudioBarsController");
        hookViewToGone(lpparam, "com.douyu.module.player.p.audiolive.mvp.widget.AudioIconsArrowView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.audiolive.mvp.view.AudioGalleryView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.socialinteraction.view.VSAudioRightWidgetView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.socialinteraction.view.left.pendant.VSAudioLeftPendantView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.audiopendant.papi.AudioRnSmallPendantContainer");
    }

    /**
     * 直播间底栏正规按键强保恢复
     * 解决“刚进一瞬间能看到礼物和互动按钮，马上就没了”的根本缺陷
     * 1. 快捷弹幕 (IFConvenientFunction & ConvenientRamboPortraitFunctionBiz) 稳定常驻
     * 2. 四个小方块互动玩法入口 (IFInteractiveEntrancesFunction) 稳定常驻
     * 3. 黄色礼物盒 (IFOutsideGiftFunction & OutsideGiftEntranceView) 稳定常驻
     * 4. 强保右侧功能区容器 this.j (0x7f0a363b) 永久可见，输入框不横向拉伸占满
     */
    private static void hookRestoreBottomBarButtons(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 强保右侧容器 InputFrameView.this.j (0x7f0a363b) 永久可见，拦截被设为 GONE
        try {
            Class<?> inputFrameViewCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.inputframe.InputFrameView",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(inputFrameViewCls, "s0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        View j = (View) XposedHelpers.getObjectField(param.thisObject, "j");
                        if (j != null) {
                            j.setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedHelpers.findAndHookMethod(inputFrameViewCls, "H0", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = false;
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "l", false);
                    } catch (Throwable ignored) {}
                }
            });

            Log.i(TAG, "Hooked InputFrameView -> keep right buttons container visible");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook InputFrameView", t);
        }

        // 2. 拦截 InputframeNeuron.os(boolean)
        try {
            Class<?> inputframeNeuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.inputframe.InputframeNeuron",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(inputframeNeuronCls, "os", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = false;
                }
            });
        } catch (Throwable ignored) {}

        // 3. 礼物盒 (IFOutsideGiftFunction) 稳定常驻
        try {
            Class<?> giftFuncCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.rambogift.outside.IFOutsideGiftFunction",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(giftFuncCls, "isEnable", XC_MethodReplacement.returnConstant(true));

            // 拦截 rx(boolean)，强制保持开启
            XposedHelpers.findAndHookMethod(giftFuncCls, "rx", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = true;
                }
            });

            // 拦截 ix(boolean)，禁止被禁用
            XposedHelpers.findAndHookMethod(giftFuncCls, "ix", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = false;
                }
            });

            // 拦截 onRoomChange，防止房间切换时被关闭隐藏
            XposedHelpers.findAndHookMethod(giftFuncCls, "onRoomChange", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "D", true);
                        XposedHelpers.setBooleanField(param.thisObject, "E", false);
                        View view = (View) XposedHelpers.getObjectField(param.thisObject, "B");
                        if (view != null) {
                            view.setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            Log.i(TAG, "Restored and stabilized IFOutsideGiftFunction (Yellow Gift Box)");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook IFOutsideGiftFunction", t);
        }

        // 4. 四个小方块互动玩法 (IFInteractiveEntrancesFunction) 稳定常驻
        try {
            Class<?> interactiveCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.interactionentry.IFInteractiveEntrancesFunction",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(interactiveCls, "isEnable", XC_MethodReplacement.returnConstant(true));

            // 拦截 kx(boolean)，禁止被置为禁用状态 (I = false)
            XposedHelpers.findAndHookMethod(interactiveCls, "kx", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = false;
                }
            });

            Log.i(TAG, "Restored and stabilized IFInteractiveEntrancesFunction (Interactive 4-Squares)");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook IFInteractiveEntrancesFunction", t);
        }

        // 5. 快捷弹幕 (IFConvenientFunction & ConvenientRamboPortraitFunctionBiz) 稳定常驻
        try {
            Class<?> convenientCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.convenientdanmu.input.function.IFConvenientFunction",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(convenientCls, "isEnable", XC_MethodReplacement.returnConstant(true));

            // 拦截 rx(boolean)，强制保持开启显示
            XposedHelpers.findAndHookMethod(convenientCls, "rx", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = true;
                }
            });

            Log.i(TAG, "Restored and stabilized IFConvenientFunction (Quick Danmu)");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook IFConvenientFunction", t);
        }

        try {
            Class<?> ramboConvenientBizCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.convenientdanmu.input.function.ConvenientRamboPortraitFunctionBiz",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(ramboConvenientBizCls, "b", XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(ramboConvenientBizCls, "e", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        View a = (View) XposedHelpers.getObjectField(param.thisObject, "a");
                        if (a != null) {
                            a.setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Log.i(TAG, "Hooked ConvenientRamboPortraitFunctionBiz -> permanent quick danmu icon");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook ConvenientRamboPortraitFunctionBiz", t);
        }

        // 5.5 MultiFuncEntranceView.a(View)：精准拒绝挂载弹幕卡片推广入口 / 带货 / 手游入口，其余正规按键不受影响
        try {
            Class<?> multiCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.rambobase.functionarea.MultiFuncEntranceView",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(multiCls, "a", View.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object v = param.args[0];
                    if (v == null) return;
                    String n = v.getClass().getName();
                    if (n.contains("DanmuCardEntry") || n.contains("RamboNewShopping") || n.contains("XHShopping")) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 6. RamboFunctionEntranceNeuron 保活兜底：确保 OutsideGiftEntranceView 始终挂载在 MultiFuncEntranceView
        try {
            Class<?> ramboNeuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.rambobase.papi.RamboFunctionEntranceNeuron",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(ramboNeuronCls, "NE", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        ViewGroup n = (ViewGroup) XposedHelpers.getObjectField(param.thisObject, "n");
                        if (n != null) {
                            java.util.Map<?, ?> jMap = (java.util.Map<?, ?>) XposedHelpers.getObjectField(param.thisObject, "j");
                            if (jMap != null && jMap.containsKey("bottomOutsideGift")) {
                                Object giftEntrance = jMap.get("bottomOutsideGift");
                                if (giftEntrance != null) {
                                    View giftView = (View) XposedHelpers.callMethod(giftEntrance, "k");
                                    if (giftView != null && giftView.getParent() != n) {
                                        if (giftView.getParent() instanceof ViewGroup) {
                                            ((ViewGroup) giftView.getParent()).removeView(giftView);
                                        }
                                        XposedHelpers.callMethod(n, "a", giftView);
                                        giftView.setVisibility(View.VISIBLE);
                                        Log.i(TAG, "Ensured OutsideGiftEntranceView attached to MultiFuncEntranceView");
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 彻底铲除中插劣质游戏卡片广告 (AdMidRollNeuron, AdMidRollView)
     */
    private static void hookAdMidRoll(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookViewToGone(lpparam, "com.douyu.module.player.p.admidroll.AdMidRollView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.admidroll.AdMidRollH5Container");
        hookViewToGone(lpparam, "com.douyu.module.player.p.admidroll.AdMidRollVideoWidget");

        try {
            Class<?> neuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.admidroll.AdMidRollNeuron",
                    lpparam.classLoader
            );
            for (Method m : neuronCls.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("isEnable")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                } else if (name.equals("BE") || name.equals("FE") || name.equals("GE") ||
                        name.equals("HE") || name.equals("IE") || name.startsWith("onReceive")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
            Log.i(TAG, "Hooked AdMidRollNeuron -> blocked ad requests");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook AdMidRollNeuron", t);
        }
    }

    /**
     * 彻底铲除穿山甲/PTG 原生广告与联运劣质手游大卡片（烈焰觉醒、仙逆）
     */
    private static void hookPtgAdSdk(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 禁用 PtgAdSdk.init
        try {
            Class<?> ptgSdkCls = XposedHelpers.findClass("com.ptg.adsdk.lib.PtgAdSdk", lpparam.classLoader);
            for (Method m : ptgSdkCls.getDeclaredMethods()) {
                if (m.getName().equals("init")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
            Log.i(TAG, "Disabled PtgAdSdk.init");
        } catch (Throwable ignored) {}

        // 2. 禁用 PtgAdProxy 的所有广告拉取
        try {
            Class<?> proxyCls = XposedHelpers.findClass("com.ptg.ptgapi.PtgAdProxy", lpparam.classLoader);
            for (Method m : proxyCls.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
            }
            Log.i(TAG, "Disabled PtgAdProxy all ad fetch methods");
        } catch (Throwable ignored) {}

        // 3. 拦截 MpNativeAdLoader & MpBannerAdLoader (斗鱼 SDK 广告加载器)
        try {
            Class<?> loaderCls = XposedHelpers.findClass("com.douyu.sdk.ad2.idle.MpNativeAdLoader", lpparam.classLoader);
            for (Method m : loaderCls.getDeclaredMethods()) {
                if (m.getName().equals("a")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> bannerCls = XposedHelpers.findClass("com.douyu.sdk.ad2.idle.MpBannerAdLoader", lpparam.classLoader);
            for (Method m : bannerCls.getDeclaredMethods()) {
                if (m.getName().equals("a")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
        } catch (Throwable ignored) {}

        // 4. 彻底隐藏工信部六要素下载视图、卡片布局与穿山甲组件
        hookViewToGone(lpparam, "com.douyu.sdk.ad2.view.LandingPageSixElementView");
        hookViewToGone(lpparam, "com.douyu.sdk.ad2.view.LandingPageDownloadWidgetView");
        hookViewToGone(lpparam, "com.douyu.sdk.ad2.view.LandingPageDownloadWidgetProgressBar");
        hookViewToGone(lpparam, "com.douyu.sdk.ad2.floating.ADFloatingContainer");
        hookViewToGone(lpparam, "com.ptg.ptgapi.widget.PtgRoundLinearLayout");
        hookViewToGone(lpparam, "com.ptg.ptgapi.widget.PtgRoundFrameLayout");
        hookViewToGone(lpparam, "com.ptg.ptgapi.component.widgets.PtgConfirmBtn");
        hookViewToGone(lpparam, "com.ptg.ptgapi.component.videoplayer.NiceVideoPlayer");
        hookViewToGone(lpparam, "com.ptg.ptgapi.component.video.PtgVideoView");
    }

    /**
     * 彻底拔除竖屏右下角与全屏右侧活动大卡片（B区小挂件 EntriesGroup、分区活动挂件、RN小挂件等）
     * 关键设计：绝不拦截 ActiveEntryPresenter 的配置请求与分发（保持与 v18 一致），
     * 确保底栏功能区（EntranceFuncAreaConfig / EntranceDConfig）100% 正常工作！
     */
    private static void hookActiveEntryPendant(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.view.EntriesGroup");
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.view.Auto2LastChildScroller");
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.view.EntryWrapperView");
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.view.entry.EntryWrapperView");
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.view.ContainerViewHolder");
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.view.AlienGroupView");
        hookViewToGone(lpparam, "tv.douyu.business.partitionentries.PartitionEntriesPendant");
        hookViewToGone(lpparam, "com.douyu.module.rn.smallpendant.RnSmallPendantContainer");
        hookViewToGone(lpparam, "com.douyu.module.rn.smallpendant.RamboSmallPendantContainer");
        hookViewToGone(lpparam, "com.douyu.module.player.p.live2pendant.small.SmallPendantView");
        hookViewToGone(lpparam, "com.douyu.module.peiwan.widget.PeiwanPendantView");
        hookViewToGone(lpparam, "tv.douyu.business.anchorentry.AnchorPendantContainer");
        hookViewToGone(lpparam, "com.douyu.module.player.p.partycoming.view.PartyPendantView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.adfloatball.AdFloatBallView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.adfloatball.papi.SweepLightContainer");

        // 0. 拦截 AdFloatBallNeuron & AdFloatBallView（彻底铲除右下角残留的独立外部关闭按钮 X #7f0a1250 / 2131366480）
        try {
            Class<?> adBallNeuronCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.player.p.adfloatball.AdFloatBallNeuron",
                    lpparam.classLoader
            );
            if (adBallNeuronCls != null) {
                Set<String> blockAdBallMethods = new HashSet<>(Arrays.asList(
                        "BE", "HF", "tF", "AF", "BF", "GF", "wF", "qz", "v"
                ));
                for (Method m : adBallNeuronCls.getDeclaredMethods()) {
                    String name = m.getName();
                    if (blockAdBallMethods.contains(name) && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    Object act = XposedHelpers.callMethod(param.thisObject, "lE");
                                    if (act instanceof android.app.Activity) {
                                        View outerClose = ((android.app.Activity) act).findViewById(2131366480);
                                        if (outerClose != null) {
                                            outerClose.setVisibility(View.GONE);
                                        }
                                        View ballView = ((android.app.Activity) act).findViewById(2131362050);
                                        if (ballView != null) {
                                            ballView.setVisibility(View.GONE);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                param.setResult(null);
                            }
                        });
                    } else if (name.equals("iF") && m.getReturnType() == View.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    Object act = XposedHelpers.callMethod(param.thisObject, "lE");
                                    if (act instanceof android.app.Activity) {
                                        View outerClose = ((android.app.Activity) act).findViewById(2131366480);
                                        if (outerClose != null) {
                                            outerClose.setVisibility(View.GONE);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                param.setResult(null);
                            }
                        });
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> adBallViewCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.player.p.adfloatball.AdFloatBallView",
                    lpparam.classLoader
            );
            if (adBallViewCls != null) {
                for (Method m : adBallViewCls.getDeclaredMethods()) {
                    String name = m.getName();
                    if ((name.equals("ug") || name.equals("Gg") || name.equals("Xg") || name.equals("Ug"))
                            && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 0.5 拦截 ActiveEntryPresenter.h0(int)（仅阻断向 B 区 EntriesGroup 添加带 X 关闭按钮的小挂件，绝不触碰 b0/c0/d0 配置拉取，100% 保护底栏三键）
        try {
            Class<?> activePresenterCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.pendantframework.presenter.ActiveEntryPresenter",
                    lpparam.classLoader
            );
            if (activePresenterCls != null) {
                XposedHelpers.findAndHookMethod(activePresenterCls, "h0", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object entriesGroup = XposedHelpers.getObjectField(param.thisObject, "c");
                            if (entriesGroup instanceof View) {
                                ((View) entriesGroup).setVisibility(View.GONE);
                            }
                        } catch (Throwable ignored) {}
                        param.setResult(null);
                    }
                });
            }
        } catch (Throwable ignored) {}

        // 1. 拦截 PartitionEntriesNeuron (负责下发 _room_activity_a_pendant_load 活动挂件与 PartitionEntriesPendant)
        try {
            Class<?> partCls = XposedHelpers.findClassIfExists(
                    "tv.douyu.business.partitionentries.PartitionEntriesNeuron",
                    lpparam.classLoader
            );
            if (partCls != null) {
                for (Method m : partCls.getDeclaredMethods()) {
                    String name = m.getName();
                    if ((name.equals("SE") || name.equals("VE") || name.equals("FE")) && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. 拦截 RnSmallPendantNeuron (右侧 RN 小挂件容器神经元)
        try {
            Class<?> rnSmallCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.rn.smallpendant.RnSmallPendantNeuron",
                    lpparam.classLoader
            );
            if (rnSmallCls != null) {
                for (Method m : rnSmallCls.getDeclaredMethods()) {
                    String name = m.getName();
                    if ((name.equals("zE") || name.equals("DE")) && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    }
                }
            }
        } catch (Throwable ignored) {}

        Log.i(TAG, "Permanently disabled ActiveEntry / Partition / RnSmall pendant views");
    }

    /**
     * 弹幕卡片广告与星海带货拦截
     */
    private static void hookAnimatedAdAndDanmuCard(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.danmucard.DanmuCardWidgetContainer");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.danmucard.DanmuCardIconWidget");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.danmucard.DanmuCardWidget");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.danmucard.DanmuCardVideoWidget");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.propmall.DanmuCardPropMallWidget");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.danmucard.DanmuCardEntryAnimatedView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.shopping.XHShoppingEntryView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.shopping.XHShoppingEntryViewNew");
        hookViewToGone(lpparam, "com.douyu.module.player.p.animatedad.widget.shopping.XHShoppingWidget");

        try {
            Class<?> ctrlCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.animatedad.widget.danmucard.DanmuCardViewController",
                    lpparam.classLoader
            );
            // 标记 n (mIsManualClose) = true
            XposedBridge.hookAllConstructors(ctrlCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        XposedHelpers.setStaticBooleanField(ctrlCls, "n", true);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 斗鱼小店带货视图与底栏粉色小手提袋拦截（强保快捷弹幕、四个小方块互动玩法、黄色礼物盒）
     */
    private static void hookRamboNewShopping(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookViewToGone(lpparam, "com.douyu.module.player.p.rambonewshopping.view.RamboNewShoppingEntry");
        hookViewToGone(lpparam, "com.douyu.module.player.p.rambonewshopping.view.RamboNewShoppingEntryView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.rambonewshopping.view.RamboNewShoppingWidget");

        try {
            Class<?> neuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.rambonewshopping.RamboShoppingNeuron",
                    lpparam.classLoader
            );
            for (Method m : neuronCls.getDeclaredMethods()) {
                if (m.getName().equals("isEnable")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                }
            }
        } catch (Throwable ignored) {}

        // 精准阻断底栏粉色小手提袋 (bottomShopping) 注册，彻底清除手提袋同时绝不影响其他任何正规功能
        try {
            Class<?> ramboNeuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.rambobase.papi.RamboFunctionEntranceNeuron",
                    lpparam.classLoader
            );
            Class<?> absEntranceCls = XposedHelpers.findClass(
                    "com.douyu.api.player.bean.AbsFuncAreaEntranceView",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(ramboNeuronCls, "OE", absEntranceCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object entrance = param.args[0];
                    if (entrance != null) {
                        try {
                            String key = (String) XposedHelpers.getObjectField(entrance, "e");
                            String clsName = entrance.getClass().getName();
                            if ((key != null && (key.toLowerCase().contains("shop") || key.toLowerCase().contains("goods") || key.toLowerCase().contains("bag") || key.toLowerCase().contains("game")))
                                    || clsName.contains("Shopping")) {
                                param.setResult(null); // 精准阻断带货手提袋与手游推广入口挂载
                                Log.i(TAG, "Blocked bottom shopping/game entrance: key=" + key + ", class=" + clsName);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            Log.i(TAG, "Hooked RamboFunctionEntranceNeuron.OE -> precise shopping bag filter");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RamboFunctionEntranceNeuron.OE", t);
        }
    }

    /**
     * 竖屏右下角与全屏右侧 A 区大挂件拦截 (BigPendantView & BigPendantViewPager)
     * 8.2.1.0 真实包名: com.douyu.sdk.pendantframework.big.BigPendantView
     */
    private static void hookBigPendant(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.big.BigPendantView");
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.big.BigPendantViewPager");
        hookViewToGone(lpparam, "com.douyu.sdk.pendantframework.big.IEDotIndicator");
        hookViewToGone(lpparam, "com.douyu.module.player.p.pendant.BigPendantView");
        hookViewToGone(lpparam, "com.douyu.module.player.p.pendant.BigPendantWidget");

        // 拦截 BigPendantView 添加子视图和显示的方法 (d, e, f, g, setList, m, u)
        try {
            Class<?> bigCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.pendantframework.big.BigPendantView",
                    lpparam.classLoader
            );
            if (bigCls != null) {
                Set<String> blockMethods = new HashSet<>(Arrays.asList(
                        "d", "e", "f", "g", "setList", "m", "u", "v"
                ));
                for (Method m : bigCls.getDeclaredMethods()) {
                    if (blockMethods.contains(m.getName()) && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.thisObject instanceof View) {
                                    ((View) param.thisObject).setVisibility(View.GONE);
                                }
                                param.setResult(null);
                            }
                        });
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 拦截 BigPendantView$ListPagerAdapter，使其永远为空
        try {
            Class<?> adapterCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.pendantframework.big.BigPendantView$ListPagerAdapter",
                    lpparam.classLoader
            );
            if (adapterCls != null) {
                XposedHelpers.findAndHookMethod(adapterCls, "getCount", XC_MethodReplacement.returnConstant(0));
                XposedHelpers.findAndHookMethod(adapterCls, "l", XC_MethodReplacement.returnConstant(0));
                for (Method m : adapterCls.getDeclaredMethods()) {
                    String name = m.getName();
                    if ((name.equals("h") || name.equals("i") || name.equals("q")) && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 拦截 BigPendantNeuron（控制竖屏右下角与全屏右侧的斗鱼活动RN广告 + 斗鱼语音厅RN广告 + 小挂件）
        try {
            Class<?> neuronCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.player.p.bigpendant.BigPendantNeuron",
                    lpparam.classLoader
            );
            if (neuronCls != null) {
                // KE() 检查 BizSwitchKey.RAMBO_PENDENT，Hq() 检查横屏小挂件是否显示
                try {
                    XposedHelpers.findAndHookMethod(neuronCls, "KE", XC_MethodReplacement.returnConstant(false));
                } catch (Throwable ignored) {}
                try {
                    XposedHelpers.findAndHookMethod(neuronCls, "Hq", XC_MethodReplacement.returnConstant(false));
                } catch (Throwable ignored) {}
                try {
                    XposedHelpers.findAndHookMethod(neuronCls, "OE", XC_MethodReplacement.returnConstant(0));
                } catch (Throwable ignored) {}

                Set<String> blockNeuronMethods = new HashSet<>(Arrays.asList(
                        "UE", "VE", "JE", "XE", "TE", "SE", "hF", "gF", "fF", "mF", "nF", "lF", "dF"
                ));
                for (Method m : neuronCls.getDeclaredMethods()) {
                    if (blockNeuronMethods.contains(m.getName()) && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                // 若传入了 root view (UE/VE)，把其中的大挂件和小挂件容器全部隐藏
                                if (param.args != null && param.args.length == 1 && param.args[0] instanceof View) {
                                    View root = (View) param.args[0];
                                    int[] ids = new int[]{2131363024, 2131363022, 2131384870, 2131366480, 2131362050};
                                    for (int id : ids) {
                                        View v = root.findViewById(id);
                                        if (v != null) {
                                            v.setVisibility(View.GONE);
                                        }
                                    }
                                }
                                String[] viewFields = new String[]{"k", "l", "m", "n", "q", "r"};
                                for (String f : viewFields) {
                                    try {
                                        Object vObj = XposedHelpers.getObjectField(param.thisObject, f);
                                        if (vObj instanceof View) {
                                            ((View) vObj).setVisibility(View.GONE);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                param.setResult(null);
                            }
                        });
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 部落活动挂件拦截（彻底铲除横屏/全屏「三团联动」卡片大挂件）
     */
    private static void hookTribePendant(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 8.2.1.0 实际包名 (com.douyu.live.p.tribe)
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.pendant.TribeUserPendant");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.pendant.TribeCallingFlagView");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.TribeEntryView");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.TribeWelcomeBanner");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.guide.TribeGuideTipView");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.guide.TribeGuideTipLandView");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.guide.TribeGuideTipBaseView");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.TribeAnchorWrapper");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.TribeUserWrapper");
        hookViewToGone(lpparam, "com.douyu.live.p.tribe.view.TribeBaseWrapper");

        // 兼容旧版/备用包名
        hookViewToGone(lpparam, "com.douyu.module.player.p.tribe.view.TribeUserPendant");
        hookViewToGone(lpparam, "com.douyu.module.player.p.tribe.view.TribeCallingFlagView");
    }

    /**
     * 杂项广告与悬浮球拦截
     */
    private static void hookMiscAdFunctions(final XC_LoadPackage.LoadPackageParam lpparam) {
        String[] adFunctions = new String[]{
                "com.douyu.module.player.p.commercialad.IFCommercialADFunction",
                "com.douyu.module.player.p.gameprop.IFGamePropFunction",
                "com.douyu.module.player.p.redpacket.IFRedPacketFunction",
                "com.douyu.module.player.p.roomlottery.IFRoomLotteryFunction",
                "com.douyu.module.player.p.treasurebox.IFTreasureBoxFunction"
        };
        for (String funcClsName : adFunctions) {
            try {
                Class<?> cls = XposedHelpers.findClass(funcClsName, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(cls, "isEnable", XC_MethodReplacement.returnConstant(false));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 最安全稳健的 View 隐藏：
     * 1. 构造函数直接置为 View.GONE
     * 2. 拦截 setVisibility(int)：外部无论设为 VISIBLE 还是其他，强制覆盖为 View.GONE
     * 3. 拦截 onMeasure(int, int)：调用 setMeasuredDimension(0, 0) 并阻断测量，尺寸恒为 0x0
     * 绝对不修改 View 树结构，绝不抛任何异常，永久隐形
     */
    private static void hookViewToGone(XC_LoadPackage.LoadPackageParam lpparam, String className) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (cls == null || !View.class.isAssignableFrom(cls)) return;

            // 1. 构造函数直接置为 GONE
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof View) {
                        ((View) param.thisObject).setVisibility(View.GONE);
                    }
                }
            });

            // 2. setVisibility
            try {
                XposedHelpers.findAndHookMethod(cls, "setVisibility", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.args[0] = View.GONE;
                    }
                });
            } catch (Throwable ignored) {}

            // 3. onMeasure
            try {
                XposedHelpers.findAndHookMethod(cls, "onMeasure", int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setMeasuredDimension", 0, 0);
                        } catch (Throwable ignored) {}
                        param.setResult(null);
                    }
                });
            } catch (Throwable ignored) {}

            Log.i(TAG, "Permanently disabled View: " + className);
        } catch (Throwable ignored) {}
    }

    /**
     * 彻底铲除首页（推荐流）、动态页等信息流广告卡片
     * 从【数据源层】+【Adapter 转换层】+【视图渲染层】三位一体根治：
     * 1. 首页推荐流：在 NewHomeRecDataFactory$Companion.a 中直接移除广告 item，彻底消除 GridLayoutManager 留下的黑色空块
     * 2. 动态流：在 FeedListWarpData 与 DynamicAdItem 中彻底剔除广告与推广项，阻断语音厅等广告卡片
     * 3. 兜底视图检测：拦截折叠所有广告视图组件
     */
    private static void hookFeedAds(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 0. 阻断官方 AdFactory 生产任何广告 View
        try {
            Class<?> adFactoryCls = XposedHelpers.findClassIfExists("com.douyu.sdk.ad.common.AdFactory", lpparam.classLoader);
            if (adFactoryCls != null) {
                for (Method m : adFactoryCls.getDeclaredMethods()) {
                    if (m.getName().equals("e")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                    }
                }
                Log.i(TAG, "Blocked AdFactory.e -> disabled all official ad views creation");
            }
        } catch (Throwable ignored) {}

        // 1. 【核心修复：首页推荐流数据源层剔除广告】
        // 在数据工厂转换 NewHomeRecContentList 时直接从列表剔除广告 item，
        // 从而让 GridLayoutManager 接收到的数据集完全没有广告，网格卡片紧密平铺，彻底根除黑色背景大空洞！
        try {
            Class<?> recFactoryCompanionCls = XposedHelpers.findClassIfExists(
                    "com.douyu.list.p.newhome.tabs.rec.data.NewHomeRecDataFactory$Companion",
                    lpparam.classLoader
            );
            if (recFactoryCompanionCls != null) {
                XC_MethodHook filterRecHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object recContentList = param.getResult();
                        if (recContentList == null && param.args.length > 0) {
                            recContentList = param.args[0];
                        }
                        if (recContentList == null) return;

                        // 过滤普通推荐双列卡片流 recContent
                        try {
                            List<?> recContent = (List<?>) XposedHelpers.callMethod(recContentList, "getRecContent");
                            if (recContent != null) {
                                java.util.Iterator<?> it = recContent.iterator();
                                while (it.hasNext()) {
                                    Object item = it.next();
                                    if (item == null) continue;
                                    Integer type = (Integer) XposedHelpers.callMethod(item, "getType");
                                    Object localAd = XposedHelpers.callMethod(item, "getLocalAd");
                                    String ad = (String) XposedHelpers.callMethod(item, "getAd");
                                    // type == 2 (NewHomeRecDataType.AD) 或包含 ad 数据
                                    if ((type != null && type == 2) || localAd != null || (!TextUtils.isEmpty(ad) && !"{}".equals(ad.trim()))) {
                                        it.remove();
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Failed to filter recContent", t);
                        }

                        // 过滤大播放器/Banner流 recCard
                        try {
                            List<?> recCard = (List<?>) XposedHelpers.callMethod(recContentList, "getRecCard");
                            if (recCard != null) {
                                java.util.Iterator<?> it = recCard.iterator();
                                while (it.hasNext()) {
                                    Object item = it.next();
                                    if (item == null) continue;
                                    Integer type = (Integer) XposedHelpers.callMethod(item, "getType");
                                    Object localAd = XposedHelpers.callMethod(item, "getLocalAd");
                                    String ad = (String) XposedHelpers.callMethod(item, "getAd");
                                    if ((type != null && type == 2) || localAd != null || (!TextUtils.isEmpty(ad) && !"{}".equals(ad.trim()))) {
                                        it.remove();
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                };

                for (Method m : recFactoryCompanionCls.getDeclaredMethods()) {
                    if (m.getName().equals("a")) {
                        XposedBridge.hookMethod(m, filterRecHook);
                    }
                }
                Log.i(TAG, "Hooked NewHomeRecDataFactory$Companion.a -> cleanly stripped ad cards from data source (No black gap)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook NewHomeRecDataFactory$Companion", t);
        }

        // 2. 【核心修复：动态页关注流数据源层彻底剔除广告与推广】
        // 动态页由 FeedListWarpData 加载，包含 DynamicAdItem（渲染语音厅、游戏联运等推广卡片）
        try {
            Class<?> feedListWarpCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.yuba2.dynamic.hometab.follow.bean.FeedListWarpData",
                    lpparam.classLoader
            );
            if (feedListWarpCls != null) {
                XC_MethodHook filterFeedHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        List<?> list = (List<?>) param.getResult();
                        if (list != null) {
                            java.util.Iterator<?> it = list.iterator();
                            while (it.hasNext()) {
                                Object item = it.next();
                                if (item == null) continue;
                                try {
                                    Object type = XposedHelpers.callMethod(item, "getType");
                                    Object card = XposedHelpers.callMethod(item, "getCard");
                                    String typeStr = type != null ? type.toString() : "";
                                    String cardCls = card != null ? card.getClass().getName() : "";
                                    if (typeStr.contains("AD") || cardCls.contains("AdBean") || cardCls.contains("Advert")
                                            || cardCls.contains("RecommendFollow")) {
                                        it.remove();
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                };

                for (Method m : feedListWarpCls.getDeclaredMethods()) {
                    if (m.getName().equals("a") || m.getName().equals("getFeedList")) {
                        XposedBridge.hookMethod(m, filterFeedHook);
                    }
                }
                Log.i(TAG, "Hooked FeedListWarpData -> cleanly stripped dynamic ad feed items");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook FeedListWarpData", t);
        }

        // 3. 拦截 DynamicAdItem.isTargetData，彻底让动态广告条目失效
        try {
            Class<?> dynamicAdItemCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.yuba2.dynamic.hometab.follow.item.DynamicAdItem",
                    lpparam.classLoader
            );
            if (dynamicAdItemCls != null) {
                XposedHelpers.findAndHookMethod(dynamicAdItemCls, "isTargetData", Object.class,
                        XC_MethodReplacement.returnConstant(false));
                Log.i(TAG, "Hooked DynamicAdItem.isTargetData -> return false");
            }
        } catch (Throwable ignored) {}

        // 4. 拦截鱼吧动态流广告 BaseMainDynamicAdvertItem
        try {
            Class<?> ybAdvertCls = XposedHelpers.findClassIfExists(
                    "com.douyu.yuba.adapter.item.main.BaseMainDynamicAdvertItem",
                    lpparam.classLoader
            );
            if (ybAdvertCls != null) {
                for (Method m : ybAdvertCls.getDeclaredMethods()) {
                    if (m.getName().equals("k") || m.getName().equals("g")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 5. 底层拦截并折叠所有官方信息流与动态广告卡片 View 类
        String[] adViewClasses = new String[]{
                "com.douyu.sdk.ad.douyu.view.AdShadowRoomCardView",
                "com.douyu.sdk.ad.douyu.view.NewHomeAdShadowRoomCardView",
                "com.douyu.sdk.ad.douyu.view.TailCateAdShadowRoomCardView",
                "com.douyu.sdk.ad.douyu.view.AdDynamicCardView",
                "com.douyu.sdk.ad.douyu.view.AdRoomSelectedView",
                "com.douyu.sdk.ad.douyu.view.AdFollowFakeRoomView",
                "com.douyu.sdk.ad.douyu.view.AdImgView",
                "com.douyu.sdk.ad.douyu.view.AdFlowView",
                "com.douyu.sdk.ad.douyu.view.AdBannerView",
                "com.douyu.sdk.ad.douyu.view.AdBannerBView",
                "com.douyu.sdk.ad.douyu.view.AdCustomBannerView",
                "com.douyu.sdk.ad.douyu.view.CloseRoomAdView",
                "com.douyu.sdk.ad.douyu.view.DyIAdView",
                "com.douyu.sdk.ad.AdView",
                "com.douyu.module.yuba2.dynamic.common.card.view.FollowRecommendFollowUserCardView"
        };
        for (String adViewCls : adViewClasses) {
            hookViewToGone(lpparam, adViewCls);
        }

        // 6. 通用 RecyclerView.Adapter.bindViewHolder 绑定后检测广告标识并隐藏 (渲染层兜底防线)
        try {
            Class<?> adapterCls = XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView$Adapter", lpparam.classLoader);
            if (adapterCls == null) {
                adapterCls = XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView.Adapter", lpparam.classLoader);
            }
            if (adapterCls != null) {
                XC_MethodHook bindHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject != null) {
                            String adapterName = param.thisObject.getClass().getName();
                            if (adapterName.contains(".listchat") || adapterName.contains(".danmu") || adapterName.contains(".rambogift")) {
                                return;
                            }
                        }
                        if (param.args.length > 0 && param.args[0] != null) {
                            try {
                                View itemView = (View) XposedHelpers.getObjectField(param.args[0], "itemView");
                                if (itemView != null) {
                                    handleAdItemView(itemView);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                };

                for (Method m : adapterCls.getDeclaredMethods()) {
                    if (m.getName().equals("bindViewHolder") && !java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                        XposedBridge.hookMethod(m, bindHook);
                    }
                }
                Log.i(TAG, "Hooked RecyclerView.Adapter.bindViewHolder for feed ad removal");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RecyclerView.Adapter.bindViewHolder", t);
        }

        // 7. 针对具体的 DYMultiTypeAdapter、DynamicFeedAdapter 等做具体实现类 Hook
        String[] concreteAdapters = new String[]{
                "com.douyu.module.list.nf.adapter.DYMultiTypeAdapter",
                "com.douyu.module.yuba2.dynamic.common.adapter.DynamicFeedAdapter"
        };
        for (String adapterName : concreteAdapters) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(adapterName, lpparam.classLoader);
                if (cls != null) {
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("onBindViewHolder") && !java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    if (param.args.length > 0 && param.args[0] != null) {
                                        try {
                                            View itemView = (View) XposedHelpers.getObjectField(param.args[0], "itemView");
                                            if (itemView != null) {
                                                handleAdItemView(itemView);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            });
                        }
                    }
                    Log.i(TAG, "Hooked concrete adapter: " + adapterName);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static final java.util.Map<View, int[]> sCollapsedAdViewParams =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, int[]>());

    private static void handleAdItemView(View itemView) {
        try {
            boolean isAd = isAdItem(itemView, 0);

            if (isAd) {
                itemView.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                if (lp != null) {
                    if (!sCollapsedAdViewParams.containsKey(itemView) && (lp.width != 0 || lp.height != 0)) {
                        sCollapsedAdViewParams.put(itemView, new int[]{lp.width, lp.height});
                    }
                    lp.width = 0;
                    lp.height = 0;
                    itemView.setLayoutParams(lp);
                }
            } else {
                // 仅当该 itemView 之前被本模块作为广告折叠过时，在复用时精准还原其原始尺寸与可见性
                int[] origSize = sCollapsedAdViewParams.remove(itemView);
                if (origSize != null) {
                    itemView.setVisibility(View.VISIBLE);
                    ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                    if (lp != null) {
                        lp.width = origSize[0];
                        lp.height = origSize[1];
                        itemView.setLayoutParams(lp);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 多维深度检测是否为广告卡片：
     * 1. 类名检测：AdShadowRoomCardView, NewHomeAdShadowRoomCardView, AdDynamicCardView, AdView, DyIAdView 等
     * 2. 广告 ID 检测：tv_ad (0x7f0a3492), tv_ad_close (0x7f0a3493), 右上角广告标 (0x7f0a0154 / id/im)
     * 3. 标签文字检测：任意可见 TextView 文本为“广告”、“推广”或以“广告”开头
     */
    private static boolean isAdItem(View v, int depth) {
        if (v == null || depth > 8) return false;

        String clsName = v.getClass().getName();
        if (clsName.contains("AdShadowRoomCardView")
                || clsName.contains("NewHomeAdShadowRoomCardView")
                || clsName.contains("TailCateAdShadowRoomCardView")
                || clsName.contains("AdDynamicCardView")
                || clsName.contains("AdRoomSelectedView")
                || clsName.contains("AdFollowFakeRoomView")
                || clsName.contains("AdFlowView")
                || clsName.contains("PtgRound")
                || clsName.contains("DyIAdView")
                || (clsName.endsWith(".AdView") && clsName.contains("douyu"))
                || clsName.contains("FollowRecommendFollowUser")) {
            return true;
        }

        int id = v.getId();
        if (id == 0x7f0a3492 || id == 0x7f0a3493 || id == 0x7f0a0154) {
            return true;
        }

        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null) {
                String txt = cs.toString().trim();
                if ("广告".equals(txt) || "推广".equals(txt)
                        || (txt.startsWith("广告") && txt.length() <= 6)
                        || (txt.startsWith("推广") && txt.length() <= 6)) {
                    return true;
                }
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int childCount = vg.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (isAdItem(vg.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }

        return false;
    }
}