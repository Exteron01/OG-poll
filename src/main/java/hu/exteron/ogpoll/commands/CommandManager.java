package hu.exteron.ogpoll.commands;

import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.managers.PollManager;
import hu.exteron.ogpoll.utils.DurationParser;
import org.bukkit.entity.Player;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.List;
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
                .required("duration", StringParser.stringParser())
                .required("question", StringParser.greedyStringParser())
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    String durationInput = context.get("duration");
                    String question = context.get("question");

                    long durationMillis;
                    try {
                        durationMillis = DurationParser.parseToMillis(durationInput);
                    } catch (IllegalArgumentException e) {
                        configManager.sendMessage(player, "error.invalid-duration");
                        return;
                    }

                    new PollCreationSession(plugin, player, question, durationMillis).start();
                })
        );

        manager.command(
            manager.commandBuilder("poll", "polls")
                .permission("ogpoll.view")
                .handler(context -> {
                    if (!(context.sender().getSender() instanceof Player player)) {
                        configManager.sendMessage(context.sender().getSender(), "error.player-only");
                        return;
                    }
                    databaseManager.getActivePolls(polls -> {
                        if (polls.isEmpty()) {
                            configManager.sendMessage(player, "poll-list-empty");
                            return;
                        }
                        configManager.sendMessage(player, "poll-list-header");
                        polls.forEach(poll -> configManager.sendMessage(player, "poll-list-entry", Map.of(
                            "id", String.valueOf(poll.getId()),
                            "question", poll.getQuestion()
                        )));
                    }, throwable -> configManager.sendMessage(player, "error.database"));
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

    private String buildPollStatus(hu.exteron.ogpoll.models.Poll poll, long now) {
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
