package hu.exteron.ogpoll.database;

import hu.exteron.oGPoll.OGPoll;

public class DatabaseManager {
    private final OGPoll plugin;

    public DatabaseManager(OGPoll plugin) {
        this.plugin = plugin;
        // TODO: we need a database over here
    }

    public void shutdown() {
        // TODO: we need the to close the db connection
    }
}
