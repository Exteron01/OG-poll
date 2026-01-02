package hu.exteron.ogpoll.database;

import hu.exteron.ogpoll.OGPoll;

public class DatabaseManager {
    private final OGPoll plugin;

    public DatabaseManager(OGPoll plugin) {
        this.plugin = plugin;
        // TODO: we need a database over here
    }

    public void shutdown() {
        // TODO: we need to close the db connection
    }
}
