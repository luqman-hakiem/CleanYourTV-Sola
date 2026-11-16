package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Today's weather for the line under the date: a condition icon, the current condition and temperature,
 * plus the air quality index, from Open-Meteo (free, no key) at the prayer-time location. Open-Meteo is
 * only asked again every twenty minutes; in between, the last reading is shown from the settings prefs,
 * so Home still has a line while the TV is offline. Everything runs off the UI thread.
 */
final class WeatherSource {

    private static final String PREF_TEXT = "weather_text";
    private static final String PREF_CODE = "weather_code";
    private static final String PREF_AQI = "weather_aqi";
    private static final String PREF_AT = "weather_at";
    private static final String PREF_PLACE = "weather_place";
    private static final long FRESH_MS = 20 * 60_000L;
    /** After a failed request, wait a little instead of asking again on every minute tick. */
    private static final long RETRY_MS = 2 * 60_000L;

    private static boolean fetching;
    private static long nextTry;

    private WeatherSource() {
    }

    /** Shows the cached line at once and refreshes it when it is old (or the place changed). */
    static void update(Context context, TextView view) {
        if (view == null) return;
        final Context app = context.getApplicationContext();
        final SharedPreferences p = app.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        final String place = p.getString(HomeWidgets.PREF_NAME, HomeWidgets.DEFAULT_NAME);
        String cached = p.getString(PREF_TEXT, "");
        int cachedCode = p.getInt(PREF_CODE, -1);
        int cachedAqi = p.getInt(PREF_AQI, -1);
        boolean samePlace = place.equals(p.getString(PREF_PLACE, ""));
        long now = System.currentTimeMillis();
        if (samePlace && !cached.isEmpty()) {
            show(view, cached, cachedCode, cachedAqi);
            // A line cached by an older build has no code or no air quality yet: fetch once more to fill it in.
            if (cachedCode >= 0 && cachedAqi >= 0 && now - p.getLong(PREF_AT, 0L) < FRESH_MS) return;
        }
        if (fetching || now < nextTry) return;
        fetching = true;
        new Thread(() -> {
            Reading reading = null;
            try {
                reading = fetch(app);
            } catch (Exception ignored) {
                // Offline or a changed reply: keep the cached line, if there is one.
            }
            final Reading result = reading;
            new Handler(Looper.getMainLooper()).post(() -> {
                fetching = false;
                if (result == null) {
                    nextTry = System.currentTimeMillis() + RETRY_MS;
                    return;
                }
                nextTry = 0;
                p.edit()
                        .putString(PREF_TEXT, result.text)
                        .putInt(PREF_CODE, result.code)
                        .putInt(PREF_AQI, result.aqi)
                        .putLong(PREF_AT, System.currentTimeMillis())
                        .putString(PREF_PLACE, place)
                        .apply();
                show(view, result.text, result.code, result.aqi);
            });
        }, "weather").start();
    }

