package io.github.ysl_ovo.douyuplus.hooks;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.List;

/**
 * 自动最高画质 Hook (零卡顿秒开 + 2K/4K/原画精准梯队 + 用户手动切换豁免)
 * 1. 起播请求直接命中最高画质 (DYRtmpPlayerLoader.l() 返回 0 / 原画，关掉自适应与协商降级)，首播即最高画质；
 * 2. 监听 RamboDefinitionNeuron.lC(RoomRtmpInfo)，在下发流瞬间进行瞬间同步，废除 600ms 滞后切流；
 * 3. 严格梯队评分：4K(50000) > 2K(40000) > HDR(35000) > 原画(30000) > 蓝光(20000) > 超清(10000)；
 * 4. 彻底修复原画 bit 码率反超 2K 的 bug；码率仅作同梯队微调决胜；
 * 5. 官方赛事直播间（如王者荣耀 998）2K 档位免受 diamondFan 误杀拦截；
 * 6. 【重大修复】支持用户手动切换画质：
 *    当用户在清晰度菜单手动选择画质时，记录当前房间的手动状态，
 *    停止后续自动切回最高画质，彻底解决“用户切不了画质被强切回最高画质”的 bug；
 *    切房间时自动重置，新房间恢复自动最高画质。
 */
public class AutoQualityHook {
    private static final String TAG = "DouyuPlus_AutoQuality";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static String sLastSwitchedRoomId = null;
    private static String sLastSwitchedRate = null;

    // 手动切换豁免机制：记录当前房间用户是否主动手动选择过画质
    private static volatile boolean sIsAutoSwitching = false;
    private static volatile boolean sHasUserManualSwitched = false;
    private static volatile String sUserManualRoomId = null;
    private static volatile String sUserManualRate = null;
    private static volatile java.lang.ref.WeakReference<Object> sCurrentNeuronRef = null;
    private static volatile ClassLoader sClassLoader = null;

