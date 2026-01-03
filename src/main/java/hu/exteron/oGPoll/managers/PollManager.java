package hu.exteron.ogpoll.managers;

import com.artillexstudios.axapi.scheduler.ScheduledTask;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.Cooldown;
import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.models.Poll;
import hu.exteron.ogpoll.models.PollOption;
import hu.exteron.ogpoll.models.Vote;
import hu.exteron.ogpoll.utils.InputValidator;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PollManager {
    private final OGPoll plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private ScheduledTask expirationTask;
    private final Cooldown<UUID> voteCooldown = Cooldown.create();
    private final Map<Integer, Poll> activePolls = new ConcurrentHashMap<>();

    public PollManager(OGPoll plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = plugin.getDatabaseManager();
    }

    public void loadActivePolls() {
        runExpirationScan();
        startExpirationScanner();
    }

    public void shutdown() {
        if (expirationTask != null) {
            expirationTask.cancel();
            expirationTask = null;
        }
    }

    public void createPoll(
        UUID creator,
        String question,
        List<String> options,
        long durationMillis,
        int maxVotes,
        Consumer<Poll> onSuccess,
        Consumer<String> onFailure
    ) {
        String sanitizedQuestion = InputValidator.sanitize(question);
        if (sanitizedQuestion.isEmpty()) {
            onFailure.accept("error.invalid-question");
            return;
        }

        int minOptions = configManager.getMinOptions();
        int maxOptions = configManager.getMaxOptions();
        if (options == null || options.size() < minOptions || options.size() > maxOptions) {
            onFailure.accept("error.invalid-options");
            return;
        }

        List<String> sanitizedOptions = new ArrayList<>();
        for (String option : options) {
            String sanitized = InputValidator.sanitize(option);
            if (sanitized.isEmpty()) {
                onFailure.accept("error.invalid-option");
                return;
            }
            sanitizedOptions.add(sanitized);
        }

        long durationMinutes = durationMillis / (60L * 1000L);
        if (durationMinutes < configManager.getMinDurationMinutes()) {
            onFailure.accept("error.duration-too-short");
            return;
        }
        if (durationMinutes > configManager.getMaxDurationMinutes()) {
            onFailure.accept("error.duration-too-long");
            return;
        }

        databaseManager.getActivePolls(polls -> {
            if (polls.size() >= configManager.getMaxActivePolls()) {
                onFailure.accept("error.max-polls-reached");
                return;
            }

            Poll poll = new Poll();
            poll.setQuestion(sanitizedQuestion);
            poll.setCreatorUuid(creator);
            poll.setCreatedAt(System.currentTimeMillis());
            poll.setExpiresAt(System.currentTimeMillis() + durationMillis);
            poll.setActive(true);
            poll.setMaxVotes(maxVotes);

            databaseManager.createPoll(poll, pollId -> {
                poll.setId(pollId);
                List<PollOption> pollOptions = new ArrayList<>();
                for (int i = 0; i < sanitizedOptions.size(); i++) {
                    PollOption option = new PollOption();
                    option.setPollId(pollId);
                    option.setOptionText(sanitizedOptions.get(i));
                    option.setDisplayOrder(i);
                    pollOptions.add(option);
                }
                addOptionsSequential(new ArrayList<>(pollOptions), () -> {
                    poll.setOptions(pollOptions);
                    this.activePolls.put(poll.getId(), poll);
                    scheduleExpiration(poll);
                    onSuccess.accept(poll);
                }, throwable -> onFailure.accept("error.database"));
            }, throwable -> onFailure.accept("error.database"));
        }, throwable -> onFailure.accept("error.database"));
    }

    public void vote(UUID playerUuid, int pollId, int optionId, Consumer<VoteResult> onResult) {
        if (voteCooldown.hasCooldown(playerUuid)) {
            long remaining = voteCooldown.getRemaining(playerUuid);
            onResult.accept(VoteResult.fail("error.vote-cooldown", remaining));
            return;
        }

        databaseManager.getPollById(pollId, poll -> {
            if (poll == null || !poll.isActive() || poll.isExpired()) {
                onResult.accept(VoteResult.fail("error.poll-expired", 0L));
                return;
            }

            databaseManager.hasVoted(pollId, playerUuid, hasVoted -> {
                if (hasVoted) {
                    onResult.accept(VoteResult.fail("vote-already", 0L));
                    return;
                }

                Vote vote = new Vote();
                vote.setPollId(pollId);
                vote.setOptionId(optionId);
                vote.setPlayerUuid(playerUuid);
                vote.setVotedAt(System.currentTimeMillis());

                databaseManager.recordVote(vote, () -> {
                    voteCooldown.addCooldown(playerUuid, configManager.getVoteCooldownMillis());
                    onResult.accept(VoteResult.ok());
                    checkMaxVotesReached(poll);
                }, throwable -> onResult.accept(VoteResult.fail("error.database", 0L)));
            }, throwable -> onResult.accept(VoteResult.fail("error.database", 0L)));
        }, throwable -> onResult.accept(VoteResult.fail("error.database", 0L)));
    }

    public void closePoll(int pollId, Runnable onSuccess, Consumer<Throwable> onError) {
        databaseManager.closePoll(pollId, () -> {
            activePolls.remove(pollId);
            onSuccess.run();
        }, onError);
    }

    public void deletePoll(int pollId, Runnable onSuccess, Consumer<Throwable> onError) {
        databaseManager.deletePoll(pollId, () -> {
            activePolls.remove(pollId);
            onSuccess.run();
        }, onError);
    }

    public List<String> getActivePollIdStrings() {
        List<String> ids = new ArrayList<>();
        for (Integer id : activePolls.keySet()) {
            ids.add(String.valueOf(id));
        }
        return ids;
    }

    private void startExpirationScanner() {
        if (expirationTask != null) {
            expirationTask.cancel();
        }
        long intervalTicks = 20L * 60L;
        expirationTask = Scheduler.get().runAsyncTimer(this::runExpirationScan, intervalTicks, intervalTicks);
    }

    private void runExpirationScan() {
        databaseManager.getActivePolls(polls -> {
            activePolls.clear();
            for (Poll poll : polls) {
                activePolls.put(poll.getId(), poll);
                if (poll.isExpired()) {
                    handleExpiration(poll);
                }
            }
        }, throwable -> plugin.getLogger().warning("Failed to scan active polls: " + throwable.getMessage()));
    }

    private void handleExpiration(Poll poll) {
        databaseManager.closePoll(poll.getId(), () -> {
            activePolls.remove(poll.getId());
            Map<String, String> replacements = new HashMap<>();
            replacements.put("question", poll.getQuestion());
            Bukkit.getOnlinePlayers().forEach(player ->
                configManager.sendMessage(player, "poll-expired-broadcast", replacements)
            );
        }, throwable -> plugin.getLogger().warning("Failed to close poll: " + throwable.getMessage()));
    }

    private void checkMaxVotesReached(Poll poll) {
        int maxVotes = poll.getMaxVotes();
        if (maxVotes <= 0) {
            return;
        }

        databaseManager.getVoteCounts(poll.getId(), counts -> {
            int totalVotes = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (totalVotes >= maxVotes) {
                databaseManager.closePoll(poll.getId(), () -> {
                    activePolls.remove(poll.getId());
                    Map<String, String> replacements = new HashMap<>();
                    replacements.put("question", poll.getQuestion());
                    replacements.put("votes", String.valueOf(totalVotes));
                    Bukkit.getOnlinePlayers().forEach(player ->
                        configManager.sendMessage(player, "poll-max-votes-reached", replacements)
                    );
                }, throwable -> plugin.getLogger().warning("Failed to close poll after max votes: " + throwable.getMessage()));
            }
        }, throwable -> plugin.getLogger().warning("Failed to check vote counts: " + throwable.getMessage()));
    }

    private void scheduleExpiration(Poll poll) {
        long delayMillis = poll.getExpiresAt() - System.currentTimeMillis();
        if (delayMillis <= 0L) {
            handleExpiration(poll);
            return;
        }
        long delayTicks = Math.max(1L, delayMillis / 50L);
        Scheduler.get().runLater(() -> handleExpiration(poll), delayTicks);
    }

    private void addOptionsSequential(
        List<PollOption> options,
        Runnable onSuccess,
        Consumer<Throwable> onError
    ) {
        if (options.isEmpty()) {
            onSuccess.run();
            return;
        }

        PollOption option = options.remove(0);
        databaseManager.addOption(option, () -> addOptionsSequential(options, onSuccess, onError), onError);
    }

    public record VoteResult(boolean success, String messageKey, long remainingMillis) {
        public static VoteResult ok() {
            return new VoteResult(true, "", 0L);
        }

        public static VoteResult fail(String messageKey, long remainingMillis) {
            return new VoteResult(false, messageKey, remainingMillis);
        }
    }
}
