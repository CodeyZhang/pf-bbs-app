package cn.peopleforever.bbs;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private boolean pageHandled = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 让 WebView 在后台继续运行 + 加 UA 标记
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);

            String ua = settings.getUserAgentString();
            if (ua != null && !ua.contains("PFBBSApp")) {
                settings.setUserAgentString(ua + " PFBBSApp/1.0");
            }
        }
        CookieManager.getInstance().setAcceptCookie(true);

        // 启动前台服务保活
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Android 13+ 请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        syncCookieToService();
        notifyServiceForeground(true);
        handleOpenPage(getIntent());
    }

    @Override
    public void onPause() {
        super.onPause();
        // 关键：不调用 webView.onPause()，让 SSE 继续运行
        syncCookieToService();
        notifyServiceForeground(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenPage(intent);
    }

    // 把 WebView 的 cookie 同步给 Service，让它能调未读接口
    private void syncCookieToService() {
        try {
            String cookies = CookieManager.getInstance().getCookie("https://bbs.peopleforever.cn");
            if (cookies != null && !cookies.isEmpty()) {
                SharedPreferences sp = getSharedPreferences("pf_bbs", MODE_PRIVATE);
                sp.edit().putString("cookie", cookies).apply();
            }
        } catch (Exception ignored) {}
    }

    // 通过 Intent action 更新 Service 的前后台标志
    private void notifyServiceForeground(boolean foreground) {
        try {
            Intent svc = new Intent(this, KeepAliveService.class);
            svc.setAction(foreground ? "APP_FOREGROUND" : "APP_BACKGROUND");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception ignored) {}
    }

    // 通知点击进来时，如果带了 open_page=messages，跳转到私信页
    private void handleOpenPage(Intent intent) {
        if (intent == null || pageHandled) return;
        String page = intent.getStringExtra("open_page");
        if ("messages".equals(page)) {
            WebView webView = getBridge().getWebView();
            if (webView != null) {
                pageHandled = true;
                webView.post(() -> webView.loadUrl("https://bbs.peopleforever.cn/messages.html"));
            }
        }
    }

    @Override
    public void onBackPressed() {
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            String url = webView.getUrl();
            if (url != null && url.contains("messages.html")) {
                webView.evaluateJavascript(
                        "window.handleAndroidBack ? window.handleAndroidBack() : false",
                        null
                );
                return;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return;
            }
        }
        super.onBackPressed();
    }
}
