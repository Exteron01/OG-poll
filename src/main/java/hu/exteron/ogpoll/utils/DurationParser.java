package hu.exteron.ogpoll.utils;

public final class DurationParser {
    private DurationParser() {
    }

    public static long parseToMillis(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }

        char unit = input.charAt(input.length() - 1);
        String numberPart = input.substring(0, input.length() - 1);

        long number;
        try {
            number = Long.parseLong(numberPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format");
        }

        return switch (unit) {
            case 'm', 'M' -> number * 60L * 1000L;
            case 'h', 'H' -> number * 60L * 60L * 1000L;
            case 'd', 'D' -> number * 24L * 60L * 60L * 1000L;
            default -> throw new IllegalArgumentException("Invalid unit. Use m, h, or d");
        };
    }

    public static long toMinutes(long millis) {
        return millis / (60L * 1000L);
    }
}
