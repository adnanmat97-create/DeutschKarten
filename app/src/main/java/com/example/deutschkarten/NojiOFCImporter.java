package com.example.deutschkarten;

import android.content.Context;
import android.text.Html;
import android.util.Base64;

import com.github.luben.zstd.ZstdInputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NojiOFCImporter {
    private static final long MAX_INPUT = 220L * 1024L * 1024L;
    private static final long MAX_UNPACKED = 350L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 25000;
    private static final int MAX_CARDS = 20000;
    private static final int MAX_ONE_MEDIA = 900 * 1024;
    private static final int MAX_TOTAL_MEDIA = 3 * 1024 * 1024;

    private NojiOFCImporter() {}

    public static JSONObject importPackage(Context context, InputStream source, String displayName) {
        File work = null;
        try {
            work = new File(context.getCacheDir(), "noji-ofc-" + System.currentTimeMillis());
            if (!work.mkdirs()) throw new Exception("تعذر إنشاء مجلد مؤقت لاستيراد Noji.");
            File jsonFile = null;
            File compressedFile = null;
            File attachmentsFile = null;
            long unpacked = 0;
            int entries = 0;
            byte[] buffer = new byte[32768];

            try (ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_ENTRIES) throw new Exception("ملف OFC يحتوي عدداً كبيراً جداً من العناصر.");
                    if (entry.isDirectory()) { zip.closeEntry(); continue; }
                    String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("../")) { zip.closeEntry(); continue; }
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    boolean isJson = base.equalsIgnoreCase("deck_data.json");
                    boolean isCompressed = base.equalsIgnoreCase("deck_export_data");
                    boolean isAttachments = base.equalsIgnoreCase("attachments.zip");
                    if (!isJson && !isCompressed && !isAttachments) { zip.closeEntry(); continue; }
                    File target = new File(work, isJson ? "deck_data.json" : isCompressed ? "deck_export_data" : "attachments.zip");
                    try (OutputStream out = new FileOutputStream(target)) {
                        int n;
                        long local = 0;
                        while ((n = zip.read(buffer)) != -1) {
                            local += n; unpacked += n;
                            if (unpacked > MAX_UNPACKED || local > MAX_INPUT) throw new Exception("حجم ملف OFC بعد فك الضغط يتجاوز الحد المسموح.");
                            out.write(buffer, 0, n);
                        }
                    }
                    if (isJson) jsonFile = target;
                    if (isCompressed) compressedFile = target;
                    if (isAttachments) attachmentsFile = target;
                    zip.closeEntry();
                }
            }

            if (jsonFile == null && compressedFile != null) {
                jsonFile = new File(work, "deck_data.json");
                try (InputStream in = new ZstdInputStream(new FileInputStream(compressedFile));
                     OutputStream out = new FileOutputStream(jsonFile)) {
                    copyLimited(in, out, MAX_UNPACKED);
                }
            }
            if (jsonFile == null) throw new Exception("لم أجد deck_data.json أو deck_export_data داخل ملف OFC. تأكد أن الملف نسخة احتياطية OFC من Noji.");

            String json = new String(readLimited(new FileInputStream(jsonFile), MAX_UNPACKED), StandardCharsets.UTF_8);
            JSONArray sourceDecks = new JSONArray(json);
            if (sourceDecks.length() == 0) throw new Exception("ملف OFC لا يحتوي مجموعات.");
            Map<String, byte[]> media = readAttachments(attachmentsFile);
            JSONArray outDecks = new JSONArray();
            Map<String, String> names = new HashMap<>();
            for (int i = 0; i < sourceDecks.length(); i++) {
                JSONObject d = sourceDecks.optJSONObject(i);
                if (d != null) names.put(String.valueOf(d.opt("id")), d.optString("name", "Noji"));
            }

            int totalCards = 0;
            int mediaUsed = 0;
            int mediaCount = 0;
            for (int i = 0; i < sourceDecks.length(); i++) {
                JSONObject sourceDeck = sourceDecks.optJSONObject(i);
                if (sourceDeck == null) continue;
                JSONArray notes = sourceDeck.optJSONArray("notes");
                if (notes == null || notes.length() == 0) continue;
                JSONObject targetDeck = new JSONObject();
                targetDeck.put("name", fullDeckName(sourceDeck, names));
                JSONArray cards = new JSONArray();
                for (int n = 0; n < notes.length(); n++) {
                    if (totalCards >= MAX_CARDS) throw new Exception("تجاوز الملف الحد الأقصى للاستيراد (" + MAX_CARDS + " بطاقة).");
                    JSONObject note = notes.optJSONObject(n);
                    if (note == null) continue;
                    JSONObject fields = note.optJSONObject("fields");
                    if (fields == null) continue;
                    String front = htmlToText(fields.optString("front_side", ""));
                    String back = htmlToText(fields.optString("back_side", ""));
                    if (front.trim().isEmpty() && back.trim().isEmpty()) continue;

                    JSONObject card = new JSONObject();
                    card.put("de", front);
                    card.put("ar", back);
                    card.put("ex", "");
                    card.put("nojiData", note);
                    JSONArray attachments = note.optJSONArray("note_attachments");
                    JSONArray savedMedia = new JSONArray();
                    String firstImage = "";
                    String firstAudio = "";
                    if (attachments != null) {
                        for (int a = 0; a < attachments.length(); a++) {
                            JSONObject wrapper = attachments.optJSONObject(a);
                            JSONObject attachment = wrapper == null ? null : wrapper.optJSONObject("attachment");
                            JSONObject mediaFile = attachment == null ? null : attachment.optJSONObject("media_file");
                            String url = mediaFile == null ? "" : mediaFile.optString("url", "");
                            String filename = url.substring(url.lastIndexOf('/') + 1);
                            if (filename.isEmpty()) continue;
                            byte[] bytes = media.get(filename);
                            if (bytes == null || bytes.length == 0 || bytes.length > MAX_ONE_MEDIA || mediaUsed + bytes.length > MAX_TOTAL_MEDIA) continue;
                            String mime = mimeType(filename);
                            String dataUrl = "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                            JSONObject saved = new JSONObject();
                            saved.put("filename", filename);
                            saved.put("field", wrapper.optString("field_name", "back_side"));
                            saved.put("mime", mime);
                            boolean primaryMedia = false;
                            if (firstImage.isEmpty() && mime.startsWith("image/")) { firstImage = dataUrl; primaryMedia = true; }
                            if (firstAudio.isEmpty() && mime.startsWith("audio/")) { firstAudio = dataUrl; primaryMedia = true; }
                            if (!primaryMedia) saved.put("data", dataUrl);
                            savedMedia.put(saved);
                            mediaUsed += bytes.length;
                            mediaCount++;
                        }
                    }
                    card.put("image", firstImage);
                    card.put("audio", firstAudio);
                    card.put("nojiMedia", savedMedia);
                    cards.put(card);
                    totalCards++;
                }
                if (cards.length() > 0) {
                    targetDeck.put("cards", cards);
                    outDecks.put(targetDeck);
                }
            }
            if (totalCards == 0) throw new Exception("لم يتم العثور على بطاقات قابلة للاستيراد داخل OFC.");
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("format", "noji-ofc");
            result.put("sourceName", displayName == null ? "Noji OFC" : displayName);
            result.put("decks", outDecks);
            result.put("totalCards", totalCards);
            result.put("mediaCount", mediaCount);
            result.put("progressNote", "تم الاحتفاظ ببيانات كل بطاقة الأصلية في nojiData عند وجودها. لم يتم تحويل جدولة Noji إلى FSRS تلقائياً لأن خوارزميات الجدولة قد تختلف.");
            return result;
        } catch (Throwable error) {
            JSONObject failed = new JSONObject();
            try {
                failed.put("success", false);
                failed.put("message", error.getMessage() == null ? "تعذر استيراد ملف Noji OFC." : error.getMessage());
            } catch (Exception ignored) {}
            return failed;
        } finally {
            deleteRecursively(work);
        }
    }

    private static String fullDeckName(JSONObject deck, Map<String, String> names) {
        StringBuilder path = new StringBuilder();
        String ancestry = deck.optString("ancestry", "/");
        for (String id : ancestry.split("/")) {
            String name = names.get(id);
            if (name != null && !name.trim().isEmpty()) {
                if (path.length() > 0) path.append(" :: ");
                path.append(name.trim());
            }
        }
        String current = deck.optString("name", "Noji").trim();
        if (path.length() > 0) path.append(" :: ");
        path.append(current.isEmpty() ? "Noji" : current);
        return path.toString();
    }

    private static String htmlToText(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])\\s*>", "\n");
        return Html.fromHtml(normalized).toString().trim();
    }

    private static Map<String, byte[]> readAttachments(File archive) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        if (archive == null || !archive.exists()) return out;
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new Exception("ملف وسائط OFC يحتوي عناصر كثيرة جداً.");
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                String filename = name.substring(name.lastIndexOf('/') + 1);
                if (filename.isEmpty() || filename.startsWith(".")) { zip.closeEntry(); continue; }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int n; byte[] buffer = new byte[16384]; boolean tooLarge = false;
                while ((n = zip.read(buffer)) != -1) {
                    if (bytes.size() + n > MAX_ONE_MEDIA) { tooLarge = true; break; }
                    bytes.write(buffer, 0, n);
                }
                byte[] data = bytes.toByteArray();
                if (!tooLarge && data.length > 0 && total + data.length <= MAX_TOTAL_MEDIA) {
                    out.put(filename, data); total += data.length;
                }
                zip.closeEntry();
            }
        }
        return out;
    }

    private static String mimeType(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static byte[] readLimited(InputStream in, long limit) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copyLimited(source, out, limit);
            return out.toByteArray();
        }
    }

    private static void copyLimited(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buffer = new byte[32768]; long count = 0; int read;
        while ((read = in.read(buffer)) != -1) {
            count += read; if (count > limit) throw new Exception("ملف OFC أكبر من الحد المسموح.");
            out.write(buffer, 0, read);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursively(child); }
        file.delete();
    }
}
