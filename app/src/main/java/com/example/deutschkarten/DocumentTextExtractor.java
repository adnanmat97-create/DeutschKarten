package com.example.deutschkarten;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class DocumentTextExtractor {
    private static final long MAX_INPUT_BYTES = 45L * 1024L * 1024L;
    private static final int MAX_PDF_PAGES = 30;
    private static final int MAX_OCR_PAGES = 15;
    private static final int MAX_OUTPUT_CHARS = 350000;

    private DocumentTextExtractor() {}

    public static JSONObject extract(Context context, Uri uri, String type, String displayName) {
        File temp = null;
        try {
            temp = copyToCache(context, uri);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            try {
                String text;
                int pages = 0;
                int ocrPages = 0;
                if ("image".equals(type)) {
                    InputImage image = InputImage.fromFilePath(context, uri);
                    Text result = Tasks.await(recognizer.process(image));
                    text = result.getText();
                    ocrPages = 1;
                } else {
                    PDFBoxResourceLoader.init(context.getApplicationContext());
                    text = extractPdfText(temp);
                    try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
                         PdfRenderer renderer = new PdfRenderer(descriptor)) {
                        pages = Math.min(renderer.getPageCount(), MAX_PDF_PAGES);
                        StringBuilder combined = new StringBuilder(text == null ? "" : text);
                        int checkedOcrPages = 0;
                        for (int pageNumber = 0; pageNumber < pages && combined.length() < MAX_OUTPUT_CHARS; pageNumber++) {
                            String pageText = "";
                            try (PdfRenderer.Page page = renderer.openPage(pageNumber)) {
                                if (page.getWidth() <= 0 || page.getHeight() <= 0) continue;
                                int width = Math.min(1800, Math.max(600, page.getWidth() * 2));
                                int height = Math.max(1, Math.round(width * page.getHeight() / (float) page.getWidth()));
                                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                                page.render(bitmap, null, new Matrix(), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                try {
                                    Text scanned = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
                                    pageText = scanned.getText();
                                    checkedOcrPages++;
                                    ocrPages++;
                                } finally {
                                    bitmap.recycle();
                                }
                            } catch (Throwable ignored) {
                                // Keep extracted PDF text even if a scanned page cannot be OCR'd.
                            }
                            if (pageText != null && !pageText.trim().isEmpty()) {
                                if (combined.length() > 0) combined.append("\n\n");
                                combined.append(pageText.trim());
                            }
                            if (checkedOcrPages >= MAX_OCR_PAGES) break;
                        }
                        text = combined.toString();
                    }
                }
                if (text == null) text = "";
                if (text.length() > MAX_OUTPUT_CHARS) text = text.substring(0, MAX_OUTPUT_CHARS);
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("type", type);
                result.put("name", displayName == null ? "" : displayName);
                result.put("text", text);
                result.put("pages", pages);
                result.put("ocrPages", ocrPages);
                result.put("characters", text.length());
                result.put("truncated", text.length() >= MAX_OUTPUT_CHARS);
                if (text.trim().isEmpty()) {
                    result.put("warning", "لم يتم العثور على نص. قد يكون الملف مشوشاً أو محمياً.");
                }
                return result;
            } finally {
                recognizer.close();
            }
        } catch (Throwable error) {
            JSONObject failed = new JSONObject();
            try {
                failed.put("success", false);
                failed.put("message", error.getMessage() == null ? "تعذر استخراج النص من الملف." : error.getMessage());
            } catch (Exception ignored) {}
            return failed;
        } finally {
            if (temp != null) temp.delete();
        }
    }

    private static File copyToCache(Context context, Uri uri) throws Exception {
        File file = File.createTempFile("dk-document-", ".bin", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new Exception("تعذر فتح الملف المحدد.");
            byte[] buffer = new byte[32768];
            long size = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
                if (size > MAX_INPUT_BYTES) throw new Exception("حجم الملف أكبر من 45 ميغابايت.");
                out.write(buffer, 0, read);
            }
        } catch (Throwable error) {
            file.delete();
            throw error;
        }
        return file;
    }

    private static String extractPdfText(File file) throws Exception {
        try (PDDocument document = PDDocument.load(file)) {
            if (document.isEncrypted()) throw new Exception("ملفات PDF المحمية بكلمة مرور غير مدعومة حالياً.");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(document.getNumberOfPages(), MAX_PDF_PAGES));
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            if (text == null) return "";
            return text.length() > MAX_OUTPUT_CHARS ? text.substring(0, MAX_OUTPUT_CHARS) : text;
        }
    }
}
