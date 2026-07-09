package com.ginkhao.borne;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // ════════════════════════════════════════════════════════════
    //  ⚙️ CONFIGURATION — Mets ici l'URL de ta borne
    // ════════════════════════════════════════════════════════════
    private static final String BORNE_URL = "https://gin-khao-manager.netlify.app/borne.html";

    private WebView webView;
    private UsbEscPosPrinter printer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Écran toujours allumé (borne)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Module d'impression USB
        printer = new UsbEscPosPrinter(this);

        // Permission caméra (pour le scan QR fidélité dans la WebView)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
        }

        // ── WebView plein écran ──
        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Pont JS : window.AndroidPrinter.print(base64) depuis le HTML
        webView.addJavascriptInterface(new PrinterBridge(), "AndroidPrinter");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // On reste dans la WebView pour http/https, on bloque le reste
                // (rawbt:, intent:, mailto:... inutiles maintenant)
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Autorise la caméra pour le scanner QR html5-qrcode
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.loadUrl(BORNE_URL);
    }

    // ────────────────────────────────────────────────────────────
    //  Pont JavaScript → impression native
    // ────────────────────────────────────────────────────────────
    private class PrinterBridge {

        /** Reçoit le ticket ESC/POS encodé en base64 et l'imprime. */
        @JavascriptInterface
        public void print(String base64Data) {
            try {
                byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                printer.print(data);
            } catch (Exception e) {
                Log.e("PrinterBridge", "Erreur décodage/impression : " + e.getMessage());
            }
        }

        /** true si l'imprimante est branchée et autorisée. */
        @JavascriptInterface
        public boolean isReady() {
            return printer.isReady();
        }

        /** true si une imprimante USB est détectée. */
        @JavascriptInterface
        public boolean isConnected() {
            return printer.isConnected();
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Mode kiosk : plein écran immersif + blocage bouton retour
    // ────────────────────────────────────────────────────────────

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Bouton retour désactivé (borne) — on ne sort pas de l'app
        // Si la WebView peut revenir en arrière, on la laisse faire
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        }
        // Sinon : rien (on ne quitte pas)
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
