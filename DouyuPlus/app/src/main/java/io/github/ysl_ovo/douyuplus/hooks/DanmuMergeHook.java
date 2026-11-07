package io.github.ysl_ovo.douyuplus.hooks;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重复弹幕合并显示 (DouyuPlus 连击合并)
 * 拦截播放器原生渲染的飘屏弹幕 (BaseDanmaku)，阻止重复弹幕刷屏，
 * 在首条正在飘过的弹幕末尾追加连击计数 (xN) 并按阶梯放大字号。
 *
 * 稳定性设计：
 * 1. 渲染线程同步更新：仅在合并时置位 measureResetFlag = -1，由渲染线程在 BaseDanmaku.measure()
 *    中构建全新的 SpannableStringBuilder 与 StaticLayout，彻底杜绝跨线程并发异常导致绘制线程死掉。
 * 2. 缓存池与纹理零泄漏：移除逐帧刷新的 ValueAnimator，合并重绘时保留 CacheManager.mRealSize 记账平衡，
 *    OpenGL 模式下在 GLThread 原地回收旧 textureId 并复用 GLDrawingCache。
 * 3. 恒速防跳帧与底边防越界：锁定 R2LDanmaku.mStepX 保证文本变宽时不发生水平跳变，限制底边轨道最大行高防止越界消失。
 */
public class DanmuMergeHook {
    private static final String TAG = "DouyuPlus_DanmuMerge";

    // 合并时间窗口 (毫秒)：6 秒内相同内容的弹幕视为重复连击
    private static final long MERGE_WINDOW_MS = 6000L;
    // 最大允许单条弹幕滑行时长 (毫秒)，防止滑动窗口无限制膨胀
    private static final long MAX_ALLOWED_DURATION = 15000L;

    // 附加到 BaseDanmaku 实例上的关联字段名
    private static final String KEY_MERGE_ENTRY = "douyuplus_merge_entry";

    // 缓存活跃的弹幕条目：Key = roomId_type_cleanText -> MergeEntry
    private static final Map<String, MergeEntry> sActiveDanmakuMap = new ConcurrentHashMap<>();

    // 捕获最近活跃的 DanmakuFactory 弱引用
    private static volatile WeakReference<Object> sDanmakuFactoryRef = null;
    // 记录合并弹幕所需的最大滑行时长，防止 DanmakuFactory.updateMaxDanmakuDuration() 重置覆盖
    private static volatile long sCustomMaxDanmakuDuration = 0L;

