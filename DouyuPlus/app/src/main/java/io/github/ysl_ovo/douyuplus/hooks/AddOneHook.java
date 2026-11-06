package io.github.ysl_ovo.douyuplus.hooks;

import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AddOneHook {
    private static final String TAG = "DouyuPlus_AddOne";
    private static final Set<Class<?>> sHookedConcreteClasses = Collections.synchronizedSet(new HashSet<>());

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. Hook OptFunBean.b() (解除 60s CD 冷却限制)
        try {
            final Class<?> optFunBeanCls = XposedHelpers.findClass("tv.douyu.danmuopt.bean.OptFunBean", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(optFunBeanCls, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String type = getOptType(param.thisObject);
                    if (type == null || "type_Cppy".equalsIgnoreCase(type) || "type_copy".equalsIgnoreCase(type)) {
                        // 强制返回 false，彻底解除 +1 冷却！
                        param.setResult(false);
                    }
                }
            });
            Log.i(TAG, "Successfully hooked OptFunBean.b (CD Bypass)");

            // 2. 动态 Hook 子类 concrete a() 方法保证 +1 按钮常显（每个子类仅 Hook 一次）
            XposedBridge.hookAllConstructors(optFunBeanCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Class<?> concreteCls = param.thisObject.getClass();
                    if (concreteCls != optFunBeanCls && sHookedConcreteClasses.add(concreteCls)) {
                        try {
                            Method aMethod = concreteCls.getDeclaredMethod("a");
                            XposedBridge.hookMethod(aMethod, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam aParam) throws Throwable {
                                    String type = getOptType(aParam.thisObject);
                                    if (type == null || "type_Cppy".equalsIgnoreCase(type) || "type_copy".equalsIgnoreCase(type)) {
                                        aParam.setResult(true);
                                    }
                                }
                            });
                        } catch (Throwable ignored) {}
                    }
                }
            });
            Log.i(TAG, "Successfully hooked OptFunBean constructors for concrete a() visibility");

        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook OptFunBean", t);
        }
    }

    private static String getOptType(Object optFunBean) {
        if (optFunBean == null) return null;
        Class<?> cur = optFunBean.getClass();
        while (cur != null && cur != Object.class) {
            for (Field f : cur.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(optFunBean);
                        if (val != null) {
                            return val.toString();
                        }
                    } catch (Throwable ignored) {}
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }
}
