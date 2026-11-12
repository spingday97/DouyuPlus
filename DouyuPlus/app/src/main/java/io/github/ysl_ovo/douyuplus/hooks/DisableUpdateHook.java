package io.github.ysl_ovo.douyuplus.hooks;

import android.util.Log;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;

public class DisableUpdateHook {
    private static final String TAG = "DouyuPlus_Update";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 拦截 UpdateBean 更新状态与下载信息
        try {
            Class<?> updateBeanCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.update.bean.UpdateBean",
                    lpparam.classLoader
            );
            if (updateBeanCls != null) {
                for (String boolMethod : new String[]{"isUpdate", "isForceUpdate", "isForce", "hasUpdate"}) {
                    try {
                        XposedHelpers.findAndHookMethod(updateBeanCls, boolMethod,
                                XC_MethodReplacement.returnConstant(false));
                    } catch (Throwable ignored) {}
                }
                for (String strMethod : new String[]{"getDownloadUrl", "getUpdateUrl", "getVersion", "getUpdateContent", "getTitle", "getDesc"}) {
                    try {
                        XposedHelpers.findAndHookMethod(updateBeanCls, strMethod,
                                XC_MethodReplacement.returnConstant(""));
                    } catch (Throwable ignored) {}
                }
                Log.i(TAG, "Hooked UpdateBean: disabled updates and cleared update fields");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook UpdateBean", t);
        }

        // 2. 拦截常见更新检查管理器与更新弹窗入口，阻止检查更新请求与弹窗弹出
        String[] updateClasses = new String[]{
                "com.douyu.module.update.UpdateManager",
                "com.douyu.module.update.DYUpdateManager",
                "com.douyu.module.update.UpdateHelper",
                "com.douyu.module.update.view.UpdateDialog",
                "com.douyu.module.update.dialog.UpdateDialog"
        };
        for (String clsName : updateClasses) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader);
                if (cls == null) continue;
                for (Method m : cls.getDeclaredMethods()) {
                    String name = m.getName();
                    if (name.contains("check") || name.contains("Check")
                            || name.contains("update") || name.contains("Update")
                            || name.equals("show") || name.equals("showDialog")) {
                        if (m.getReturnType() == void.class) {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                        } else if (m.getReturnType() == boolean.class) {
                            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                        }
                    }
                }
                Log.i(TAG, "Hooked update class: " + clsName);
            } catch (Throwable ignored) {}
        }
    }
}