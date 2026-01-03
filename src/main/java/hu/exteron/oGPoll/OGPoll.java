package hu.exteron.ogpoll;

import com.artillexstudios.axapi.AxPlugin;
import com.artillexstudios.axapi.utils.featureflags.FeatureFlags;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.commands.CommandManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.managers.ChatInputManager;
import hu.exteron.ogpoll.managers.CleanupManager;
import hu.exteron.ogpoll.managers.PollManager;

public final class OGPoll extends AxPlugin {

    private static OGPoll instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PollManager pollManager;
    private CommandManager commandManager;

    @Override
    public void enable() {
        instance = this;

        getLogger().info("Enabling OG-Poll...");

        configManager = new ConfigManager(this);
        databaseManager = new DatabaseManager(this, configManager);
        pollManager = new PollManager(this, configManager);
        pollManager.loadActivePolls();
        commandManager = new CommandManager(this);
        ChatInputManager.init(configManager);
        getServer().getPluginManager().registerEvents(new ChatInputManager(configManager), this);
        getServer().getPluginManager().registerEvents(new CleanupManager(), this);

        getLogger().info("OG-Poll enabled successfully!");
    }

    @Override
    public void disable() {
        getLogger().info("Disabling OG-Poll...");

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        if (pollManager != null) {
            pollManager.shutdown();
        }

        getLogger().info("OG-Poll disabled successfully!");
    }

    @Override
    public void reload() {
        getLogger().info("Reloading configurations...");
        if (configManager != null) {
            configManager.reload();
        }
        getLogger().info("Configurations reloaded!");
    }

    @Override
    public void updateFlags() {
        FeatureFlags.PLACEHOLDER_API_HOOK.set(true);
        FeatureFlags.PLACEHOLDER_API_IDENTIFIER.set("ogpoll");
        FeatureFlags.ENABLE_PACKET_LISTENERS.set(true);
    }

    public static OGPoll getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PollManager getPollManager() {
        return pollManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }
}
