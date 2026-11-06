package io.github.ysl_ovo.douyuplus;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import io.github.ysl_ovo.douyuplus.hooks.AdBlockHook;
import io.github.ysl_ovo.douyuplus.hooks.AddOneHook;
import io.github.ysl_ovo.douyuplus.hooks.AiStrengthenHook;
import io.github.ysl_ovo.douyuplus.hooks.AutoQualityHook;
import io.github.ysl_ovo.douyuplus.hooks.DanmuCleanHook;
import io.github.ysl_ovo.douyuplus.hooks.DanmuMergeHook;
import io.github.ysl_ovo.douyuplus.hooks.DanmuSuffixHook;
import io.github.ysl_ovo.douyuplus.hooks.DisableUpdateHook;
import io.github.ysl_ovo.douyuplus.hooks.HomeTabHook;
import io.github.ysl_ovo.douyuplus.hooks.PadModeHook;
import io.github.ysl_ovo.douyuplus.hooks.PerformanceHook;
import io.github.ysl_ovo.douyuplus.hooks.SettingsHook;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "DouyuPlus";
    public static final String TARGET_PACKAGE = "air.tv.douyu.android";
    private static volatile boolean sHooksInitialized = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Log.i(TAG, "Loading DouyuPlus for package: " + lpparam.packageName + ", process: " + lpparam.processName);

        // 在 Application.attach(Context) 时读取 SharedPreferences 配置并挂载对应 Hook
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context ctx = (Context) param.args[0];
                    DouyuPlusConfig.init(ctx);
                    initHooks(lpparam);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Application.attach, initializing hooks directly", t);
            initHooks(lpparam);
        }
    }

    private static synchronized void initHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sHooksInitialized) {
            return;
        }
        sHooksInitialized = true;

        // 后台省电与统计风控净化（跨主进程与推送子进程生效）
        if (DouyuPlusConfig.isPerformanceEnabled()) {
            PerformanceHook.hook(lpparam);
        }

        // 非主进程仅挂载后台服务拦截，跳过主界面与播放器 UI Hook
        if (lpparam.processName != null && !TARGET_PACKAGE.equals(lpparam.processName)) {
            Log.i(TAG, "Skipping UI/Player hooks in sub-process: " + lpparam.processName);
            return;
        }

        // 0. 设置页「斗鱼Plus 增强与平板设置」入口（始终挂载）
        SettingsHook.hook(lpparam);

        // 1. 平板/大屏宽屏与横屏强制解锁（由设置页开关控制，默认关闭，开启后生效）
        if (DouyuPlusConfig.isPadModeEnabled()) {
            PadModeHook.hook(lpparam);
        }

        // 2. 无限制 +1 与双击弹幕 +1
        if (DouyuPlusConfig.isAddOneNoCdEnabled()) {
            AddOneHook.hook(lpparam);
        }

        // 3. 弹幕零宽防重后缀
        if (DouyuPlusConfig.isDanmuSuffixEnabled()) {
            DanmuSuffixHook.hook(lpparam);
        }

        // 4. 全屏弹幕净化
        if (DouyuPlusConfig.isDanmuCleanEnabled()) {
            DanmuCleanHook.hook(lpparam);
        }

        // 5. 去除广告、直播间底部广告、活动挂件与劣质游戏
        if (DouyuPlusConfig.isAdBlockEnabled()) {
            AdBlockHook.hook(lpparam);
        }

        // 6. 首页底部导航栏（「关注」替换、隐藏中间「+」号、自定义启动默认页各自独立受控）
        HomeTabHook.hook(lpparam);

        // 7. 屏蔽更新弹窗（始终开启）
        DisableUpdateHook.hook(lpparam);

        // 8. 解锁画质增强并启用高通 SGSR (Snapdragon Game Super Resolution) 超分算法
        if (DouyuPlusConfig.isAiStrengthenEnabled()) {
            AiStrengthenHook.hook(lpparam);
        }

        // 9. 重复飘屏弹幕合并显示 (xN 连击)
        if (DouyuPlusConfig.isDanmuMergeEnabled()) {
            DanmuMergeHook.hook(lpparam);
        }

        // 10. 自动切换最高画质 (支持 WiFi 下生效或始终生效)
        AutoQualityHook.hook(lpparam);

        // 11. 调试模式：后台日志环形收集与测试运行器（仅在开启调试模式时激活）
        if (DouyuPlusConfig.isDebugModeEnabled()) {
            TestRunner.init(lpparam);
        }
    }
}