    /** The text and the icon only change when the reading does; the bounds are kept otherwise. */
    private static void show(TextView view, String text, int code, int aqi) {
        int icon = iconFor(code);
        Object tag = view.getTag();
        if (!(tag instanceof Integer) || (Integer) tag != icon) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            view.setTag(icon);
        }
        CharSequence line = aqi >= 0 ? airQuality(view, text, aqi) : text;
        if (!line.toString().contentEquals(view.getText())) view.setText(line);
        view.setVisibility(View.VISIBLE);
    }

    /**
     * " · [leaf] AQI 45 Baik", the icon and the index painted in the air quality's own colour.
     * The icon follows the band (leaf, hazy sun, mask, warning). It is a space the ImageSpan takes
     * over, so the text length and the cache stay plain.
     */
    private static CharSequence airQuality(TextView view, String text, int aqi) {
        String head = text + " \u00b7 ";
        Drawable icon = view.getContext().getDrawable(aqiIcon(aqi));
        if (icon != null) icon = icon.mutate();
        String gap = icon == null ? "" : "\u00a0 ";
        SpannableString line = new SpannableString(head + gap + "AQI " + aqi + " " + aqiLabel(aqi));
        int color = aqiColor(aqi);
        if (icon != null) {
            int size = Math.round(view.getTextSize() * 1.15f);
            icon.setBounds(0, 0, size, size);
            icon.setTint(color);
            line.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_CENTER),
                    head.length(), head.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        line.setSpan(new ForegroundColorSpan(color), head.length() + gap.length(), line.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return line;
    }

    /**
     * The icon that matches the band: a leaf for clean air, a hazy sun for moderate, a mask once it is
     * unhealthy, lungs when it is very unhealthy, and a warning triangle when it is hazardous.
     */
    private static int aqiIcon(int aqi) {
        if (aqi <= 50) return R.drawable.ic_aqi_leaf;
        if (aqi <= 100) return R.drawable.ic_aqi_haze;
        if (aqi <= 200) return R.drawable.ic_aqi_mask;
        if (aqi <= 300) return R.drawable.ic_aqi_lungs;
        return R.drawable.ic_aqi_warning;
    }

    /** United States AQI bands, worded the way Indonesian weather apps word them. */
    private static String aqiLabel(int aqi) {
        if (aqi <= 50) return "Baik";
        if (aqi <= 100) return "Sedang";
        if (aqi <= 200) return "Tidak Sehat";
        if (aqi <= 300) return "Sangat Tidak Sehat";
        return "Berbahaya";
    }

    private static int aqiColor(int aqi) {
        if (aqi <= 50) return 0xFF81C784;
        if (aqi <= 100) return 0xFFFFD54F;
        if (aqi <= 150) return 0xFFFFB74D;
        if (aqi <= 200) return 0xFFEF5350;
        if (aqi <= 300) return 0xFFAB47BC;
        return 0xFFC62828;
    }

    private static Reading fetch(Context c) throws Exception {
        SharedPreferences p = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        double lat = Double.longBitsToDouble(p.getLong(HomeWidgets.PREF_LAT,
                Double.doubleToLongBits(HomeWidgets.DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(p.getLong(HomeWidgets.PREF_LON,
                Double.doubleToLongBits(HomeWidgets.DEFAULT_LON)));
        String url = "https://api.open-meteo.com/v1/forecast?latitude="
                + String.format(Locale.US, "%.4f", lat)
                + "&longitude=" + String.format(Locale.US, "%.4f", lon)
                + "&current=temperature_2m,weather_code"
                + "&hourly=weather_code,precipitation"
                + "&timezone=" + URLEncoder.encode("Asia/Jakarta", "UTF-8")
                + "&forecast_days=1";
        JSONObject reply = new JSONObject(HomeWidgets.get(url));
        JSONObject current = reply.optJSONObject("current");
        if (current == null || !current.has("temperature_2m")) throw new Exception("no current weather");
        int code = current.optInt("weather_code", -1);
        // The "current" block smooths a drizzle into its cloud: 18:30 with 0.3 mm in the 18:00 row
        // comes back as code 3 (overcast). When the hour that holds now says rain, say rain.
        int hourly = rainingThisHour(reply);
        if (hourly >= 0 && isRain(hourly) && !isRain(code)) code = hourly;
        String text = describe(code)
                + " \u00b7 " + Math.round(current.getDouble("temperature_2m")) + "\u00b0C";
        return new Reading(text, code, fetchAqi(lat, lon));
    }

    /**
     * The hourly code for the hour that holds now, when that hour carries at least 0.1 mm of rain; -1
     * when the hour is dry or the reply has no hourly block. Times are in the requested timezone:
     * hourly "2026-11-16T18:00", current "2026-11-16T18:30".
     */
    private static int rainingThisHour(JSONObject reply) {
        JSONObject hourly = reply.optJSONObject("hourly");
        JSONObject current = reply.optJSONObject("current");
        if (hourly == null || current == null) return -1;
        JSONArray times = hourly.optJSONArray("time");
        JSONArray codes = hourly.optJSONArray("weather_code");
        JSONArray rain = hourly.optJSONArray("precipitation");
        String now = current.optString("time", "");
        if (times == null || codes == null || rain == null || now.length() < 13) return -1;
        String hour = now.substring(0, 13);
        int n = Math.min(times.length(), Math.min(codes.length(), rain.length()));
        for (int i = 0; i < n; i++) {
            String at = times.optString(i, "");
            if (!at.startsWith(hour)) continue;
            if (rain.optDouble(i, 0) < 0.1) return -1;
            return codes.optInt(i, -1);
        }
        return -1;
    }

    /** WMO codes that mean water is falling: drizzle, rain, freezing rain and thunderstorms. */
    private static boolean isRain(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    /** The air quality is a bonus: when it cannot be read, the line keeps the weather without it. */
    private static int fetchAqi(double lat, double lon) {
        try {
            String url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                    + String.format(Locale.US, "%.4f", lat)
                    + "&longitude=" + String.format(Locale.US, "%.4f", lon)
                    + "&current=us_aqi"
                    + "&timezone=" + URLEncoder.encode("Asia/Jakarta", "UTF-8");
            JSONObject reply = new JSONObject(HomeWidgets.get(url));
            JSONObject current = reply.optJSONObject("current");
            if (current == null || !current.has("us_aqi")) return -1;
            int aqi = current.optInt("us_aqi", -1);
            return aqi >= 0 ? aqi : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** A WMO weather code in the words the prayer card and the rest of Home use. */
    private static String describe(int code) {
        switch (code) {
            case 0:
            case 1:
                return "Cerah";
            case 2:
                return "Cerah Berawan";
            case 3:
                return "Berawan";
            case 45:
            case 48:
                return "Berkabut";
            case 51:
            case 53:
            case 55:
                return "Gerimis";
            case 56:
            case 57:
            case 66:
            case 67:
                return "Hujan Membeku";
            case 61:
                return "Hujan Ringan";
            case 63:
                return "Hujan";
            case 65:
                return "Hujan Lebat";
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                return "Hujan Salju";
            case 80:
            case 81:
                return "Hujan Lokal";
            case 82:
                return "Hujan Lebat Lokal";
            case 95:
                return "Badai Petir";
            case 96:
            case 99:
                return "Badai Petir Hujan Es";
            default:
                return "Cuaca";
        }
    }

    /** The drawable in front of the line, in the same white as its text. */
    private static int iconFor(int code) {
        switch (code) {
            case 0:
            case 1:
                return R.drawable.ic_weather_sun;
            case 2:
                return R.drawable.ic_weather_partly;
            case 3:
                return R.drawable.ic_weather_cloud;
            case 45:
            case 48:
                return R.drawable.ic_weather_fog;
            case 51:
            case 53:
            case 55:
                return R.drawable.ic_weather_drizzle;
            case 56:
            case 57:
            case 61:
            case 63:
            case 66:
            case 67:
            case 80:
            case 81:
                return R.drawable.ic_weather_rain;
            case 65:
            case 82:
                return R.drawable.ic_weather_heavy;
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                return R.drawable.ic_weather_snow;
            case 95:
            case 96:
            case 99:
                return R.drawable.ic_weather_thunder;
            default:
                return 0;
        }
    }

    /** One Open-Meteo reading: the line's words, the WMO code its icon comes from, and the AQI (-1 when unknown). */
    static final class Reading {
        final String text;
        final int code;
        final int aqi;

        Reading(String text, int code, int aqi) {
            this.text = text;
            this.code = code;
            this.aqi = aqi;
        }
    }
}
