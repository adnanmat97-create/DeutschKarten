package com.example.deutschkarten;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AiFlashcardClient {
    private static final int MAX_SOURCE_CHARS = 18000;
    private AiFlashcardClient() {}

    public static JSONObject generate(Context context, String source, String level, String language, int count) throws Exception {
        if (source == null || source.trim().isEmpty()) throw new Exception("أدخل نصاً أو مفردات أولاً.");
        if (source.length() > MAX_SOURCE_CHARS) throw new Exception("النص طويل جداً. الحد الأقصى 18,000 حرف.");
        String endpoint = AiSecureConfig.getEndpoint(context);
        String token = AiSecureConfig.getToken(context);
        if (endpoint.isEmpty() || token.isEmpty()) throw new Exception("لم يتم إعداد وسيط الذكاء الاصطناعي بعد.");

        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()) {
            throw new Exception("عنوان الوسيط يجب أن يبدأ بـ https://");
        }
        String safeLevel = level == null ? "B1" : level.toUpperCase();
        if (!safeLevel.matches("A1|A2|B1|B2|C1")) safeLevel = "B1";
        String safeLanguage = language == null ? "ar" : language;
        if (!safeLanguage.matches("ar|darija|fr|en")) safeLanguage = "ar";
        int safeCount = Math.max(1, Math.min(25, count));

        JSONObject requestBody = new JSONObject();
        requestBody.put("text", source);
        requestBody.put("level", safeLevel);
        requestBody.put("translation_language", safeLanguage);
        requestBody.put("count", safeCount);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(90000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] payload = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseText = readLimited(responseStream, 1024 * 1024);
            JSONObject response;
            try { response = new JSONObject(responseText); }
            catch (Exception error) { throw new Exception("ردّ وسيط الذكاء الاصطناعي غير صالح."); }
            if (status < 200 || status >= 300) {
                String message = response.optString("error", "فشل الاتصال بخدمة الذكاء الاصطناعي.");
                throw new Exception(message);
            }
            JSONArray cards = response.optJSONArray("cards");
            if (cards == null || cards.length() == 0) throw new Exception("لم يتم إنشاء بطاقات صالحة.");
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("cards", cards);
            result.put("model", response.optString("model", ""));
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream input, int maxBytes) throws Exception {
        if (input == null) return "{}";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxBytes) throw new Exception("ردّ الذكاء الاصطناعي كبير جداً.");
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }
}
