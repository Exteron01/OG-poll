package hu.exteron.ogpoll.commands;

import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.managers.ChatInputManager;
import hu.exteron.ogpoll.managers.PollManager;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PollCreationSession {
    private final OGPoll plugin;
    private final Player player;
    private final String question;
    private final long durationMillis;
    private final ConfigManager configManager;
    private final PollManager pollManager;
    private final List<String> options = new ArrayList<>();
    private final int minOptions;
    private final int maxOptions;
    private final boolean isYesNo;
    private int maxVotes = 0;

    public PollCreationSession(OGPoll plugin, Player player, String question, long durationMillis, int maxOptions, boolean isYesNo) {
        this.plugin = plugin;
        this.player = player;
        this.question = question;
        this.durationMillis = durationMillis;
        this.configManager = plugin.getConfigManager();
        this.pollManager = plugin.getPollManager();
        this.minOptions = configManager.getMinOptions();
        int configuredMax = configManager.getMaxOptions();
        if (maxOptions > 0) {
            this.maxOptions = Math.max(this.minOptions, Math.min(configuredMax, maxOptions));
        } else {
            this.maxOptions = configuredMax;
        }
        this.isYesNo = isYesNo;
    }

    public void start() {
        if (isYesNo) {
            options.add("Yes");
            options.add("No");
            promptMaxVotes();
        } else {
            promptOption(1);
        }
    }

    private void promptOption(int number) {
        if (number > maxOptions) {
            promptMaxVotes();
            return;
        }

        Map<String, String> replacements = new HashMap<>();
        replacements.put("number", String.valueOf(number));
        configManager.sendMessage(player, "chat-input.enter-option", replacements);
        String rawPrompt = applyReplacements(
            configManager.getMessagesConfig().getString("chat-input.enter-option", ""),
            replacements
        );

        ChatInputManager.request(player, rawPrompt, input -> true, input -> {
            if (input.equalsIgnoreCase("cancel")) {
                configManager.sendMessage(player, "chat-input.cancelled");
                return;
            }
            if (input.equalsIgnoreCase("done")) {
                if (options.size() >= minOptions) {
                    promptMaxVotes();
                } else {
                    configManager.sendMessage(player, "error.invalid-options");
                    promptOption(number);
                }
                return;
            }

            options.add(input);
            promptOption(number + 1);
        }, () -> {
            configManager.sendMessage(player, "error.invalid-option");
            promptOption(number);
        });
    }

    private void promptMaxVotes() {
        configManager.sendMessage(player, "chat-input.enter-max-votes");
        String rawPrompt = configManager.getMessagesConfig().getString("chat-input.enter-max-votes", "");

        ChatInputManager.request(player, rawPrompt, input -> true, input -> {
            if (input.equalsIgnoreCase("cancel")) {
                configManager.sendMessage(player, "chat-input.cancelled");
                return;
            }
            if (input.equalsIgnoreCase("unlimited")) {
                maxVotes = 0;
                createPoll();
                return;
            }

            try {
                int parsed = Integer.parseInt(input);
                if (parsed <= 0) {
                    configManager.sendMessage(player, "error.invalid-max-votes");
                    promptMaxVotes();
                    return;
                }
                maxVotes = parsed;
                createPoll();
            } catch (NumberFormatException e) {
                configManager.sendMessage(player, "error.invalid-max-votes");
                promptMaxVotes();
            }
        }, () -> {
            configManager.sendMessage(player, "error.invalid-max-votes");
            promptMaxVotes();
        });
    }

    private String applyReplacements(String input, Map<String, String> replacements) {
        String output = input;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private void createPoll() {
        pollManager.createPoll(
            player.getUniqueId(),
            question,
            options,
            durationMillis,
            maxVotes,
            poll -> configManager.sendMessage(player, "poll-created", Map.of(
                "id", String.valueOf(poll.getId())
            )),
            messageKey -> {
                if (messageKey.startsWith("error.")) {
                    configManager.sendMessage(player, messageKey);
                } else {
                    configManager.sendMessage(player, "error.database");
                }
            }
        );
    }
}
