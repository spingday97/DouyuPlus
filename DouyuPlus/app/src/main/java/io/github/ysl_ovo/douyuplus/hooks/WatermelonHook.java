package io.github.ysl_ovo.douyuplus.hooks;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.PopupWindow;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;

public class WatermelonHook {
    private static final String TAG = "DouyuPlus";
    private static final Set<Class<?>> hookedClients = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> toastedViews = Collections.synchronizedSet(new HashSet<>());
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.i(TAG, "[Watermelon] init in process: " + lpparam.processName);
        hookWebViewClass(WebView.class, WebViewClient.class);
        hookByName(lpparam.classLoader, "com.tencent.smtt.sdk.WebView", "com.tencent.smtt.sdk.WebViewClient");
        hookByName(lpparam.classLoader, "com.uc.webview.export.WebView", "com.uc.webview.export.WebViewClient");
        hookDebugInspectors();
    }

    private static void hookDebugInspectors() {
        try {
            XposedBridge.hookAllConstructors(SurfaceView.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (DouyuPlusConfig.isDebugModeEnabled() && param.thisObject != null) {
                        Log.i(TAG, "[Watermelon-Probe] SurfaceView: " + param.thisObject.getClass().getName());
                    }
                }
            });
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (DouyuPlusConfig.isDebugModeEnabled() && param.thisObject instanceof Dialog) {
                        Dialog d = (Dialog) param.thisObject;
                        Log.i(TAG, "[Watermelon-Probe] Dialog.show: " + d.getClass().getName());
                        if (d.getWindow() != null) {
                            dumpViews(d.getWindow().getDecorView(), 0);
                        }
                    }
                }
            });
            XposedBridge.hookAllConstructors(TextureView.class, new XC_MethodHook() {
               @Override protected void afterHookedMethod(MethodHookParam param) { if (DouyuPlusConfig.isDebugModeEnabled() && param.thisObject != null) { Log.i(TAG, "[Watermelon-Probe] TextureView: " + param.thisObject.getClass().getName()); } } });
            XC_MethodHook popupHook = new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam param) { if (DouyuPlusConfig.isDebugModeEnabled() && param.thisObject instanceof PopupWindow) { PopupWindow pw = (PopupWindow) param.thisObject; Log.i(TAG, "[Watermelon-Probe] PopupWindow: " + pw.getClass().getName()); if (pw.getContentView() != null) { dumpViews(pw.getContentView(), 0); } } } }; XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popupHook); XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popupHook);
        } catch (Throwable t) {
            Log.w(TAG, "[Watermelon] hookDebugInspectors failed: " + t.getMessage());
        }
    }

    private static void dumpViews(View v, int depth) {
        if (v == null || depth > 8) return;
        String name = v.getClass().getName();
        String lower = name.toLowerCase();
        if (lower.contains("web") || lower.contains("cocos") || lower.contains("game") || lower.contains("surface") || lower.contains("texture") || lower.contains("tbs") || lower.contains("x5") || lower.contains("chromium") || lower.contains("lynx") || lower.contains("rn") || lower.contains("mini") || lower.contains("xwalk")) {
            Log.i(TAG, "[Watermelon-Probe] View@" + depth + ": " + name);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpViews(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private static void hookByName(ClassLoader cl, String webViewName, String clientName) {
        try {
            Class<?> wvClass = XposedHelpers.findClassIfExists(webViewName, cl);
            Class<?> clientClass = XposedHelpers.findClassIfExists(clientName, cl);
            Log.i(TAG, "[Watermelon] class " + webViewName + " -> " + (wvClass != null));
            if (wvClass != null) {
                hookWebViewClass(wvClass, clientClass);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[Watermelon] hookByName " + webViewName + " failed: " + t.getMessage());
        }
    }

    private static void hookWebViewClass(Class<?> wvClass, Class<?> baseClientClass) {
        try {
            if (baseClientClass != null) {
                hookClientOnPageFinished(baseClientClass);
            }
            XposedBridge.hookAllConstructors(wvClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "[Watermelon] ctor: " + param.thisObject.getClass().getName());
                    scheduleInject(param.thisObject, "ctor://init");
                }
            });
            XposedBridge.hookAllMethods(wvClass, "setWebViewClient", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        hookClientOnPageFinished(param.args[0].getClass());
                    }
                }
            });
            String[] loadMethods = new String[]{"loadUrl", "loadData", "loadDataWithBaseURL", "postUrl"};
            for (String m : loadMethods) {
                XposedBridge.hookAllMethods(wvClass, m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String url = (param.args != null && param.args.length > 0 && param.args[0] != null)
                            ? param.args[0].toString() : "load://call";
                        Log.i(TAG, "[Watermelon] " + m + ": " + url);
                        scheduleInject(param.thisObject, url);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "[Watermelon] hookWebViewClass " + wvClass.getName() + " failed: " + t.getMessage());
        }
    }

    private static void hookClientOnPageFinished(Class<?> clientClass) {
        if (hookedClients.contains(clientClass)) {
            return;
        }
        hookedClients.add(clientClass);
        try {
            XposedBridge.hookAllMethods(clientClass, "onPageFinished", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    final Object webView = param.args[0];
                    final String url = param.args[1] != null ? param.args[1].toString() : "";
                    Log.i(TAG, "[Watermelon] onPageFinished: " + url);
                    scheduleInject(webView, url);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[Watermelon] hookClientOnPageFinished failed: " + t.getMessage());
        }
    }

    private static void scheduleInject(final Object webView, final String url) {
        if (!DouyuPlusConfig.isWatermelonEnabled() || webView == null) {
            return;
        }
        if (url != null && (url.startsWith("about:") || url.startsWith("javascript:"))) {
            return;
        }
        injectScript(webView, url);
        mainHandler.postDelayed(() -> injectScript(webView, url), 1500);
        mainHandler.postDelayed(() -> injectScript(webView, url), 3500);
        mainHandler.postDelayed(() -> injectScript(webView, url), 6000);
    }

    private static void injectScript(final Object webView, final String url) {
        final String js = WatermelonScript.getScript();
        if (js == null || js.isEmpty()) {
            Log.w(TAG, "[Watermelon] empty script!");
            return;
        }
        mainHandler.post(() -> {
            try {
                if (DouyuPlusConfig.isDebugModeEnabled()) {
                    int id = System.identityHashCode(webView);
                    if (toastedViews.add(id)) {
                        Context ctx = (Context) XposedHelpers.callMethod(webView, "getContext");
                        if (ctx != null) {
                            Toast.makeText(ctx, "DouyuPlus注入: " + webView.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                if (webView instanceof WebView) {
                    ((WebView) webView).evaluateJavascript(js, null);
                } else {
                    XposedHelpers.callMethod(webView, "evaluateJavascript", js, null);
                }
            } catch (Throwable t) {
                Log.w(TAG, "[Watermelon] injectScript failed: " + t.getMessage());
            }
        });
    }
}
