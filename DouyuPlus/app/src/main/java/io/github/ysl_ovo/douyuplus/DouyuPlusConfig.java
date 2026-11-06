package io.github.ysl_ovo.douyuplus;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

public class DouyuPlusConfig {
    private static final String PREF_NAME = "douyuplus_settings";
    private static volatile SharedPreferences sPrefs;

    public static final String KEY_AD_BLOCK = "enable_ad_block";
    public static final String KEY_PAD_MODE = "enable_pad_mode";
    public static final String KEY_HOME_FOLLOW_TAB = "enable_home_follow_tab";
    public static final String KEY_DANMU_CLEAN = "enable_danmu_clean";
    public static final String KEY_DANMU_SUFFIX = "enable_danmu_suffix";
    public static final String KEY_DANMU_MERGE = "enable_danmu_merge";
    public static final String KEY_ADD_ONE_NO_CD = "enable_add_one_no_cd";
    public static final String KEY_PERFORMANCE = "enable_performance";
    public static final String KEY_AI_STRENGTHEN = "enable_ai_strengthen";
    public static final String KEY_DEBUG_MODE = "enable_debug_mode";
    public static final String KEY_AI_STRENGTHEN_SGSR_LEVEL = "ai_strengthen_sgsr_level";
    public static final String KEY_AI_STRENGTHEN_CAS_LEVEL = "ai_strengthen_cas_level";
    public static final int DEFAULT_SGSR_LEVEL = 60;
    public static final int DEFAULT_CAS_LEVEL = 70;

    public static final String KEY_AUTO_QUALITY_MODE = "auto_quality_mode";
    public static final int AUTO_QUALITY_OFF = 0;
    public static final int AUTO_QUALITY_WIFI_ONLY = 1;
    public static final int AUTO_QUALITY_ALWAYS = 2;
    public static final int DEFAULT_AUTO_QUALITY_MODE = AUTO_QUALITY_WIFI_ONLY;

    public static final String KEY_HIDE_BOTTOM_PLUS = "hide_bottom_plus";
    public static final boolean DEFAULT_HIDE_BOTTOM_PLUS = true;

    public static final String KEY_DEFAULT_START_PAGE = "default_start_page";
    public static final int START_PAGE_HOME = 0;
    public static final int START_PAGE_FOLLOW = 1;
    public static final int START_PAGE_DYNAMIC = 2;
    public static final int START_PAGE_MINE = 3;
    public static final int DEFAULT_START_PAGE = START_PAGE_HOME;

    public static void init(Context context) {
        if (sPrefs == null && context != null) {
            Context appCtx = context.getApplicationContext();
            if (appCtx == null) {
                appCtx = context;
            }
            sPrefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        if (sPrefs == null && context != null) {
            init(context);
        }
        if (sPrefs == null) {
            try {
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Application app = (Application) atCls.getMethod("currentApplication").invoke(null);
                if (app != null) {
                    init(app);
                }
            } catch (Throwable ignored) {}
        }
        return sPrefs;
    }

    public static boolean isEnabled(String key, boolean defValue) {
        SharedPreferences prefs = getPrefs(null);
        return prefs != null ? prefs.getBoolean(key, defValue) : defValue;
    }

    public static boolean isEnabled(Context context, String key, boolean defValue) {
        SharedPreferences prefs = getPrefs(context);
        return prefs != null ? prefs.getBoolean(key, defValue) : defValue;
    }

    public static void setEnabled(Context context, String key, boolean value) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().putBoolean(key, value).apply();
        }
    }

    public static boolean isPadModeEnabled() {
        return isEnabled(KEY_PAD_MODE, false);
    }

    public static boolean isAdBlockEnabled() {
        return isEnabled(KEY_AD_BLOCK, true);
    }

    public static boolean isHomeFollowTabEnabled() {
        return isEnabled(KEY_HOME_FOLLOW_TAB, true);
    }

    public static boolean isDanmuCleanEnabled() {
        return isEnabled(KEY_DANMU_CLEAN, true);
    }

    public static boolean isDanmuSuffixEnabled() {
        return isEnabled(KEY_DANMU_SUFFIX, true);
    }

    public static boolean isDanmuMergeEnabled() {
        return isEnabled(KEY_DANMU_MERGE, true);
    }

    public static boolean isAddOneNoCdEnabled() {
        return isEnabled(KEY_ADD_ONE_NO_CD, true);
    }

    public static boolean isPerformanceEnabled() {
        return isEnabled(KEY_PERFORMANCE, true);
    }

    public static boolean isAiStrengthenEnabled() {
        return isEnabled(KEY_AI_STRENGTHEN, true);
    }

    public static boolean isDebugModeEnabled() {
        return isEnabled(KEY_DEBUG_MODE, false);
    }

    public static int getSgsrLevel(Context context) {
        SharedPreferences prefs = getPrefs(context);
        return prefs != null ? prefs.getInt(KEY_AI_STRENGTHEN_SGSR_LEVEL, DEFAULT_SGSR_LEVEL) : DEFAULT_SGSR_LEVEL;
    }

    public static void setSgsrLevel(Context context, int level) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().putInt(KEY_AI_STRENGTHEN_SGSR_LEVEL, level).apply();
        }
    }

    public static int getCasLevel(Context context) {
        SharedPreferences prefs = getPrefs(context);
        return prefs != null ? prefs.getInt(KEY_AI_STRENGTHEN_CAS_LEVEL, DEFAULT_CAS_LEVEL) : DEFAULT_CAS_LEVEL;
    }

    public static void setCasLevel(Context context, int level) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().putInt(KEY_AI_STRENGTHEN_CAS_LEVEL, level).apply();
        }
    }

    public static int getPackedEnhanceLevel() {
        return getPackedEnhanceLevel(null);
    }

    public static int getPackedEnhanceLevel(Context context) {
        int sgsr = getSgsrLevel(context);
        int cas = getCasLevel(context);
        return 100000 + sgsr * 1000 + cas;
    }

    public static int getAutoQualityMode(Context context) {
        SharedPreferences prefs = getPrefs(context);
        return prefs != null ? prefs.getInt(KEY_AUTO_QUALITY_MODE, DEFAULT_AUTO_QUALITY_MODE) : DEFAULT_AUTO_QUALITY_MODE;
    }

    public static void setAutoQualityMode(Context context, int mode) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().putInt(KEY_AUTO_QUALITY_MODE, mode).apply();
        }
    }

    public static boolean isHideBottomPlus() {
        return isHideBottomPlus(null);
    }

    public static boolean isHideBottomPlus(Context context) {
        return isEnabled(context, KEY_HIDE_BOTTOM_PLUS, DEFAULT_HIDE_BOTTOM_PLUS);
    }

    public static void setHideBottomPlus(Context context, boolean hide) {
        setEnabled(context, KEY_HIDE_BOTTOM_PLUS, hide);
    }

    public static int getDefaultStartPage() {
        return getDefaultStartPage(null);
    }

    public static int getDefaultStartPage(Context context) {
        SharedPreferences prefs = getPrefs(context);
        return prefs != null ? prefs.getInt(KEY_DEFAULT_START_PAGE, DEFAULT_START_PAGE) : DEFAULT_START_PAGE;
    }

    public static void setDefaultStartPage(Context context, int page) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().putInt(KEY_DEFAULT_START_PAGE, page).apply();
        }
    }
}
