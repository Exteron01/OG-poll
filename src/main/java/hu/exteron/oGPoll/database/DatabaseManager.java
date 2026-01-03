package hu.exteron.ogpoll.database;

import com.artillexstudios.axapi.database.DatabaseConfig;
import com.artillexstudios.axapi.database.DatabaseHandler;
import com.artillexstudios.axapi.database.DatabaseTypes;
import com.artillexstudios.axapi.database.ResultHandler;
import com.artillexstudios.axapi.database.impl.H2DatabaseType;
import com.artillexstudios.axapi.scheduler.Scheduler;
import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.models.Poll;
import hu.exteron.ogpoll.models.PollOption;
import hu.exteron.ogpoll.models.Vote;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class DatabaseManager {
    private final OGPoll plugin;
    private final ConfigManager configManager;
    private DatabaseHandler handler;
    private volatile boolean ready = false;

    public DatabaseManager(OGPoll plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;

        DatabaseTypes.register(new H2DatabaseType(), true);

        DatabaseConfig databaseConfig = new DatabaseConfig();
        String configuredType = configManager.getDatabaseType();
        DatabaseConfig.Pool pool = databaseConfig.pool != null
            ? databaseConfig.pool
            : new DatabaseConfig.Pool();

        databaseConfig.type = DatabaseTypes.fetch(configuredType.toUpperCase(Locale.ROOT));
        if (databaseConfig.type == null) {
            databaseConfig.type = DatabaseTypes.defaultType();
        }
        databaseConfig.database = configManager.getDatabaseName();
        databaseConfig.tablePrefix("");
        databaseConfig.pool = pool;
        databaseConfig.pool.maximumPoolSize = configManager.getDatabasePoolMaximumPoolSize();
        databaseConfig.pool.minimumIdle = configManager.getDatabasePoolMinimumIdle();
        databaseConfig.pool.maximumLifetime = Math.toIntExact(configManager.getDatabasePoolMaximumLifetime());
        databaseConfig.pool.keepaliveTime = Math.toIntExact(configManager.getDatabasePoolKeepaliveTime());
        databaseConfig.pool.connectionTimeout = Math.toIntExact(configManager.getDatabasePoolConnectionTimeout());

        handler = new DatabaseHandler(plugin, databaseConfig);
        
        Scheduler.get().runAsync(() -> {
            try {
                initializeTables();
                ready = true;
                plugin.getLogger().info("Database tables initialized successfully!");
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize database tables: " + e.getMessage());
            }
        });
    }

    public boolean isReady() {
        return ready;
    }

    public void shutdown() {
        if (handler != null) {
            handler.close();
        }
    }

    public DatabaseHandler getHandler() {
        return handler;
    }

    public void createPoll(Poll poll, Consumer<Integer> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            String sql = """
                INSERT INTO polls (question, creator_uuid, creator_name, created_at, expires_at, active, closed_at, max_votes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try (Connection connection = handler.connection();
                 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, poll.getQuestion());
                statement.setString(2, poll.getCreatorUuid().toString());
                statement.setString(3, poll.getCreatorName());
                statement.setLong(4, poll.getCreatedAt());
                statement.setLong(5, poll.getExpiresAt());
                statement.setBoolean(6, poll.isActive());
                if (poll.getClosedAt() == null) {
                    statement.setNull(7, Types.BIGINT);
                } else {
                    statement.setLong(7, poll.getClosedAt());
                }
                statement.setInt(8, poll.getMaxVotes());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        int id = keys.getInt(1);
                        Scheduler.get().run(() -> onSuccess.accept(id));
                        return;
                    }
                }
                throw new IllegalStateException("Failed to retrieve generated poll ID");
            } catch (Exception e) {
                handleError("Failed to create poll", e, onError);
            }
        });
    }

    public void addOption(PollOption option, Runnable onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                handler.rawQuery(
                    "INSERT INTO poll_options (poll_id, option_text, display_order) VALUES (?, ?, ?)"
                ).create().update(option.getPollId(), option.getOptionText(), option.getDisplayOrder());
                Scheduler.get().run(onSuccess);
            } catch (Exception e) {
                handleError("Failed to add poll option", e, onError);
            }
        });
    }

    public void getActivePolls(Consumer<List<Poll>> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                List<Poll> polls = handler.rawQuery(
                    "SELECT * FROM polls WHERE active = TRUE ORDER BY created_at DESC",
                    pollListHandler()
                ).create().query();
                attachOptions(polls, completed -> Scheduler.get().run(() -> onSuccess.accept(completed)), onError);
            } catch (Exception e) {
                handleError("Failed to fetch active polls", e, onError);
            }
        });
    }

    public void getAllPolls(Consumer<List<Poll>> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                List<Poll> polls = handler.rawQuery(
                    "SELECT * FROM polls ORDER BY created_at DESC",
                    pollListHandler()
                ).create().query();
                Scheduler.get().run(() -> onSuccess.accept(polls));
            } catch (Exception e) {
                handleError("Failed to fetch all polls", e, onError);
            }
        });
    }

    public void getFinishedPolls(Consumer<List<Poll>> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                List<Poll> polls = handler.rawQuery(
                    "SELECT * FROM polls WHERE active = FALSE ORDER BY closed_at DESC",
                    pollListHandler()
                ).create().query();
                attachOptions(polls, completed -> Scheduler.get().run(() -> onSuccess.accept(completed)), onError);
            } catch (Exception e) {
                handleError("Failed to fetch finished polls", e, onError);
            }
        });
    }

    public void getPollById(int id, Consumer<Poll> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                Poll poll = handler.rawQuery(
                    "SELECT * FROM polls WHERE id = ?",
                    pollSingleHandler()
                ).create().query(id);
                if (poll == null) {
                    Scheduler.get().run(() -> onSuccess.accept(null));
                    return;
                }
                getOptions(poll.getId(), options -> {
                    poll.setOptions(options);
                    Scheduler.get().run(() -> onSuccess.accept(poll));
                }, onError);
            } catch (Exception e) {
                handleError("Failed to fetch poll", e, onError);
            }
        });
    }

    public void hasVoted(int pollId, UUID playerUuid, Consumer<Boolean> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                boolean exists = handler.rawQuery(
                    "SELECT 1 FROM votes WHERE poll_id = ? AND player_uuid = ?",
                    existsHandler()
                ).create().query(pollId, playerUuid.toString());
                Scheduler.get().run(() -> onSuccess.accept(exists));
            } catch (Exception e) {
                handleError("Failed to check vote status", e, onError);
            }
        });
    }

    public void getPlayerVote(int pollId, UUID playerUuid, Consumer<Integer> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                Integer optionId = handler.rawQuery(
                    "SELECT option_id FROM votes WHERE poll_id = ? AND player_uuid = ?",
                    playerVoteHandler()
                ).create().query(pollId, playerUuid.toString());
                Scheduler.get().run(() -> onSuccess.accept(optionId));
            } catch (Exception e) {
                handleError("Failed to get player vote", e, onError);
            }
        });
    }

    private ResultHandler<Integer> playerVoteHandler() {
        return new ResultHandler<>() {
            @Override
            public Integer handle(ResultSet resultSet) throws java.sql.SQLException {
                return handle(resultSet, true);
            }

            @Override
            public Integer handle(ResultSet resultSet, boolean close) throws java.sql.SQLException {
                if (resultSet.next()) {
                    return resultSet.getInt("option_id");
                }
                return null;
            }
        };
    }

    public void recordVote(Vote vote, Runnable onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                handler.rawQuery(
                    "INSERT INTO votes (poll_id, option_id, player_uuid, voted_at) VALUES (?, ?, ?, ?)"
                ).create().update(vote.getPollId(), vote.getOptionId(), vote.getPlayerUuid().toString(),
                    vote.getVotedAt());
                Scheduler.get().run(onSuccess);
            } catch (Exception e) {
                handleError("Failed to record vote", e, onError);
            }
        });
    }

    public void getVoteCounts(int pollId, Consumer<Map<Integer, Integer>> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                Map<Integer, Integer> counts = handler.rawQuery(
                    "SELECT option_id, COUNT(*) AS votes FROM votes WHERE poll_id = ? GROUP BY option_id",
                    voteCountHandler()
                ).create().query(pollId);
                Scheduler.get().run(() -> onSuccess.accept(counts));
            } catch (Exception e) {
                handleError("Failed to fetch vote counts", e, onError);
            }
        });
    }

    public void closePoll(int pollId, Runnable onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                handler.rawQuery(
                    "UPDATE polls SET active = FALSE, closed_at = ? WHERE id = ?"
                ).create().update(System.currentTimeMillis(), pollId);
                Scheduler.get().run(onSuccess);
            } catch (Exception e) {
                handleError("Failed to close poll", e, onError);
            }
        });
    }

    public void deletePoll(int pollId, Runnable onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                handler.rawQuery(
                    "DELETE FROM polls WHERE id = ?"
                ).create().update(pollId);
                Scheduler.get().run(onSuccess);
            } catch (Exception e) {
                handleError("Failed to delete poll", e, onError);
            }
        });
    }

    private void initializeTables() {
        handler.rawQuery("""
            CREATE TABLE IF NOT EXISTS polls (
                id INTEGER PRIMARY KEY AUTO_INCREMENT,
                question VARCHAR(255) NOT NULL,
                creator_uuid VARCHAR(36) NOT NULL,
                creator_name VARCHAR(32) DEFAULT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                active BOOLEAN DEFAULT TRUE,
                closed_at BIGINT DEFAULT NULL,
                max_votes INTEGER DEFAULT 0
            )
            """).execute();

        // No, this not supposed to happen, but it did :shrug:
        ensureColumnExists("polls", "max_votes",
            "ALTER TABLE polls ADD COLUMN max_votes INTEGER DEFAULT 0");
        
        ensureColumnExists("polls", "creator_name",
            "ALTER TABLE polls ADD COLUMN creator_name VARCHAR(32) DEFAULT NULL");

        handler.rawQuery("""
            CREATE TABLE IF NOT EXISTS poll_options (
                id INTEGER PRIMARY KEY AUTO_INCREMENT,
                poll_id INTEGER NOT NULL,
                option_text VARCHAR(100) NOT NULL,
                display_order INTEGER NOT NULL,
                FOREIGN KEY (poll_id) REFERENCES polls(id) ON DELETE CASCADE
            )
            """).execute();

        handler.rawQuery("""
            CREATE TABLE IF NOT EXISTS votes (
                id INTEGER PRIMARY KEY AUTO_INCREMENT,
                poll_id INTEGER NOT NULL,
                option_id INTEGER NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                voted_at BIGINT NOT NULL,
                FOREIGN KEY (poll_id) REFERENCES polls(id) ON DELETE CASCADE,
                FOREIGN KEY (option_id) REFERENCES poll_options(id) ON DELETE CASCADE,
                UNIQUE (poll_id, player_uuid)
            )
            """).execute();

        handler.rawQuery("CREATE INDEX IF NOT EXISTS idx_polls_active ON polls(active)").execute();
        handler.rawQuery("CREATE INDEX IF NOT EXISTS idx_polls_expires ON polls(expires_at)").execute();
        handler.rawQuery("CREATE INDEX IF NOT EXISTS idx_votes_poll ON votes(poll_id)").execute();
        handler.rawQuery("CREATE INDEX IF NOT EXISTS idx_votes_player ON votes(player_uuid)").execute();
    }

    private void ensureColumnExists(String tableName, String columnName, String ddl) {
        try (Connection connection = handler.connection()) {
            DatabaseMetaData meta = connection.getMetaData();
            if (columnExists(meta, tableName, columnName)) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }

        try {
            handler.rawQuery(ddl).execute();
        } catch (Exception ignored) {
            // Ignore if the column already exists
        }
    }

    private boolean columnExists(DatabaseMetaData meta, String tableName, String columnName) throws Exception {
        try (ResultSet rs = meta.getColumns(null, null, tableName, columnName)) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = meta.getColumns(null, null, tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))) {
            return rs.next();
        }
    }

    private void attachOptions(
        List<Poll> polls,
        Consumer<List<Poll>> onSuccess,
        Consumer<Throwable> onError
    ) {
        if (polls.isEmpty()) {
            onSuccess.accept(polls);
            return;
        }
        int total = polls.size();
        AtomicInteger remaining = new AtomicInteger(total);
        for (Poll poll : polls) {
            getOptions(poll.getId(), options -> {
                poll.setOptions(options);
                if (remaining.decrementAndGet() <= 0) {
                    onSuccess.accept(polls);
                }
            }, onError);
        }
    }

    public void getOptions(int pollId, Consumer<List<PollOption>> onSuccess, Consumer<Throwable> onError) {
        Scheduler.get().runAsync(() -> {
            try {
                List<PollOption> options = handler.rawQuery(
                    "SELECT * FROM poll_options WHERE poll_id = ? ORDER BY display_order ASC",
                    pollOptionListHandler()
                ).create().query(pollId);
                Scheduler.get().run(() -> onSuccess.accept(options));
            } catch (Exception e) {
                handleError("Failed to fetch poll options", e, onError);
            }
        });
    }

    private ResultHandler<List<Poll>> pollListHandler() {
        return new ResultHandler<>() {
            @Override
            public List<Poll> handle(ResultSet resultSet) throws java.sql.SQLException {
                return handle(resultSet, true);
            }

            @Override
            public List<Poll> handle(ResultSet resultSet, boolean close) throws java.sql.SQLException {
                List<Poll> polls = new ArrayList<>();
                while (resultSet.next()) {
                    polls.add(mapPoll(resultSet));
                }
                return polls;
            }
        };
    }

    private ResultHandler<Poll> pollSingleHandler() {
        return new ResultHandler<>() {
            @Override
            public Poll handle(ResultSet resultSet) throws java.sql.SQLException {
                return handle(resultSet, true);
            }

            @Override
            public Poll handle(ResultSet resultSet, boolean close) throws java.sql.SQLException {
                if (resultSet.next()) {
                    return mapPoll(resultSet);
                }
                return null;
            }
        };
    }

    private ResultHandler<List<PollOption>> pollOptionListHandler() {
        return new ResultHandler<>() {
            @Override
            public List<PollOption> handle(ResultSet resultSet) throws java.sql.SQLException {
                return handle(resultSet, true);
            }

            @Override
            public List<PollOption> handle(ResultSet resultSet, boolean close) throws java.sql.SQLException {
                List<PollOption> options = new ArrayList<>();
                while (resultSet.next()) {
                    PollOption option = new PollOption();
                    option.setId(resultSet.getInt("id"));
                    option.setPollId(resultSet.getInt("poll_id"));
                    option.setOptionText(resultSet.getString("option_text"));
                    option.setDisplayOrder(resultSet.getInt("display_order"));
                    options.add(option);
                }
                return options;
            }
        };
    }

    private ResultHandler<Boolean> existsHandler() {
        return new ResultHandler<>() {
            @Override
            public Boolean handle(ResultSet resultSet) throws java.sql.SQLException {
                return handle(resultSet, true);
            }

            @Override
            public Boolean handle(ResultSet resultSet, boolean close) throws java.sql.SQLException {
                return resultSet.next();
            }
        };
    }

    private ResultHandler<Map<Integer, Integer>> voteCountHandler() {
        return new ResultHandler<>() {
            @Override
            public Map<Integer, Integer> handle(ResultSet resultSet) throws java.sql.SQLException {
                return handle(resultSet, true);
            }

            @Override
            public Map<Integer, Integer> handle(ResultSet resultSet, boolean close) throws java.sql.SQLException {
                Map<Integer, Integer> counts = new HashMap<>();
                while (resultSet.next()) {
                    counts.put(resultSet.getInt("option_id"), resultSet.getInt("votes"));
                }
                return counts;
            }
        };
    }

    private Poll mapPoll(ResultSet resultSet) throws java.sql.SQLException {
        Poll poll = new Poll();
        poll.setId(resultSet.getInt("id"));
        poll.setQuestion(resultSet.getString("question"));
        poll.setCreatorUuid(UUID.fromString(resultSet.getString("creator_uuid")));
        poll.setCreatorName(resultSet.getString("creator_name"));
        poll.setCreatedAt(resultSet.getLong("created_at"));
        poll.setExpiresAt(resultSet.getLong("expires_at"));
        poll.setActive(resultSet.getBoolean("active"));
        long closedAt = resultSet.getLong("closed_at");
        poll.setClosedAt(resultSet.wasNull() ? null : closedAt);
        poll.setMaxVotes(resultSet.getInt("max_votes"));
        return poll;
    }

    private void handleError(String message, Exception exception, Consumer<Throwable> onError) {
        plugin.getLogger().severe(message + ": " + exception.getMessage());
        if (onError != null) {
            Scheduler.get().run(() -> onError.accept(exception));
        }
    }
}
