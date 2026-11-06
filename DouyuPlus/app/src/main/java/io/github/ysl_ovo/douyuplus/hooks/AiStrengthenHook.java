package io.github.ysl_ovo.douyuplus.hooks;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;

/**
 * 画质增强全自动自适应超分辨率与锐化架构 (SGSR 1.0 + AMD CAS 自适应)
 * 1. 突破房间白名单限制 (RoomRtmpInfo.isStrongerOpen)；
 * 2. 突破机型黑名单与设备支持锁定 (PlayDotConfig / AiStrengthenPlayManager / AiStrengthenNeuron)；
 * 3. 拦截 onVideoEnhanceError 阻断设备不支持标记；
 * 4. 优化清晰度弹窗文字提示为「画质增强 (自适应 SGSR / CAS)」；
 * 5. 动态在开关正下方注入【画质增强强度滑块 (0% ~ 100%)】，支持即拖即生效与本地持久化记忆；
 * 6. 在底层渲染器 DYEnhanceRender 与 DYPlayerRenderImpl 中，自动激活自适应渲染管线并同步用户强度。
 */
public class AiStrengthenHook {
    private static final String TAG = "DouyuPlus_SGSR";
    private static final String SLIDER_TAG = "douyuplus_enhance_slider_tag";
    private static final String SUB_TV_TAG = "douyuplus_sub_tv";

    // 运行时 libdyplayer.so 内存补丁状态 (true=已修补/底包自带修补, false=原版未修补需回退 0~100 范围)
    private static volatile boolean sSoPatchChecked = false;
    private static volatile boolean sSoPatchedSuccess = false;

