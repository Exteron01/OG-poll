package hu.exteron.ogpoll.managers;

import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;

public class PollManager {
    private final OGPoll plugin;
    private final ConfigManager configManager;

    public PollManager(OGPoll plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        // TODO: implement this
    }

    public void loadActivePolls() {
        // TODO: implement this
    }

    public void shutdown() {
        // TODO: implement this
    }
}