    public static void simulateUserManualSwitch(String targetRate, String targetName) {
        if (sCurrentNeuronRef == null || sCurrentNeuronRef.get() == null || sClassLoader == null) {
            Log.w(TAG, "simulateUserManualSwitch failed: current neuron is null");
            return;
        }
        try {
            Object neuron = sCurrentNeuronRef.get();
            Class<?> rateBeanCls = XposedHelpers.findClass("com.douyu.sdk.playerframework.business.live.liveuser.beans.LiveRateBean", sClassLoader);
            Object bean = rateBeanCls.newInstance();
            XposedHelpers.setObjectField(bean, "rate", targetRate);
            XposedHelpers.setObjectField(bean, "name", targetName != null ? targetName : ("Rate_" + targetRate));
            Log.i(TAG, "Simulating user manual switch to rate=" + targetRate + " (name=" + targetName + ")");
            XposedHelpers.callMethod(neuron, "aF", bean);
        } catch (Throwable t) {
            Log.e(TAG, "simulateUserManualSwitch error", t);
        }
    }

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 起播请求阶段：优化 DYRtmpPlayerLoader.l()
        // 仅在官方返回 -1 (自适应降级) 时兜底为 0 (原画秒开)；
        // 当存在真实目标画质 (例如 2K=15, 4K=16, 超清=2, 流畅=3 等 >= 0) 时 100% 放行真实请求！
        try {
            Class<?> playerLoaderCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.rtmp.DYRtmpPlayerLoader",
                    lpparam.classLoader
            );
            if (playerLoaderCls != null) {
                XposedHelpers.findAndHookMethod(playerLoaderCls, "l", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int mode = DouyuPlusConfig.getAutoQualityMode(null);
                        if (mode == DouyuPlusConfig.AUTO_QUALITY_OFF) return;
                        if (mode == DouyuPlusConfig.AUTO_QUALITY_WIFI_ONLY && !isWifiConnected(null)) return;

                        Object resultObj = param.getResult();
                        int currentResult = resultObj instanceof Integer ? (Integer) resultObj : -1;

                        // 核心修复：
                        // 1. 如果已有明确的目标画质 (currentResult >= 0，例如 15=2K, 16=4K, 2=超清 等)，
                        //    坚决放行，绝对不能覆盖为 0，否则向服务器发出的请求会被生生篡改成原画！
                        // 2. 只有当返回值是 -1 (官方自适应降级模式)，且用户在当前房间未手动选择画质时，
                        //    才兜底指定为 0 (原画)，实现首播秒开免降级！
                        if (currentResult < 0 && !sHasUserManualSwitched) {
                            param.setResult(0);
                            Log.d(TAG, "DYRtmpPlayerLoader.l(): replaced adaptive (-1) with rate=0 for initial start");
                        }
                    }
                });

                // 强制关闭官方自适应动态切换标志，防止向服务端请求 rate=-1
                XposedHelpers.findAndHookMethod(playerLoaderCls, "r", XC_MethodReplacement.returnConstant(false));
                Log.i(TAG, "Hooked DYRtmpPlayerLoader -> smart rate forwarding (2K/4K/manual passthrough, initial rate=0)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook DYRtmpPlayerLoader", t);
        }

        // 2. 监听 RamboDefinitionNeuron
        try {
            final Class<?> neuronCls = XposedHelpers.findClassIfExists(
                    "com.douyu.module.player.p.rambodefinition.RamboDefinitionNeuron",
                    lpparam.classLoader
            );
            if (neuronCls == null) {
                return;
            }

            Class<?> rtmpInfoCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.RoomRtmpInfo",
                    lpparam.classLoader
            );

            // Hook aF: 捕获用户在清晰度菜单的手动选择
            Class<?> liveRateBeanCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.LiveRateBean",
                    lpparam.classLoader
            );
            if (liveRateBeanCls != null) {
                XposedHelpers.findAndHookMethod(neuronCls, "aF", liveRateBeanCls, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (sIsAutoSwitching) {
                            // 模块自动切画质触发，忽略
                            return;
                        }

                        Object targetRateBean = param.args[0];
                        if (targetRateBean == null) return;

                        Object neuron = param.thisObject;
                        String roomId = getRoomIdFromNeuronOrGlobal(neuron, lpparam.classLoader);

                        String rate = "";
                        String name = "";
                        try {
                            rate = (String) XposedHelpers.getObjectField(targetRateBean, "rate");
                            name = (String) XposedHelpers.getObjectField(targetRateBean, "name");
                        } catch (Throwable ignored) {}

                        sHasUserManualSwitched = true;
                        sUserManualRoomId = roomId;
                        sUserManualRate = rate;
                        Log.i(TAG, "User manually switched quality: name=" + name + ", rate=" + rate + " for roomId=" + roomId);
                    }
                });
            }

            if (rtmpInfoCls != null) {
                // Hook lC: 下发流信息瞬间，立即同步最高画质
                XposedHelpers.findAndHookMethod(neuronCls, "lC", rtmpInfoCls, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final Object roomRtmpInfo = param.args[0];
                        if (roomRtmpInfo == null) return;

                        // 强制关闭 autoEnable，防止官方协商自适应降级
                        try {
                            XposedHelpers.setObjectField(roomRtmpInfo, "autoEnable", "0");
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Object neuron = param.thisObject;
                        final Object roomRtmpInfo = param.args[0];
                        if (roomRtmpInfo == null) return;

                        triggerAutoQuality(neuron, roomRtmpInfo, "lC_instant", lpparam.classLoader);
                    }
                });
            }

            // Hook onVideoPrepared: 视频就绪后兜底验证
            XposedHelpers.findAndHookMethod(neuronCls, "onVideoPrepared", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Object neuron = param.thisObject;
                    try {
                        Object roomRtmpInfo = XposedHelpers.getObjectField(neuron, "roomRtmpInfo");
                        if (roomRtmpInfo != null) {
                            triggerAutoQuality(neuron, roomRtmpInfo, "onVideoPrepared", lpparam.classLoader);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Hook BE (房间变更): 重置房间切换记录与手动豁免状态
            Class<?> roomInfoCls = XposedHelpers.findClassIfExists(
                    "com.douyu.sdk.playerframework.business.live.liveuser.beans.RoomInfoBean",
                    lpparam.classLoader
            );
            if (roomInfoCls != null) {
                XposedHelpers.findAndHookMethod(neuronCls, "BE", roomInfoCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object roomInfo = param.args[0];
                            if (roomInfo != null) {
                                String newRoomId = (String) XposedHelpers.getObjectField(roomInfo, "roomId");
                                if (!TextUtils.isEmpty(newRoomId)) {
                                    if (!TextUtils.equals(newRoomId, sLastSwitchedRoomId)) {
                                        sLastSwitchedRoomId = null;
                                        sLastSwitchedRate = null;
                                    }
                                    if (!TextUtils.equals(newRoomId, sUserManualRoomId)) {
                                        sHasUserManualSwitched = false;
                                        sUserManualRoomId = null;
                                        sUserManualRate = null;
                                        Log.d(TAG, "Reset user manual quality state for new room=" + newRoomId);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }

            Log.i(TAG, "Hooked RamboDefinitionNeuron for auto highest quality (with manual switch exemption)");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook RamboDefinitionNeuron", t);
        }
    }

    private static String getRoomIdFromNeuronOrGlobal(Object neuron, ClassLoader classLoader) {
        String roomId = null;
        if (neuron != null) {
            try {
                Object rtmpInfo = XposedHelpers.getObjectField(neuron, "roomRtmpInfo");
                if (rtmpInfo != null) {
                    roomId = (String) XposedHelpers.getObjectField(rtmpInfo, "roomId");
                }
            } catch (Throwable ignored) {}

            if (TextUtils.isEmpty(roomId)) {
                try {
                    Object roomInfo = XposedHelpers.getObjectField(neuron, "roomInfoBean");
                    if (roomInfo != null) {
                        roomId = (String) XposedHelpers.getObjectField(roomInfo, "roomId");
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (TextUtils.isEmpty(roomId) && classLoader != null) {
            try {
                Class<?> mgrCls = XposedHelpers.findClass("com.douyu.sdk.playerframework.business.manager.RoomInfoManager", classLoader);
                Object mgr = XposedHelpers.callStaticMethod(mgrCls, "k");
                if (mgr != null) {
                    roomId = (String) XposedHelpers.callMethod(mgr, "r");
                }
            } catch (Throwable ignored) {}
        }
        return roomId;
    }

    private static void triggerAutoQuality(final Object neuron, final Object roomRtmpInfo, final String source, final ClassLoader classLoader) {
        try {
            sCurrentNeuronRef = new java.lang.ref.WeakReference<>(neuron);
            sClassLoader = classLoader;

            // 核心安全拦截：若用户在当前直播间手动选择过画质，坚决不进行自动切流！
            if (sHasUserManualSwitched) {
                Log.d(TAG, "Auto quality skipped: user manually switched quality in current room (" + sUserManualRate + ")");
                return;
            }

            Activity act = null;
            try {
                Object actObj = XposedHelpers.callMethod(neuron, "lE");
                if (actObj instanceof Activity) {
                    act = (Activity) actObj;
                }
            } catch (Throwable ignored) {}

            Context ctx = act;
            int mode = DouyuPlusConfig.getAutoQualityMode(ctx);
            if (mode == DouyuPlusConfig.AUTO_QUALITY_OFF) {
                return;
            }

            if (mode == DouyuPlusConfig.AUTO_QUALITY_WIFI_ONLY && !isWifiConnected(ctx)) {
                Log.i(TAG, "Auto quality skipped: current network is mobile, not WiFi");
                return;
            }

            String roomId = (String) XposedHelpers.getObjectField(roomRtmpInfo, "roomId");
            if (TextUtils.isEmpty(roomId)) {
                roomId = getRoomIdFromNeuronOrGlobal(neuron, classLoader);
            }

            // 再次校验当前房间手动豁免
            if (sHasUserManualSwitched && (roomId == null || TextUtils.equals(roomId, sUserManualRoomId))) {
                Log.d(TAG, "Auto quality skipped: user manually selected rate=" + sUserManualRate + " for room=" + roomId);
                return;
            }

            List<?> rateList = (List<?>) XposedHelpers.getObjectField(roomRtmpInfo, "rateBeanList");
            if (rateList == null || rateList.isEmpty()) {
                return;
            }

            Object bestBean = findHighestQualityBean(rateList);
            if (bestBean == null) {
                return;
            }

            final String bestRate = (String) XposedHelpers.getObjectField(bestBean, "rate");
            final String bestName = (String) XposedHelpers.getObjectField(bestBean, "name");
            int bestRateInt = 0;
            try {
                bestRateInt = Integer.parseInt(bestRate);
            } catch (Throwable ignored) {}

            // 检查当前实际是否已经在播放最高画质
            boolean alreadyHighest = false;
            try {
                Object config = XposedHelpers.callMethod(neuron, "VE");
                if (config != null) {
                    int currentPlayRate = (int) XposedHelpers.callMethod(config, "r");
                    boolean isAuto = (boolean) XposedHelpers.callMethod(config, "B");
                    if (!isAuto && currentPlayRate == bestRateInt) {
                        alreadyHighest = true;
                    }
                }
            } catch (Throwable ignored) {}

            String currentStreamRate = (String) XposedHelpers.getObjectField(roomRtmpInfo, "rate");
            if (TextUtils.equals(currentStreamRate, bestRate)) {
                alreadyHighest = true;
            }

            if (alreadyHighest) {
                // 如果当前已经在播放最高画质，仅确保关闭自适应并同步配置
                try {
                    XposedHelpers.callMethod(neuron, "lu", false, false);
                } catch (Throwable ignored) {}
                return;
            }

            // 防重保护：同一个房间相同画质已成功触发过
            if (TextUtils.equals(roomId, sLastSwitchedRoomId) && TextUtils.equals(bestRate, sLastSwitchedRate)) {
                return;
            }

            sLastSwitchedRoomId = roomId;
            sLastSwitchedRate = bestRate;

            final Activity finalAct = act;
            final Object targetBean = bestBean;
            sMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 1. 关闭自适应画质，防止被官方协商降级
                        try {
                            XposedHelpers.callMethod(neuron, "lu", false, true);
                        } catch (Throwable ignored) {}

                        // 2. 调用官方切流 (置位 sIsAutoSwitching 避免被 aF hook 误判为用户手动操作)
                        sIsAutoSwitching = true;
                        try {
                            XposedHelpers.callMethod(neuron, "aF", targetBean);
                        } finally {
                            sIsAutoSwitching = false;
                        }

                        // 3. 刷新入口文字
                        try {
                            Object freshFunc = XposedHelpers.getObjectField(neuron, "freshEntrance");
                            if (freshFunc != null) {
                                XposedHelpers.callMethod(freshFunc, "invoke");
                            }
                        } catch (Throwable ignored) {}

                        Log.i(TAG, "Successfully auto-switched to highest quality: " + bestName + " (rate=" + bestRate + ") via " + source);
                        if (finalAct != null) {
                            Toast.makeText(finalAct, "斗鱼Plus: 已自动切换至最高画质 (" + bestName + ")", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to switch quality via aF", t);
                    }
                }
            });

        } catch (Throwable t) {
            Log.w(TAG, "triggerAutoQuality error", t);
        }
    }

    private static Object findHighestQualityBean(List<?> rateList) {
        Object best = null;
        int maxScore = -99999;
        for (Object item : rateList) {
            if (item == null) continue;
            try {
                // 排除自适应画质 (-1)
                boolean isAuto = false;
                try {
                    isAuto = (boolean) XposedHelpers.callMethod(item, "isAutoRate");
                } catch (Throwable ignored) {}
                if (isAuto) continue;

                // 排除需要钻粉/付费的画质，避免切换后黑屏或弹窗
                // 注意：若为 2K/4K 官方赛事房间（如王者荣耀 998）或大主播（如玩机器），2K 档位常标粉丝/会员标签但实际开放，放行 2K/4K
                boolean needVip = false;
                try {
                    needVip = (boolean) XposedHelpers.callMethod(item, "isNeedVipFan");
                } catch (Throwable ignored) {}

                boolean is2kOr4k = false;
                try {
                    is2kOr4k = (boolean) XposedHelpers.callMethod(item, "is4KSuperHigh")
                            || (boolean) XposedHelpers.callMethod(item, "is2KSuperHigh");
                } catch (Throwable ignored) {}
                if (!is2kOr4k) {
                    String rate = (String) XposedHelpers.getObjectField(item, "rate");
                    String name = (String) XposedHelpers.getObjectField(item, "name");
                    String upperName = name != null ? name.toUpperCase() : "";
                    if ("15".equals(rate) || "16".equals(rate)
                            || upperName.contains("2K") || upperName.contains("4K")
                            || upperName.contains("1440") || upperName.contains("2160")) {
                        is2kOr4k = true;
                    }
                }

                if (needVip && !is2kOr4k) {
                    continue;
                }

                int score = calculateQualityScore(item);
                String rate = (String) XposedHelpers.getObjectField(item, "rate");
                String name = (String) XposedHelpers.getObjectField(item, "name");
                Log.d(TAG, "RateCandidate: name=" + name + ", rate=" + rate + ", score=" + score + ", is2kOr4k=" + is2kOr4k + ", needVip=" + needVip);
                if (score > maxScore) {
                    maxScore = score;
                    best = item;
                }
            } catch (Throwable ignored) {}
        }
        return best;
    }

    /**
     * 严密分层画质评分体系：
     * 梯队 1: 4K 档位 (50,000 基准分)
     * 梯队 2: 2K 档位 (40,000 基准分) - 保证王者荣耀赛事、玩机器等 2K 绝不被原画反超
     * 梯队 3: HDR 档位 (35,000 基准分)
     * 梯队 4: 原画 / 蓝光8M (30,000 基准分)
     * 梯队 5: 蓝光6M / 蓝光4M (20,000 基准分)
     * 梯队 6: 超清 / 1080P (10,000 基准分)
     * 梯队 7: 高清 / 720P (5,000 基准分)
     * 梯队 8: 流畅 / 540P (1,000 基准分)
     * 梯队内微调加分：60帧(+500分)、高码率(+200分)、bit码率微调(最多+100分)
     */
    private static int calculateQualityScore(Object bean) {
        if (bean == null) return -99999;

        String name = "";
        try {
            name = (String) XposedHelpers.getObjectField(bean, "name");
        } catch (Throwable ignored) {}
        if (name == null) name = "";
        String upperName = name.toUpperCase();

        String rateStr = "";
        try {
            rateStr = (String) XposedHelpers.getObjectField(bean, "rate");
        } catch (Throwable ignored) {}
        if (rateStr == null) rateStr = "";

        // 梯队 1: 4K 档位
        boolean is4k = false;
        try {
            is4k = (boolean) XposedHelpers.callMethod(bean, "is4KSuperHigh");
        } catch (Throwable ignored) {}
        if (is4k || upperName.contains("4K") || upperName.contains("2160") || "16".equals(rateStr)) {
            return 50000 + getBonusScore(bean, name);
        }

        // 梯队 2: 2K 档位
        boolean is2k = false;
        try {
            is2k = (boolean) XposedHelpers.callMethod(bean, "is2KSuperHigh");
        } catch (Throwable ignored) {}
        if (is2k || upperName.contains("2K") || upperName.contains("1440") || "15".equals(rateStr)) {
            return 40000 + getBonusScore(bean, name);
        }

        // 梯队 3: HDR 档位
        boolean isHdr = false;
        try {
            isHdr = (boolean) XposedHelpers.callMethod(bean, "isHdrRate");
        } catch (Throwable ignored) {}
        if (isHdr || upperName.contains("HDR") || "13".equals(rateStr)) {
            return 35000 + getBonusScore(bean, name);
        }

        // 梯队 4: 原画 / 蓝光8M / rate=0
        if (name.contains("原画") || "0".equals(rateStr) || name.contains("8M") || upperName.contains("8000")) {
            return 30000 + getBonusScore(bean, name);
        }

        // 梯队 5: 蓝光6M / 蓝光4M / 蓝光
        if (name.contains("6M") || name.contains("4M") || name.contains("蓝光")) {
            return 20000 + getBonusScore(bean, name);
        }

        // 梯队 6: 超清 / 1080P
        if (name.contains("超清") || name.contains("1080") || "2".equals(rateStr)) {
            return 10000 + getBonusScore(bean, name);
        }

        // 梯队 7: 高清 / 720P
        if (name.contains("高清") || name.contains("720") || "1".equals(rateStr)) {
            return 5000 + getBonusScore(bean, name);
        }

        // 梯队 8: 流畅 / 540P
        if (name.contains("流畅") || name.contains("540") || "3".equals(rateStr)) {
            return 1000 + getBonusScore(bean, name);
        }

        return 100 + getBonusScore(bean, name);
    }

    private static int getBonusScore(Object bean, String name) {
        int bonus = 0;
        if (name.contains("60")) {
            bonus += 500;
        }
        try {
            boolean isHighBit = (boolean) XposedHelpers.callMethod(bean, "isHighBitRate");
            if (isHighBit) bonus += 200;
        } catch (Throwable ignored) {}

        // 码率 bit 仅作为同梯队微调 (最大不超过 100 分，杜绝跨档位反超)
        try {
            String bit = (String) XposedHelpers.getObjectField(bean, "bit");
            if (!TextUtils.isEmpty(bit)) {
                int bitVal = Integer.parseInt(bit);
                if (bitVal > 0) {
                    bonus += Math.min(bitVal / 200, 100);
                }
            }
        } catch (Throwable ignored) {}

        return bonus;
    }

    public static boolean isWifiConnected(Context context) {
        if (context == null) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.net.Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                        if (caps != null) {
                            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                        }
                    }
                }
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    int type = info.getType();
                    return type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isWifiConnected check failed", t);
        }
        return false;
    }
}
