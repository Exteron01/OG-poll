package hu.exteron.ogpoll.utils;

import com.artillexstudios.axapi.config.Config;

public class ProgressBarUtil {

    public static String createProgressBar(int votes, int totalVotes, int length, String filledChar, String emptyChar, Config config) {
        if (totalVotes == 0) {
            return createBar(0, length, filledChar, emptyChar, "<gray>");
        }

        double percentage = (double) votes / totalVotes * 100;
        int filled = (int) Math.round((double) votes / totalVotes * length);

        String color;
        if (percentage >= 60) {
            color = config.getString("voting-gui.progress_bar.colors.high", "<green>");
        } else if (percentage >= 30) {
            color = config.getString("voting-gui.progress_bar.colors.medium", "<yellow>");
        } else {
            color = config.getString("voting-gui.progress_bar.colors.low", "<gray>");
        }

        return createBar(filled, length, filledChar, emptyChar, color);
    }

    private static String createBar(int filled, int total, String filledChar, String emptyChar, String color) {
        StringBuilder bar = new StringBuilder();
        bar.append(color);

        for (int i = 0; i < filled; i++) {
            bar.append(filledChar);
        }

        bar.append("<dark_gray>");
        for (int i = filled; i < total; i++) {
            bar.append(emptyChar);
        }

        return bar.toString();
    }

    public static double calculatePercentage(int votes, int totalVotes) {
        if (totalVotes == 0) return 0.0;
        return Math.round((double) votes / totalVotes * 1000) / 10.0;
    }
}
