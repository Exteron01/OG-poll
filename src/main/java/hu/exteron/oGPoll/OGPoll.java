package hu.exteron.oGPoll;

import com.artillexstudios.axapi.AxPlugin;
import com.artillexstudios.axapi.config.Config;
import com.artillexstudios.axapi.utils.featureflags.FeatureFlags;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.managers.PollManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;

public final class OGPoll extends AxPlugin {

    private static OGPoll instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PollManager pollManager;
    private PaperCommandManager<org.bukkit.command.CommandSender> commandManager;

    @Override
    public void enable() {
        instance = this;

        getLogger().info("Enabling OG-Poll...");

        configManager = new ConfigManager(this);
        databaseManager = new DatabaseManager(this);
        pollManager = new PollManager(this);
        pollManager.loadActivePolls();

        getLogger().info("OG-Poll started!");
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

    public PaperCommandManager<org.bukkit.command.CommandSender> getCommandManager() {
        return commandManager;
    }
}
