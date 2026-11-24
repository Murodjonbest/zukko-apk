package uz.zukko.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.*;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Locale;

/** Zukko: offline language-learning app. All content is bundled in assets; no network access is used. */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    WebView web; FrameLayout root; View custom; WebChromeClient.CustomViewCallback customCb;
    ValueCallback<Uri[]> fileCb; TextToSpeech tts; boolean ttsOk;
    SpeechRecognizer sr; String pendingLang;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#2B44C2"));
        web = new WebView(this);
        root.addView(web);
        setContentView(root);
        // Android 15+ draws edge-to-edge: keep content clear of status/navigation bars and keyboard.
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; bt = i.bottom;
            } else {
                l = ins.getSystemWindowInsetLeft(); t = ins.getSystemWindowInsetTop(); r = ins.getSystemWindowInsetRight(); bt = ins.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, bt);
            return ins;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true); s.setAllowFileAccessFromFileURLs(true);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        tts = new TextToSpeech(this, this);
        web.addJavascriptInterface(new TtsBridge(), "AndroidTTS");
        web.addJavascriptInterface(new SttBridge(), "AndroidSTT");
        web.addJavascriptInterface(new RemBridge(), "AndroidRem");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return ext(r.getUrl().toString()); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                String t = "*/*"; String[] a = p.getAcceptTypes();
                if (a != null && a.length > 0 && a[0] != null && a[0].indexOf('/') > 0) t = a[0];
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(t);
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Faylni tanlang"), 7); } catch (Throwable e) { fileCb = null; return false; }
                return true;
            }
            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (custom != null) { cb.onCustomViewHidden(); return; }
                custom = v; customCb = cb; root.addView(v); web.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            @Override public void onHideCustomView() {
                if (custom == null) return;
                root.removeView(custom); custom = null; web.setVisibility(View.VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden(); customCb = null;
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            @Override public void onPermissionRequest(PermissionRequest req) { req.deny(); }
        });
        web.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT >= 33 && getSharedPreferences("rem", MODE_PRIVATE).getBoolean("on", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
    }

    /** Links (YouTube, official exam sites) open in the phone's own apps. */
    boolean ext(String u) {
        if (u.startsWith("file:") || u.startsWith("blob:") || u.startsWith("data:") || u.startsWith("about:")) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Throwable t) { }
        return true;
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req != 7 || fileCb == null) return;
        Uri[] r = null;
        if (res == RESULT_OK && d != null) {
            try {
                if (d.getClipData() != null) { int n = d.getClipData().getItemCount(); r = new Uri[n]; for (int i = 0; i < n; i++) r[i] = d.getClipData().getItemAt(i).getUri(); }
                else if (d.getData() != null) r = new Uri[]{d.getData()};
            } catch (Throwable t) { }
        }
        fileCb.onReceiveValue(r); fileCb = null;
    }

    @Override public void onBackPressed() {
        if (custom != null) { web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null); return; }
        if (web.getUrl() != null && !web.getUrl().endsWith("/index.html") && web.canGoBack()) { web.goBack(); return; }
        web.evaluateJavascript("(window.__back&&window.__back())?1:0", v -> { if (!"1".equals(v)) finish(); });
    }

    @Override public void onInit(int st) { ttsOk = st == TextToSpeech.SUCCESS; }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] g) {
        if (code == 11) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED && pendingLang != null) startStt(pendingLang);
            else js("__sttErr('Mikrofonga ruxsat berilmadi')");
        }
    }

    @Override protected void onDestroy() {
        try { tts.shutdown(); } catch (Throwable t) { }
        try { if (sr != null) sr.destroy(); } catch (Throwable t) { }
        super.onDestroy();
    }

    void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }
    static String q(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"; }

    void startStt(String lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { js("__sttErr('Bu telefonda ovozni tanish xizmati yoʻq')"); return; }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle p) { js("__sttState('listening')"); }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { }
                public void onError(int e) {
                    String m = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "Ovoz eshitilmadi, qayta urinib koʻring"
                            : e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "Internetsiz ovoz tanish uchun telefon sozlamalarida shu tilning oflayn paketini yuklab oling"
                            : "Ovozni tanib boʻlmadi";
                    js("__sttErr(" + q(m) + ")");
                }
                public void onResults(Bundle r) {
                    ArrayList<String> a = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    js("__sttFinal(" + q(a != null && !a.isEmpty() ? a.get(0) : "") + ")");
                }
                public void onPartialResults(Bundle r) { }
                public void onEvent(int t, Bundle p) { }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        sr.startListening(i);
    }

    class TtsBridge {
        @JavascriptInterface public boolean ok() { return ttsOk; }
        @JavascriptInterface public void speak(String text, String lang, float rate, boolean add) {
            if (!ttsOk) return;
            try {
                if (lang != null && lang.length() > 0) tts.setLanguage(Locale.forLanguageTag(lang));
                tts.setSpeechRate(rate > 0 ? rate : 1f);
                tts.speak(text, add ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, null, "t" + System.nanoTime());
            } catch (Throwable t) { }
        }
        @JavascriptInterface public void stop() { try { tts.stop(); } catch (Throwable t) { } }
    }

    class SttBridge {
        @JavascriptInterface public void start(String lang) {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingLang = lang; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11); return;
                }
                startStt(lang);
            });
        }
        @JavascriptInterface public void stop() { runOnUiThread(() -> { if (sr != null) sr.stopListening(); }); }
    }

    class RemBridge {
        @JavascriptInterface public void set(int h, int m) {
            getSharedPreferences("rem", MODE_PRIVATE).edit().putBoolean("on", true).putInt("h", h).putInt("m", m).apply();
            Reminder.schedule(MainActivity.this);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21));
        }
        @JavascriptInterface public void cancel() {
            getSharedPreferences("rem", MODE_PRIVATE).edit().putBoolean("on", false).apply();
            Reminder.cancel(MainActivity.this);
        }
    }
}
