package io.github.ysl_ovo.douyuplus.hooks;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.ysl_ovo.douyuplus.DouyuPlusConfig;

public class WatermelonHook {
    private static final String TAG = "DouyuPlus-Watermelon";
    private static final Set<Class<?>> hookedClients = Collections.synchronizedSet(new HashSet<>());
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookSystemWebView();
        hookX5WebView(lpparam.classLoader);
    }

    private static void hookSystemWebView() {
        try {
            hookClientOnPageFinished(WebViewClient.class);
            XposedHelpers.findAndHookMethod(
                WebView.class,
                "setWebViewClient",
                WebViewClient.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object client = param.args[0];
                        if (client != null) {
                            hookClientOnPageFinished(client.getClass());
                        }
                    }
                }
            );
            XposedHelpers.findAndHookMethod(
                WebView.class,
                "loadUrl",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        scheduleInject(param.thisObject, param.args[0] != null ? param.args[0].toString() : "");
                    }
                }
            );
            XposedHelpers.findAndHookMethod(
                WebView.class,
                "loadUrl",
                String.class,
                Map.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        scheduleInject(param.thisObject, param.args[0] != null ? param.args[0].toString() : "");
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookSystemWebView failed: " + t.getMessage());
        }
    }

    private static void hookX5WebView(ClassLoader cl) {
        try {
            Class<?> x5WebViewClass = XposedHelpers.findClassIfExists("com.tencent.smtt.sdk.WebView", cl);
            Class<?> x5ClientClass = XposedHelpers.findClassIfExists("com.tencent.smtt.sdk.WebViewClient", cl);
            if (x5ClientClass != null) {
                hookClientOnPageFinished(x5ClientClass);
            }
            if (x5WebViewClass != null && x5ClientClass != null) {
                XposedHelpers.findAndHookMethod(
                    x5WebViewClass,
                    "setWebViewClient",
                    x5ClientClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object client = param.args[0];
                            if (client != null) {
                                hookClientOnPageFinished(client.getClass());
                            }
                        }
                    }
                );
            }
            if (x5WebViewClass != null) {
                XposedBridge.hookAllMethods(x5WebViewClass, "loadUrl", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String url = (param.args != null && param.args.length > 0 && param.args[0] != null)
                            ? param.args[0].toString() : "";
                        scheduleInject(param.thisObject, url);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookX5WebView failed: " + t.getMessage());
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
        if (url == null || url.isEmpty() || url.startsWith("about:") || url.startsWith("javascript:")) {
            return;
        }
        injectScript(webView);
        mainHandler.postDelayed(() -> injectScript(webView), 1500);
        mainHandler.postDelayed(() -> injectScript(webView), 3500);
    }

    private static void injectScript(final Object webView) {
        final String js = WatermelonScript.getScript();
        if (js == null || js.isEmpty()) {
            return;
        }
        mainHandler.post(() -> {
            try {
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
