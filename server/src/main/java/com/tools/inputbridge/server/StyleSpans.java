package com.tools.inputbridge.server;

import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;

/**
 * Flattens the parcelable subset of Android text spans into the
 * (start, end, kind, value) tuples carried by clipboard frames.
 *
 * <p>Offsets are UTF-16 code unit indexes into the clipboard text, so they stay
 * valid after the text is decoded from UTF-8 on the plugin side.
 */
final class StyleSpans {
    static final int KIND_BOLD = 1;
    static final int KIND_ITALIC = 2;
    static final int KIND_UNDERLINE = 3;
    static final int KIND_STRIKETHROUGH = 4;
    static final int KIND_FOREGROUND = 5;
    static final int KIND_BACKGROUND = 6;
    static final int KIND_RELATIVE_SIZE = 7;
    static final int KIND_MONOSPACE = 8;
    static final int KIND_LINK = 9;
    static final int KIND_SUPERSCRIPT = 10;
    static final int KIND_SUBSCRIPT = 11;

    static final int VALUES_PER_SPAN = 4;
    static final int[] NONE = new int[0];

    private static final String MONOSPACE_FAMILY = "monospace";
    private static final int MIN_SIZE_PERCENT = 50;
    private static final int MAX_SIZE_PERCENT = 300;
    private static final int NOMINAL_TEXT_SIZE = 16;

    private StyleSpans() {
    }

    /** Returns a flat tuple array; an unstyled sequence produces {@link #NONE}. */
    static int[] extract(CharSequence content, int maxSpans) {
        if (!(content instanceof Spanned)) {
            return NONE;
        }
        Spanned spanned = (Spanned) content;
        int length = spanned.length();
        if (length == 0) {
            return NONE;
        }

        Collector collector = new Collector(maxSpans);
        for (Object span : spanned.getSpans(0, length, Object.class)) {
            int start = Math.max(0, spanned.getSpanStart(span));
            int end = Math.min(length, spanned.getSpanEnd(span));
            if (start >= end) {
                continue;
            }
            collect(collector, span, start, end);
            if (collector.isFull()) {
                break;
            }
        }
        return collector.toArray();
    }

    private static void collect(Collector collector, Object span, int start, int end) {
        if (span instanceof StyleSpan) {
            int style = ((StyleSpan) span).getStyle();
            if ((style & Typeface.BOLD) != 0) {
                collector.add(start, end, KIND_BOLD, 0);
            }
            if ((style & Typeface.ITALIC) != 0) {
                collector.add(start, end, KIND_ITALIC, 0);
            }
        } else if (span instanceof URLSpan) {
            collector.add(start, end, KIND_LINK, 0);
        } else if (span instanceof UnderlineSpan) {
            collector.add(start, end, KIND_UNDERLINE, 0);
        } else if (span instanceof StrikethroughSpan) {
            collector.add(start, end, KIND_STRIKETHROUGH, 0);
        } else if (span instanceof ForegroundColorSpan) {
            collector.add(start, end, KIND_FOREGROUND, ((ForegroundColorSpan) span).getForegroundColor());
        } else if (span instanceof BackgroundColorSpan) {
            collector.add(start, end, KIND_BACKGROUND, ((BackgroundColorSpan) span).getBackgroundColor());
        } else if (span instanceof RelativeSizeSpan) {
            collector.add(start, end, KIND_RELATIVE_SIZE, sizePercent(((RelativeSizeSpan) span).getSizeChange() * 100f));
        } else if (span instanceof AbsoluteSizeSpan) {
            // Approximated against a nominal body size; the plugin only needs a relative hint.
            collector.add(start, end, KIND_RELATIVE_SIZE,
                    sizePercent(((AbsoluteSizeSpan) span).getSize() * 100f / NOMINAL_TEXT_SIZE));
        } else if (span instanceof TypefaceSpan) {
            if (MONOSPACE_FAMILY.equals(((TypefaceSpan) span).getFamily())) {
                collector.add(start, end, KIND_MONOSPACE, 0);
            }
        } else if (span instanceof SuperscriptSpan) {
            collector.add(start, end, KIND_SUPERSCRIPT, 0);
        } else if (span instanceof SubscriptSpan) {
            collector.add(start, end, KIND_SUBSCRIPT, 0);
        }
    }

    private static int sizePercent(float percent) {
        int rounded = Math.round(percent);
        return Math.max(MIN_SIZE_PERCENT, Math.min(MAX_SIZE_PERCENT, rounded));
    }

    private static final class Collector {
        private final int maxSpans;
        private int[] values = new int[16 * VALUES_PER_SPAN];
        private int size;

        Collector(int maxSpans) {
            this.maxSpans = maxSpans;
        }

        void add(int start, int end, int kind, int value) {
            if (isFull()) {
                return;
            }
            if (size + VALUES_PER_SPAN > values.length) {
                int[] grown = new int[values.length * 2];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = start;
            values[size++] = end;
            values[size++] = kind;
            values[size++] = value;
        }

        boolean isFull() {
            return size >= maxSpans * VALUES_PER_SPAN;
        }

        int[] toArray() {
            if (size == 0) {
                return NONE;
            }
            int[] result = new int[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }
}
