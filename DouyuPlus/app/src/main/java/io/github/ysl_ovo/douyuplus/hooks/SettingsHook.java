package io.github.ysl_ovo.douyuplus.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;
import io.github.ysl_ovo.douyuplus.TestRunner;
import io.github.ysl_ovo.douyuplus.views.DouyuPlusIconDrawable;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 斗鱼Plus (DouyuPlus) 设置入口与面板管理：
 * 1. 【首要入口】在「我的」页面顶部「签到」按钮（日历图标）的右侧，注入专属绘制的矢量齿轮+加号图标；
 *    避开页面上滑时昵称移至顶栏产生的文字重合，享有充足安全间距；
 * 2. 【兼容入口】在「设置」页面 (SetupActivity) 顶部保留独立条目；
 * 点击均弹出独立的斗鱼Plus增强与平板设置面板。
 */
public class SettingsHook {
    private static final String TAG = "DouyuPlus_Settings";
    private static final String TAG_TOPBAR_ENTRY = "douyuplus_topbar_entry";

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookSetupActivity(lpparam);
        hookUserCenterTopBar(lpparam);
    }

    /**
     * 1. 注入「我的」页面顶栏入口 (位于顶部「签到」按钮右侧)
     */
    private static void hookUserCenterTopBar(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> topBarCls = XposedHelpers.findClass(
                    "com.douyu.module.yuba2.usercenter.topbar.UserCenterTopBarView",
                    lpparam.classLoader
            );

            // 监听 UserCenterTopBarView 构造完成
            for (Constructor<?> ctor : topBarCls.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        injectUserCenterTopBarEntry(param.thisObject, lpparam.classLoader);
                    }
                });
            }

            // 监听 Z() 初始化
            try {
                XposedHelpers.findAndHookMethod(topBarCls, "Z", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        injectUserCenterTopBarEntry(param.thisObject, lpparam.classLoader);
                    }
                });
            } catch (Throwable ignored) {}

            // 主题/暗色模式联动更新
            XC_MethodHook themeHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    updateTopBarEntryTheme(param.thisObject, lpparam.classLoader);
                }
            };
            try {
                XposedHelpers.findAndHookMethod(topBarCls, "R", themeHook);
                XposedHelpers.findAndHookMethod(topBarCls, "o0", themeHook);
                XposedHelpers.findAndHookMethod(topBarCls, "p0", boolean.class, themeHook);
            } catch (Throwable ignored) {}

            Log.i(TAG, "Hooked UserCenterTopBarView successfully");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook UserCenterTopBarView", t);
        }

        // 双重保障：监听 UserCenterFragment.onViewCreated
        try {
            Class<?> ucFragCls = XposedHelpers.findClass(
                    "com.douyu.module.yuba2.usercenter.UserCenterFragment",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(ucFragCls, "onViewCreated", View.class, Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View root = (View) param.args[0];
                    if (root != null) {
                        View signAnchorView = root.findViewById(2131370637); // layoutUcenterTopbarOwnerLeft
                        if (signAnchorView == null) {
                            signAnchorView = root.findViewById(2131366442); // flUcenterTopbarOwnerSign
                        }
                        if (signAnchorView != null && signAnchorView.getParent() instanceof ViewGroup) {
                            ViewGroup container = (ViewGroup) signAnchorView.getParent();
                            injectTopBarButton(container, signAnchorView, lpparam.classLoader);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void injectUserCenterTopBarEntry(final Object topBarObj, final ClassLoader classLoader) {
        if (topBarObj == null) return;
        try {
            View signAnchorView = null;
            ViewGroup container = null;

            // 策略 1: 从字段 d / mTopBarBinding (Yb2UserCenterTopBar2Binding) 中获取
            Object binding = null;
            for (String fieldName : new String[]{"d", "mTopBarBinding"}) {
                try {
                    binding = XposedHelpers.getObjectField(topBarObj, fieldName);
                    if (binding != null) break;
                } catch (Throwable ignored) {}
            }
            if (binding == null) {
                for (Field f : topBarObj.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(topBarObj);
                    if (val != null && val.getClass().getName().contains("TopBar")) {
                        binding = val;
                        break;
                    }
                }
            }

            if (binding != null) {
                // binding.m: layoutUcenterTopbarOwnerLeft (ID 2131370637)
                // binding.b: flUcenterTopbarOwnerSign (ID 2131366442)
                for (String mField : new String[]{"m", "layoutUcenterTopbarOwnerLeft", "b", "flUcenterTopbarOwnerSign"}) {
                    try {
                        signAnchorView = (View) XposedHelpers.getObjectField(binding, mField);
                        if (signAnchorView != null) break;
                    } catch (Throwable ignored) {}
                }

                if (signAnchorView == null) {
                    try {
                        View root = (View) XposedHelpers.callMethod(binding, "getRoot");
                        if (root != null) {
                            signAnchorView = root.findViewById(2131370637);
                            if (signAnchorView == null) {
                                signAnchorView = root.findViewById(2131366442);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // 策略 2: 从 rootBinding (字段 b) 中查找
            if (signAnchorView == null) {
                try {
                    Object rootBinding = XposedHelpers.getObjectField(topBarObj, "b");
                    if (rootBinding != null) {
                        View root = (View) XposedHelpers.callMethod(rootBinding, "getRoot");
                        if (root != null) {
                            signAnchorView = root.findViewById(2131370637);
                            if (signAnchorView == null) {
                                signAnchorView = root.findViewById(2131366442);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (signAnchorView != null && signAnchorView.getParent() instanceof ViewGroup) {
                container = (ViewGroup) signAnchorView.getParent();
                injectTopBarButton(container, signAnchorView, classLoader);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed in injectUserCenterTopBarEntry", t);
        }
    }

    private static void injectTopBarButton(final ViewGroup container, final View signAnchorView, final ClassLoader classLoader) {
        if (container == null || signAnchorView == null) return;
        if (container.findViewWithTag(TAG_TOPBAR_ENTRY) != null) {
            return;
        }

        // 确保 signAnchorView 拥有有效 ID 以便约束
        if (signAnchorView.getId() == View.NO_ID) {
            signAnchorView.setId(View.generateViewId());
        }

        final Context ctx = container.getContext();
        DouyuPlusConfig.init(ctx);
        final float density = ctx.getResources().getDisplayMetrics().density;

        ImageView plusBtn = new ImageView(ctx);
        plusBtn.setTag(TAG_TOPBAR_ENTRY);
        plusBtn.setContentDescription("斗鱼Plus 设置");

        final DouyuPlusIconDrawable drawable = new DouyuPlusIconDrawable();
        boolean isDark = isDarkTheme(classLoader);
        drawable.setIconColor(isDark ? Color.parseColor("#DDDDDD") : Color.parseColor("#333333"));
        plusBtn.setImageDrawable(drawable);

        // 原生无边界圆形水波纹点击反馈
        TypedValue outValue = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            plusBtn.setBackgroundResource(outValue.resourceId);
        }

        int pad = (int) (4 * density);
        plusBtn.setPadding(pad, pad, pad, pad);
        plusBtn.setClickable(true);
        plusBtn.setFocusable(true);

        try {
            int sizePx = (int) (28 * density);
            Class<?> lpCls = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", classLoader);
            ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) lpCls.getConstructor(int.class, int.class).newInstance(sizePx, sizePx);

            XposedHelpers.setIntField(lp, "topToTop", 0); // PARENT_ID (0)
            XposedHelpers.setIntField(lp, "bottomToBottom", 0); // PARENT_ID (0)
            XposedHelpers.setIntField(lp, "startToEnd", signAnchorView.getId());
            ((ViewGroup.MarginLayoutParams) lp).setMarginStart((int) (12 * density));

            plusBtn.setLayoutParams(lp);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to create ConstraintLayout.LayoutParams via reflection", t);
            return;
        }

        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Activity act = getActivity(v.getContext());
                if (act != null) {
                    showDouyuPlusSettingsDialog(act, null);
                }
            }
        });

        container.addView(plusBtn);
        Log.i(TAG, "Successfully injected DouyuPlus settings icon into '我的' topbar (to the right of sign-in)!");
    }

    private static void updateTopBarEntryTheme(final Object topBarObj, final ClassLoader classLoader) {
        if (topBarObj == null) return;
        try {
            ViewGroup container = null;
            Object binding = null;
            for (String fieldName : new String[]{"d", "mTopBarBinding"}) {
                try {
                    binding = XposedHelpers.getObjectField(topBarObj, fieldName);
                    if (binding != null) break;
                } catch (Throwable ignored) {}
            }
            if (binding != null) {
                for (String kField : new String[]{"k", "layoutUcenterTopbar"}) {
                    try {
                        container = (ViewGroup) XposedHelpers.getObjectField(binding, kField);
                        if (container != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            if (container != null) {
                View entry = container.findViewWithTag(TAG_TOPBAR_ENTRY);
                if (entry instanceof ImageView) {
                    Drawable d = ((ImageView) entry).getDrawable();
                    if (d instanceof DouyuPlusIconDrawable) {
                        boolean isDark = isDarkTheme(classLoader);
                        ((DouyuPlusIconDrawable) d).setIconColor(isDark ? Color.parseColor("#DDDDDD") : Color.parseColor("#333333"));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isDarkTheme(ClassLoader classLoader) {
        try {
            Class<?> baseThemeUtilsCls = XposedHelpers.findClass("com.douyu.lib.theme.BaseThemeUtils", classLoader);
            return (Boolean) XposedHelpers.callStaticMethod(baseThemeUtilsCls, "i");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Activity getActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * 2. 注入「设置」页面 (SetupActivity) 顶部条目
     */
    private static void hookSetupActivity(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> setupActCls = XposedHelpers.findClass(
                    "com.douyu.module.settings.activity.SetupActivity",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(setupActCls, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity act = (Activity) param.thisObject;
                    DouyuPlusConfig.init(act);

                    try {
                        View firstItem = act.findViewById(2131368119);
                        if (firstItem != null && firstItem.getParent() instanceof ViewGroup) {
                            ViewGroup container = (ViewGroup) firstItem.getParent();
                            if (container.findViewWithTag("douyuplus_settings_entry") != null) {
                                return;
                            }

                            float density = act.getResources().getDisplayMetrics().density;
                            View entryView = createDouyuPlusEntryView(act);
                            entryView.setTag("douyuplus_settings_entry");
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                     ViewGroup.LayoutParams.MATCH_PARENT,
                                    (int) (54 * density)
                            );
                            lp.topMargin = (int) (8 * density);
                            lp.bottomMargin = (int) (8 * density);
                            container.addView(entryView, 0, lp);
                            Log.i(TAG, "Injected DouyuPlus settings entry into SetupActivity");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to inject DouyuPlus entry view into SetupActivity", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook SetupActivity", t);
        }
    }

    private static View createDouyuPlusEntryView(final Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        int padH = (int) (16 * density);
        layout.setPadding(padH, 0, padH, 0);
        layout.setClickable(true);
        layout.setFocusable(true);

        TextView titleTv = new TextView(activity);
        titleTv.setText("斗鱼Plus (DouyuPlus) 模块设置");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        layout.addView(titleTv, lp);

        final TextView subTv = new TextView(activity);
        subTv.setTag("douyuplus_sub_tv");
        subTv.setText(getEntrySubtitleText());
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subTv.setTextColor(Color.parseColor("#FF5D23"));
        layout.addView(subTv);

        layout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDouyuPlusSettingsDialog(activity, subTv);
            }
        });
        return layout;
    }

    private static String getEntrySubtitleText() {
        return DouyuPlusConfig.isPadModeEnabled() ? "平板宽屏已开启 >" : "平板宽屏 / 去广告 >";
    }

    public static void showDouyuPlusSettingsDialog(final Activity activity, final TextView subTv) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, (int) (12 * density), pad, (int) (8 * density));
        scrollView.addView(root);

        // 1. 强制平板/大屏宽屏模式
        addSwitchRow(
                activity,
                root,
                "强制解锁平板宽屏与横屏模式",
                "解决小米平板等设备两侧留空问题：强制开启大屏 4 列网格布局、传感器横屏与直播间自动全屏铺满",
                DouyuPlusConfig.KEY_PAD_MODE,
                false
        );

        // 3. 去广告与挂件净化
        addSwitchRow(
                activity,
                root,
                "去除全站广告与直播间挂件",
                "拦截开屏/信息流广告、直播间右下角与全屏右侧活动/语音厅广告、底栏带货与手游入口（保留快捷弹幕/互动/礼物盒）",
                DouyuPlusConfig.KEY_AD_BLOCK,
                true
        );

        // 4. 首页底栏「关注」页优化
        addSwitchRow(
                activity,
                root,
                "底栏「关注」直达【直播中】",
                "将首页底栏第 2 项替换为「关注」并默认定位到【直播中】分栏",
                DouyuPlusConfig.KEY_HOME_FOLLOW_TAB,
                true
        );

        // 5. 全屏弹幕纯净文本化
        addSwitchRow(
                activity,
                root,
                "全屏弹幕纯净模式",
                "去除炫彩弹幕背景气泡、粉丝牌前缀、贵族特效与头尾挂件，恢复清爽纯文本弹幕",
                DouyuPlusConfig.KEY_DANMU_CLEAN,
                true
        );

        // 6. 弹幕零宽字符防重复
        addSwitchRow(
                activity,
                root,
                "弹幕防重复隐形后缀",
                "发送弹幕时自动追加不可见零宽字符，避免相同弹幕被系统吞掉",
                DouyuPlusConfig.KEY_DANMU_SUFFIX,
                true
        );

        // 7. 弹幕 +1 无冷却
        addSwitchRow(
                activity,
                root,
                "解除弹幕 +1 冷却限制",
                "移除直播间弹幕复制/+1 按钮的 60 秒冷却时间",
                DouyuPlusConfig.KEY_ADD_ONE_NO_CD,
                true
        );

        // 8. 自适应画质增强 (高通 SGSR + AMD CAS)
        addSwitchRow(
                activity,
                root,
                "自适应画质增强 (SGSR + CAS)",
                "解锁所有直播间画质增强功能与实时强度调节滑块。低画质放大播放自动启用高通 SGSR 1.0 空间超分辨率重建，2K点对点与缩小播放自动启用 AMD FidelityFX CAS 纯正抗锯齿对比度自适应锐化，彻底告别文字发虚与细节粘连",
                DouyuPlusConfig.KEY_AI_STRENGTHEN,
                true
        );

        // 9. 重复飘屏弹幕合并显示
        addSwitchRow(
                activity,
                root,
                "重复飘屏弹幕合并 (xN)",
                "自动识别并合并屏幕上正在滑过的重复弹幕，后续重复项不重复显示，并在首条弹幕末尾显示连击计数 (xN)，保持直播画面清爽整洁",
                DouyuPlusConfig.KEY_DANMU_MERGE,
                true
        );

        // 10. 自动最高画质切换 (支持 WiFi / 任何网络环境 + 手动切换豁免)
        addAutoQualityRow(activity, root);

        // 11. 去除底栏中间「+」号按钮 (默认去除)
        addSwitchRow(
                activity,
                root,
                "去除底栏中间「+」号",
                "隐藏底部导航栏中间的发布/开播按钮，剩余 4 个底栏项（首页、关注、动态、我的）均匀居中分布（默认开启）",
                DouyuPlusConfig.KEY_HIDE_BOTTOM_PLUS,
                true
        );

        // 11b. 后台省电与统计风控净化
        addSwitchRow(
                activity,
                root,
                "后台省电与遥测统计净化",
                "禁用 Bugly 异常轮询、友盟统计、神策埋点、腾讯神盾风控扫描与后台推送保活守护服务（默认开启）",
                DouyuPlusConfig.KEY_PERFORMANCE,
                true
        );

        // 12. 自定义启动落地页面 (四选一)
        addDefaultStartPageRow(activity, root);

        // 13. 调试模式与一键复制运行日志
        addDebugModeRows(activity, root);

        AlertDialog.Builder builder = new AlertDialog.Builder(
                activity,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        );
        builder.setTitle("斗鱼Plus (DouyuPlus) 设置");
        builder.setView(scrollView);
        builder.setPositiveButton("保存并重启应用生效", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (subTv != null) {
                    subTv.setText(getEntrySubtitleText());
                }
                Toast.makeText(activity, "设置已保存，正在重启斗鱼生效...", Toast.LENGTH_SHORT).show();
                restartApp(activity);
            }
        });
        builder.setNegativeButton("稍后手动重启", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (subTv != null) {
                    subTv.setText(getEntrySubtitleText());
                }
            }
        });
        builder.show();
    }

    private static void addSwitchRow(
            final Context context,
            LinearLayout parent,
            String title,
            String desc,
            final String prefKey,
            boolean defaultVal
    ) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * density), 0, (int) (10 * density));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLp.rightMargin = (int) (12 * density);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.parseColor("#222222"));
        textCol.addView(titleView);

        TextView descView = new TextView(context);
        descView.setText(desc);
        descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        descView.setTextColor(Color.parseColor("#777777"));
        descView.setPadding(0, (int) (3 * density), 0, 0);
        textCol.addView(descView);

        row.addView(textCol, textLp);

        final Switch sw = new Switch(context);
        sw.setChecked(DouyuPlusConfig.isEnabled(context, prefKey, defaultVal));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DouyuPlusConfig.setEnabled(context, prefKey, sw.isChecked());
            }
        });
        row.addView(sw);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newState = !sw.isChecked();
                sw.setChecked(newState);
                DouyuPlusConfig.setEnabled(context, prefKey, newState);
            }
        });

        parent.addView(row);

        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        parent.addView(divider, divLp);
    }

    private static void addAutoQualityRow(final Activity activity, LinearLayout parent) {
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLp.rightMargin = (int) (12 * density);

        TextView titleView = new TextView(activity);
        titleView.setText("自动最高画质切换");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.parseColor("#222222"));
        textCol.addView(titleView);

        TextView descView = new TextView(activity);
        descView.setText("进直播间自动切换最高清晰度 (4K/2K/原画/蓝光/超清)，智能避开钻粉限制。手动选择其他画质时不会强行回切");
        descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        descView.setTextColor(Color.parseColor("#777777"));
        descView.setPadding(0, (int) (3 * density), 0, 0);
        textCol.addView(descView);

        row.addView(textCol, textLp);

        final TextView valView = new TextView(activity);
        valView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valView.setTextColor(Color.parseColor("#FF5D23"));
        valView.setText(getQualityModeText(DouyuPlusConfig.getAutoQualityMode(activity)));
        row.addView(valView);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] items = new String[]{
                        "关闭自动切换",
                        "仅在 WiFi 下生效 (推荐)",
                        "任何网络均生效 (消耗流量)"
                };
                int currentMode = DouyuPlusConfig.getAutoQualityMode(activity);
                new AlertDialog.Builder(activity)
                        .setTitle("选择自动最高画质模式")
                        .setSingleChoiceItems(items, currentMode, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                DouyuPlusConfig.setAutoQualityMode(activity, which);
                                valView.setText(getQualityModeText(which));
                                dialog.dismiss();
                                Toast.makeText(activity, "已设置为: " + items[which], Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        parent.addView(row);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        parent.addView(divider, divLp);
    }

    private static String getQualityModeText(int mode) {
        switch (mode) {
            case DouyuPlusConfig.AUTO_QUALITY_WIFI_ONLY:
                return "仅在 WiFi 下 >";
            case DouyuPlusConfig.AUTO_QUALITY_ALWAYS:
                return "任何时候 >";
            case DouyuPlusConfig.AUTO_QUALITY_OFF:
            default:
                return "已关闭 >";
        }
    }

    private static void addDefaultStartPageRow(final Activity activity, LinearLayout parent) {
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLp.rightMargin = (int) (12 * density);

        TextView titleView = new TextView(activity);
        titleView.setText("启动默认落地页面");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.parseColor("#222222"));
        textCol.addView(titleView);

        TextView descView = new TextView(activity);
        descView.setText("打开斗鱼时直接进入所选的底栏页面（无需手动点击切换）");
        descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        descView.setTextColor(Color.parseColor("#777777"));
        descView.setPadding(0, (int) (3 * density), 0, 0);
        textCol.addView(descView);

        row.addView(textCol, textLp);

        final TextView valView = new TextView(activity);
        valView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valView.setTextColor(Color.parseColor("#FF5D23"));
        valView.setText(getStartPageText(DouyuPlusConfig.getDefaultStartPage(activity)));
        row.addView(valView);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] items = new String[]{
                        "首页 (默认推荐)",
                        "关注 (直达直播中)",
                        "动态",
                        "我的"
                };
                int currentPage = DouyuPlusConfig.getDefaultStartPage(activity);
                new AlertDialog.Builder(activity)
                        .setTitle("选择打开 App 后的默认页面")
                        .setSingleChoiceItems(items, currentPage, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                DouyuPlusConfig.setDefaultStartPage(activity, which);
                                valView.setText(getStartPageText(which));
                                dialog.dismiss();
                                Toast.makeText(activity, "已设置为: " + items[which], Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        parent.addView(row);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        parent.addView(divider, divLp);
    }

    private static String getStartPageText(int page) {
        switch (page) {
            case DouyuPlusConfig.START_PAGE_FOLLOW:
                return "关注 >";
            case DouyuPlusConfig.START_PAGE_DYNAMIC:
                return "动态 >";
            case DouyuPlusConfig.START_PAGE_MINE:
                return "我的 >";
            case DouyuPlusConfig.START_PAGE_HOME:
            default:
                return "首页 >";
        }
    }

    private static void addDebugModeRows(final Activity activity, LinearLayout parent) {
        float density = activity.getResources().getDisplayMetrics().density;

        // 13a. 调试模式开关
        LinearLayout switchRow = new LinearLayout(activity);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, (int) (10 * density), 0, (int) (10 * density));

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLp.rightMargin = (int) (12 * density);

        TextView titleView = new TextView(activity);
        titleView.setText("开启调试模式");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.parseColor("#222222"));
        textCol.addView(titleView);

        TextView descView = new TextView(activity);
        descView.setText("在内存中环形记录最近 600 条模块日志与异常信息（零磁盘写入，默认关闭）");
        descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        descView.setTextColor(Color.parseColor("#777777"));
        descView.setPadding(0, (int) (3 * density), 0, 0);
        textCol.addView(descView);

        switchRow.addView(textCol, textLp);

        final Switch sw = new Switch(activity);
        sw.setChecked(DouyuPlusConfig.isEnabled(activity, DouyuPlusConfig.KEY_DEBUG_MODE, false));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = sw.isChecked();
                DouyuPlusConfig.setEnabled(activity, DouyuPlusConfig.KEY_DEBUG_MODE, enabled);
                if (enabled) {
                    TestRunner.startLogCollectorIfNeeded();
                }
            }
        });
        switchRow.addView(sw);

        switchRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newState = !sw.isChecked();
                sw.setChecked(newState);
                DouyuPlusConfig.setEnabled(activity, DouyuPlusConfig.KEY_DEBUG_MODE, newState);
                if (newState) {
                    TestRunner.startLogCollectorIfNeeded();
                }
            }
        });

        parent.addView(switchRow);

        View divider1 = new View(activity);
        divider1.setBackgroundColor(Color.parseColor("#EEEEEE"));
        parent.addView(divider1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // 13b. 一键复制调试日志到剪贴板
        LinearLayout copyRow = new LinearLayout(activity);
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        copyRow.setGravity(Gravity.CENTER_VERTICAL);
        copyRow.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
        copyRow.setClickable(true);
        copyRow.setFocusable(true);

        LinearLayout copyTextCol = new LinearLayout(activity);
        copyTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyTextLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        copyTextLp.rightMargin = (int) (12 * density);

        TextView copyTitle = new TextView(activity);
        copyTitle.setText("导出调试日志");
        copyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        copyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        copyTitle.setTextColor(Color.parseColor("#222222"));
        copyTextCol.addView(copyTitle);

        TextView copyDesc = new TextView(activity);
        copyDesc.setText("将当前设备信息、功能开关状态及捕获到的模块运行日志复制到剪贴板");
        copyDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        copyDesc.setTextColor(Color.parseColor("#777777"));
        copyDesc.setPadding(0, (int) (3 * density), 0, 0);
        copyTextCol.addView(copyDesc);

        copyRow.addView(copyTextCol, copyTextLp);

        TextView actionTv = new TextView(activity);
        actionTv.setText("复制到剪贴板 >");
        actionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        actionTv.setTextColor(Color.parseColor("#FF5D23"));
        copyRow.addView(actionTv);

        copyRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TestRunner.copyDebugLogsToClipboard(activity);
            }
        });

        parent.addView(copyRow);
    }

    private static void restartApp(Activity activity) {
        try {
            android.content.Intent intent = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.startActivity(intent);
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {}
    }
}
