package hu.exteron.ogpoll.database;

import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;

public class DatabaseManager {
    private final OGPoll plugin;
    private final ConfigManager configManager;

    public DatabaseManager(OGPoll plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        // TODO: we need a database over here
    }

    public void shutdown() {
        // TODO: we need to close the db connection
    }
}
