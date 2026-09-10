package net.kdt.pojavlaunch.servers;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Renders a Minecraft server MOTD exactly like the vanilla multiplayer screen does:
 * both the modern JSON chat-component format (with nested "extra", inherited styles,
 * named colours and #RRGGBB colours) and the legacy section-sign (\u00a7) colour codes.
 *
 * <p>Vanilla clients apply the parent component's style to every child unless the child
 * overrides it, and a legacy section-sign code inside a text payload resets the style from that point
 * on. Both rules are implemented here, which is why colours finally show up in the hub.
 *
 * <p>The result is a {@link CharSequence} that can be handed straight to a TextView.
 */
public final class MotdRenderer {

    private MotdRenderer() {}

    // ── Vanilla colour table (the 16 legacy codes == the 16 named JSON colours) ──
    private static final char[] LEGACY_KEYS = {
            '0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'
    };
    private static final int[] LEGACY_COLORS = {
            0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA,
            0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
            0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF,
            0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF
    };
    private static final String[] NAMED = {
            "black","dark_blue","dark_green","dark_aqua",
            "dark_red","dark_purple","gold","gray",
            "dark_gray","blue","green","aqua",
            "red","light_purple","yellow","white"
    };

    /** Default text colour used when the server did not specify one. */
    public static final int DEFAULT_COLOR = 0xFFEDEEF2;

    /** Mutable style state while walking the component tree / legacy string. */
    private static final class Style {
        Integer color;
        boolean bold, italic, underlined, strikethrough;
        Style copy() {
            Style s = new Style();
            s.color = color; s.bold = bold; s.italic = italic;
            s.underlined = underlined; s.strikethrough = strikethrough;
            return s;
        }
        void reset() { color = null; bold = italic = underlined = strikethrough = false; }
    }

    /**
     * Main entry point. Give it the raw MOTD (JSON component or legacy string);
     * it never throws and always returns something printable.
     *
     * @param raw       raw description, JSON or legacy
     * @param plainFallback plain text used when the raw payload cannot be understood
     * @param darkBackground when true, pitch-black / very dark colours get lifted a bit so
     *                       they stay readable on the launcher's graphite cards
     */
    public static CharSequence render(String raw, String plainFallback, boolean darkBackground) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        try {
            String src = raw != null ? raw.trim() : "";
            if (src.isEmpty()) src = plainFallback != null ? plainFallback : "";
            if (src.isEmpty()) return "A Minecraft Server";

            if (src.startsWith("{") || src.startsWith("[")) {
                Object parsed = new JSONTokener(src).nextValue();
                appendComponent(out, parsed, new Style(), darkBackground);
            } else {
                appendLegacy(out, src, new Style(), darkBackground);
            }
        } catch (Exception ignored) {
            out.clear();
        }
        if (out.length() == 0) {
            String fb = plainFallback != null && !plainFallback.isEmpty() ? plainFallback : "A Minecraft Server";
            SpannableStringBuilder sb = new SpannableStringBuilder();
            appendLegacy(sb, fb, new Style(), darkBackground);
            return sb;
        }
        return trimTrailingNewlines(out);
    }

    public static CharSequence render(ServerEntry e) {
        String raw = e.motdRaw != null && !e.motdRaw.isEmpty() ? e.motdRaw : e.motd;
        return render(raw, e.motd, true);
    }

    /** Strips every colour code, for places that need plain text (server name, servers.dat). */
    public static String toPlain(String raw) {
        if (raw == null) return "";
        String src = raw.trim();
        try {
            if (src.startsWith("{") || src.startsWith("[")) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                appendComponent(sb, new JSONTokener(src).nextValue(), new Style(), false);
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return stripLegacy(src);
    }

    public static String stripLegacy(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)\u00a7[0-9A-FK-ORX]", "");
    }

    // ─────────────────────────── JSON components ───────────────────────────

    private static void appendComponent(SpannableStringBuilder out, Object node,
                                        Style inherited, boolean dark) {
        if (node == null) return;

        if (node instanceof String) {
            appendLegacy(out, (String) node, inherited, dark);
            return;
        }
        if (node instanceof Boolean || node instanceof Number) {
            appendStyled(out, String.valueOf(node), inherited, dark);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            // Vanilla: the first element of an array is the parent style for the rest.
            Style style = inherited;
            for (int i = 0; i < arr.length(); i++) {
                Object child = arr.opt(i);
                if (i == 0 && child instanceof JSONObject) {
                    style = mergeStyle(inherited, (JSONObject) child);
                }
                appendComponent(out, child, i == 0 ? inherited : style, dark);
            }
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject jo = (JSONObject) node;
            Style style = mergeStyle(inherited, jo);

            String text = jo.optString("text", null);
            if (text == null) text = jo.optString("literal", null); // some proxies use this
            if (text != null && !text.isEmpty()) appendLegacy(out, text, style, dark);

            String translate = jo.optString("translate", "");
            if (!translate.isEmpty() && (text == null || text.isEmpty())) {
                JSONArray with = jo.optJSONArray("with");
                StringBuilder sb = new StringBuilder(translate);
                if (with != null) {
                    for (int i = 0; i < with.length(); i++) {
                        String w = toPlain(String.valueOf(with.opt(i)));
                        if (!w.isEmpty()) sb.append(' ').append(w);
                    }
                }
                appendStyled(out, sb.toString(), style, dark);
            }

            JSONArray extra = jo.optJSONArray("extra");
            if (extra != null) {
                for (int i = 0; i < extra.length(); i++) {
                    appendComponent(out, extra.opt(i), style, dark);
                }
            }
        }
    }

    private static Style mergeStyle(Style parent, JSONObject jo) {
        Style s = parent.copy();
        String color = jo.optString("color", null);
        if (color != null && !color.isEmpty()) {
            Integer c = parseColor(color);
            if (c != null) s.color = c;
        }
        if (jo.has("bold")) s.bold = jo.optBoolean("bold", s.bold);
        if (jo.has("italic")) s.italic = jo.optBoolean("italic", s.italic);
        if (jo.has("underlined")) s.underlined = jo.optBoolean("underlined", s.underlined);
        if (jo.has("strikethrough")) s.strikethrough = jo.optBoolean("strikethrough", s.strikethrough);
        return s;
    }

    private static Integer parseColor(String color) {
        String c = color.trim().toLowerCase();
        if (c.startsWith("#")) {
            try { return 0xFF000000 | (Color.parseColor(c) & 0x00FFFFFF); }
            catch (Exception ignored) { return null; }
        }
        for (int i = 0; i < NAMED.length; i++) if (NAMED[i].equals(c)) return LEGACY_COLORS[i];
        if (c.equals("reset")) return null;
        return null;
    }

    // ─────────────────────────── Legacy § codes ───────────────────────────

    private static void appendLegacy(SpannableStringBuilder out, String text,
                                     Style inherited, boolean dark) {
        if (text == null || text.isEmpty()) return;
        Style style = inherited.copy();
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean isMarker = (ch == '\u00a7');
            if (isMarker && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                // flush what we have with the current style, then switch style
                if (buffer.length() > 0) { appendStyled(out, buffer.toString(), style, dark); buffer.setLength(0); }
                i++;
                int idx = indexOfLegacy(code);
                if (idx >= 0) {
                    // A colour code resets all formatting in vanilla.
                    style.reset();
                    style.color = LEGACY_COLORS[idx];
                } else switch (code) {
                    case 'l': style.bold = true; break;
                    case 'm': style.strikethrough = true; break;
                    case 'n': style.underlined = true; break;
                    case 'o': style.italic = true; break;
                    case 'k': break; // obfuscated — rendered as normal text
                    case 'r': style = inherited.copy(); break;
                    case 'x': {
                        // Bungee hex: §x§R§R§G§G§B§B
                        StringBuilder hex = new StringBuilder("#");
                        int j = i + 1;
                        for (int k = 0; k < 6 && j + 1 < text.length(); k++, j += 2) {
                            if (text.charAt(j) != '\u00a7') break;
                            hex.append(text.charAt(j + 1));
                        }
                        if (hex.length() == 7) {
                            Integer c = parseColor(hex.toString());
                            if (c != null) { style.reset(); style.color = c; i = j - 1; }
                        }
                        break;
                    }
                    default: break;
                }
                continue;
            }
            buffer.append(ch);
        }
        if (buffer.length() > 0) appendStyled(out, buffer.toString(), style, dark);
    }

    private static int indexOfLegacy(char c) {
        for (int i = 0; i < LEGACY_KEYS.length; i++) if (LEGACY_KEYS[i] == c) return i;
        return -1;
    }

    // ─────────────────────────── span helper ───────────────────────────

    private static void appendStyled(SpannableStringBuilder out, String text, Style s, boolean dark) {
        if (text == null || text.isEmpty()) return;
        int start = out.length();
        out.append(text);
        int end = out.length();

        int color = s.color != null ? s.color : DEFAULT_COLOR;
        if (dark) color = liftForDarkBackground(color);
        out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (s.bold && s.italic) out.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if (s.bold) out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if (s.italic) out.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (s.underlined) out.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (s.strikethrough) out.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Keeps black / dark_blue style colours readable on the graphite server cards. */
    private static int liftForDarkBackground(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b);
        if (lum >= 70) return color;
        double factor = 70.0 / Math.max(lum, 1.0);
        factor = Math.min(factor, 3.2);
        r = (int) Math.min(255, r * factor + 55);
        g = (int) Math.min(255, g * factor + 55);
        b = (int) Math.min(255, b * factor + 55);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static CharSequence trimTrailingNewlines(SpannableStringBuilder sb) {
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == '\n' || sb.charAt(end - 1) == ' ')) end--;
        int start = 0;
        while (start < end && (sb.charAt(start) == '\n' || sb.charAt(start) == ' ')) start++;
        return sb.subSequence(start, end);
    }
}
