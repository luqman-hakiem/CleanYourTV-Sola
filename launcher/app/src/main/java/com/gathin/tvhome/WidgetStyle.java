package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

/**
 * The look of the home widget cards (Launcher settings → Widget Home):
 * <ul>
 * <li>Kaca buram: the wallpaper behind the card, blurred, under a dark veil, like iOS widgets.</li>
 * <li>Gelap: a dark translucent card.</li>
 * <li>Terang: the light frosted card of the first version.</li>
 * <li>Otomatis: dark on a bright wallpaper, light on a dark one.</li>
 * </ul>
 * The blur comes from a small snapshot of the layers under the widget row (sky and wallpaper), taken
 * when the wallpaper may have changed, never per frame.
 */
final class WidgetStyle {

    static final String PREF_STYLE = "widget_style";
    static final String PREF_SHADE = "widget_shade";
    static final String BLUR = "blur", DARK = "dark", LIGHT = "light", AUTO = "auto";
    static final String[] STYLES = {BLUR, DARK, LIGHT, AUTO};
    static final String[] STYLE_NAMES = {"Kaca buram", "Gelap", "Terang", "Otomatis"};
    static final String[] SHADE_NAMES = {"Tipis", "Sedang", "Tebal"};
    /** The dark veil over the blur, and the dark card, per shade. */
    private static final int[] VEIL = {0x24, 0x3A, 0x50};
    private static final int[] DARK_FILL = {0x73, 0x99, 0xC2};
    /** The snapshot's scale: a fifth of the screen is plenty under a blur. */
    private static final float SCALE = 0.2f;

    private WidgetStyle() {
    }

