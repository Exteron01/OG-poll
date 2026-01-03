package hu.exteron.ogpoll.managers;

import com.artillexstudios.axapi.scheduler.ScheduledTask;
import com.artillexstudios.axapi.scheduler.Scheduler;
import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.models.Poll;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;

public class PollManager {
    private final OGPoll plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private ScheduledTask expirationTask;

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

    private void startExpirationScanner() {
        if (expirationTask != null) {
            expirationTask.cancel();
        }
        long intervalTicks = 20L * 60L;
        expirationTask = Scheduler.get().runAsyncTimer(this::runExpirationScan, intervalTicks, intervalTicks);
    }

    private void runExpirationScan() {
        databaseManager.getActivePolls(polls -> {
            for (Poll poll : polls) {
                if (poll.isExpired()) {
                    handleExpiration(poll);
                }
            }
        }, throwable -> plugin.getLogger().warning("Failed to scan active polls: " + throwable.getMessage()));
    }

    private void handleExpiration(Poll poll) {
        databaseManager.closePoll(poll.getId(), () -> {
            Map<String, String> replacements = new HashMap<>();
            replacements.put("question", poll.getQuestion());
            Bukkit.getOnlinePlayers().forEach(player ->
                configManager.sendMessage(player, "poll-expired-broadcast", replacements)
            );
        }, throwable -> plugin.getLogger().warning("Failed to close poll: " + throwable.getMessage()));
    }
}
