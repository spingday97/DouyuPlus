package io.github.ysl_ovo.douyuplus.hooks;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 需求 1 & 2: 弹幕净化与全屏纯净文本化
 * 1. 彻底拦截全屏特殊弹幕神经元 (SpecialDanmakuNeuron.EE -> false, LPLandSpecialDanmakuLayer.q -> false)
 * 2. 彻底拦截特殊弹幕布局绘制 (LPSpecialDanmuLayout 各发射方法 -> DO_NOTHING)
 * 3. 拦截皮肤图片解析 (LPSkinDanmakuEmitter.m -> "", AchievementManager.c -> "")
 * 4. 拦截气泡派发 (CustomDanmakuManager.l / a -> false)
 * 5. 移除弹幕前缀 (DanmaPrefixUtils$Companion.c / g / h / a)
 * 6. 抹除弹幕数据源皮肤、贵族、至尊、气泡字段 (DanmukuBean)
 * 7. 移除边框和底色高亮 (BaseFlameDanmakuHandler.i)
 * 8. 移除动图表情挂件 (CommonWebpEndViewUtil)
 */
public class DanmuCleanHook {
    private static final String TAG = "DouyuPlus_DanmuClean";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 拦截 SpecialDanmakuNeuron.EE(DanmukuBean) -> return false
        // 防止全屏弹幕被特殊弹幕管道捕获，强制回退为普通文本弹幕
        try {
            Class<?> specialDanmakuNeuronCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.innerlayer.landscape.danma.SpecialDanmakuNeuron",
                    lpparam.classLoader
            );
            for (Method m : specialDanmakuNeuronCls.getDeclaredMethods()) {
                if (m.getName().equals("EE") && m.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                    Log.i(TAG, "Hooked SpecialDanmakuNeuron.EE -> return false");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook SpecialDanmakuNeuron", t);
        }

        // 2. 拦截 LPLandSpecialDanmakuLayer.q 与 k -> return false
        try {
            Class<?> landSpecialLayerCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.innerlayer.landscape.layer.LPLandSpecialDanmakuLayer",
                    lpparam.classLoader
            );
            for (Method m : landSpecialLayerCls.getDeclaredMethods()) {
                if ((m.getName().equals("q") || m.getName().equals("k")) && m.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                    Log.i(TAG, "Hooked LPLandSpecialDanmakuLayer." + m.getName() + " -> return false");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook LPLandSpecialDanmakuLayer", t);
        }

        // 3. 拦截 LPSpecialDanmuLayout 的发射方法，彻底杜绝特殊弹幕渲染
        try {
            Class<?> specialDanmuLayoutCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.innerlayer.landscape.danma.LPSpecialDanmuLayout",
                    lpparam.classLoader
            );
            for (Method m : specialDanmuLayoutCls.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("b") || name.equals("c") || name.equals("d") ||
                    name.equals("e") || name.equals("g") || name.equals("h") ||
                    name.equals("i") || name.equals("j") || name.equals("n")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
            Log.i(TAG, "Hooked LPSpecialDanmuLayout render methods -> DO_NOTHING");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook LPSpecialDanmuLayout", t);
        }

        // 4. 拦截皮肤 URL 解析，强制无皮肤
        try {
            Class<?> skinEmitterCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.innerlayer.landscape.customdanmu.skin.LPSkinDanmakuEmitter",
                    lpparam.classLoader
            );
            for (Method m : skinEmitterCls.getDeclaredMethods()) {
                if (m.getName().equals("m") && m.getReturnType() == String.class) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(""));
                    Log.i(TAG, "Hooked LPSkinDanmakuEmitter.m -> return \"\"");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook LPSkinDanmakuEmitter", t);
        }

        try {
            Class<?> achMgrCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.manager.AchievementManager",
                    lpparam.classLoader
            );
            for (Method m : achMgrCls.getDeclaredMethods()) {
                if (m.getName().equals("c") && m.getReturnType() == String.class) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(""));
                }
            }
        } catch (Throwable ignored) {}

        // 5. 拦截 CustomDanmakuManager（气泡、特殊装饰背景）
        try {
            Class<?> customDanmakuMgrCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.innerlayer.landscape.customdanmu.base.CustomDanmakuManager",
                    lpparam.classLoader
            );
            for (Method m : customDanmakuMgrCls.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class && m.getParameterTypes().length == 1) {
                    if (m.getName().equals("l") || m.getName().equals("a")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                        Log.i(TAG, "Hooked CustomDanmakuManager." + m.getName() + " -> return false");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook CustomDanmakuManager", t);
        }

        // 6. 移除弹幕前缀（粉丝牌、头衔、勋章图片）
        try {
            Class<?> prefixCompanionCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.utils.DanmaPrefixUtils$Companion",
                    lpparam.classLoader
            );
            for (Method m : prefixCompanionCls.getDeclaredMethods()) {
                if (m.getName().equals("c") && m.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                    Log.i(TAG, "Hooked DanmaPrefixUtils$Companion.c -> DO_NOTHING");
                } else if ((m.getName().equals("g") || m.getName().equals("h")) && m.getReturnType() == String.class) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                } else if (m.getName().equals("a") && m.getParameterTypes().length >= 4) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DanmaPrefixUtils$Companion", t);
        }

        // 7. 抹除弹幕数据源的皮肤、气泡、特效字段，并强制普通文本弹幕
        try {
            Class<?> danmukuBeanCls = XposedHelpers.findClass("com.douyu.lib.xdanmuku.bean.DanmukuBean", lpparam.classLoader);
            XposedBridge.hookAllConstructors(danmukuBeanCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object obj = param.thisObject;
                        XposedHelpers.setObjectField(obj, "eci", "");
                        XposedHelpers.setObjectField(obj, "ecid", "");
                        XposedHelpers.setObjectField(obj, "diaf", "");
                        XposedHelpers.setObjectField(obj, "cdiaf", "");
                        XposedHelpers.setObjectField(obj, "diafid", "");
                        XposedHelpers.setObjectField(obj, "csuperScreen", "");
                        XposedHelpers.setObjectField(obj, "dceh", "");
                        XposedHelpers.setObjectField(obj, "nc", "");
                        XposedHelpers.setObjectField(obj, "nl", "");
                        XposedHelpers.setObjectField(obj, "sl", "0");
                        XposedHelpers.setObjectField(obj, "el", null);

                        Object headIcon = XposedHelpers.getObjectField(obj, "headIcon");
                        if (headIcon instanceof ArrayList) {
                            ((ArrayList<?>) headIcon).clear();
                        }
                        Object headHalfIcon = XposedHelpers.getObjectField(obj, "headHalfIcon");
                        if (headHalfIcon instanceof ArrayList) {
                            ((ArrayList<?>) headHalfIcon).clear();
                        }
                        Object tailIcon = XposedHelpers.getObjectField(obj, "tailIcon");
                        if (tailIcon instanceof ArrayList) {
                            ((ArrayList<?>) tailIcon).clear();
                        }
                        Object tailHalfIcon = XposedHelpers.getObjectField(obj, "tailHalfIcon");
                        if (tailHalfIcon instanceof ArrayList) {
                            ((ArrayList<?>) tailHalfIcon).clear();
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // 强制所有贵族/至尊/特效弹幕判定为 false，强制回退为普通纯文本
            XposedHelpers.findAndHookMethod(danmukuBeanCls, "isNobleDanma", XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(danmukuBeanCls, "isSupremeDanmu", XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(danmukuBeanCls, "isSupremeFloatDanmaku", XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(danmukuBeanCls, "isNoble", XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(danmukuBeanCls, "getNobleLevel", XC_MethodReplacement.returnConstant(0));
            Log.i(TAG, "Hooked DanmukuBean methods & constructors to strip all skins/effects");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DanmukuBean", t);
        }

        // 8. 移除弹幕尾部 WebP 动图
        try {
            Class<?> webpEndUtilCls = XposedHelpers.findClass(
                    "tv.douyu.liveplayer.innerlayer.landscape.customdanmu.common.CommonWebpEndViewUtil",
                    lpparam.classLoader
            );
            for (Method m : webpEndUtilCls.getDeclaredMethods()) {
                if (m.getReturnType() == String.class) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                }
            }
        } catch (Throwable ignored) {}

        // 9. 移除自身边框高亮
        try {
            Class<?> baseHandlerCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.danmaku.papi.BaseFlameDanmakuHandler",
                    lpparam.classLoader
            );
            for (Method m : baseHandlerCls.getDeclaredMethods()) {
                if (m.getName().equals("i") && m.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object danmaku = param.getResult();
                            if (danmaku != null) {
                                try {
                                    XposedHelpers.setIntField(danmaku, "borderColor", 0);
                                    XposedHelpers.setIntField(danmaku, "underlineColor", 0);
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }
}
