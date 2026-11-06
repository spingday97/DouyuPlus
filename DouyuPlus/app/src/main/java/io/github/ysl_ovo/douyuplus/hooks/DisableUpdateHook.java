package io.github.ysl_ovo.douyuplus.hooks;

import android.util.Log;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class DisableUpdateHook {
    private static final String TAG = "DouyuPlus_Update";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> updateBeanCls = XposedHelpers.findClass("com.douyu.module.update.bean.UpdateBean", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(updateBeanCls, "isUpdate", XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(updateBeanCls, "isForceUpdate", XC_MethodReplacement.returnConstant(false));
            Log.i(TAG, "Hooked UpdateBean: disabled updates");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook UpdateBean", t);
        }
    }
}
