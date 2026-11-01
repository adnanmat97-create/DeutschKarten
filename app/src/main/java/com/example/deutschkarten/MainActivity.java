package com.example.deutschkarten;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_ANKI_PACKAGE = 501;
    private static final int PICK_DOCUMENT = 502;
    private static final int PICK_NOJI_FILE = 503;
    private String pendingDocumentType = "pdf";
    private WebView webView;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();

    private TextToSpeech germanTts;
    private volatile boolean germanTtsReady = false;
    private volatile boolean germanTtsFailed = false;
    private volatile String pendingGermanSpeech = "";
    private volatile float germanSpeechRate = 0.85f;
    private final Object germanTtsLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidAnkiBridge(), "AndroidAnkiBridge");
        initializeGermanTts();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void openAnkiPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "application/x-zip-compressed"
        });
        startActivityForResult(intent, PICK_ANKI_PACKAGE);
    }

    private void openDocumentPicker(String type) {
        pendingDocumentType = type;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if ("image".equals(type)) {
            intent.setType("image/*");
        } else {
            intent.setType("application/pdf");
        }
        startActivityForResult(intent, PICK_DOCUMENT);
    }

    private void openNojiImportPicker(String format) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if ("ofc".equals(format)) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        } else {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "text/plain", "application/octet-stream"});
        }
        intent.putExtra("deutschkarten_import_format", format);
        startActivityForResult(intent, PICK_NOJI_FILE);
    }

    private String displayName(Uri uri) {
        String name = "anki-package.apkg";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Throwable ignored) {}
        return name;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DOCUMENT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                Toast.makeText(this, "تم إلغاء اختيار المستند.", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri documentUri = data.getData();
            String name = displayName(documentUri);
            String type = pendingDocumentType;
            Toast.makeText(this, "جارٍ استخراج النص محلياً…", Toast.LENGTH_SHORT).show();
            importExecutor.execute(() -> {
                JSONObject result = DocumentTextExtractor.extract(this, documentUri, type, name);
                final String payload = result.toString();
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.onNativeDocumentExtracted(" + JSONObject.quote(payload) + ");",
                                null
                        );
                    }
                });
            });
            return;
        }
        if (requestCode == PICK_NOJI_FILE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                Toast.makeText(this, "تم إلغاء اختيار ملف الاستيراد.", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri uri = data.getData();
            String name = displayName(uri);
            boolean isOfc = name.toLowerCase(Locale.ROOT).endsWith(".ofc");
            Toast.makeText(this, isOfc ? "جارٍ قراءة نسخة Noji الاحتياطية…" : "جارٍ قراءة ملف CSV…", Toast.LENGTH_SHORT).show();
            importExecutor.execute(() -> {
                JSONObject result = null;
                String csvText = null;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new Exception("لا يمكن فتح الملف المحدد.");
                    if (isOfc) {
                        result = NojiOFCImporter.importPackage(this, input, name);
                    } else {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[16384];
                        int read; long total = 0;
                        while ((read = input.read(buffer)) != -1) {
                            total += read;
                            if (total > 12L * 1024L * 1024L) throw new Exception("ملف CSV أكبر من 12 ميغابايت.");
                            output.write(buffer, 0, read);
                        }
                        csvText = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    }
                } catch (Throwable error) {
                    result = new JSONObject();
                    try {
                        result.put("success", false);
                        result.put("message", error.getMessage() == null ? "تعذر قراءة الملف." : error.getMessage());
                    } catch (Exception ignored) {}
                }
                final String payload = result == null ? null : result.toString();
                final String csvPayload = csvText;
                runOnUiThread(() -> {
                    if (webView == null) return;
                    if (payload != null) {
                        webView.evaluateJavascript("window.onNativeNojiImportResult(" + JSONObject.quote(payload) + ");", null);
                    } else if (csvPayload != null) {
                        webView.evaluateJavascript("window.onNativeCsvFileSelected(" + JSONObject.quote(csvPayload) + "," + JSONObject.quote(name) + ");", null);
                    }
                });
            });
            return;
        }
        if (requestCode != PICK_ANKI_PACKAGE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, "تم إلغاء اختيار ملف Anki.", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri = data.getData();
        String name = displayName(uri);
        Toast.makeText(this, "جارٍ قراءة ملف Anki…", Toast.LENGTH_SHORT).show();

        importExecutor.execute(() -> {
            JSONObject result;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new Exception("لا يمكن فتح الملف المحدد.");
                result = AnkiPackageImporter.importPackage(this, input, name);
            } catch (Throwable error) {
                result = new JSONObject();
                try {
                    result.put("success", false);
                    result.put("message", error.getMessage() == null ? "تعذّر فتح الملف." : error.getMessage());
                } catch (Exception ignored) {}
            }
            final String payload = result.toString();
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "window.onNativeAnkiImportResult(" + JSONObject.quote(payload) + ");",
                            null
                    );
                }
            });
        });
    }

    public class AndroidAnkiBridge {
        @JavascriptInterface
        public void importPackage() {
            runOnUiThread(() -> openAnkiPicker());
        }

        @JavascriptInterface
        public void importNojiOFC() {
            runOnUiThread(() -> openNojiImportPicker("ofc"));
        }

        @JavascriptInterface
        public void importCsvFile() {
            runOnUiThread(() -> openNojiImportPicker("csv"));
        }

        @JavascriptInterface
        public void importPdf() {
            runOnUiThread(() -> openDocumentPicker("pdf"));
        }

        @JavascriptInterface
        public void importImage() {
            runOnUiThread(() -> openDocumentPicker("image"));
        }

        @JavascriptInterface
        public boolean speakGerman(String text, float rate) {
            if (text == null || text.trim().isEmpty()) return false;
            String safeText = text.trim();
            if (safeText.length() > 2000) safeText = safeText.substring(0, 2000);
            germanSpeechRate = Math.max(0.5f, Math.min(1.2f, rate));
            synchronized (germanTtsLock) {
                if (germanTtsFailed) return false;
                if (!germanTtsReady || germanTts == null) {
                    pendingGermanSpeech = safeText;
                    return true;
                }
            }
            return speakGermanNow(safeText);
        }

        @JavascriptInterface
        public boolean isGermanTtsReady() {
            return germanTtsReady;
        }

        @JavascriptInterface
        public boolean isGermanTtsUnavailable() {
            return germanTtsFailed;
        }

        @JavascriptInterface
        public void setGermanSpeechRate(float rate) {
            germanSpeechRate = Math.max(0.5f, Math.min(1.2f, rate));
            TextToSpeech engine = germanTts;
            if (germanTtsReady && engine != null) {
                engine.setSpeechRate(germanSpeechRate);
            }
        }

        @JavascriptInterface
        public void stopGermanSpeech() {
            TextToSpeech engine = germanTts;
            if (engine != null) engine.stop();
        }

        @JavascriptInterface
        public boolean saveAiConfig(String endpoint, String accessToken) {
            return AiSecureConfig.save(MainActivity.this, endpoint, accessToken);
        }

        @JavascriptInterface
        public boolean hasAiConfig() {
            return AiSecureConfig.isConfigured(MainActivity.this);
        }

        @JavascriptInterface
        public String getAiEndpoint() {
            return AiSecureConfig.getEndpoint(MainActivity.this);
        }

        @JavascriptInterface
        public void clearAiConfig() {
            AiSecureConfig.clear(MainActivity.this);
        }

        @JavascriptInterface
        public void generateCards(String source, String level, String language, int count) {
            importExecutor.execute(() -> {
                JSONObject result;
                try {
                    result = AiFlashcardClient.generate(MainActivity.this, source, level, language, count);
                } catch (Throwable error) {
                    result = new JSONObject();
                    try {
                        result.put("success", false);
                        String message = error.getMessage();
                        result.put("message", message == null ? "تعذّر إنشاء البطاقات." : message);
                    } catch (Exception ignored) {}
                }
                final String payload = result.toString();
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.onAiCardsGenerated(" + JSONObject.quote(payload) + ");",
                                null
                        );
                    }
                });
            });
        }
    }

    private void initializeGermanTts() {
        germanTts = new TextToSpeech(this, status -> {
            boolean ready = false;
            if (status == TextToSpeech.SUCCESS && germanTts != null) {
                int languageStatus = germanTts.setLanguage(Locale.GERMANY);
                if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                        || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                    languageStatus = germanTts.setLanguage(Locale.GERMAN);
                }
                ready = languageStatus >= TextToSpeech.LANG_AVAILABLE;
                if (ready) germanTts.setSpeechRate(germanSpeechRate);
            }

            String queued = "";
            synchronized (germanTtsLock) {
                germanTtsReady = ready;
                germanTtsFailed = !ready;
                if (ready) {
                    queued = pendingGermanSpeech;
                    pendingGermanSpeech = "";
                } else {
                    pendingGermanSpeech = "";
                }
            }

            if (ready && !queued.isEmpty()) speakGermanNow(queued);
            final boolean finalReady = ready;
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "if(window.onNativeTtsReady)window.onNativeTtsReady("
                                    + finalReady + ");",
                            null
                    );
                }
            });
        });
    }

    private boolean speakGermanNow(String text) {
        TextToSpeech engine = germanTts;
        if (!germanTtsReady || engine == null || text == null || text.trim().isEmpty()) {
            return false;
        }
        engine.setSpeechRate(germanSpeechRate);
        int result = engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "deutschkarten-" + System.currentTimeMillis()
        );
        return result == TextToSpeech.SUCCESS;
    }

    @Override
    protected void onDestroy() {
        importExecutor.shutdownNow();
        if (germanTts != null) {
            germanTts.stop();
            germanTts.shutdown();
            germanTts = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidAnkiBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
