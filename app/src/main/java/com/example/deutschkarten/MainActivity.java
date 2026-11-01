package com.example.deutschkarten;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_ANKI_PACKAGE = 501;
    private static final int PICK_DOCUMENT = 502;
    private String pendingDocumentType = "pdf";
    private WebView webView;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();

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
        public void importPdf() {
            runOnUiThread(() -> openDocumentPicker("pdf"));
        }

        @JavascriptInterface
        public void importImage() {
            runOnUiThread(() -> openDocumentPicker("image"));
        }
    }

    @Override
    protected void onDestroy() {
        importExecutor.shutdownNow();
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
