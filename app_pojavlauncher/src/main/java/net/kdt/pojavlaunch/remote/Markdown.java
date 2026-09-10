package net.kdt.pojavlaunch.remote;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.BulletSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny GitHub-flavoured Markdown subset renderer (no external dependency):
 * headings, bold, italic, inline code, links, lists, blockquotes, line breaks.
 * Used by the Firebase announcement/update dialogs.
 */
public final class Markdown {

    private Markdown() { }

    public static SpannableStringBuilder render(Context ctx, String md) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (md == null) return sb;

        // code blocks → plain pre block styling
        md = replaceCodeBlocks(md);

        String[] lines = md.split("\n");
        for (String line : lines) {
            int start = sb.length();
            String t = line;
            if (t.startsWith("### ")) { sb.append(t.substring(4)); styleHeading(sb, start, 16); }
            else if (t.startsWith("## ")) { sb.append(t.substring(3)); styleHeading(sb, start, 17); }
            else if (t.startsWith("# ")) { sb.append(t.substring(2)); styleHeading(sb, start, 18); }
            else if (t.startsWith("> ")) { sb.append("▍ " + t.substring(2)); }
            else if (t.startsWith("- ") || t.startsWith("* ")) {
                sb.append(t.substring(2));
                sb.setSpan(new BulletSpan(18), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(t);
            }
            sb.append("\n");
        }
        applyInline(sb);
        return sb;
    }

    private static void styleHeading(SpannableStringBuilder sb, int start, int size) {
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(size, true), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Bold, italic, code, links — applied over the whole text. */
    private static void applyInline(SpannableStringBuilder sb) {
        Pattern bold = Pattern.compile("\\*\\*([^*]+)\\*\\*");
        Matcher m = bold.matcher(sb);
        while (m.find()) sb.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        Pattern italic = Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)");
        m = italic.matcher(sb);
        while (m.find()) sb.setSpan(new StyleSpan(Typeface.ITALIC), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        Pattern code = Pattern.compile("`([^`]+)`");
        m = code.matcher(sb);
        while (m.find()) {
            sb.setSpan(new TypefaceSpan("monospace"), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFFD0D0D0), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        Pattern link = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)");
        m = link.matcher(sb);
        while (m.find()) {
            sb.setSpan(new URLSpan(m.group(2)), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String replaceCodeBlocks(String input) {
        if (input == null) return "";
        Pattern pattern = Pattern.compile("```[\\s\\S]*?```");
        Matcher matcher = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String text = matcher.group(0).replaceAll("`", "").replaceAll("\\s+", " ").trim();
            matcher.appendReplacement(sb, Matcher.quoteReplacement("« " + text + " »"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
