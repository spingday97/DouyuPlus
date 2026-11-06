package io.github.ysl_ovo.douyuplus.hooks;

import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Random;

public class DanmuSuffixHook {
    private static final String TAG = "DouyuPlus_DanmuSuffix";
    // 零宽不换行空格、零宽空格、零宽连字符等，视觉完全不可见
    private static final String[] ZERO_WIDTH_CHARS = new String[]{
            "\u200B", // Zero-width space
            "\u200C", // Zero-width non-joiner
            "\u200D", // Zero-width joiner
            "\uFEFF"  // Zero-width no-break space
    };
    private static final Random RANDOM = new Random();

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> jniDanmuCls = XposedHelpers.findClass("com.douyu.lib.xdanmuku.x.JniDanmu", lpparam.classLoader);

            // 动态查找 sendDanmu 方法，自适应所有版本混淆名（如 7.x 的 i/o, 8.x 的 m 等）
            int hookedCount = 0;
            for (Method m : jniDanmuCls.getDeclaredMethods()) {
                if (m.getReturnType() == int.class
                        && m.getParameterTypes().length == 1
                        && Map.class.isAssignableFrom(m.getParameterTypes()[0])
                        && !Modifier.isNative(m.getModifiers())) {

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Map<String, Object> map = (Map<String, Object>) param.args[0];
                                if (map != null && map.containsKey("content")) {
                                    Object contentObj = map.get("content");
                                    if (contentObj != null) {
                                        String original = contentObj.toString();
                                        // 组合 1~2 个随机零宽隐形字符
                                        String suffix = ZERO_WIDTH_CHARS[RANDOM.nextInt(ZERO_WIDTH_CHARS.length)];
                                        if (RANDOM.nextBoolean()) {
                                            suffix += ZERO_WIDTH_CHARS[RANDOM.nextInt(ZERO_WIDTH_CHARS.length)];
                                        }
                                        map.put("content", original + suffix);
                                        Log.d(TAG, "Appended zero-width suffix to: " + original);
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "Error in JniDanmu hook", t);
                            }
                        }
                    });
                    hookedCount++;
                    Log.i(TAG, "Hooked JniDanmu send method: " + m.getName());
                }
            }
            if (hookedCount == 0) {
                Log.w(TAG, "No matching send method found in JniDanmu");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook JniDanmu", t);
        }
    }
}
