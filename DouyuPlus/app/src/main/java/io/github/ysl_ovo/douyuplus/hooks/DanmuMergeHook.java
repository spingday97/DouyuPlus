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

    // 附加到 BaseDanmaku 实例上的关联字段名
    private static final String KEY_MERGE_ENTRY = "douyuplus_merge_entry";

    // 缓存活跃的弹幕条目：Key = roomId_type_cleanText -> MergeEntry
    private static final Map<String, MergeEntry> sActiveDanmakuMap = new ConcurrentHashMap<>();

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
                            if (entry.origStepX <= 0.0f) {
                                if (curStepX > 0.0f) {
                                    entry.origStepX = curStepX;
                                }
                            } else if (entry.count >= 2) {
                                XposedHelpers.setFloatField(danmaku, "mStepX", entry.origStepX);
                                int distance = XposedHelpers.getIntField(danmaku, "mDistance");
                                int hoverTime = XposedHelpers.getIntField(danmaku, "mHoverTime");
                                Object durationObj = XposedHelpers.getObjectField(danmaku, "duration");
                                if (durationObj != null && distance > 0) {
                                    long newDur = (long) (distance / entry.origStepX) + hoverTime;
                                    long curDur = XposedHelpers.getLongField(durationObj, "value");
                                    if (newDur > curDur) {
                                        XposedHelpers.setLongField(durationObj, "value", newDur);
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
                        // 仅标记 measureResetFlag = -1，交由渲染线程在下一帧自动调用 measure() 完成文本更新与缓存刷新
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
     * 在渲染线程内安全刷新弹幕绘制缓存，保持 CacheManager.mRealSize 记账平衡并防止 OpenGL 纹理泄漏
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
                if (hasRefs) {
                    XposedHelpers.callMethod(cache, "decreaseReference");
                    XposedHelpers.setObjectField(danmaku, "cache", null);
                } else {
                    Object holder = XposedHelpers.callMethod(cache, "get");
                    if (holder != null) {
                        try {
                            XposedHelpers.callMethod(holder, "deleteTextureId");
                        } catch (Throwable ignored) {}
                    }
                    int origSize = 0;
                    try {
                        origSize = XposedHelpers.getIntField(cache, "mSize");
                    } catch (Throwable ignored) {}
                    Class<?> utilsCls = XposedHelpers.findClass(
                            "master.flame.danmaku.danmaku.util.DanmakuUtils",
                            danmaku.getClass().getClassLoader()
                    );
                    Object newCache = XposedHelpers.callStaticMethod(
                            utilsCls,
                            "buildDanmakuDrawingCache",
                            danmaku,
                            displayer,
                            cache
                    );
                    XposedHelpers.setObjectField(danmaku, "cache", newCache);
                    if (origSize > 0 && newCache != null) {
                        try {
                            XposedHelpers.setIntField(newCache, "mSize", origSize);
                        } catch (Throwable ignored) {}
                    }
                }
            } else {
                // Canvas 渲染模式 (AndroidDisplayer + CacheManagingDrawTask)
                if (hasRefs) {
                    XposedHelpers.callMethod(cache, "decreaseReference");
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
