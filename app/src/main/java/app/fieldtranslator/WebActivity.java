package app.fieldtranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The full app (same as the iPhone version): the web app bundled inside the APK and shown in a WebView.
 * Every file is served straight from the APK under a private https address, so it works with no internet
 * and the app still has no INTERNET permission.
 */
public class WebActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    private static final String TAG = "FTWEB";
    private static final int REQ_MIC = 1, REQ_FILE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMic;
    private boolean selfTest;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        selfTest = getIntent().getBooleanExtra("selftest", false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (selfTest) WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0C10"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // chats, saved phrases, settings
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // read-aloud voice
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);                // photos from the camera / gallery
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return blocked();     // nothing ever leaves the phone
                return serve(u.getPath());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (selfTest) runSelfTest();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleMic(request));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                return openChooser(params.isCaptureEnabled());
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, m.message());
                return true;
            }
        });
        setContentView(web);
        if (saved != null) web.restoreState(saved);
        else web.loadUrl(START);
    }

    // ------------------------------------------------------------------ files from the APK
    private WebResourceResponse serve(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        String p = path.substring(1);
        InputStream in = open("web/" + p);
        if (in == null && p.startsWith("models/")) {
            // the big models are shared with the classic app: models/mt/ru-en/encoder.onnx~1a2b3c -> mt/ru-en/encoder.onnx
            in = open(p.substring(7).replaceAll("~[0-9a-f]+$", ""));
        }
        if (in == null) {
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", null);
            r.setStatusCodeAndReasonPhrase(404, "Not Found");
            return r;
        }
        if (p.equals("sw.js")) {                 // no service worker needed: every file is already on the phone
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", null);
            r.setStatusCodeAndReasonPhrase(404, "Not Found");
            try { in.close(); } catch (IOException ignored) { }
            return r;
        }
        if (p.equals("js/store.js")) in = new SequenceInputStream(in, new ByteArrayInputStream(STORE_SHIM.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        WebResourceResponse r = new WebResourceResponse(mime(p), null, in);
        r.setResponseHeaders(headers);
        return r;
    }

    /**
     * Added to the end of the web app's file store when it runs inside this app (the web app itself is not
     * changed): files are read straight from the APK instead of being downloaded and cached a second time.
     */
    static final String STORE_SHIM = "\n;(function () {\n" +
        "  var S = self.FTStore; if (!S) return;\n" +
        "  S.isCached = function () { return Promise.resolve(true); };\n" +
        "  S.getFile = function (url, asText) { return fetch(url).then(function (r) {\n" +
        "    if (!r.ok) throw new Error('missing file: ' + url + ' (' + r.status + ')');\n" +
        "    return asText ? r.text() : r.arrayBuffer().then(function (b) { return new Uint8Array(b); }); }); };\n" +
        "  S.downloadAll = function (base, files, progress) {\n" +
        "    var t = files.reduce(function (a, f) { return a + f.size; }, 0); progress(t, t); return Promise.resolve(); };\n" +
        "})();\n";

    private InputStream open(String asset) {
        try {
            return getAssets().open(asset, AssetManager.ACCESS_STREAMING);
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static WebResourceResponse blocked() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", null);
        r.setStatusCodeAndReasonPhrase(403, "Offline app");
        return r;
    }

    static String mime(String p) {
        p = p.replaceAll("~[0-9a-f]+$", "");
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".txt") || p.endsWith(".tsv")) return "text/plain";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ microphone
    private void handleMic(PermissionRequest request) {
        boolean wantsMic = false;
        for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
        if (!wantsMic) { request.deny(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingMic = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC && pendingMic != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingMic.deny();
            pendingMic = null;
        }
    }

    // ------------------------------------------------------------------ camera / gallery for the text reader
    private boolean openChooser(boolean camera) {
        Intent intent;
        if (camera) {
            PhotoProvider.clear(this);                       // the last photo is never kept
            intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.uri(this));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        try {
            startActivityForResult(intent, REQ_FILE);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "no app for " + intent.getAction(), e);
            if (camera) return openChooser(false);           // no camera app: fall back to the gallery
            fileCallback.onReceiveValue(null);
            fileCallback = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQ_FILE || fileCallback == null) { super.onActivityResult(code, result, data); return; }
        Uri[] out = null;
        if (result == RESULT_OK) {
            if (data != null && data.getData() != null) out = new Uri[]{data.getData()};
            else if (PhotoProvider.file(this).length() > 0) out = new Uri[]{PhotoProvider.uri(this)};
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    // ------------------------------------------------------------------ Back button walks back through the app
    @Override
    public void onBackPressed() {
        // inner screens of the app have a "Back" button at the top: press it; on the main screen, leave the app
        web.evaluateJavascript("(function () { var b = document.querySelector('#learn > button');" +
            " if (b && /Back/.test(b.textContent)) { b.click(); return true; } return false; })()", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        PhotoProvider.clear(this);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ automatic check used by the build
    private void runSelfTest() {
        web.evaluateJavascript(
            "(async () => {" +
            "  const t0 = Date.now();" +
            "  while (!document.querySelector('.listen:not([disabled])')) {" +
            "    if (Date.now() - t0 > 600000) throw new Error('app never became ready: ' + document.body.innerText.slice(0, 200));" +
            "    await new Promise(r => setTimeout(r, 500));" +
            "  }" +
            "  console.log('FTSELF ready in ' + (Date.now() - t0) + ' ms');" +
            "  const r = await window.FTSelfTest();" +
            "  console.log('FTSELF RESULT ' + JSON.stringify(r));" +
            "})().catch(e => console.log('FTSELF ERROR ' + (e && e.stack || e)));", null);
    }
}
