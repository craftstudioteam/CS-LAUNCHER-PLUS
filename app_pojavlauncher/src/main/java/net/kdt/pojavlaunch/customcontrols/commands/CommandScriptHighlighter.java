package net.kdt.pojavlaunch.customcontrols.commands;

import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.EditText;

import androidx.annotation.NonNull;

/**
 * Lightweight live syntax highlighter for Command Studio scripts.
 * Runs in-place on the Editable and never changes text content.
 */
public final class CommandScriptHighlighter implements TextWatcher {

    private static final int COMMAND = 0xFFB9A5FF;
    private static final int DIRECTIVE = 0xFFFFB020;
    private static final int COMMENT = 0xFF6B7280;
    private static final int VARIABLE = 0xFF7DD3FC;

    private CommandScriptHighlighter() {}

    public static void attach(@NonNull EditText target) {
        target.addTextChangedListener(new CommandScriptHighlighter());
    }

    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(Editable editable) {
        clearMarks(editable);
        int start = 0;
        CharSequence text = editable;
        while (start <= text.length()) {
            int end = start;
            while (end < text.length() && text.charAt(end) != '\n') end++;
            highlightLine(editable, start, end);
            if (end == text.length()) break;
            start = end + 1;
        }
    }

    private static void highlightLine(Editable editable, int start, int end) {
        if (end <= start) return;
        int first = start;
        while (first < end && Character.isWhitespace(editable.charAt(first))) first++;
        if (first >= end) return;

        char c = editable.charAt(first);
        if (c == '#') {
            span(editable, start, end, COMMENT, false);
            return;
        }

        boolean directive = starts(editable, first, "delay:")
                || starts(editable, first, "var:")
                || starts(editable, first, "if:")
                || starts(editable, first, "repeat:");
        if (directive) {
            int colon = first;
            while (colon < end && editable.charAt(colon) != ':' && editable.charAt(colon) != ' ') colon++;
            span(editable, first, Math.min(end, colon + (colon < end && editable.charAt(colon) == ':' ? 1 : 0)), DIRECTIVE, true);
        } else if (c == '/') {
            int cmdEnd = first + 1;
            while (cmdEnd < end && !Character.isWhitespace(editable.charAt(cmdEnd))) cmdEnd++;
            span(editable, first, cmdEnd, COMMAND, true);
        }

        int i = start;
        while (i < end - 1) {
            if (editable.charAt(i) == '$' && editable.charAt(i + 1) == '{') {
                int close = i + 2;
                while (close < end && editable.charAt(close) != '}') close++;
                if (close < end) span(editable, i, close + 1, VARIABLE, false);
                i = close;
            }
            i++;
        }
    }

    private static boolean starts(Editable e, int offset, String token) {
        if (offset + token.length() > e.length()) return false;
        for (int i = 0; i < token.length(); i++) {
            if (Character.toLowerCase(e.charAt(offset + i)) != token.charAt(i)) return false;
        }
        return true;
    }

    private static void span(Editable e, int s, int end, int color, boolean bold) {
        if (end <= s) return;
        e.setSpan(new ForegroundColorSpan(color), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) e.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void clearMarks(Editable e) {
        ForegroundColorSpan[] colors = e.getSpans(0, e.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : colors) e.removeSpan(span);
        StyleSpan[] styles = e.getSpans(0, e.length(), StyleSpan.class);
        for (StyleSpan span : styles) e.removeSpan(span);
    }
}
