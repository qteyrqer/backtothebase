package xin.bbtt.backtothebase;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the private-chat command sent by whitelisted players:
 * {@code back} or {@code back <base id>}.
 */
public final class BackRequest {
    /** "back" optionally followed by a positive base number; extra words are ignored. */
    private static final Pattern PATTERN =
            Pattern.compile("^/?\\s*back(?:\\s+(\\d{1,4}))?\\s*$", Pattern.CASE_INSENSITIVE);
    /** Minecraft colour codes that some chat pipelines leave in the payload. */
    private static final Pattern COLOR_CODES = Pattern.compile("\u00a7[0-9A-FK-ORa-fk-or]");

    private BackRequest() {
    }

    /**
     * @return the requested base id (defaults to 1), or empty when the message
     *         is not a "back" request
     */
    public static OptionalInt parse(String message) {
        if (message == null) return OptionalInt.empty();
        Matcher matcher = PATTERN.matcher(COLOR_CODES.matcher(message).replaceAll("").trim());
        if (!matcher.matches()) return OptionalInt.empty();
        String base = matcher.group(1);
        if (base == null) return OptionalInt.of(1);
        try {
            int value = Integer.parseInt(base);
            return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    public static String format(int base) {
        return "back " + base;
    }
}
