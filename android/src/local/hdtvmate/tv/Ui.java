package local.hdtvmate.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Look and feel taken from Queue DJ's "Music" theme (dark): black, graphite cards, red accent, glass bars. */
final class Ui {
    static final int BG = 0xFF000000;
    static final int CARD = 0xFF161618;
    static final int SECONDARY = 0xFF1D1D1F;
    static final int MUTED = 0xFF29292B;
    static final int GLASS = 0xE0161618;        // --glass: card at 88 %
    static final int TEXT = 0xFFF8F8F8;
    static final int TEXT_2 = 0xFFA4A4A9;       // --muted-foreground
    static final int SEPARATOR = 0x47A2A2A8;    // --border
    static final int ACCENT = 0xFFFF4757;       // --primary (dark)
    static final int ACCENT_2 = 0xFFE8293F;

    static Typeface regular, semibold, bold, heavy;

    static void init(Context c) {
        if (regular != null) return;
        regular = Typeface.create(Typeface.SANS_SERIF, 400, false);
        semibold = Typeface.create(Typeface.SANS_SERIF, 600, false);
        bold = Typeface.create(Typeface.SANS_SERIF, 700, false);
        heavy = Typeface.create(Typeface.SANS_SERIF, 800, false);
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable accentTile(float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF6B78, ACCENT_2});
        g.setCornerRadius(r);
        return g;
    }

    static GradientDrawable scrim(GradientDrawable.Orientation o, int from) {
        return new GradientDrawable(o, new int[]{from, 0x00000000});
    }

    /** q-enter / q-art-spring: fade up with a little spring. */
    static void show(final View v, boolean show) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f); v.setTranslationY(dp(v.getContext(), 10)); v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).translationY(0).setInterpolator(new OvershootInterpolator(1.2f)).setDuration(360).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).translationY(dp(v.getContext(), 6)).setDuration(220).withEndAction(new Runnable() {
                public void run() { v.setVisibility(View.GONE); }
            }).start();
        }
    }

    /** Rounded square with the channel number, like Queue DJ's artwork tiles. */
    static FrameLayout tile(Context c, String number, float sizeDp, boolean accent) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(accent ? accentTile(dp(c, sizeDp * 0.22f)) : rounded(MUTED, dp(c, sizeDp * 0.22f)));
        TextView t = text(c, sizeDp * (number.length() > 4 ? 0.26f : 0.32f), heavy, TEXT);
        t.setText(number);
        t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        return f;
    }

    /** q-eq: three bouncing bars for "now playing". */
    static final class Equalizer extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final long t0 = System.currentTimeMillis();
        Equalizer(Context c, int color) { super(c); p.setColor(color); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), bw = w / 4.2f, gap = (w - 3 * bw) / 2;
            long t = System.currentTimeMillis() - t0;
            for (int i = 0; i < 3; i++) {
                double ph = ((t + i * 300) % 900) / 900.0 * Math.PI * 2;
                float s = (float) (0.3 + 0.7 * (0.5 - 0.5 * Math.cos(ph)));
                float x = i * (bw + gap);
                r.set(x, h * (1 - s), x + bw, h);
                cv.drawRoundRect(r, bw / 2, bw / 2, p);
            }
            if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
        }
    }

    /** Five rising bars, filled by signal strength (0-100). */
    static final class SignalBars extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level;
        private final RectF r = new RectF();
        SignalBars(Context c) { super(c); on.setColor(TEXT); off.setColor(0x44FFFFFF); }
        void setStrength(int pct) { level = pct <= 0 ? 0 : Math.min(5, (pct + 19) / 20); invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), gap = w * 0.08f, bw = (w - gap * 4) / 5, rad = bw * 0.4f;
            for (int i = 0; i < 5; i++) {
                float bh = h * (0.36f + 0.16f * i), x = i * (bw + gap);
                r.set(x, h - bh, x + bw, h);
                cv.drawRoundRect(r, rad, rad, i < level ? on : off);
            }
        }
    }

    /** Thin rounded progress line (accent fill). */
    static final class ProgressLine extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float frac;
        private final RectF r = new RectF();
        ProgressLine(Context c) { super(c); track.setColor(MUTED); fill.setColor(ACCENT); }
        void set(float f) { frac = Math.max(0, Math.min(1, f)); invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), w = getWidth();
            r.set(0, 0, w, h); cv.drawRoundRect(r, h / 2, h / 2, track);
            if (frac > 0) { r.set(0, 0, Math.max(h, w * frac), h); cv.drawRoundRect(r, h / 2, h / 2, fill); }
        }
    }

    private Ui() { }
}
