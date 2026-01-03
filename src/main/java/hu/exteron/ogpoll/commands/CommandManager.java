package hu.exteron.ogpoll.commands;

import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.managers.ChatInputManager;
import hu.exteron.ogpoll.managers.PollManager;
import hu.exteron.ogpoll.utils.DurationParser;
import hu.exteron.ogpoll.gui.FinishedPollsGUI;
import hu.exteron.ogpoll.gui.PollCreationGUI;
import hu.exteron.ogpoll.gui.PollListGUI;
import hu.exteron.ogpoll.models.Poll;
import org.bukkit.entity.Player;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CommandManager {
    private final OGPoll plugin;
    private final ConfigManager configManager;
    private final PollManager pollManager;
    private final DatabaseManager databaseManager;
    private final PaperCommandManager<CommandSourceStack> manager;

    public CommandManager(OGPoll plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.pollManager = plugin.getPollManager();
        this.databaseManager = plugin.getDatabaseManager();
        this.manager = PaperCommandManager.builder()
            .executionCoordinator(ExecutionCoordinator.asyncCoordinator())
            .buildOnEnable(plugin);

        registerCommands();
    }

    private void registerCommands() {
        manager.command(
            manager.commandBuilder("createpoll")
                .permission("ogpoll.create")
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    startCreatePollWizard(player);
                })
        );

        manager.command(
            manager.commandBuilder("pollcreate")
                .permission("ogpoll.create")
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    startCreatePollWizard(player);
                })
        );

        manager.command(
            manager.commandBuilder("poll")
                .permission("ogpoll.view")
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    new PollListGUI(plugin).open(player);
                })
        );

        manager.command(
            manager.commandBuilder("polls")
                .permission("ogpoll.view")
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    new PollListGUI(plugin).open(player);
                })
        );

        manager.command(
            manager.commandBuilder("polls")
                .literal("finished")
                .permission("ogpoll.manage")
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    new FinishedPollsGUI(plugin).open(player);
                })
        );

        manager.command(
            manager.commandBuilder("polls")
                .literal("close")
                .permission("ogpoll.manage")
                .required("pollId", IntegerParser.integerParser(),
                    SuggestionProvider.blockingStrings((ctx, input) -> pollManager.getActivePollIdStrings())
                )
                .handler(context -> {
                    int pollId = context.get("pollId");
                    CommandSourceStack sender = context.sender();
                    databaseManager.getPollById(pollId, poll -> {
                        if (poll == null) {
                            configManager.sendMessage(sender.getSender(), "error.poll-not-found");
                            return;
                        }
                        pollManager.closePoll(pollId,
                            () -> configManager.sendMessage(sender.getSender(), "poll-closed", Map.of(
                                "question", poll.getQuestion()
                            )),
                            throwable -> configManager.sendMessage(sender.getSender(), "error.database")
                        );
                    }, throwable -> configManager.sendMessage(sender.getSender(), "error.database"));
                })
        );

        manager.command(
            manager.commandBuilder("polls")
                .literal("remove")
                .permission("ogpoll.manage")
                .required("pollId", IntegerParser.integerParser(),
                    SuggestionProvider.blockingStrings((ctx, input) -> pollManager.getActivePollIdStrings())
                )
                .handler(context -> {
                    int pollId = context.get("pollId");
                    CommandSourceStack sender = context.sender();
                    pollManager.deletePoll(pollId,
                        () -> configManager.sendMessage(sender.getSender(), "poll-removed"),
                        throwable -> configManager.sendMessage(sender.getSender(), "error.database")
                    );
                })
        );

        manager.command(
            manager.commandBuilder("polls")
                .literal("list")
                .permission("ogpoll.manage")
                .handler(context -> {
                    CommandSourceStack sender = context.sender();
                    databaseManager.getAllPolls(polls -> {
                        if (polls.isEmpty()) {
                            configManager.sendMessage(sender.getSender(), "admin-poll-list-empty");
                            return;
                        }
                        configManager.sendMessage(sender.getSender(), "admin-poll-list-header");
                        long now = System.currentTimeMillis();
                        polls.forEach(poll -> {
                            String status = buildPollStatus(poll, now);
                            configManager.sendMessage(sender.getSender(), "admin-poll-list-entry", Map.of(
                                "id", String.valueOf(poll.getId()),
                                "question", poll.getQuestion(),
                                "status", status
                            ));
                        });
                    }, throwable -> configManager.sendMessage(sender.getSender(), "error.database"));
                })
        );

        manager.command(
            manager.commandBuilder("poll")
                .literal("finished")
                .permission("ogpoll.manage")
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    new FinishedPollsGUI(plugin).open(player);
                })
        );

        manager.command(
            manager.commandBuilder("poll")
                .literal("close")
                .permission("ogpoll.manage")
                .required("pollId", IntegerParser.integerParser(),
                    SuggestionProvider.blockingStrings((ctx, input) -> pollManager.getActivePollIdStrings())
                )
                .handler(context -> {
                    int pollId = context.get("pollId");
                    CommandSourceStack sender = context.sender();
                    databaseManager.getPollById(pollId, poll -> {
                        if (poll == null) {
                            configManager.sendMessage(sender.getSender(), "error.poll-not-found");
                            return;
                        }
                        pollManager.closePoll(pollId,
                            () -> configManager.sendMessage(sender.getSender(), "poll-closed", Map.of(
                                "question", poll.getQuestion()
                            )),
                            throwable -> configManager.sendMessage(sender.getSender(), "error.database")
                        );
                    }, throwable -> configManager.sendMessage(sender.getSender(), "error.database"));
                })
        );

        manager.command(
            manager.commandBuilder("poll")
                .literal("remove")
                .permission("ogpoll.manage")
                .required("pollId", IntegerParser.integerParser(),
                    SuggestionProvider.blockingStrings((ctx, input) -> pollManager.getActivePollIdStrings())
                )
                .handler(context -> {
                    int pollId = context.get("pollId");
                    CommandSourceStack sender = context.sender();
                    pollManager.deletePoll(pollId,
                        () -> configManager.sendMessage(sender.getSender(), "poll-removed"),
                        throwable -> configManager.sendMessage(sender.getSender(), "error.database")
                    );
                })
        );

        manager.command(
            manager.commandBuilder("poll")
                .literal("list")
                .permission("ogpoll.manage")
                .handler(context -> {
                    CommandSourceStack sender = context.sender();
                    databaseManager.getAllPolls(polls -> {
                        if (polls.isEmpty()) {
                            configManager.sendMessage(sender.getSender(), "admin-poll-list-empty");
                            return;
                        }
                        configManager.sendMessage(sender.getSender(), "admin-poll-list-header");
                        long now = System.currentTimeMillis();
                        polls.forEach(poll -> {
                            String status = buildPollStatus(poll, now);
                            configManager.sendMessage(sender.getSender(), "admin-poll-list-entry", Map.of(
                                "id", String.valueOf(poll.getId()),
                                "question", poll.getQuestion(),
                                "status", status
                            ));
                        });
                    }, throwable -> configManager.sendMessage(sender.getSender(), "error.database"));
                })
        );
    }

    private void startCreatePollWizard(Player player) {
        promptDuration(player);
    }

    private void promptDuration(Player player) {
        String rawPrompt = configManager.getMessagesConfig().getString("chat-input.enter-duration", "");
        configManager.sendMessage(player, "chat-input.enter-duration");
        ChatInputManager.request(player, rawPrompt, input -> {
            if (input.equalsIgnoreCase("cancel")) {
                return true;
            }
            try {
                DurationParser.parseToMillis(input);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }, input -> {
            if (input.equalsIgnoreCase("cancel")) {
                configManager.sendMessage(player, "chat-input.cancelled");
                return;
            }
            long durationMillis;
            try {
                durationMillis = DurationParser.parseToMillis(input);
            } catch (IllegalArgumentException e) {
                configManager.sendMessage(player, "error.invalid-duration");
                promptDuration(player);
                return;
            }

            long minutes = DurationParser.toMinutes(durationMillis);
            if (minutes < configManager.getMinDurationMinutes()) {
                configManager.sendMessage(player, "error.duration-too-short", Map.of(
                    "min", String.valueOf(configManager.getMinDurationMinutes())
                ));
                promptDuration(player);
                return;
            }
            if (minutes > configManager.getMaxDurationMinutes()) {
                configManager.sendMessage(player, "error.duration-too-long", Map.of(
                    "max", String.valueOf(configManager.getMaxDurationMinutes())
                ));
                promptDuration(player);
                return;
            }

            promptQuestion(player, durationMillis);
        }, () -> {
            configManager.sendMessage(player, "error.invalid-duration");
            promptDuration(player);
        });
    }

    private void promptQuestion(Player player, long durationMillis) {
        String rawPrompt = configManager.getMessagesConfig().getString("chat-input.enter-question", "");
        configManager.sendMessage(player, "chat-input.enter-question");
        ChatInputManager.request(player, rawPrompt, input -> true, input -> {
            if (input.equalsIgnoreCase("cancel")) {
                configManager.sendMessage(player, "chat-input.cancelled");
                return;
            }
            new PollCreationGUI(plugin, player, input, durationMillis).open();
        }, () -> {
            configManager.sendMessage(player, "error.invalid-question");
            promptQuestion(player, durationMillis);
        });
    }

    private String buildPollStatus(Poll poll, long now) {
        if (!poll.isActive() || poll.getExpiresAt() <= now) {
            return "ended";
        }
        long remainingMillis = poll.getExpiresAt() - now;
        return "ends in " + formatDuration(remainingMillis);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(millis));
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0) {
            builder.append(minutes).append("m ");
        }
        builder.append(seconds).append("s");
        return builder.toString();
    }
}
