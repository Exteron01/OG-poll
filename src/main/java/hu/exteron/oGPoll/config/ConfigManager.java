package hu.exteron.ogpoll.config;

import com.artillexstudios.axapi.config.Config;
import com.artillexstudios.axapi.utils.StringUtils;
import hu.exteron.ogpoll.OGPoll;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.Collections;
import java.util.Map;

public class ConfigManager {
    private final OGPoll plugin;
    private Config config;
    private Config guiConfig;
    private Config messagesConfig;

    public ConfigManager(OGPoll plugin) {
        this.plugin = plugin;
        loadConfigs();
    }

    public void reload() {
        loadConfigs();
    }

    public Config getConfig() {
        return config;
    }

    public Config getGuiConfig() {
        return guiConfig;
    }

    public Config getMessagesConfig() {
        return messagesConfig;
    }

    public String getPrefix() {
        return config.getString("prefix", "");
    }

    public String getDatabaseType() {
        return config.getString("database.type", "h2");
    }

    public String getDatabaseName() {
        return config.getString("database.database", "ogpoll");
    }

    public int getDatabasePoolMaximumPoolSize() {
        return config.getInt("database.pool.maximum-pool-size", 10);
    }

    public int getDatabasePoolMinimumIdle() {
        return config.getInt("database.pool.minimum-idle", 2);
    }

    public long getDatabasePoolMaximumLifetime() {
        return config.getLong("database.pool.maximum-lifetime", 1800000L);
    }

    public long getDatabasePoolKeepaliveTime() {
        return config.getLong("database.pool.keepalive-time", 0L);
    }

    public long getDatabasePoolConnectionTimeout() {
        return config.getLong("database.pool.connection-timeout", 5000L);
    }

    public int getMaxActivePolls() {
        return config.getInt("poll.max-active-polls", 10);
    }

    public int getMinOptions() {
        return config.getInt("poll.min-options", 2);
    }

    public int getMaxOptions() {
        return config.getInt("poll.max-options", 6);
    }

    public boolean shouldShowVoteCounts() {
        return config.getBoolean("poll.show-vote-counts", true);
    }

    public long getVoteCooldownMillis() {
        return config.getLong("poll.vote-cooldown-milliseconds", 2000L);
    }

    public boolean shouldBroadcastEnd() {
        return config.getBoolean("poll.broadcast-end", true);
    }

    public int getMinDurationMinutes() {
        return config.getInt("duration.min-minutes", 1);
    }

    public int getMaxDurationMinutes() {
        return config.getInt("duration.max-minutes", 10080);
    }

    public Component message(String path) {
        return message(path, Collections.emptyMap());
    }

    public Component message(String path, Map<String, String> replacements) {
        String raw = messagesConfig.getString(path, "");
        String resolved = applyReplacements(prependPrefix(raw), replacements);
        return StringUtils.format(resolved);
    }

    public String messageString(String path) {
        return messageString(path, Collections.emptyMap());
    }

    public String messageString(String path, Map<String, String> replacements) {
        String raw = messagesConfig.getString(path, "");
        String resolved = applyReplacements(prependPrefix(raw), replacements);
        return StringUtils.formatToString(resolved);
    }

    public void sendMessage(CommandSender sender, String path) {
        sender.sendMessage(message(path));
    }

    public void sendMessage(CommandSender sender, String path, Map<String, String> replacements) {
        sender.sendMessage(message(path, replacements));
    }

    private void loadConfigs() {
        config = new Config(
            new File(plugin.getDataFolder(), "config.yml"),
            plugin.getResource("config.yml")
        );
        guiConfig = new Config(
            new File(plugin.getDataFolder(), "gui.yml"),
            plugin.getResource("gui.yml")
        );
        messagesConfig = new Config(
            new File(plugin.getDataFolder(), "messages.yml"),
            plugin.getResource("messages.yml")
        );
    }

    private String applyReplacements(String input, Map<String, String> replacements) {
        String output = input;
        if (replacements == null || replacements.isEmpty()) {
            return output;
        }
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private String prependPrefix(String input) {
        String prefix = getPrefix();
        if (prefix == null || prefix.isEmpty()) {
            return input;
        }
        return prefix + input;
    }
}
