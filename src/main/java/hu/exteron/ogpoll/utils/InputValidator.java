package hu.exteron.ogpoll.utils;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;

public final class InputValidator {
    private static final int MAX_INPUT_LENGTH = 256;
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+$");

    private InputValidator() {
    }

    public static String sanitize(String input) {
        if (input == null) {
            return "";
        }

        String sanitized = input.trim();
        if (sanitized.length() > MAX_INPUT_LENGTH) {
            return "";
        }

        sanitized = sanitized.replaceAll("[\\x00-\\x1F\\x7F]", "");
        return sanitized;
    }

    public static OptionalInt parseIntBounded(String input, int min, int max) {
        String sanitized = sanitize(input);
        if (!isPlainInteger(sanitized)) {
            return OptionalInt.empty();
        }

        try {
            int value = Integer.parseInt(sanitized);
            if (value < min || value > max) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(value);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    public static OptionalLong parseLongBounded(String input, long min, long max) {
        String sanitized = sanitize(input);
        if (!isPlainInteger(sanitized)) {
            return OptionalLong.empty();
        }

        try {
            long value = Long.parseLong(sanitized);
            if (value < min || value > max) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(value);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static OptionalDouble parseDoubleBounded(String input, double min, double max) {
        String sanitized = sanitize(input);
        if (sanitized.isEmpty() || sanitized.contains("e") || sanitized.contains("E")) {
            return OptionalDouble.empty();
        }

        try {
            double value = Double.parseDouble(sanitized);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return OptionalDouble.empty();
            }
            if (value < min || value > max) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(value);
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static boolean isPlainInteger(String input) {
        return !input.isEmpty() && INTEGER_PATTERN.matcher(input).matches();
    }
}