    static int index(String style) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return i;
        return 0;
    }

    /**
     * The layers of {@code root} that sit under {@code row} (wallpaper, sky), drawn small, blurred and read
     * back into a software bitmap; null while a layer is still waiting for its first layout or the platform
     * cannot read it back (the caller keeps its previous glass and tries again).
     */
    static Bitmap backdrop(ViewGroup root, View row) {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0 || android.os.Build.VERSION.SDK_INT < 28) return null;
        if (!underlaysReady(root, row)) return null;
        try {
            int sw = Math.max(1, Math.round(w * SCALE)), sh = Math.max(1, Math.round(h * SCALE));
            Picture picture = new Picture();
            Canvas c = picture.beginRecording(sw, sh);
            c.scale(SCALE, SCALE);
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child == row) break;                       // only what lies under the widgets
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) continue;
                int save = c.save();
                c.translate(child.getX(), child.getY());
                if (child.getAlpha() < 1f) {
                    c.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), Math.round(child.getAlpha() * 255));
                }
                drawUnderlay(c, child);
                c.restoreToCount(save);
            }
            picture.endRecording();
            // The wallpaper is a GPU bitmap: render the recording on the GPU, then read it back once.
            Bitmap gpu = Bitmap.createBitmap(picture);
            Bitmap soft = gpu.copy(Bitmap.Config.ARGB_8888, true);
            gpu.recycle();
            if (soft == null) return null;
            blur(soft, Math.max(2, Math.round(sw / 90f)));
            return soft;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** False while a full-screen layer (the wallpaper) is on screen but still unmeasured. */
    private static boolean underlaysReady(ViewGroup root, View row) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == row) break;
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) continue;
            if (child instanceof ImageView && ((ImageView) child).getDrawable() != null
                    && (child.getWidth() == 0 || child.getHeight() == 0)) return false;
        }
        return true;
    }

    /**
     * Draws one layer under the widgets. A hardware bitmap (the wallpaper) cannot be recorded into a
     * software Picture, so it is read back into a normal bitmap and drawn itself, centred and cropped the
     * way its ImageView shows it. Without this the snapshot fails and the cards keep their plain glass.
     */
    private static void drawUnderlay(Canvas c, View child) {
        if (child instanceof ImageView) {
            ImageView iv = (ImageView) child;
            Drawable d = iv.getDrawable();
            if (d instanceof BitmapDrawable && iv.getScaleType() == ImageView.ScaleType.CENTER_CROP) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null && b.getConfig() == Bitmap.Config.HARDWARE) {
                    Bitmap soft = null;
                    try {
                        soft = b.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        // fall through to the normal draw
                    }
                    if (soft != null) {
                        int vw = iv.getWidth(), vh = iv.getHeight();
                        float cover = Math.max(vw / (float) b.getWidth(), vh / (float) b.getHeight());
                        float dw = b.getWidth() * cover, dh = b.getHeight() * cover;
                        RectF dst = new RectF((vw - dw) / 2f, (vh - dh) / 2f,
                                (vw + dw) / 2f, (vh + dh) / 2f);
                        c.drawBitmap(soft, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
                        soft.recycle();
                        return;
                    }
                }
            }
        }
        child.draw(c);
    }

    /** Three box-blur passes in place, close enough to a Gaussian. */
    private static void blur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[at(line, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
                n++;
            }
            for (int i = 0; i < len; i++) {
                out[at(line, i, w, horizontal)] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int add = in[at(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                int del = in[at(line, Math.max(0, i - r), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((del >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((del >> 8) & 0xFF);
                sb += (add & 0xFF) - (del & 0xFF);
            }
        }
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** The average brightness (0..1) of the backdrop under a card. */
    static float brightness(Bitmap backdrop, RectF card) {
        if (backdrop == null) return 0f;
        int x0 = clamp((int) (card.left * SCALE), backdrop.getWidth() - 1);
        int y0 = clamp((int) (card.top * SCALE), backdrop.getHeight() - 1);
        int x1 = clamp((int) (card.right * SCALE), backdrop.getWidth());
        int y1 = clamp((int) (card.bottom * SCALE), backdrop.getHeight());
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                int p = backdrop.getPixel(x, y);
                sum += (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
                n++;
            }
        }
        return n == 0 ? 0f : sum / (255f * n);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /** The background for a card at {@code card} (root coordinates). */
    static Drawable background(View card, String style, int shade, Bitmap backdrop, RectF where) {
        float d = card.getResources().getDisplayMetrics().density;
        shade = Math.max(0, Math.min(2, shade));
        String s = style;
        if (AUTO.equals(s)) s = brightness(backdrop, where) > 0.5f ? DARK : LIGHT;
        if (BLUR.equals(s)) {
            return backdrop != null ? new Frosted(backdrop, where, 20 * d, VEIL[shade] << 24, d)
                    : plainGlass(card, shade);
        }
        if (LIGHT.equals(s)) return card.getResources().getDrawable(R.drawable.widget_card_bg, null);
        GradientDrawable dark = new GradientDrawable();
        dark.setColor(DARK_FILL[shade] << 24 | 0x0E1014);
        dark.setCornerRadius(20 * d);
        dark.setStroke(Math.max(1, Math.round(d)), 0x29FFFFFF);
        return dark;
    }

    /**
     * The glass before (or without) a wallpaper snapshot: the same corner, frost sheen and rim in a fixed
     * dark tone, so a card is never a plain slab and never changes while a snapshot is retried.
     */
    static Drawable plainGlass(View card, int shade) {
        float d = card.getResources().getDisplayMetrics().density;
        int[] baseAlpha = {0xD9, 0xE6, 0xF2};
        int a = baseAlpha[Math.max(0, Math.min(2, shade))];
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{a << 24 | 0x2A2E37, a << 24 | 0x1C1F26});
        base.setCornerRadius(20 * d);
        GradientDrawable frost = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x24FFFFFF, 0x04FFFFFF});
        frost.setCornerRadius(20 * d);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(20 * d);
        rim.setStroke(Math.max(1, Math.round(d)), 0x66FFFFFF);
        return new LayerDrawable(new Drawable[]{base, frost, rim});
    }

    /** The blurred backdrop clipped to a rounded card: desaturated glass, frost sheen, veil and a bright rim. */
    private static final class Frosted extends Drawable {
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final BitmapShader shader;
        private final RectF where;
        private final float radius;
        private final RectF rect = new RectF();
        private final RectF frosted = new RectF();

        Frosted(Bitmap backdrop, RectF where, float radius, int veilColor, float density) {
            this.where = new RectF(where);
            this.radius = radius;
            shader = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            image.setShader(shader);
            // Drain most of the wallpaper's colour out of the glass, so the card reads as glass, not paint.
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0.35f);
            ColorMatrix lift = new ColorMatrix();
            lift.setScale(1.03f, 1.03f, 1.03f, 1f);
            matrix.postConcat(lift);
            image.setColorFilter(new ColorMatrixColorFilter(matrix));
            veil.setColor(veilColor);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, density));
            rim.setColor(0x66FFFFFF);
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            // Map the small backdrop so the part under this card fills it.
            Matrix m = new Matrix();
            m.setScale(1f / SCALE, 1f / SCALE);
            m.postTranslate(-where.left + rect.left, -where.top + rect.top);
            shader.setLocalMatrix(m);
            canvas.drawRoundRect(rect, radius, radius, image);
            canvas.drawRoundRect(rect, radius, radius, veil);
            // A white frost, bright at the top, so the card reads as glass whatever the wallpaper does.
            if (!frosted.equals(rect)) {
                frosted.set(rect);
                frost.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                        0x24FFFFFF, 0x04FFFFFF, Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(rect, radius, radius, frost);
            rect.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
            canvas.drawRoundRect(rect, radius, radius, rim);
        }

        @Override
        public void setAlpha(int alpha) {
            image.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            image.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
