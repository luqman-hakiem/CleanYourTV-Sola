package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One-camera snapshot straight from Hik-Connect, so the Home widget never needs the PC bridge.
 *
 * <p>The session token and the serial/channel are copied once into the app's private files by the
 * installer; neither is part of the public source. The session is refreshed with its refresh token
 * (no password, no 2FA) before it ages out, and the renewed token is written back. The capture API
 * returns a signed JPEG URL; an encrypted image is opened with the code in that URL. No stream is
 * decoded on the TV, so this stays as light as the old HTTP snapshot.
 */
final class HikCctv {
    private static final String TAG = "HikCctv";
    private static final String TOKEN_FILE = "hik_token.json";
    private static final String DEVICE_FILE = "hik_device.json";
    private static final long SESSION_MAX_AGE = 1500L;
    private static final byte[] IMAGE_HEADER = "hikencodepicture".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IMAGE_IV = {'0', '1', '2', '3', '4', '5', '6', '7',
            0, 0, 0, 0, 0, 0, 0, 0};

    private static HikCctv instance;

    static synchronized HikCctv get(Context context) {
        if (instance == null) instance = new HikCctv(context.getApplicationContext());
        return instance;
    }

    private final File tokenFile;
    private String sessionId;
    private String refreshId;
    private String featureCode;
    private String apiHost;
    private String serial;
    private int channel;
    private long sessionTime;

    private HikCctv(Context context) {
        tokenFile = new File(context.getFilesDir(), TOKEN_FILE);
        load();
        loadDevice(context);
    }