    // 标记用户是否在当前会话中主动手动关闭了增强
    private static volatile boolean sUserManuallyDisabled = false;
    // 缓存当前底层激活的 DYEnhanceRender 实例弱引用
    private static volatile WeakReference<Object> sCurrentEnhanceRender = null;
    // 缓存当前底层激活的 DYPlayerRenderImpl 实例弱引用
    private static volatile WeakReference<Object> sCurrentPlayerRender = null;
    // 缓存清晰度面板中的滑块容器 View 弱引用，用于联动显隐
    private static volatile WeakReference<View> sSliderContainerRef = null;
    // 记录底层当前视频分辨率，用于在界面实时展示正在生效的算法 (SGSR 1.0 还是 AMD CAS)
    public static volatile int sCurrentVideoWidth = 0;
    public static volatile int sCurrentVideoHeight = 0;

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!DouyuPlusConfig.isAiStrengthenEnabled()) {
            return;
        }

        ClassLoader cl = lpparam.classLoader;
        Class<?> roomRtmpInfoCls = null;

        // 1. 突破房间白名单限制：所有直播间强制开放画质增强
        try {
            roomRtmpInfoCls = XposedHelpers.findClass(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.RoomRtmpInfo", cl);
            XposedHelpers.findAndHookMethod(roomRtmpInfoCls, "isStrongerOpen", XC_MethodReplacement.returnConstant(true));
            Log.i(TAG, "✓ Hooked RoomRtmpInfo: force isStrongerOpen = true");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RoomRtmpInfo.isStrongerOpen", t);
        }

        // 2. 绕过机型黑名单判断
        try {
            Class<?> blackListCls = XposedHelpers.findClass(
                    "com.douyu.sdk.liveshell.init.PlayDotConfig$StrongStreamerBlackList", cl);
            XposedHelpers.findAndHookMethod(blackListCls, "d", XC_MethodReplacement.returnConstant(false));
            Log.i(TAG, "✓ Hooked PlayDotConfig.StrongStreamerBlackList: bypassed blacklist");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook PlayDotConfig.StrongStreamerBlackList", t);
        }

        // 3. 解锁 AiStrengthenPlayManager：设备支持判断、初始开启状态与错误阻断
        try {
            Class<?> playMgrCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.aistrengthen.papi.AiStrengthenPlayManager", cl);

            // a() -> 是否支持设备，强制 true
            XposedHelpers.findAndHookMethod(playMgrCls, "a", XC_MethodReplacement.returnConstant(true));

            // d() -> 黑名单检查，强制 false
            XposedHelpers.findAndHookMethod(playMgrCls, "d", XC_MethodReplacement.returnConstant(false));

            // h() -> 初始化本地状态时，默认将 this.b 设置为 true (开启)
            XposedHelpers.findAndHookMethod(playMgrCls, "h", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!sUserManuallyDisabled) {
                        XposedHelpers.setBooleanField(param.thisObject, "b", true);
                    }
                    XposedHelpers.setBooleanField(param.thisObject, "e", true);
                }
            });

            // g(RoomRtmpInfo) -> 初始化房间参数时，确保 this.d = true (房间支持)
            if (roomRtmpInfoCls != null) {
                XposedHelpers.findAndHookMethod(playMgrCls, "g", roomRtmpInfoCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedHelpers.setBooleanField(param.thisObject, "d", true);
                        XposedHelpers.setBooleanField(param.thisObject, "e", true);
                        if (!sUserManuallyDisabled) {
                            XposedHelpers.setBooleanField(param.thisObject, "b", true);
                            try {
                                XposedHelpers.callMethod(param.thisObject, "m", true);
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            }

            // o(boolean, boolean) -> 用户手动切换开关时记录状态，并联动更新 SeekBar 显隐与底层渲染管线
            XposedHelpers.findAndHookMethod(playMgrCls, "o", boolean.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final boolean isOpen = (Boolean) param.args[0];
                    sUserManuallyDisabled = !isOpen;
                    Log.i(TAG, "User toggled video enhance switch: " + isOpen);

                    // 1. 直接同步通知底层播放器激活或关闭增强管线
                    if (sCurrentPlayerRender != null) {
                        Object player = sCurrentPlayerRender.get();
                        if (player != null) {
                            try {
                                XposedHelpers.callMethod(player, "setEnableVideoEnhance", isOpen);
                                Log.i(TAG, "Directly synchronized DYPlayerRenderImpl.setEnableVideoEnhance(" + isOpen + ")");
                            } catch (Throwable t) {
                                Log.w(TAG, "Failed to sync setEnableVideoEnhance to DYPlayerRenderImpl", t);
                            }
                        }
                    }

                    // 2. 联动更新清晰度抽屉中 SeekBar 容器的显隐
                    if (sSliderContainerRef != null) {
                        final View slider = sSliderContainerRef.get();
                        if (slider != null) {
                            slider.post(new Runnable() {
                                @Override
                                public void run() {
                                    slider.setVisibility(isOpen ? View.VISIBLE : View.GONE);
                                }
                            });
                        }
                    }
                }
            });

            // k() -> onVideoEnhanceError，阻断防止标记设备不支持
            XposedHelpers.findAndHookMethod(playMgrCls, "k", XC_MethodReplacement.DO_NOTHING);

            Log.i(TAG, "✓ Hooked AiStrengthenPlayManager: support=true, blacklist=false, default=true");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook AiStrengthenPlayManager", t);
        }

        // 4. 解锁 AiStrengthenNeuron 与清晰度面板 UI：入口注入与 SeekBar 挂载
        try {
            Class<?> neuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.aistrengthen.AiStrengthenNeuron", cl);

            // GE() -> 机型是否支持，强制 true
            XposedHelpers.findAndHookMethod(neuronCls, "GE", XC_MethodReplacement.returnConstant(true));

            // lC(RoomRtmpInfo) -> 确保 this.j = true (房间支持画质增强入口)
            if (roomRtmpInfoCls != null) {
                XposedHelpers.findAndHookMethod(neuronCls, "lC", roomRtmpInfoCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedHelpers.setBooleanField(param.thisObject, "j", true);
                    }
                });
            }

            // Aw(ViewStub) -> 在清晰度弹窗中挂载开关
            XposedHelpers.findAndHookMethod(neuronCls, "Aw", ViewStub.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedHelpers.setBooleanField(param.thisObject, "j", true);
                    ViewStub stub = (ViewStub) param.args[0];
                    if (stub != null && stub.getParent() instanceof ViewGroup) {
                        param.setObjectExtra("stub_parent", stub.getParent());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    ViewGroup parent = (ViewGroup) param.getObjectExtra("stub_parent");
                    if (parent != null) {
                        View switchRow = parent.findViewById(2131368447); // 0x7f0a19ff (R.id.e62)
                        if (switchRow == null) {
                            switchRow = parent.findViewById(2131382711); // 0x7f0a51b7 (R.id.oje)
                        }
                        if (switchRow != null) {
                            setupEnhanceSlider(switchRow);
                        }
                    }
                }
            });

            // qu(View) -> 弹窗刷新时挂载/同步 SeekBar
            XposedHelpers.findAndHookMethod(neuronCls, "qu", View.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View switchRow = (View) param.args[0];
                    if (switchRow != null) {
                        setupEnhanceSlider(switchRow);
                    }
                }
            });

            Log.i(TAG, "✓ Hooked AiStrengthenNeuron: GE=true, lC.j=true, Aw/qu customized with SeekBar");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook AiStrengthenNeuron", t);
        }

        // 4.1 直接 Hook RamboDefinitionView2.f(boolean)：确保清晰度抽屉展开时 SeekBar 必现
        try {
            Class<?> ramboDefCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.rambodefinition.RamboDefinitionView2", cl);
            XposedHelpers.findAndHookMethod(ramboDefCls, "f", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof View) {
                        View defView = (View) param.thisObject;
                        View switchRow = defView.findViewById(2131368447); // 0x7f0a19ff (R.id.e62)
                        if (switchRow == null) {
                            switchRow = defView.findViewById(2131382711); // 0x7f0a51b7 (R.id.oje)
                        }
                        if (switchRow != null) {
                            setupEnhanceSlider(switchRow);
                        }
                    }
                }
            });
            Log.i(TAG, "✓ Hooked RamboDefinitionView2.f: guaranteed SeekBar injection");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RamboDefinitionView2.f", t);
        }

        // 5. 底层渲染层直通与参数即时联动 (突破 2K 分辨率帧阻断与参数锁)
        try {
            // 5.0 拦截 BaseDexClassLoader.findLibrary("dyplayer")：
            // 自动检测底包 libdyplayer.so 是否已包含 SGSR+CAS 补丁；若为未修补官方原包 (LSPosed 模式)，自动生成修补版 SO 供加载
            try {
                XposedHelpers.findAndHookMethod(
                        dalvik.system.BaseDexClassLoader.class,
                        "findLibrary",
                        String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if ("dyplayer".equals(param.args[0])) {
                                    String origPath = (String) param.getResult();
                                    String patchedPath = preparePatchedLibDyPlayer(origPath);
                                    if (patchedPath != null && !patchedPath.equals(origPath)) {
                                        param.setResult(patchedPath);
                                    }
                                }
                            }
                        }
                );
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook BaseDexClassLoader.findLibrary", t);
            }

            // 5.1 Hook com.dy.rtc.VideoFrame.getEnhandceEnable()
            // 斗鱼在 2K 直播时输出的 VideoFrame 其 m_EnhanceEnable 恒为 false，导致 EglRenderer 在第 530 行
            // 直接 bypass enhanceRender，走普通 frameDrawer 绘制！此处强制放行，解除 2K 帧拦截！
            try {
                Class<?> videoFrameCls = XposedHelpers.findClass("com.dy.rtc.VideoFrame", cl);
                XposedHelpers.findAndHookMethod(videoFrameCls, "getEnhandceEnable", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sUserManuallyDisabled && DouyuPlusConfig.isAiStrengthenEnabled()) {
                            param.setResult(true);
                        } else {
                            param.setResult(false);
                        }
                    }
                });
                Log.i(TAG, "✓ Hooked VideoFrame.getEnhandceEnable: unblocked 2K frames for enhanceRender");
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook VideoFrame.getEnhandceEnable", t);
            }

            // 5.2 Hook DYEnhanceRender (SGSR 1.0 + CAS 自适应底层渲染器)
            Class<?> enhanceRenderCls = XposedHelpers.findClass(
                    "com.douyu.lib.player.render.DYEnhanceRender", cl);

            // init 时记录引用并自动下发用户保存的增强强度
            XposedHelpers.findAndHookMethod(enhanceRenderCls, "init", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sCurrentEnhanceRender = new WeakReference<>(param.thisObject);
                    int level = getEffectiveEnhanceLevel(null);
                    try {
                        XposedHelpers.callMethod(param.thisObject, "setVideoEnhanceLevel", level);
                        Log.i(TAG, "DYEnhanceRender.init -> applied videoEnhanceLevel: " + level);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to setVideoEnhanceLevel on init", t);
                    }
                }
            });

            // 拦截 setVideoEnhanceLevel：防止 EglRenderer.init 硬编码强制覆盖为 100，确保用户调节的滑块数值生效
            XposedHelpers.findAndHookMethod(enhanceRenderCls, "setVideoEnhanceLevel", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    sCurrentEnhanceRender = new WeakReference<>(param.thisObject);
                    int requestedLevel = (Integer) param.args[0];
                    int userLevel = getEffectiveEnhanceLevel(null);
                    if (requestedLevel == 100 && userLevel != 100) {
                        param.args[0] = userLevel;
                        Log.i(TAG, "Overrode hardcoded setVideoEnhanceLevel(100) -> " + userLevel);
                    }
                }
            });

            // 监控底层渲染帧调用频率，验证着色器是否持续生效
            XposedHelpers.findAndHookMethod(enhanceRenderCls, "render", int.class, int.class, int.class, new XC_MethodHook() {
                private long renderCount = 0;
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    sCurrentEnhanceRender = new WeakReference<>(param.thisObject);
                    try {
                        sCurrentVideoWidth = (Integer) param.args[1];
                        sCurrentVideoHeight = (Integer) param.args[2];
                    } catch (Throwable ignored) {}
                    renderCount++;
                    if (renderCount % 300 == 1) {
                        Log.i(TAG, "DYEnhanceRender.render ACTIVE (Frame #" + renderCount + ", tex=" + param.args[0] + ", res=" + param.args[1] + "x" + param.args[2] + ")");
                    }
                }
            });

            // 5.3 Hook DYPlayerRenderImpl：首帧自动激活渲染管线 (兼容 onFrame 与 2K YUV 帧 onYuvFrame)
            Class<?> renderImplCls = XposedHelpers.findClass(
                    "com.douyu.lib.player.render.DYPlayerRenderImpl", cl);

            XposedHelpers.findAndHookMethod(renderImplCls, "setEnableVideoEnhance", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    sCurrentPlayerRender = new WeakReference<>(param.thisObject);
                    boolean enable = (Boolean) param.args[0];
                    // 斗鱼官方逻辑在 2K 或切流时若试图关闭画质增强，强制纠正为 true
                    if (!sUserManuallyDisabled && DouyuPlusConfig.isAiStrengthenEnabled() && !enable) {
                        param.args[0] = true;
                        enable = true;
                        Log.i(TAG, "DYPlayerRenderImpl.setEnableVideoEnhance(false) overridden -> true");
                    }
                    Log.i(TAG, "DYPlayerRenderImpl.setEnableVideoEnhance called: " + enable);
                }
            });

            XC_MethodHook frameArrivalHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sCurrentPlayerRender = new WeakReference<>(param.thisObject);
                    if (sUserManuallyDisabled || !DouyuPlusConfig.isAiStrengthenEnabled()) {
                        return;
                    }
                    Object enhanceRender = XposedHelpers.getObjectField(param.thisObject, "m_enhanceRender");
                    if (enhanceRender == null) {
                        Log.i(TAG, ">>> Auto-activating SGSR/CAS enhance pipeline on " + param.method.getName() + " arrival! <<<");
                        XposedHelpers.callMethod(param.thisObject, "setEnableVideoEnhance", true);
                    }
                }
            };

            boolean hookedAny = false;
            for (java.lang.reflect.Method m : renderImplCls.getDeclaredMethods()) {
                if (("onFrame".equals(m.getName()) || "onYuvFrame".equals(m.getName())) && m.getParameterTypes().length == 1) {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, frameArrivalHook);
                    Log.i(TAG, "✓ Hooked DYPlayerRenderImpl." + m.getName() + " for enhance auto-activation!");
                    hookedAny = true;
                }
            }

            if (!hookedAny) {
                Log.w(TAG, "Could not find onFrame or onYuvFrame method in DYPlayerRenderImpl");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DYPlayerRenderImpl / DYEnhanceRender", t);
        }
    }

    /**
     * 在清晰度弹窗中定制「画质增强 (自适应 SGSR/CAS)」并挂载 SeekBar 调节滑块
     */
    private static void setupEnhanceSlider(View switchRow) {
        if (switchRow == null) return;
        try {
            if (switchRow.getId() == 2131382711 && switchRow.getParent() instanceof View) {
                switchRow = (View) switchRow.getParent();
            }

            View switchBtn = switchRow.findViewById(2131382711); // 0x7f0a51b7 (R.id.oje)
            final Context context = switchRow.getContext();
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            int maxScreenDim = Math.max(screenWidth, screenHeight);

            String modeTitle = "画质增强 (自适应 SGSR/CAS)";
            String defaultSubDesc = "双引擎独立调控: ①高通SGSR空间平滑超分 + ②AMD CAS轮廓锐化 (并联实时生效)";
            String resInfo = defaultSubDesc;

            if (sCurrentVideoWidth > 0) {
                boolean isUpscaling = sCurrentVideoWidth < (maxScreenDim * 0.95f);
                if (isUpscaling) {
                    modeTitle = "画质增强 (高通 SGSR 1.0)";
                    resInfo = "当前源: " + sCurrentVideoWidth + "p 放大超分 | " + defaultSubDesc;
                } else {
                    modeTitle = "画质增强 (AMD CAS 锐化)";
                    resInfo = "当前源: " + sCurrentVideoWidth + "p 点对点锐化 | " + defaultSubDesc;
                }
            }

            TextView titleTv = switchRow.findViewById(2131382712); // 0x7f0a51b8 (R.id.ojf)
            if (titleTv != null) {
                titleTv.setText(modeTitle);
            }

            if (switchBtn == null || !(switchRow.getParent() instanceof ViewGroup)) {
                return;
            }

            final ViewGroup parentGroup = (ViewGroup) switchRow.getParent();

            // 避免重复注入滑块
            View existingSlider = parentGroup.findViewWithTag(SLIDER_TAG);
            if (existingSlider == null && switchRow.getParent() instanceof View) {
                View grandParent = (View) switchRow.getParent();
                existingSlider = grandParent.findViewWithTag(SLIDER_TAG);
            }

            boolean isChecked = true;
            try {
                isChecked = (Boolean) XposedHelpers.callMethod(switchBtn, "isChecked");
            } catch (Throwable t) {
                TextView statusTv = switchRow.findViewById(2131382713);
                if (statusTv != null && "关闭".equals(statusTv.getText().toString())) {
                    isChecked = false;
                }
            }

            if (existingSlider != null) {
                existingSlider.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                TextView existingSub = existingSlider.findViewWithTag(SUB_TV_TAG);
                if (existingSub != null) {
                    existingSub.setText(resInfo);
                }
                sSliderContainerRef = new WeakReference<>(existingSlider);
                return;
            }

            // 动态构建双引擎独立调节 SeekBar 容器 (SGSR 1.0 + AMD CAS 协同双开)
            int padH = dp2px(context, 16);
            int padTop = dp2px(context, 4);
            int padBottom = dp2px(context, 10);

            LinearLayout sliderContainer = new LinearLayout(context);
            sliderContainer.setTag(SLIDER_TAG);
            sliderContainer.setOrientation(LinearLayout.VERTICAL);
            sliderContainer.setPadding(padH, padTop, padH, padBottom);

            // ==================== 滑块 1: 高通 SGSR 1.0 超分平滑 ====================
            LinearLayout textRow1 = new LinearLayout(context);
            textRow1.setOrientation(LinearLayout.HORIZONTAL);
            textRow1.setGravity(Gravity.CENTER_VERTICAL);
            textRow1.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView label1 = new TextView(context);
            label1.setText("高通 SGSR 1.0 (超分平滑去噪)");
            label1.setTextColor(0xFFCCCCCC);
            label1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            textRow1.addView(label1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            final TextView val1 = new TextView(context);
            int currentSgsr = DouyuPlusConfig.getSgsrLevel(context);
            val1.setText(currentSgsr + "%");
            val1.setTextColor(0xFFFF5D23); // 斗鱼标志橙色
            val1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            val1.setTypeface(Typeface.DEFAULT_BOLD);
            textRow1.addView(val1);
            sliderContainer.addView(textRow1);

            SeekBar seekBar1 = new SeekBar(context);
            seekBar1.setMax(100);
            seekBar1.setProgress(currentSgsr);
            try {
                ColorStateList csl = ColorStateList.valueOf(0xFFFF5D23);
                seekBar1.setProgressTintList(csl);
                seekBar1.setThumbTintList(csl);
            } catch (Throwable ignored) {}
            LinearLayout.LayoutParams seekLp1 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            seekLp1.topMargin = dp2px(context, 2);
            seekLp1.bottomMargin = dp2px(context, 8);
            sliderContainer.addView(seekBar1, seekLp1);

            // ==================== 滑块 2: AMD FidelityFX CAS 轮廓锐化 ====================
            LinearLayout textRow2 = new LinearLayout(context);
            textRow2.setOrientation(LinearLayout.HORIZONTAL);
            textRow2.setGravity(Gravity.CENTER_VERTICAL);
            textRow2.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView label2 = new TextView(context);
            label2.setText("AMD CAS (纯正对比度锐化)");
            label2.setTextColor(0xFFCCCCCC);
            label2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            textRow2.addView(label2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            final TextView val2 = new TextView(context);
            int currentCas = DouyuPlusConfig.getCasLevel(context);
            val2.setText(currentCas + "%");
            val2.setTextColor(0xFFFF5D23);
            val2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            val2.setTypeface(Typeface.DEFAULT_BOLD);
            textRow2.addView(val2);
            sliderContainer.addView(textRow2);

            SeekBar seekBar2 = new SeekBar(context);
            seekBar2.setMax(100);
            seekBar2.setProgress(currentCas);
            try {
                ColorStateList csl = ColorStateList.valueOf(0xFFFF5D23);
                seekBar2.setProgressTintList(csl);
                seekBar2.setThumbTintList(csl);
            } catch (Throwable ignored) {}
            LinearLayout.LayoutParams seekLp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            seekLp2.topMargin = dp2px(context, 2);
            sliderContainer.addView(seekBar2, seekLp2);

            // 双滑块联动下发事件
            seekBar1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    val1.setText(progress + "%");
                    if (fromUser) {
                        DouyuPlusConfig.setSgsrLevel(sb.getContext(), progress);
                        applyVideoEnhanceLevel(getEffectiveEnhanceLevel(sb.getContext()));
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar sb) {}
                @Override
                public void onStopTrackingTouch(SeekBar sb) {}
            });

            seekBar2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    val2.setText(progress + "%");
                    if (fromUser) {
                        DouyuPlusConfig.setCasLevel(sb.getContext(), progress);
                        applyVideoEnhanceLevel(getEffectiveEnhanceLevel(sb.getContext()));
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar sb) {}
                @Override
                public void onStopTrackingTouch(SeekBar sb) {}
            });

            // 状态提示行
            TextView subTv = new TextView(context);
            subTv.setTag(SUB_TV_TAG);
            subTv.setText(resInfo);
            subTv.setTextColor(0xFF888888);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            subTv.setPadding(0, dp2px(context, 4), 0, 0);
            sliderContainer.addView(subTv);

            // 初始显隐与开关保持一致
            sliderContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            sSliderContainerRef = new WeakReference<View>(sliderContainer);

            // 将 sliderContainer 注入到父容器中，放置在 switchRow 正下方
            if (parentGroup instanceof LinearLayout &&
                    ((LinearLayout) parentGroup).getOrientation() == LinearLayout.VERTICAL) {
                int idx = parentGroup.indexOfChild(switchRow);
                parentGroup.addView(sliderContainer, idx + 1);
            } else {
                // 通用容器包裹机制：将 switchRow 与 sliderContainer 合并为一个垂直容器
                int idx = parentGroup.indexOfChild(switchRow);
                ViewGroup.LayoutParams origLp = switchRow.getLayoutParams();
                LinearLayout wrapper = new LinearLayout(context);
                wrapper.setOrientation(LinearLayout.VERTICAL);
                wrapper.setLayoutParams(origLp);
                parentGroup.removeViewAt(idx);
                wrapper.addView(switchRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                wrapper.addView(sliderContainer, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                parentGroup.addView(wrapper, idx);
            }

            Log.i(TAG, "✓ Successfully injected video enhance level SeekBar into definition panel!");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to customize definition panel UI in setupEnhanceSlider", t);
        }
    }

    /**
     * 实时应用画质增强强度到底层 DYEnhanceRender
     */
    private static void applyVideoEnhanceLevel(int level) {
        Object render = null;
        if (sCurrentEnhanceRender != null) {
            render = sCurrentEnhanceRender.get();
        }
        if (render == null && sCurrentPlayerRender != null) {
            Object player = sCurrentPlayerRender.get();
            if (player != null) {
                try {
                    render = XposedHelpers.getObjectField(player, "m_enhanceRender");
                } catch (Throwable ignored) {}
            }
        }
        if (render != null) {
            try {
                XposedHelpers.callMethod(render, "setVideoEnhanceLevel", level);
                Log.i(TAG, "Realtime applied videoEnhanceLevel: " + level);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to call setVideoEnhanceLevel realtime", t);
            }
        } else {
            Log.i(TAG, "Saved videoEnhanceLevel: " + level + " (render not yet initialized, will apply on init)");
        }
    }

    private static int getEffectiveEnhanceLevel(Context context) {
        if (!sSoPatchChecked) {
            preparePatchedLibDyPlayer(null);
        }
        if (sSoPatchedSuccess) {
            return DouyuPlusConfig.getPackedEnhanceLevel(context);
        }
        // 若确实未修补成功，回退至 0~100 范围防止被原版 b.hi 丢弃
        return Math.min(100, Math.max(0, Math.max(DouyuPlusConfig.getSgsrLevel(context), DouyuPlusConfig.getCasLevel(context))));
    }

    private static final String SGSR_CAS_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform sampler2D tex_y;\n" +
            "uniform float blueLine;\n" +
            "uniform highp float enhanceRatio;\n" +
            "in highp vec2 textureCoordinate;\n" +
            "out vec4 fragColor;\n" +
            "float fL(float x){float a=x-4.0;return(x*a-a)*a*a;}\n" +
            "vec2 wY(float x,float y,float c,float s){return vec2(fL((x*x+y*y)*0.55+clamp(abs(c)*s,0.0,1.0)))*vec2(1.0,c);}\n" +
            "vec4 gG(sampler2D s,highp vec2 c){return vec4(textureLodOffset(s,c,0.0,ivec2(0,1)).g,textureLodOffset(s,c,0.0,ivec2(1,1)).g,textureLodOffset(s,c,0.0,ivec2(1,0)).g,textureLodOffset(s,c,0.0,ivec2(0,0)).g);}\n" +
            "void main(){\n" +
            "vec3 org=textureLod(tex_y,textureCoordinate,0.0).rgb;\n" +
            "vec2 sz=vec2(textureSize(tex_y,0));\n" +
            "float sR=enhanceRatio,cR=enhanceRatio;\n" +
            "int p=int(enhanceRatio*100.0+0.5);\n" +
            "if(p>=100000){\n" +
            "p-=100000;\n" +
            "sR=float(p/1000)*0.01;\n" +
            "cR=float(p%1000)*0.01;\n" +
            "}\n" +
            "vec3 dS=vec3(0.0);\n" +
            "if(sR>0.005){\n" +
            "vec4 v=vec4(1.0/sz.x,1.0/sz.y,sz.x,sz.y);\n" +
            "highp vec2 ic=(textureCoordinate*v.zw)+vec2(-0.5,0.5);\n" +
            "highp vec2 ip=floor(ic);\n" +
            "highp vec2 cd=ip*v.xy;\n" +
            "vec2 pl=ic-ip;\n" +
            "vec4 L=gG(tex_y,cd);\n" +
            "float eV=abs(L.z-L.y)+abs(org.g-L.y)+abs(org.g-L.z);\n" +
            "if(eV>(4.0/255.0)){\n" +
            "vec4 R=gG(tex_y,cd+vec2(v.x*2.0,0.0));\n" +
            "vec4 U=gG(tex_y,cd+vec2(v.x,-v.y));\n" +
            "vec4 D=gG(tex_y,cd+vec2(v.x,v.y));\n" +
            "vec4 UD=vec4(U.w,U.z,D.y,D.x);\n" +
            "float m=(L.y+L.z+R.x+R.w)*0.25;\n" +
            "L-=vec4(m);R-=vec4(m);UD-=vec4(m);\n" +
            "float sum=abs(L.x)+abs(L.y)+abs(L.z)+abs(L.w)+abs(R.x)+abs(R.y)+abs(R.z)+abs(R.w)+abs(UD.x)+abs(UD.y)+abs(UD.z)+abs(UD.w);\n" +
            "float s=2.181818/max(sum,1e-5);\n" +
            "vec2 W=wY(pl.x,pl.y+1.0,UD.x,s)+wY(pl.x-1.0,pl.y+1.0,UD.y,s)+wY(pl.x-1.0,pl.y-2.0,UD.z,s)+wY(pl.x,pl.y-2.0,UD.w,s)\n" +
            "+wY(pl.x+1.0,pl.y-1.0,L.x,s)+wY(pl.x,pl.y-1.0,L.y,s)+wY(pl.x,pl.y,L.z,s)+wY(pl.x+1.0,pl.y,L.w,s)\n" +
            "+wY(pl.x-1.0,pl.y-1.0,R.x,s)+wY(pl.x-2.0,pl.y-1.0,R.y,s)+wY(pl.x-2.0,pl.y,R.z,s)+wY(pl.x-1.0,pl.y,R.w,s);\n" +
            "float fY=clamp(mix(1.0,2.0,sR)*(W.y/max(W.x,1e-5)),min(min(L.y,L.z),min(R.x,R.w)),max(max(L.y,L.z),max(R.x,R.w)));\n" +
            "dS=vec3(clamp(fY-(org.g-m),-0.25*sR,0.25*sR));\n" +
            "}\n" +
            "}\n" +
            "vec3 dC=vec3(0.0);\n" +
            "if(cR>0.005){\n" +
            "vec3 a=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2(-1,-1)).rgb;\n" +
            "vec3 b=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2( 0,-1)).rgb;\n" +
            "vec3 c=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2( 1,-1)).rgb;\n" +
            "vec3 d=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2(-1, 0)).rgb;\n" +
            "vec3 f=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2( 1, 0)).rgb;\n" +
            "vec3 g=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2(-1, 1)).rgb;\n" +
            "vec3 h=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2( 0, 1)).rgb;\n" +
            "vec3 i=textureLodOffset(tex_y,textureCoordinate,0.0,ivec2( 1, 1)).rgb;\n" +
            "vec3 mn1=min(min(min(d,org),min(f,b)),h);\n" +
            "vec3 mx1=max(max(max(d,org),max(f,b)),h);\n" +
            "vec3 mn=mn1+min(min(min(mn1,a),c),min(g,i));\n" +
            "vec3 mx=mx1+max(max(max(mx1,a),c),max(g,i));\n" +
            "vec3 amp=sqrt(clamp(min(mn,2.0-mx)/max(mx,vec3(1e-4)),0.0,1.0));\n" +
            "vec3 w=amp*(-1.0/mix(8.0,5.0,cR));\n" +
            "vec3 cRes=clamp(((b+d+f+h)*w+org)/max(vec3(1.0)+4.0*w,vec3(0.15)),0.0,1.0);\n" +
            "dC=cRes-org;\n" +
            "}\n" +
            "vec3 res=clamp(org+dS+dC,0.0,1.0);\n" +
            "if(blueLine>=1.0){\n" +
            "fragColor=vec4(res,1.0);\n" +
            "}else{\n" +
            "if(textureCoordinate.x>blueLine){\n" +
            "fragColor=vec4(org,1.0);\n" +
            "}else if(textureCoordinate.x-blueLine>-0.002){\n" +
            "fragColor=vec4(mix(res,vec3(0.0,1.0,0.0),0.8),1.0);\n" +
            "}else{\n" +
            "fragColor=vec4(res,1.0);\n" +
            "}\n" +
            "}\n" +
            "}";

    /**
     * 纯 Xposed/LSPosed 模式动态修补加载 libdyplayer.so（无需离线修改 APK 内置 SO，LSPosed 与 NPatch 一套通用）
     */
    private static synchronized String preparePatchedLibDyPlayer(String origPath) {
        try {
            android.app.Application app = (android.app.Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) return origPath;

            long apkStamp = 0L;
            try {
                String sourceDir = app.getApplicationInfo().sourceDir;
                if (sourceDir != null) {
                    java.io.File apkFile = new java.io.File(sourceDir);
                    apkStamp = (apkFile.lastModified() / 1000L) ^ apkFile.length();
                }
            } catch (Throwable ignored) {}
            String shaderHash = Integer.toHexString(SGSR_CAS_SHADER.hashCode());
            String targetFileName = "libdyplayer_sgsr_" + Long.toHexString(apkStamp) + "_" + shaderHash + ".so";

            java.io.File cacheDir = app.getCodeCacheDir();
            java.io.File outFile = new java.io.File(cacheDir, targetFileName);
            if (outFile.exists() && outFile.length() > 0x11a100) {
                sSoPatchChecked = true;
                sSoPatchedSuccess = true;
                return outFile.getAbsolutePath();
            }

            // 清理历史版本的旧修补缓存，防止占用空间或误用旧 SO
            java.io.File[] oldFiles = cacheDir.listFiles();
            if (oldFiles != null) {
                for (java.io.File f : oldFiles) {
                    String name = f.getName();
                    if (name.startsWith("libdyplayer_sgsr_") && name.endsWith(".so") && !name.equals(targetFileName)) {
                        try {
                            f.setWritable(true);
                            f.delete();
                        } catch (Throwable ignored) {}
                    }
                }
            }

            byte[] rawSo = readLibDyPlayerBytes(origPath, app);
            if (rawSo == null || rawSo.length < 0x11a100) {
                return origPath;
            }

            sSoPatchChecked = true;
            boolean isValidTarget =
                    (rawSo[0x55180] == 0x00 && rawSo[0x55181] == 0x10 && rawSo[0x55182] == 0x2e && rawSo[0x55183] == 0x1e)
                    || (rawSo[0x55180] == 0x60 && rawSo[0x55181] == (byte) 0x82 && rawSo[0x55182] == 0x40 && rawSo[0x55183] == (byte) 0xbd);

            if (isValidTarget) {
                rawSo[0x55180] = 0x60; rawSo[0x55181] = (byte) 0x82; rawSo[0x55182] = 0x40; rawSo[0x55183] = (byte) 0xbd;
                rawSo[0x5557c] = 0x60; rawSo[0x5557d] = (byte) 0x82; rawSo[0x5557e] = 0x40; rawSo[0x5557f] = (byte) 0xbd;
                rawSo[0xa63e8] = 0x1f; rawSo[0xa63e9] = 0x20; rawSo[0xa63ea] = 0x03; rawSo[0xa63eb] = (byte) 0xd5;

                byte[] shaderBytes = SGSR_CAS_SHADER.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int fsStart = 0x1192c7;
                int fsMaxLen = 3454;
                for (int i = 0; i < fsMaxLen; i++) {
                    rawSo[fsStart + i] = i < shaderBytes.length ? shaderBytes[i] : 0;
                }

                if (outFile.exists()) {
                    outFile.setWritable(true);
                }
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                    fos.write(rawSo);
                }
                outFile.setReadable(true, false);
                outFile.setExecutable(true, false);
                outFile.setWritable(false); // 符合 Android 14+ W^X 动态库只读安全要求
                sSoPatchedSuccess = true;
                Log.i(TAG, "✓ Dynamically patched and redirected libdyplayer.so -> " + outFile.getAbsolutePath());
                return outFile.getAbsolutePath();
            }
        } catch (Throwable t) {
            Log.w(TAG, "preparePatchedLibDyPlayer warning", t);
        }
        return origPath;
    }

    private static byte[] readLibDyPlayerBytes(String origPath, Context app) {
        try {
            if (origPath != null && !origPath.contains("!") && new java.io.File(origPath).canRead()) {
                java.io.File f = new java.io.File(origPath);
                byte[] data = new byte[(int) f.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    int off = 0;
                    while (off < data.length) {
                        int r = fis.read(data, off, data.length - off);
                        if (r <= 0) break;
                        off += r;
                    }
                }
                return data;
            }
        } catch (Throwable ignored) {}

        try {
            String apkPath = app.getApplicationInfo().sourceDir;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath)) {
                java.util.zip.ZipEntry entry = zf.getEntry("lib/arm64-v8a/libdyplayer.so");
                if (entry != null) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) entry.getSize());
                    try (java.io.InputStream is = zf.getInputStream(entry)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                    }
                    return bos.toByteArray();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp2px(Context context, float dp) {
        if (context == null) return (int) (dp * 2);
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }
}
