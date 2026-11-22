package io.github.ysl_ovo.douyuplus.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;

public class WatermelonHook {
    private static final String TAG = "DouyuPlus-Watermelon";
    private static final Set<Class<?>> hookedClients = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> toastedViews = Collections.synchronizedSet(new HashSet<>());
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookWebViewClass(WebView.class, WebViewClient.class);
        hookByName(lpparam.classLoader, "com.tencent.smtt.sdk.WebView", "com.tencent.smtt.sdk.WebViewClient");
        hookByName(lpparam.classLoader, "com.uc.webview.export.WebView", "com.uc.webview.export.WebViewClient");
    }

    private static void hookByName(ClassLoader cl, String webViewName, String clientName) {
        try {
            Class<?> wvClass = XposedHelpers.findClassIfExists(webViewName, cl);
            Class<?> clientClass = XposedHelpers.findClassIfExists(clientName, cl);
            if (wvClass != null) {
                hookWebViewClass(wvClass, clientClass);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookByName " + webViewName + " failed: " + t.getMessage());
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
                        scheduleInject(param.thisObject, url);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookWebViewClass " + wvClass.getName() + " failed: " + t.getMessage());
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
                    scheduleInject(webView, url);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookClientOnPageFinished failed: " + t.getMessage());
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
                XposedBridge.log(TAG + " injectScript failed: " + t.getMessage());
            }
        });
    }
}