    private static void captureDanmakuContext(Object drawHandler) {
        if (drawHandler == null) return;
        try {
            Object ctx = XposedHelpers.getObjectField(drawHandler, "mContext");
            if (ctx != null) {
                Object factory = XposedHelpers.getObjectField(ctx, "mDanmakuFactory");
                if (factory != null) {
                    Object current = (sDanmakuFactoryRef != null) ? sDanmakuFactoryRef.get() : null;
                    if (current != factory) {
                        sDanmakuFactoryRef = new WeakReference<>(factory);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void applyCustomMaxDurationToFactory(Object factory, long targetMax) {
        if (factory == null || targetMax <= 0L) return;
        try {
            long curMax = 0L;
            String targetField = null;
            try {
                curMax = XposedHelpers.getLongField(factory, "mMaxDanmakuDuration");
                targetField = "mMaxDanmakuDuration";
            } catch (Throwable t) {
                try {
                    curMax = XposedHelpers.getLongField(factory, "MAX_DANMAKU_DURATION");
                    targetField = "MAX_DANMAKU_DURATION";
                } catch (Throwable ignored) {}
            }
            if (targetField != null && targetMax > curMax) {
                XposedHelpers.setLongField(factory, targetField, targetMax);
            }
        } catch (Throwable ignored) {}
    }

    private static void updateMaxDanmakuDuration(long newDur) {
        long targetMax = Math.min(MAX_ALLOWED_DURATION, newDur + 1000L); // 留足 1 秒缓冲，杜绝 DrawTask 滑动窗口提前截断
        if (targetMax > sCustomMaxDanmakuDuration) {
            sCustomMaxDanmakuDuration = targetMax;
        }
        Object factory = (sDanmakuFactoryRef != null) ? sDanmakuFactoryRef.get() : null;
        if (factory != null) {
            applyCustomMaxDurationToFactory(factory, sCustomMaxDanmakuDuration);
        }
    }

    /**
     * 为合并弹幕绑定或更新专属独立的 Duration 实例，严禁污染全局共享的 mMaxDurationScrollDanmaku
     */
    private static void applyIndependentDuration(Object danmaku, MergeEntry entry, Object durationObj, long newDur) {
        if (danmaku == null || entry == null || durationObj == null || newDur <= 0L) {
            return;
        }
        newDur = Math.min(MAX_ALLOWED_DURATION, newDur);
        if (entry.independentDuration == null) {
            try {
                float factor = 1.0f;
                try {
                    factor = XposedHelpers.getFloatField(durationObj, "factor");
                    if (factor <= 0.0f) factor = 1.0f;
                } catch (Throwable ignored) {}

                // 斗鱼 Duration 内部实现为 value = (long) (mInitialDuration / factor)
                // 期望实际滑行时长 value 为 newDur，因此 mInitialDuration 必须为 (newDur * factor)
                long initialDur = (long) (newDur * factor);
                Object indepDur = null;
                try {
                    indepDur = XposedHelpers.newInstance(durationObj.getClass(), initialDur);
                } catch (Throwable t) {
                    try {
                        indepDur = XposedHelpers.newInstance(durationObj.getClass());
                        XposedHelpers.setLongField(indepDur, "mInitialDuration", initialDur);
                    } catch (Throwable ignored) {}
                }

                if (indepDur != null) {
                    XposedHelpers.setFloatField(indepDur, "factor", factor);
                    XposedHelpers.setLongField(indepDur, "value", newDur);

                    entry.independentDuration = indepDur;
                    try {
                        XposedHelpers.callMethod(danmaku, "setDuration", indepDur);
                    } catch (Throwable ignored) {}
                    XposedHelpers.setObjectField(danmaku, "duration", indepDur);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to create independent Duration", t);
            }
        } else {
            try {
                float factor = XposedHelpers.getFloatField(entry.independentDuration, "factor");
                if (factor <= 0.0f) factor = 1.0f;
                XposedHelpers.setLongField(entry.independentDuration, "mInitialDuration", (long) (newDur * factor));
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.setLongField(entry.independentDuration, "value", newDur);
            } catch (Throwable ignored) {}
        }
        updateMaxDanmakuDuration(newDur);
    }

    /**
     * 静态比例缩放与基线偏移 Span（每次更新创建不可变的新实例，保证渲染线程安全）
     */
    private static class DanmuScaleSpan extends MetricAffectingSpan {
        private final float scale;
        private final float shiftRatio;

        DanmuScaleSpan(float scale, float shiftRatio) {
            this.scale = scale;
            this.shiftRatio = shiftRatio;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setTextSize(tp.getTextSize() * scale);
            if (shiftRatio != 0f) {
                tp.baselineShift -= (int) (tp.ascent() * shiftRatio);
            }
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            tp.setTextSize(tp.getTextSize() * scale);
            if (shiftRatio != 0f) {
                tp.baselineShift -= (int) (tp.ascent() * shiftRatio);
            }
        }
    }

    private static class MergeEntry {
        final WeakReference<Object> danmakuRef;
        final CharSequence originalText;
        final String cleanKey;
        final String roomId;
        final int danmakuType;
        volatile int count;
        volatile int appliedCount;
        final long createTime;
        volatile long lastUpdateTime;
        volatile float basePaintHeight = 0.0f;
        volatile float origStepX = 0.0f;
        volatile Object independentDuration = null;

        MergeEntry(Object danmaku, CharSequence originalText, String cleanKey, String roomId, int danmakuType) {
            this.danmakuRef = new WeakReference<>(danmaku);
            this.originalText = originalText;
            this.cleanKey = cleanKey;
            this.roomId = roomId;
            this.danmakuType = danmakuType;
            this.count = 1;
            this.appliedCount = 1;
            this.createTime = System.currentTimeMillis();
            this.lastUpdateTime = this.createTime;
        }
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> baseDanmakuCls;
        try {
            baseDanmakuCls = XposedHelpers.findClass(
                    "master.flame.danmaku.danmaku.model.BaseDanmaku",
                    lpparam.classLoader
            );
        } catch (Throwable t) {
            Log.w(TAG, "Failed to find BaseDanmaku", t);
            return;
        }

        // 1. Hook DanmakuNeuron.fn(BaseDanmaku) - 斗鱼高层弹幕分发收敛口
        try {
            Class<?> danmakuNeuronCls = XposedHelpers.findClass(
                    "com.douyu.module.player.p.danmaku.DanmakuNeuron",
                    lpparam.classLoader
            );
            for (Method m : danmakuNeuronCls.getDeclaredMethods()) {
                if ("fn".equals(m.getName()) && m.getParameterTypes().length == 1
                        && baseDanmakuCls.isAssignableFrom(m.getParameterTypes()[0])) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!DouyuPlusConfig.isDanmuMergeEnabled()) {
                                return;
                            }
                            if (processDanmakuMerge(param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
                    Log.i(TAG, "✓ Hooked DanmakuNeuron.fn for live danmaku merging");
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DanmakuNeuron.fn", t);
        }

        // 2. Hook DrawHandler.addDanmaku(BaseDanmaku) - 底层 FlameDanmaku 核心入队枢纽
        try {
            Class<?> drawHandlerCls = XposedHelpers.findClass(
                    "master.flame.danmaku.controller.DrawHandler",
                    lpparam.classLoader
            );
            for (Method m : drawHandlerCls.getDeclaredMethods()) {
                if ("addDanmaku".equals(m.getName()) && m.getParameterTypes().length == 1
                        && baseDanmakuCls.isAssignableFrom(m.getParameterTypes()[0])) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!DouyuPlusConfig.isDanmuMergeEnabled()) {
                                return;
                            }
                            captureDanmakuContext(param.thisObject);
                            if (processDanmakuMerge(param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
                    Log.i(TAG, "✓ Hooked DrawHandler.addDanmaku for live danmaku merging");
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DrawHandler.addDanmaku", t);
        }

        // 2.5 Hook DanmakuFactory - 防止 updateMaxDanmakuDuration 重置覆盖合并弹幕所需的滑动窗口时长
        try {
            Class<?> factoryCls = XposedHelpers.findClass(
                    "master.flame.danmaku.danmaku.parser.DanmakuFactory",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(
                    factoryCls,
                    "updateMaxDanmakuDuration",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object factory = param.thisObject;
                            if (factory != null) {
                                Object current = (sDanmakuFactoryRef != null) ? sDanmakuFactoryRef.get() : null;
                                if (current != factory) {
                                    sDanmakuFactoryRef = new WeakReference<>(factory);
                                }
                                if (sCustomMaxDanmakuDuration > 0L) {
                                    applyCustomMaxDurationToFactory(factory, sCustomMaxDanmakuDuration);
                                }
                            }
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    factoryCls,
                    "resetDurationsData",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sCustomMaxDanmakuDuration = 0L;
                        }
                    }
            );

            // 2.6 Hook DanmakuFactory.createDanmaku -> 彻底解决原版斗鱼调高弹幕速度时左侧慢弹幕瞬间消失的缺陷
            // 原版斗鱼所有滚动弹幕均复用同一个全局 mMaxDurationScrollDanmaku 实例指针。
            // 当用户调高弹幕速度时，该共享实例的 value 被改小，导致屏幕上正在滑行的慢弹幕下一帧直接判定 isTimeOut() 提前被销毁。
            // 在此处为每条新创建的弹幕深拷贝独立的 Duration 实例，使其拥有独立生命周期，调速时老弹幕依然平滑滑出屏幕！
            for (Method m : factoryCls.getDeclaredMethods()) {
                if ("createDanmaku".equals(m.getName()) && baseDanmakuCls.isAssignableFrom(m.getReturnType())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object danmaku = param.getResult();
                            if (danmaku == null) return;
                            try {
                                Object durObj = XposedHelpers.getObjectField(danmaku, "duration");
                                if (durObj != null) {
                                    long initialDur = XposedHelpers.getLongField(durObj, "mInitialDuration");
                                    float factor = XposedHelpers.getFloatField(durObj, "factor");
                                    long val = XposedHelpers.getLongField(durObj, "value");
                                    Object indepDur = XposedHelpers.newInstance(durObj.getClass(), initialDur);
                                    XposedHelpers.setFloatField(indepDur, "factor", factor);
                                    XposedHelpers.setLongField(indepDur, "value", val);
                                    XposedHelpers.setObjectField(danmaku, "duration", indepDur);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            }
            Log.i(TAG, "✓ Hooked DanmakuFactory duration lifecycle & independent createDanmaku");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DanmakuFactory", t);
        }

        // 2.7 Hook BaseDanmaku.isLate() -> 彻底根除 DanmakuRenderer.draw 中因为单条未来弹幕/时间戳抖动弹幕触发 break 截断整帧、导致整块在屏弹幕瞬间蒸发的缺陷！
        // 各弹幕自身的 layout() 方法内部均有严格的 (currMillisecond - time <= 0 -> setVisibility(false)) 保护，
        // 未到时间的弹幕绝不会被画出，而排在后面的在屏老弹幕再也不会被连坐截断！
        try {
            XposedHelpers.findAndHookMethod(
                    baseDanmakuCls,
                    "isLate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(false);
                        }
                    }
            );
            Log.i(TAG, "✓ Hooked BaseDanmaku.isLate -> fixed premature block disappearance");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook BaseDanmaku.isLate", t);
        }

        // 3. Hook BaseDanmaku.measure(IDisplayer) - 在渲染线程内安全执行连击文本替换、排版测量与缓存刷新
        try {
            Class<?> iDisplayerCls = XposedHelpers.findClass(
                    "master.flame.danmaku.danmaku.model.IDisplayer",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(
                    baseDanmakuCls,
                    "measure",
                    iDisplayerCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!DouyuPlusConfig.isDanmuMergeEnabled()) {
                                return;
                            }
                            Object danmaku = param.thisObject;
                            Object displayer = param.args[0];
                            if (danmaku == null || displayer == null) {
                                return;
                            }
                            MergeEntry entry = (MergeEntry) XposedHelpers.getAdditionalInstanceField(danmaku, KEY_MERGE_ENTRY);
                            if (entry == null) {
                                return;
                            }

                            if (entry.basePaintHeight <= 0.0f) {
                                try {
                                    float ph = XposedHelpers.getFloatField(danmaku, "paintHeight");
                                    if (ph > 0.0f) {
                                        entry.basePaintHeight = ph;
                                    }
                                } catch (Throwable ignored) {}
                            }

                            int targetCount = entry.count;
                            if (targetCount >= 2 && entry.appliedCount != targetCount) {
                                // 若在首次 measure 前就已发生连击合并，先测量一次原始未放大文本以准确提取 basePaintHeight 与原始速度 origStepX
                                if (entry.basePaintHeight <= 0.0f || entry.origStepX <= 0.0f) {
                                    try {
                                        XposedHelpers.setObjectField(danmaku, "text", entry.originalText);
                                        XposedHelpers.callMethod(displayer, "measure", danmaku);
                                        float origW = XposedHelpers.getFloatField(danmaku, "paintWidth");
                                        float origH = XposedHelpers.getFloatField(danmaku, "paintHeight");
                                        if (entry.basePaintHeight <= 0.0f && origH > 0.0f) {
                                            entry.basePaintHeight = origH;
                                        }
                                        if (entry.origStepX <= 0.0f && origW > 0.0f) {
                                            int dispW = (int) XposedHelpers.callMethod(displayer, "getWidth");
                                            Object durObj = XposedHelpers.getObjectField(danmaku, "duration");
                                            int hoverTime = 0;
                                            try {
                                                hoverTime = XposedHelpers.getIntField(danmaku, "mHoverTime");
                                            } catch (Throwable ignored) {}
                                            if (dispW > 0 && durObj != null) {
                                                long durVal = XposedHelpers.getLongField(durObj, "value");
                                                long effDur = durVal - hoverTime;
                                                if (effDur > 0L) {
                                                    entry.origStepX = (dispW + origW) / (float) effDur;
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }

                                float mainScale = Math.min(1.80f, 1.0f + 0.10f * (targetCount - 1));
                                float subScale = Math.min(1.40f, 0.75f + 0.08f * (targetCount - 2));
                                float shiftRatio = Math.max(0.06f, 0.20f - 0.02f * (targetCount - 2));

                                // 底边防越界限制：如果弹幕靠近显示区域底部，限制放大倍数以免 y + paintHeight > dispHeight 被跳过不画
                                try {
                                    int dispHeight = (int) XposedHelpers.callMethod(displayer, "getHeight");
                                    float top = (float) XposedHelpers.callMethod(danmaku, "getTop");
                                    if (dispHeight > 0 && top >= 0.0f && entry.basePaintHeight > 0.0f) {
                                        float maxAvailH = dispHeight - top;
                                        float maxSafeScale = maxAvailH / entry.basePaintHeight;
                                        if (maxSafeScale >= 1.0f && mainScale > maxSafeScale) {
                                            mainScale = maxSafeScale;
                                            subScale = Math.min(subScale, mainScale);
                                        } else if (maxSafeScale < 1.0f) {
                                            mainScale = 1.0f;
                                            subScale = Math.min(subScale, 0.85f);
                                        }
                                    }
                                } catch (Throwable ignored) {}

                                CharSequence orig = entry.originalText;
                                SpannableStringBuilder ssb = new SpannableStringBuilder(orig);
                                ssb.setSpan(new DanmuScaleSpan(mainScale, 0.0f), 0, orig.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                                ssb.append("\u00A0x").append(String.valueOf(targetCount));
                                int spanStart = orig.length() + 1;
                                int spanEnd = ssb.length();
                                ssb.setSpan(new DanmuScaleSpan(subScale, shiftRatio), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                ssb.setSpan(new StyleSpan(Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                                XposedHelpers.setObjectField(danmaku, "text", ssb);
                                try {
                                    XposedHelpers.setBooleanField(danmaku, "mForceSingleLine", true);
                                    XposedHelpers.setObjectField(danmaku, "lines", null);
                                } catch (Throwable ignored) {}

                                entry.appliedCount = targetCount;
                                param.setObjectExtra("applied_scale", Float.valueOf(mainScale));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object danmaku = param.thisObject;
                            Object displayer = param.args[0];
                            if (danmaku == null || displayer == null) {
                                return;
                            }
                            MergeEntry entry = (MergeEntry) XposedHelpers.getAdditionalInstanceField(danmaku, KEY_MERGE_ENTRY);
                            if (entry == null) {
                                return;
                            }

                            Float appliedScaleObj = (Float) param.getObjectExtra("applied_scale");
                            if (entry.basePaintHeight <= 0.0f) {
                                try {
                                    float ph = XposedHelpers.getFloatField(danmaku, "paintHeight");
                                    if (ph > 0.0f) {
                                        float scale = appliedScaleObj != null && appliedScaleObj > 0.0f ? appliedScaleObj : 1.0f;
                                        entry.basePaintHeight = ph / scale;
                                    }
                                } catch (Throwable ignored) {}
                            }

                            if (appliedScaleObj != null) {
                                // 兜底确保 getBottom() <= displayer.getHeight()，防止 DanmakuRenderer.draw 因越界跳过渲染
                                try {
                                    int dispHeight = (int) XposedHelpers.callMethod(displayer, "getHeight");
                                    float top = (float) XposedHelpers.callMethod(danmaku, "getTop");
                                    float ph = XposedHelpers.getFloatField(danmaku, "paintHeight");
                                    if (dispHeight > 0 && top >= 0.0f && top + ph > dispHeight) {
                                        XposedHelpers.setFloatField(danmaku, "paintHeight", Math.max(1.0f, dispHeight - top));
                                    }
                                } catch (Throwable ignored) {}

                                refreshDanmakuCacheOnRenderThread(danmaku, displayer);
                            }
                        }
                    }
            );
            Log.i(TAG, "✓ Hooked BaseDanmaku.measure for thread-safe combo scaling");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook BaseDanmaku.measure", t);
        }

        // 4. Hook R2LDanmaku.measure(IDisplayer) - 锁定水平步进速度 mStepX，防止弹幕变宽时瞬间向左跳帧
        try {
            Class<?> r2lDanmakuCls = XposedHelpers.findClass(
                    "master.flame.danmaku.danmaku.model.R2LDanmaku",
                    lpparam.classLoader
            );
            Class<?> iDisplayerCls = XposedHelpers.findClass(
                    "master.flame.danmaku.danmaku.model.IDisplayer",
                    lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(
                    r2lDanmakuCls,
                    "measure",
                    iDisplayerCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object danmaku = param.thisObject;
                            if (danmaku == null) {
                                return;
                            }
                            MergeEntry entry = (MergeEntry) XposedHelpers.getAdditionalInstanceField(danmaku, KEY_MERGE_ENTRY);
                            if (entry == null) {
                                return;
                            }
                            float curStepX = XposedHelpers.getFloatField(danmaku, "mStepX");
                            if (curStepX > 0.0f) {
                                if (entry.origStepX <= 0.0f || entry.appliedCount == 1) {
                                    entry.origStepX = curStepX;
                                }
                            }

                            if (entry.count >= 2) {
                                if (entry.origStepX > 0.0f) {
                                    XposedHelpers.setFloatField(danmaku, "mStepX", entry.origStepX);
                                }
                                int distance = 0;
                                try {
                                    distance = XposedHelpers.getIntField(danmaku, "mDistance");
                                } catch (Throwable ignored) {}

                                int hoverTime = 0;
                                try {
                                    hoverTime = XposedHelpers.getIntField(danmaku, "mHoverTime");
                                } catch (Throwable ignored) {}

                                Object durationObj = XposedHelpers.getObjectField(danmaku, "duration");
                                float effectiveStepX = entry.origStepX > 0.0f ? entry.origStepX : curStepX;
                                if (durationObj != null && distance > 0 && effectiveStepX > 0.0f) {
                                    // 向上取整并增加 150ms 缓冲，彻底杜绝浮点截断导致在出屏边缘提前判定超时隐藏
                                    long newDur = (long) Math.ceil(distance / (double) effectiveStepX) + hoverTime + 150L;
                                    long curDur = XposedHelpers.getLongField(durationObj, "value");
                                    if (newDur > curDur || entry.independentDuration == null) {
                                        applyIndependentDuration(danmaku, entry, durationObj, Math.max(newDur, curDur));
                                    }
                                }
                            }
                        }
                    }
            );
            Log.i(TAG, "✓ Hooked R2LDanmaku.measure for constant-speed glide");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook R2LDanmaku.measure", t);
        }
    }

    /**
     * 处理弹幕合并逻辑，返回 true 表示命中重复并已合并拦截，返回 false 表示首条弹幕正常放行
     */
    private static boolean processDanmakuMerge(Object baseDanmaku) {
        if (baseDanmaku == null) {
            return false;
        }

        try {
            CharSequence textObj = (CharSequence) XposedHelpers.getObjectField(baseDanmaku, "text");
            if (textObj == null || textObj.length() == 0) {
                return false;
            }

            String rawStr = textObj.toString().trim();
            if (rawStr.isEmpty()) {
                return false;
            }

            String roomId = "";
            try {
                Object danmuInfoBean = XposedHelpers.getObjectField(baseDanmaku, "danmuInfoBean");
                if (danmuInfoBean != null) {
                    Object rIdObj = XposedHelpers.getObjectField(danmuInfoBean, "roomId");
                    if (rIdObj != null) {
                        roomId = rIdObj.toString();
                    }
                }
            } catch (Throwable ignored) {}

            int danmaType = 1;
            try {
                danmaType = (int) XposedHelpers.callMethod(baseDanmaku, "getType");
            } catch (Throwable ignored) {}

            String mapKey = roomId + "_" + danmaType + "_" + rawStr;
            long now = System.currentTimeMillis();

            MergeEntry existing = sActiveDanmakuMap.get(mapKey);
            if (existing != null) {
                Object prevDanmaku = existing.danmakuRef.get();
                if (prevDanmaku == baseDanmaku) {
                    return false;
                }

                if (prevDanmaku != null && (now - existing.createTime <= MERGE_WINDOW_MS)) {
                    boolean canMerge = true;
                    try {
                        boolean isTimeOut = (boolean) XposedHelpers.callMethod(prevDanmaku, "isTimeOut");
                        boolean isFiltered = (boolean) XposedHelpers.callMethod(prevDanmaku, "isFiltered");
                        boolean isOutside = (boolean) XposedHelpers.callMethod(prevDanmaku, "isOutside");
                        boolean isShown = (boolean) XposedHelpers.callMethod(prevDanmaku, "isShown");
                        // 若首条弹幕已超时、已被过滤器丢弃、已滑出屏幕，或入队超过 600ms 仍未上屏显示，则不再合并进该弹幕
                        if (isTimeOut || isFiltered || (now - existing.createTime > 600L && (!isShown || isOutside))) {
                            canMerge = false;
                        }
                    } catch (Throwable ignored) {}

                    if (canMerge) {
                        existing.count++;
                        existing.lastUpdateTime = now;
                        // 预先为即将合并重测的弹幕挂载独立 Duration 并预留安全缓冲期，
                        // 防止下一帧 DanmakuRenderer.draw 在调用 measure() 之前于第 61 行判定 isTimeOut() 提前回收弹幕
                        try {
                            Object durObj = XposedHelpers.getObjectField(prevDanmaku, "duration");
                            if (durObj != null) {
                                long curDur = XposedHelpers.getLongField(durObj, "value");
                                long targetDur = curDur;
                                Object timer = XposedHelpers.getObjectField(prevDanmaku, "mTimer");
                                if (timer != null) {
                                    long currTimer = XposedHelpers.getLongField(timer, "currMillisecond");
                                    long realTime = (long) XposedHelpers.callMethod(prevDanmaku, "getActualTime");
                                    long elapsed = currTimer - realTime;
                                    if (elapsed > 0L && curDur - elapsed < 1500L) {
                                        targetDur = elapsed + 1500L;
                                    }
                                }
                                applyIndependentDuration(prevDanmaku, existing, durObj, targetDur);
                            }
                        } catch (Throwable ignored) {}

                        // 标记 measureResetFlag = -1，交由渲染线程在下一帧自动调用 measure() 完成文本更新、精准时长计算与缓存刷新
                        try {
                            XposedHelpers.setIntField(prevDanmaku, "measureResetFlag", -1);
                        } catch (Throwable ignored) {}
                        Log.i(TAG, "[MERGED] \"" + rawStr + "\" -> x" + existing.count + " (room=" + roomId + ")");
                        return true;
                    }
                }
            }

            MergeEntry newEntry = new MergeEntry(baseDanmaku, textObj, rawStr, roomId, danmaType);
            XposedHelpers.setAdditionalInstanceField(baseDanmaku, KEY_MERGE_ENTRY, newEntry);
            sActiveDanmakuMap.put(mapKey, newEntry);
            cleanExpiredEntries(now);
        } catch (Throwable t) {
            Log.w(TAG, "processDanmakuMerge error", t);
        }

        return false;
    }

    /**
     * 在渲染线程内安全刷新弹幕绘制缓存，保持 CacheManager.mRealSize 记账平衡并防止 OpenGL 纹理泄漏与跨线程异常
     */
    private static void refreshDanmakuCacheOnRenderThread(Object danmaku, Object displayer) {
        try {
            Object cache = XposedHelpers.getObjectField(danmaku, "cache");
            if (cache == null) {
                return;
            }
            String cacheClsName = cache.getClass().getName();
            boolean hasRefs = false;
            try {
                hasRefs = (boolean) XposedHelpers.callMethod(cache, "hasReferences");
            } catch (Throwable ignored) {}

            if (cacheClsName.endsWith("GLDrawingCache")) {
                // OpenGL 渲染模式 (GLDisplayer)
                // GLDisplayer.draw 在绘制时，会原生检测 !hasDrawingCache()，并在当前 GL 线程安全地调用 buildDanmakuDrawingCache
                // 重构包含连击 (xN) 的全新纹理并绑定。严禁在此处跨线程调用 GLES20.glDeleteTextures 破坏 GL 状态机！
                if (hasRefs) {
                    try {
                        XposedHelpers.callMethod(cache, "decreaseReference");
                    } catch (Throwable ignored) {}
                }
                XposedHelpers.setObjectField(danmaku, "cache", null);
            } else {
                // Canvas 渲染模式 (AndroidDisplayer + CacheManagingDrawTask)
                if (hasRefs) {
                    try {
                        XposedHelpers.callMethod(cache, "decreaseReference");
                    } catch (Throwable ignored) {}
                    XposedHelpers.setObjectField(danmaku, "cache", null);
                } else {
                    Object holder = XposedHelpers.getObjectField(cache, "mHolder");
                    if (holder != null) {
                        // 仅回收 holder 内部 bitmap 使 cache.get() 返回 null（转走 Canvas 直接绘制），
                        // 保留 DrawingCache 对象本身及其 mSize，确保弹幕超时时 entryRemoved 正常扣减 mRealSize 并归还对象池
                        XposedHelpers.callMethod(holder, "recycle");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshDanmakuCacheOnRenderThread error", t);
        }
    }

    private static volatile long sLastCleanTime = 0L;

    /**
     * 淘汰超时的活跃弹幕条目
     */
    private static void cleanExpiredEntries(long now) {
        if (sActiveDanmakuMap.size() < 40 && (now - sLastCleanTime < 3000L)) {
            return;
        }
        sLastCleanTime = now;
        Iterator<Map.Entry<String, MergeEntry>> it = sActiveDanmakuMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MergeEntry> entry = it.next();
            MergeEntry val = entry.getValue();
            if (val == null || (now - val.createTime > MERGE_WINDOW_MS) || val.danmakuRef.get() == null) {
                it.remove();
            }
        }
    }
}
