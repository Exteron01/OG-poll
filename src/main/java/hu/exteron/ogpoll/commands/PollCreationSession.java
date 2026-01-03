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

final class PollCreationSession {
    private final OGPoll plugin;
    private final Player player;
    private final String question;
    private final long durationMillis;
    private final ConfigManager configManager;
    private final PollManager pollManager;
    private final List<String> options = new ArrayList<>();
    private final int minOptions;
    private final int maxOptions;

    PollCreationSession(OGPoll plugin, Player player, String question, long durationMillis) {
        this.plugin = plugin;
        this.player = player;
        this.question = question;
        this.durationMillis = durationMillis;
        this.configManager = plugin.getConfigManager();
        this.pollManager = plugin.getPollManager();
        this.minOptions = configManager.getMinOptions();
        this.maxOptions = configManager.getMaxOptions();
    }

    void start() {
        promptOption(1);
    }

    private void promptOption(int number) {
        if (number > maxOptions) {
            createPoll();
            return;
        }

        Map<String, String> replacements = new HashMap<>();
        replacements.put("number", String.valueOf(number));
        String prompt = configManager.messageString("chat-input.enter-option", replacements);
        player.sendMessage(prompt);

        ChatInputManager.request(player, prompt, input -> true, input -> {
            if (input.equalsIgnoreCase("cancel")) {
                configManager.sendMessage(player, "chat-input.cancelled");
                return;
            }
            if (input.equalsIgnoreCase("done")) {
                if (options.size() >= minOptions) {
                    createPoll();
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

    private void createPoll() {
        pollManager.createPoll(
            player.getUniqueId(),
            question,
            options,
            durationMillis,
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