    /** Never throws: a missing or damaged token simply leaves the widget in its waiting state. */
    synchronized Bitmap capture() {
        try {
            if (serial == null || channel <= 0 || !ensureSession(false)) return null;
            String endpoint = "https://" + apiHost + "/v3/devconfig/v1/" + serial + '/'
                    + channel + "/capture";
            byte[] raw = request(endpoint, "PUT", new byte[0], true);
            JSONObject answer = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONObject meta = answer.optJSONObject("meta");
            int code = meta == null ? 0 : meta.optInt("code", 0);
            if (code == 401 || code == 403 || code == 1014) {
                if (!ensureSession(true)) return null;
                raw = request(endpoint, "PUT", new byte[0], true);
                answer = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                meta = answer.optJSONObject("meta");
                code = meta == null ? 0 : meta.optInt("code", 0);
            }
            JSONObject info = answer.optJSONObject("captureInfo");
            String picture = info == null ? null : info.optString("picUrl", "");
            if (code != 200 || picture == null || picture.isEmpty()) {
                Log.w(TAG, "capture code=" + code);
                return null;
            }
            String verification = parameter(picture, "c");
            byte[] jpeg = download(picture);
            if (jpeg == null) return null;
            if (containsImageHeader(jpeg)) jpeg = decryptImage(jpeg, verification);
            if (jpeg == null) return null;
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bitmap == null) {
                Log.w(TAG, "snapshot did not decode");
                return null;
            }
            return toHd(bitmap);
        } catch (Exception e) {
            Log.w(TAG, "capture failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The cloud capture endpoint only returns the camera's 352x288 snapshot, even when a
     * higher video level is requested. Centre-crop it to 16:9, apply a light sharpen, then
     * upscale to 720p so the full-screen view is no longer a soft, blocky 4:3 thumbnail.
     * This is display-side processing only: no second stream, no extra network call and no
     * claim that the camera itself sends more detail than it does.
     */
    private static Bitmap toHd(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int cropWidth = width;
        int cropHeight = width * 9 / 16;
        if (cropHeight > height) {
            cropHeight = height;
            cropWidth = height * 16 / 9;
        }
        int left = (width - cropWidth) / 2;
        int top = (height - cropHeight) / 2;
        Bitmap cropped = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
        Bitmap sharpened = sharpen(cropped);
        Bitmap hd = Bitmap.createScaledBitmap(sharpened, 1280, 720, true);
        Log.i(TAG, "snapshot " + width + "x" + height + " -> HD " + hd.getWidth()
                + "x" + hd.getHeight());
        if (sharpened != cropped) sharpened.recycle();
        if (cropped != source) cropped.recycle();
        return hd;
    }

    /** Mild 3x3 unsharp mask, applied before scaling so it stays cheap on the TV. */
    private static Bitmap sharpen(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] result = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - 1);
            int y2 = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - 1);
                int x2 = Math.min(width - 1, x + 1);
                int r = 0, g = 0, b = 0;
                for (int ny = y0; ny <= y2; ny++) {
                    for (int nx = x0; nx <= x2; nx++) {
                        boolean centre = ny == y && nx == x;
                        int weight = centre ? 10 : -1;
                        int pixel = pixels[ny * width + nx];
                        r += (pixel & 0xFF) * weight;
                        g += ((pixel >> 8) & 0xFF) * weight;
                        b += ((pixel >> 16) & 0xFF) * weight;
                    }
                }
                result[y * width + x] = 0xFF000000
                        | (clamp(r / 2) << 16)
                        | (clamp(g / 2) << 8)
                        | clamp(b / 2);
            }
        }
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        out.setPixels(result, 0, width, 0, 0, width, height);
        return out;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private void load() {
        try {
            if (!tokenFile.isFile()) return;
            JSONObject t;
            try (InputStream in = new FileInputStream(tokenFile)) {
                t = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
            sessionId = t.optString("session_id", "");
            refreshId = t.optString("rf_session_id", "");
            featureCode = t.optString("feature_code", "");
            sessionTime = t.optLong("session_time", 0L);
            String api = t.optString("api_url", "");
            if (api.startsWith("https://")) api = api.substring(8);
            apiHost = safeHost(api);
        } catch (Exception e) {
            Log.w(TAG, "cannot read token: " + e.getMessage());
            sessionId = null;
        }
    }

    /** The camera identity is local-only: it is never compiled into the public APK. */
    private void loadDevice(Context context) {
        try {
            File file = new File(context.getFilesDir(), DEVICE_FILE);
            if (!file.isFile()) return;
            JSONObject d;
            try (InputStream in = new FileInputStream(file)) {
                d = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
            String s = d.optString("serial", "").trim();
            if (s.matches("[A-Za-z0-9]{5,60}")) serial = s;
            int c = d.optInt("channel", 0);
            if (c >= 1 && c <= 64) channel = c;
        } catch (Exception e) {
            Log.w(TAG, "cannot read camera identity: " + e.getMessage());
        }
    }

    private void save() {
        try {
            JSONObject t = new JSONObject();
            t.put("session_id", sessionId);
            t.put("rf_session_id", refreshId);
            t.put("feature_code", featureCode);
            t.put("api_url", apiHost);
            t.put("session_time", sessionTime);
            t.put("platform", "hikconnect");
            try (OutputStream out = new FileOutputStream(tokenFile)) {
                out.write(t.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot save token: " + e.getMessage());
        }
    }

    /** Only the Hik-Connect/EZVIZ domains the PC bridge also accepts. */
    private static String safeHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.contains("/")) return null;
        String[] allowed = {".hik-connect.com", "hik-connect.com", ".hik-connectru.com",
                ".hikvision.com", ".ezvizlife.com", ".ezviz.com"};
        for (String suffix : allowed) if (h.equals(suffix) || h.endsWith(suffix)) return h;
        return null;
    }

    private boolean ensureSession(boolean force) {
        if (sessionId == null || apiHost == null || refreshId == null || featureCode == null) return false;
        long age = System.currentTimeMillis() / 1000L - sessionTime;
        if (!force && age >= 0 && age < SESSION_MAX_AGE) return true;
        try {
            String body = "refreshSessionId=" + URLEncoder.encode(refreshId, "UTF-8")
                    + "&featureCode=" + URLEncoder.encode(featureCode, "UTF-8");
            byte[] raw = request("https://" + apiHost + "/v3/apigateway/login", "PUT",
                    body.getBytes(StandardCharsets.UTF_8), false);
            JSONObject data = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONObject meta = data.optJSONObject("meta");
            if (meta == null || meta.optInt("code", 0) != 200) {
                Log.w(TAG, "refresh rejected");
                return false;
            }
            JSONObject info = data.optJSONObject("sessionInfo");
            if (info == null) return false;
            sessionId = info.optString("sessionId", sessionId);
            refreshId = info.optString("refreshSessionId", refreshId);
            sessionTime = System.currentTimeMillis() / 1000L;
            save();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "refresh failed: " + e.getMessage());
            return false;
        }
    }

    /** Cloud API call. The signed image URL is fetched separately, without a session header. */
    private byte[] request(String url, String method, byte[] body, boolean withSession)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(12000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("clientType", "55");
            connection.setRequestProperty("lang", "en-US");
            connection.setRequestProperty("language", "en-US");
            connection.setRequestProperty("featureCode", withSession ? featureCode : "");
            connection.setRequestProperty("netType", "WIFI");
            connection.setRequestProperty("customno", "1000001");
            connection.setRequestProperty("clientNo", "web_site");
            connection.setRequestProperty("appId", "ys7");
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (withSession && sessionId != null) {
                connection.setRequestProperty("sessionId", sessionId);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, method + " " + url + " -> " + status);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return readAll(in);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(12000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = connection.getInputStream()) {
                return readAll(in);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        return out.toByteArray();
    }

    private static String parameter(String url, String key) throws Exception {
        int q = url.indexOf('?');
        if (q < 0) return "";
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            }
        }
        return "";
    }

    private static boolean containsImageHeader(byte[] data) {
        int limit = Math.min(data.length - IMAGE_HEADER.length, 4096);
        for (int i = 0; i <= limit; i++) {
            boolean same = true;
            for (int j = 0; j < IMAGE_HEADER.length; j++) {
                if (data[i + j] != IMAGE_HEADER[j]) { same = false; break; }
            }
            if (same) return true;
        }
        return false;
    }

    /** Opens the "hikencodepicture" blocks: AES-CBC with the short code from the signed URL. */
    private static byte[] decryptImage(byte[] data, String code) throws Exception {
        if (code == null || code.isEmpty()) return null;
        String expected = md5Hex(md5Hex(code));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cursor = 0;
        while (cursor <= data.length - IMAGE_HEADER.length) {
            int start = indexOf(data, IMAGE_HEADER, cursor);
            if (start < 0) break;
            int next = indexOf(data, IMAGE_HEADER, start + IMAGE_HEADER.length);
            int end = next < 0 ? data.length : next;
            int hashAt = start + IMAGE_HEADER.length;
            if (end - hashAt < 32) break;
            String found = new String(data, hashAt, 32, StandardCharsets.US_ASCII);
            if (!expected.equalsIgnoreCase(found)) return null;
            byte[] cipherText = new byte[end - hashAt - 32];
            System.arraycopy(data, hashAt + 32, cipherText, 0, cipherText.length);
            cipherText = trim(cipherText, cipherText.length - cipherText.length % 16);
            byte[] key = new byte[16];
            byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(codeBytes, 0, key, 0, Math.min(16, codeBytes.length));
            Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(IMAGE_IV));
            byte[] plain = aes.doFinal(cipherText);
            int pad = plain.length == 0 ? 0 : plain[plain.length - 1] & 0xFF;
            if (pad > 0 && pad <= 16 && pad <= plain.length) {
                out.write(plain, 0, plain.length - pad);
            } else {
                out.write(plain);
            }
            if (next < 0) break;
            cursor = next;
        }
        return out.size() == 0 ? null : out.toByteArray();
    }

    private static byte[] trim(byte[] data, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, 0, out, 0, length);
        return out;
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        for (int i = Math.max(0, from); i <= data.length - needle.length; i++) {
            boolean same = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) { same = false; break; }
            }
            if (same) return i;
        }
        return -1;
    }

    private static String md5Hex(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder(digest.length * 2);
        for (byte value : digest) b.append(String.format("%02x", value & 0xFF));
        return b.toString();
    }
}
