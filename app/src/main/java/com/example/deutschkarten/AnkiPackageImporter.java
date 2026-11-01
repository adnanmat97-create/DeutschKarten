package com.example.deutschkarten;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.text.Html;
import android.util.Base64;

import com.github.luben.zstd.ZstdInputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AnkiPackageImporter {
    private static final long MAX_ARCHIVE_BYTES = 220L * 1024L * 1024L;
    private static final long MAX_UNPACKED_BYTES = 350L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 25000;
    private static final int MAX_CARDS = 12000;
    private static final long DAY_MS = 86400000L;

    private AnkiPackageImporter() {}

    public static JSONObject importPackage(Context context, InputStream source, String displayName) {
        File work = null;
        try {
            work = new File(context.getCacheDir(), "anki-import-" + System.currentTimeMillis());
            if (!work.mkdirs()) throw new Exception("تعذر إنشاء مجلد مؤقت للاستيراد.");
            File mediaDir = new File(work, "media");
            if (!mediaDir.mkdirs()) throw new Exception("تعذر إنشاء مجلد الوسائط.");

            Map<String, File> databaseCandidates = new HashMap<>();
            File mediaManifest = null;
            long unpacked = 0;
            int entries = 0;
            try (ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry entry;
                byte[] buffer = new byte[32768];
                while ((entry = zip.getNextEntry()) != null) {
                    entries++;
                    if (entries > MAX_ENTRIES) throw new Exception("الملف يحتوي عدداً كبيراً جداً من العناصر.");
                    if (entry.isDirectory()) {
                        zip.closeEntry();
                        continue;
                    }
                    String name = safeEntryName(entry.getName());
                    if (name == null) {
                        zip.closeEntry();
                        continue;
                    }
                    boolean wanted = name.equals("collection.anki2")
                            || name.equals("collection.anki21")
                            || name.equals("collection.anki21b")
                            || name.equals("media")
                            || name.matches("[0-9]{1,9}");
                    if (!wanted) {
                        zip.closeEntry();
                        continue;
                    }
                    File output = name.equals("media") ? mediaManifest : null;
                    if (name.equals("media")) {
                        output = new File(work, "media-manifest.bin");
                        mediaManifest = output;
                    } else if (name.startsWith("collection.")) {
                        output = new File(work, name);
                    } else {
                        output = new File(mediaDir, name);
                    }
                    try (OutputStream out = new FileOutputStream(output)) {
                        int read;
                        long fileBytes = 0;
                        while ((read = zip.read(buffer)) != -1) {
                            fileBytes += read;
                            unpacked += read;
                            if (unpacked > MAX_UNPACKED_BYTES || fileBytes > MAX_ARCHIVE_BYTES) {
                                throw new Exception("حجم الملف بعد فك الضغط يتجاوز الحد المسموح.");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                    if (name.startsWith("collection.")) databaseCandidates.put(name, output);
                    zip.closeEntry();
                }
            }

            if (databaseCandidates.isEmpty()) throw new Exception("لم يتم العثور على قاعدة بيانات Anki داخل الملف.");
            File databaseFile = chooseDatabase(databaseCandidates, work);
            if (databaseFile == null) throw new Exception("لم يتم العثور على قاعدة بيانات صالحة داخل الملف.");

            boolean modernZstd = databaseFile.getName().endsWith(".anki21b");
            if (modernZstd) {
                File decoded = new File(work, "collection-import.sqlite");
                try (InputStream in = new ZstdInputStream(new FileInputStream(databaseFile));
                     OutputStream out = new FileOutputStream(decoded)) {
                    copyLimited(in, out, MAX_UNPACKED_BYTES);
                }
                databaseFile = decoded;
            }

            Map<String, String> mediaIndex = parseMediaManifest(mediaManifest, modernZstd);
            Map<Long, NoteInfo> notes = new HashMap<>();
            JSONObject result = readDatabase(databaseFile, modernZstd, notes, mediaIndex, mediaDir);
            JSONArray deckArray = result.optJSONArray("decks");
            int totalCards = 0;
            if (deckArray != null) {
                for (int i = 0; i < deckArray.length(); i++) {
                    totalCards += deckArray.getJSONObject(i).optJSONArray("cards").length();
                }
            }
            result.put("success", true);
            result.put("sourceName", displayName == null ? "Anki package" : displayName);
            result.put("format", modernZstd ? "modern-zstd" : databaseFile.getName().endsWith(".anki21") ? "legacy-anki21" : "legacy-anki2");
            result.put("totalCards", totalCards);
            result.put("warnings", new JSONArray());
            return result;
        } catch (Throwable error) {
            JSONObject failed = new JSONObject();
            try {
                failed.put("success", false);
                failed.put("message", error.getMessage() == null ? "تعذّر استيراد ملف Anki." : error.getMessage());
            } catch (JSONException ignored) {}
            return failed;
        } finally {
            deleteRecursively(work);
        }
    }

    private static String safeEntryName(String raw) {
        if (raw == null || raw.contains("\\") || raw.startsWith("/") || raw.contains("..")) return null;
        int slash = raw.lastIndexOf('/');
        String name = slash >= 0 ? raw.substring(slash + 1) : raw;
        if (name.isEmpty()) return null;
        if (name.equals("collection.anki2") || name.equals("collection.anki21")
                || name.equals("collection.anki21b") || name.equals("media")
                || name.matches("[0-9]{1,9}")) return name;
        return null;
    }

    private static void copyLimited(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buffer = new byte[32768];
        long count = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            count += read;
            if (count > limit) throw new Exception("قاعدة بيانات Anki أكبر من الحد المسموح.");
            out.write(buffer, 0, read);
        }
    }

    private static File chooseDatabase(Map<String, File> candidates, File work) {
        String[] preferred = {"collection.anki21b", "collection.anki21", "collection.anki2"};
        for (String name : preferred) {
            File candidate = candidates.get(name);
            if (candidate == null) continue;
            try {
                File test = candidate;
                if (name.endsWith(".anki21b")) {
                    test = new File(work, "probe.sqlite");
                    try (InputStream in = new ZstdInputStream(new FileInputStream(candidate));
                         OutputStream out = new FileOutputStream(test)) {
                        copyLimited(in, out, MAX_UNPACKED_BYTES);
                    }
                }
                SQLiteDatabase db = SQLiteDatabase.openDatabase(test.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                boolean valid = tableExists(db, "notes") && tableExists(db, "cards");
                long noteCount = valid ? scalar(db, "SELECT count(*) FROM notes") : 0;
                db.close();
                if (valid && noteCount > 0) {
                    if (name.endsWith(".anki21b")) return candidate;
                    return candidate;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static JSONObject readDatabase(File file, boolean modern, Map<Long, NoteInfo> notes,
                                           Map<String, String> mediaIndex, File mediaDir) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            if (!tableExists(db, "notes") || !tableExists(db, "cards")) {
                throw new Exception("قاعدة بيانات Anki لا تحتوي على جداول البطاقات المتوقعة.");
            }

            JSONObject legacyDecks = new JSONObject();
            JSONObject legacyModels = new JSONObject();
            long creationSeconds = 0;
            if (tableExists(db, "col")) {
                try (Cursor c = db.rawQuery("SELECT * FROM col LIMIT 1", null)) {
                    if (c.moveToFirst()) {
                        int deckCol = c.getColumnIndex("decks");
                        int modelCol = c.getColumnIndex("models");
                        int crtCol = c.getColumnIndex("crt");
                        if (deckCol >= 0) legacyDecks = parseJsonObject(c.getString(deckCol));
                        if (modelCol >= 0) legacyModels = parseJsonObject(c.getString(modelCol));
                        if (crtCol >= 0) creationSeconds = c.getLong(crtCol);
                    }
                }
            }

            Map<Long, String> deckNames = readDeckNames(db, legacyDecks);
            Map<Long, List<String>> fieldNames = readFieldNames(db, legacyModels);
            Map<Long, String> modelNames = readModelNames(db, legacyModels);
            Map<String, MediaInfo> mediaFiles = new HashMap<>();
            JSONArray outDecks = new JSONArray();
            Map<String, JSONObject> outputByDeck = new HashMap<>();
            Set<Long> missingNotes = new HashSet<>();

            String noteSql = "SELECT id, mid, flds FROM notes";
            try (Cursor c = db.rawQuery(noteSql, null)) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    long mid = c.getLong(1);
                    String raw = c.getString(2);
                    String[] values = raw == null ? new String[0] : raw.split("\\u001f", -1);
                    notes.put(id, new NoteInfo(id, mid, values, fieldNames.get(mid), modelNames.get(mid)));
                }
            }

            long now = System.currentTimeMillis();
            try (Cursor c = db.rawQuery("SELECT * FROM cards", null)) {
                int nidIdx = c.getColumnIndex("nid");
                int didIdx = c.getColumnIndex("did");
                int ordIdx = c.getColumnIndex("ord");
                int queueIdx = c.getColumnIndex("queue");
                int dueIdx = c.getColumnIndex("due");
                int ivlIdx = c.getColumnIndex("ivl");
                int repsIdx = c.getColumnIndex("reps");
                int factorIdx = c.getColumnIndex("factor");
                if (nidIdx < 0 || didIdx < 0) throw new Exception("جدول البطاقات في ملف Anki غير مدعوم.");
                int processed = 0;
                while (c.moveToNext()) {
                    long nid = c.getLong(nidIdx);
                    NoteInfo note = notes.get(nid);
                    if (note == null) { missingNotes.add(nid); continue; }
                    long did = c.getLong(didIdx);
                    int ord = ordIdx >= 0 ? c.getInt(ordIdx) : 0;
                    int queue = queueIdx >= 0 ? c.getInt(queueIdx) : 0;
                    long due = dueIdx >= 0 ? c.getLong(dueIdx) : 0;
                    int ivl = ivlIdx >= 0 ? c.getInt(ivlIdx) : 0;
                    int reps = repsIdx >= 0 ? c.getInt(repsIdx) : 0;
                    int factor = factorIdx >= 0 ? c.getInt(factorIdx) : 2500;

                    String deckName = deckNames.get(did);
                    if (deckName == null || deckName.trim().isEmpty()) deckName = "Anki Import";
                    JSONObject target = outputByDeck.get(deckName);
                    if (target == null) {
                        target = new JSONObject();
                        target.put("name", deckName);
                        target.put("cards", new JSONArray());
                        outputByDeck.put(deckName, target);
                        outDecks.put(target);
                    }

                    int frontIndex = chooseField(note, true);
                    int backIndex = chooseField(note, false);
                    int exampleIndex = chooseExampleField(note);
                    if (frontIndex < 0 || frontIndex >= note.fields.length) frontIndex = 0;
                    if (backIndex < 0 || backIndex >= note.fields.length) backIndex = Math.min(1, Math.max(0, note.fields.length - 1));

                    String frontRaw = valueAt(note.fields, frontIndex);
                    String backRaw = valueAt(note.fields, backIndex);
                    boolean reverse = isReverseModel(note.modelName) && ord > 0;
                    String germanHtml = reverse ? backRaw : frontRaw;
                    String meaningHtml = reverse ? frontRaw : backRaw;
                    String german = cleanHtml(germanHtml);
                    String meaning = cleanHtml(meaningHtml);
                    if (german.isEmpty() && meaning.isEmpty()) continue;
                    if (german.isEmpty()) { german = meaning; meaning = ""; }
                    String example = exampleIndex >= 0 && exampleIndex < note.fields.length
                            ? cleanHtml(valueAt(note.fields, exampleIndex)) : "";

                    String imageRef = firstImage(germanHtml + " " + meaningHtml);
                    String audioRef = firstAudio(germanHtml + " " + meaningHtml);
                    String imageData = imageRef.isEmpty() ? "" : mediaData(imageRef, mediaIndex, mediaDir, mediaFiles, true, modern);
                    String audioData = audioRef.isEmpty() ? "" : mediaData(audioRef, mediaIndex, mediaDir, mediaFiles, false, modern);

                    JSONObject card = new JSONObject();
                    card.put("de", german);
                    card.put("ar", meaning);
                    card.put("ex", example);
                    card.put("image", imageData);
                    card.put("audio", audioData);
                    card.put("noteKey", Long.toString(nid));
                    card.put("isReverse", reverse);
                    card.put("queue", queue);
                    card.put("reps", reps);
                    card.put("interval", Math.max(0, ivl));
                    card.put("ease", factor > 0 ? factor / 1000.0 : 2.5);
                    card.put("due", convertDue(queue, due, creationSeconds, now));
                    card.put("state", queue == 0 ? "new" : (queue == 2 ? "review" : "learning"));
                    target.getJSONArray("cards").put(card);

                    processed++;
                    if (processed >= MAX_CARDS) break;
                }
            }
            if (outDecks.length() == 0) throw new Exception("لم يتم العثور على بطاقات قابلة للاستيراد في هذا الملف.");
            JSONObject result = new JSONObject();
            result.put("decks", outDecks);
            result.put("noteCount", notes.size());
            result.put("missingNoteCards", missingNotes.size());
            result.put("modernSchema", modern);
            return result;
        } finally {
            db.close();
        }
    }

    private static long convertDue(int queue, long due, long creationSeconds, long now) {
        if (queue == 0 || queue < 0) return now;
        if (queue == 1) {
            long value = due > 1000000000L ? due * 1000L : due;
            return value > now - DAY_MS * 30 && value < now + DAY_MS * 3650 ? value : now;
        }
        if (queue == 2 || queue == 3) {
            long created = creationSeconds > 0 ? creationSeconds * 1000L : now;
            long value = created + due * DAY_MS;
            return value > now - DAY_MS * 3650 && value < now + DAY_MS * 36500 ? value : now;
        }
        return now;
    }

    private static Map<Long, String> readDeckNames(SQLiteDatabase db, JSONObject legacy) {
        Map<Long, String> result = new HashMap<>();
        if (tableExists(db, "decks")) {
            try (Cursor c = db.rawQuery("SELECT id, name FROM decks", null)) {
                while (c.moveToNext()) result.put(c.getLong(0), displayDeckName(c.getString(1)));
            } catch (Throwable ignored) {}
        }
        if (legacy != null) {
            Iterator<String> keys = legacy.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    JSONObject obj = legacy.optJSONObject(key);
                    if (obj != null) result.put(Long.parseLong(key), displayDeckName(obj.optString("name", "Anki Import")));
                } catch (Throwable ignored) {}
            }
        }
        return result;
    }

    private static String displayDeckName(String name) {
        if (name == null || name.trim().isEmpty()) return "Anki Import";
        return name.replace('\u001f', ':').replaceAll(":{3,}", "::").trim();
    }

    private static Map<Long, List<String>> readFieldNames(SQLiteDatabase db, JSONObject legacyModels) {
        Map<Long, List<String>> result = new HashMap<>();
        if (tableExists(db, "fields")) {
            try (Cursor c = db.rawQuery("SELECT ntid, ord, name FROM fields ORDER BY ntid, ord", null)) {
                while (c.moveToNext()) {
                    long ntid = c.getLong(0);
                    int ord = c.getInt(1);
                    String name = c.getString(2);
                    List<String> list = result.get(ntid);
                    if (list == null) { list = new ArrayList<>(); result.put(ntid, list); }
                    while (list.size() <= ord) list.add("");
                    list.set(ord, name == null ? "" : name);
                }
            } catch (Throwable ignored) {}
        }
        if (legacyModels != null) {
            Iterator<String> keys = legacyModels.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    long id = Long.parseLong(key);
                    JSONObject model = legacyModels.optJSONObject(key);
                    JSONArray fields = model == null ? null : model.optJSONArray("flds");
                    if (fields == null) continue;
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i < fields.length(); i++) list.add(fields.optJSONObject(i) == null ? "" : fields.optJSONObject(i).optString("name", ""));
                    result.put(id, list);
                } catch (Throwable ignored) {}
            }
        }
        return result;
    }

    private static Map<Long, String> readModelNames(SQLiteDatabase db, JSONObject legacyModels) {
        Map<Long, String> result = new HashMap<>();
        if (legacyModels != null) {
            Iterator<String> keys = legacyModels.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject model = legacyModels.optJSONObject(key);
                try { if (model != null) result.put(Long.parseLong(key), model.optString("name", "")); }
                catch (Throwable ignored) {}
            }
        }
        if (tableExists(db, "notetypes")) {
            try (Cursor c = db.rawQuery("SELECT id, name FROM notetypes", null)) {
                while (c.moveToNext()) result.put(c.getLong(0), c.getString(1));
            } catch (Throwable ignored) {}
        }
        return result;
    }

    private static int chooseField(NoteInfo note, boolean front) {
        if (note.fieldNames != null) {
            String[] preferred = front
                    ? new String[]{"german", "deutsch", "word", "wort", "front", "question", "term", "vocabulary"}
                    : new String[]{"arabic", "meaning", "translation", "back", "answer", "definition", "bedeutung", "übersetzung"};
            for (int i = 0; i < note.fieldNames.size(); i++) {
                String name = note.fieldNames.get(i).toLowerCase(Locale.ROOT);
                for (String p : preferred) if (name.contains(p)) return i;
            }
        }
        return front ? 0 : 1;
    }

    private static int chooseExampleField(NoteInfo note) {
        if (note.fieldNames == null) return -1;
        for (int i = 0; i < note.fieldNames.size(); i++) {
            String name = note.fieldNames.get(i).toLowerCase(Locale.ROOT);
            if (name.contains("example") || name.contains("beispiel") || name.contains("satz") || name.contains("sentence")) return i;
        }
        return -1;
    }

    private static boolean isReverseModel(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("reverse") || lower.contains("reversed") || lower.contains("umgekehrt") || lower.contains("umgedreht");
    }

    private static String valueAt(String[] values, int index) {
        return index >= 0 && index < values.length && values[index] != null ? values[index] : "";
    }

    private static String cleanHtml(String value) {
        if (value == null) return "";
        String withoutMedia = value.replaceAll("(?is)<img\\b[^>]*>", " ")
                .replaceAll("(?is)<audio\\b[^>]*>.*?</audio>", " ")
                .replaceAll("(?is)<source\\b[^>]*>", " ")
                .replaceAll("\\[sound:[^\\]]+\\]", " ");
        String text = Html.fromHtml(withoutMedia, Html.FROM_HTML_MODE_LEGACY).toString();
        return text.replace('\u00a0', ' ').replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private static String firstImage(String html) {
        Matcher matcher = Pattern.compile("(?is)<img\\b[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html == null ? "" : html);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String firstAudio(String html) {
        Matcher matcher = Pattern.compile("(?is)\\[sound:([^\\]]+)\\]").matcher(html == null ? "" : html);
        if (matcher.find()) return matcher.group(1).trim();
        matcher = Pattern.compile("(?is)<(?:audio|source)\\b[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html == null ? "" : html);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Map<String, String> parseMediaManifest(File manifest, boolean modern) {
        Map<String, String> result = new HashMap<>();
        if (manifest == null || !manifest.exists()) return result;
        try {
            byte[] bytes = readFile(manifest, 16 * 1024 * 1024);
            if (modern) bytes = zstdDecode(bytes, 16 * 1024 * 1024);
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{")) {
                JSONObject obj = new JSONObject(text);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String index = keys.next();
                    result.put(obj.optString(index), index);
                }
            } else if (modern) {
                parseMediaProto(bytes, result);
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private static void parseMediaProto(byte[] data, Map<String, String> output) throws Exception {
        ProtoReader reader = new ProtoReader(data);
        int index = 0;
        while (reader.hasRemaining()) {
            long tag = reader.varint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                byte[] entry = reader.bytes();
                String name = parseMediaEntryName(entry);
                if (!name.isEmpty()) output.put(name, Integer.toString(index));
                index++;
            } else {
                reader.skip(wire);
            }
        }
    }

    private static String parseMediaEntryName(byte[] data) throws Exception {
        ProtoReader reader = new ProtoReader(data);
        while (reader.hasRemaining()) {
            long tag = reader.varint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) return new String(reader.bytes(), StandardCharsets.UTF_8);
            reader.skip(wire);
        }
        return "";
    }

    private static byte[] zstdDecode(byte[] bytes, int limit) throws Exception {
        try (InputStream in = new ZstdInputStream(new java.io.ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copyLimited(in, out, limit);
            return out.toByteArray();
        }
    }

    private static String mediaData(String reference, Map<String, String> mediaIndex, File mediaDir,
                                    Map<String, MediaInfo> cache, boolean image, boolean modern) {
        String name = reference == null ? "" : reference.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replace("\"", "").trim();
        String index = mediaIndex.get(name);
        if (index == null) index = name.matches("[0-9]+") ? name : null;
        if (index == null) return "";
        MediaInfo info = cache.get(name);
        if (info == null) {
            File source = new File(mediaDir, index);
            if (!source.exists()) return "";
            try {
                byte[] raw = readFile(source, image ? 12 * 1024 * 1024 : 1024 * 1024);
                if (modern) raw = zstdDecode(raw, image ? 12 * 1024 * 1024 : 1024 * 1024);
                info = new MediaInfo(raw);
                cache.put(name, info);
            } catch (Throwable ignored) { return ""; }
        }
        try {
            if (image) {
                if (info.imageData == null) info.imageData = compressImage(info.bytes);
                return info.imageData == null ? "" : info.imageData;
            }
            if (info.bytes.length > 512 * 1024) return "";
            String mime = mimeFor(name);
            return "data:" + mime + ";base64," + Base64.encodeToString(info.bytes, Base64.NO_WRAP);
        } catch (Throwable ignored) { return ""; }
    }

    private static String compressImage(byte[] bytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) return null;
        try {
            int max = 1100;
            float scale = Math.min(1f, max / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
            int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
            int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 68, out);
            if (scaled != bitmap) scaled.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".webm")) return "audio/webm";
        return "audio/*";
    }

    private static byte[] readFile(File file, int limit) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copyLimited(in, out, limit);
            return out.toByteArray();
        }
    }

    private static JSONObject parseJsonObject(String raw) {
        try { return raw == null ? new JSONObject() : new JSONObject(raw); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{name})) {
            return c.moveToFirst();
        } catch (Throwable ignored) { return false; }
    }

    private static long scalar(SQLiteDatabase db, String query) {
        try (Cursor c = db.rawQuery(query, null)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class NoteInfo {
        final long id;
        final long modelId;
        final String[] fields;
        final List<String> fieldNames;
        final String modelName;
        NoteInfo(long id, long modelId, String[] fields, List<String> fieldNames, String modelName) {
            this.id = id; this.modelId = modelId; this.fields = fields;
            this.fieldNames = fieldNames; this.modelName = modelName;
        }
    }

    private static final class MediaInfo {
        final byte[] bytes;
        String imageData;
        MediaInfo(byte[] bytes) { this.bytes = bytes; }
    }

    private static final class ProtoReader {
        private final byte[] data;
        private int position;
        ProtoReader(byte[] data) { this.data = data; }
        boolean hasRemaining() { return position < data.length; }
        long varint() throws Exception {
            long value = 0;
            int shift = 0;
            while (position < data.length && shift < 64) {
                int b = data[position++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new Exception("وسائط Anki تحتوي بنية Protobuf غير صالحة.");
        }
        byte[] bytes() throws Exception {
            long length = varint();
            if (length < 0 || length > data.length - position) throw new Exception("طول عنصر الوسائط غير صالح.");
            byte[] out = new byte[(int) length];
            System.arraycopy(data, position, out, 0, (int) length);
            position += (int) length;
            return out;
        }
        void skip(int wire) throws Exception {
            if (wire == 0) { varint(); return; }
            if (wire == 1) { position += 8; }
            else if (wire == 2) {
                long length = varint();
                if (length < 0 || length > data.length - position) throw new Exception("Protobuf length out of range.");
                position += (int) length;
            } else if (wire == 5) { position += 4; }
            else throw new Exception("Unsupported Protobuf wire type.");
            if (position > data.length) throw new Exception("Protobuf message truncated.");
        }
    }
}
