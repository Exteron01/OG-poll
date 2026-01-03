package hu.exteron.ogpoll.gui;

import com.artillexstudios.axapi.scheduler.ScheduledTask;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.StringUtils;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.managers.PollManager;
import hu.exteron.ogpoll.models.Poll;
import hu.exteron.ogpoll.models.PollOption;
import hu.exteron.ogpoll.utils.GuiCooldowns;
import hu.exteron.ogpoll.utils.ProgressBarUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class PollVotingGUI {
    private final OGPoll plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final PollManager pollManager;
    private static final Map<UUID, ScheduledTask> updateTasks = new ConcurrentHashMap<>();

    public PollVotingGUI(OGPoll plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.databaseManager = plugin.getDatabaseManager();
        this.pollManager = plugin.getPollManager();
    }

    public void open(Player player, Poll poll) {
        cancelUpdateTask(player.getUniqueId());

        if (poll.getOptions() == null || poll.getOptions().isEmpty()) {
            databaseManager.getPollById(poll.getId(), fetched -> {
                if (fetched == null) {
                    configManager.sendMessage(player, "error.poll-not-found");
                    return;
                }
                openWithPoll(player, fetched);
            }, throwable -> configManager.sendMessage(player, "error.database"));
        } else {
            openWithPoll(player, poll);
        }
    }

    private void openWithPoll(Player player, Poll poll) {
        databaseManager.hasVoted(poll.getId(), player.getUniqueId(), hasVoted -> {
            databaseManager.getVoteCounts(poll.getId(), counts -> {
                databaseManager.getPlayerVote(poll.getId(), player.getUniqueId(), playerVotedOptionId -> {
                    List<PollOption> options = poll.getOptions();
                    int optionCount = options.size();
                    int rows = calculateRows(optionCount);
                    int[] slots = resolveOptionSlots(optionCount, rows);

                    String titleRaw = configManager.getGuiConfig().getString("voting-gui.layout.title", "Vote Now");
                    Gui gui = Gui.gui()
                        .rows(rows)
                        .title(StringUtils.format(titleRaw))
                        .disableAllInteractions()
                        .create();

                    gui.setCloseGuiAction(event -> cancelUpdateTask(player.getUniqueId()));
                    addDecoration(gui, rows);

                    int totalVotes = counts.values().stream().mapToInt(Integer::intValue).sum();
                    boolean showVotes = configManager.shouldShowVoteCounts();

                    addQuestionInfo(gui, poll, totalVotes, showVotes, player, rows);
                    addBackButton(gui, player, rows);

                    int maxOptions = Math.min(optionCount, slots.length);
                    for (int i = 0; i < maxOptions; i++) {
                        PollOption option = options.get(i);
                        int votes = counts.getOrDefault(option.getId(), 0);
                        boolean isPlayerChoice = playerVotedOptionId != null && playerVotedOptionId == option.getId();
                        gui.setItem(slots[i], buildOptionItem(gui, player, poll, option, votes, totalVotes, hasVoted, isPlayerChoice, showVotes, i));
                    }

                    Scheduler.get().run(() -> {
                        gui.open(player);
                        startUpdateTask(player, gui, poll, slots, hasVoted, showVotes, playerVotedOptionId, rows);
                    });
                }, throwable -> configManager.sendMessage(player, "error.database"));
            }, throwable -> configManager.sendMessage(player, "error.database"));
        }, throwable -> configManager.sendMessage(player, "error.database"));
    }

    private int calculateRows(int optionCount) {
        if (optionCount <= 3) {
            return 3;
        }
        return 4;
    }

    private int[] resolveOptionSlots(int optionCount, int rows) {
        return switch (optionCount) {
            case 2 -> new int[]{12, 14};
            case 3 -> new int[]{11, 13, 15};
            case 4 -> new int[]{10, 12, 14, 16};
            case 5 -> new int[]{10, 12, 13, 14, 16};
            default -> new int[]{10, 11, 12, 14, 15, 16};
        };
    }

    private void startUpdateTask(Player player, Gui gui, Poll poll, int[] slots, boolean hasVoted, boolean showVotes, Integer playerVotedOptionId, int rows) {
        ScheduledTask task = Scheduler.get().runTimer(() -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != gui.getInventory()) {
                cancelUpdateTask(player.getUniqueId());
                return;
            }

            databaseManager.getVoteCounts(poll.getId(), counts -> {
                int totalVotes = counts.values().stream().mapToInt(Integer::intValue).sum();

                updateQuestionInfo(gui, poll, totalVotes, showVotes, player, rows);

                List<PollOption> options = poll.getOptions();
                int maxOptions = Math.min(options.size(), slots.length);
                for (int i = 0; i < maxOptions; i++) {
                    PollOption option = options.get(i);
                    int votes = counts.getOrDefault(option.getId(), 0);
                    boolean isPlayerChoice = playerVotedOptionId != null && playerVotedOptionId == option.getId();

                    ItemStack newItem = buildOptionItemStack(poll, option, votes, totalVotes, hasVoted, isPlayerChoice, showVotes, i);
                    gui.updateItem(slots[i], newItem);
                }
            }, t -> {});
        }, 20L, 20L);

        updateTasks.put(player.getUniqueId(), task);
    }

    private GuiItem buildOptionItem(
        Gui gui, Player player, Poll poll, PollOption option,
        int votes, int totalVotes, boolean hasVoted, boolean isPlayerChoice, boolean showVotes, int index
    ) {
        String basePath = isPlayerChoice ? "voting-gui.items.option-item-voted" : "voting-gui.items.option-item";

        String nameRaw = configManager.getGuiConfig().getString(basePath + ".name",
            configManager.getGuiConfig().getString("voting-gui.items.option-item.name", ""));
        List<String> loreRaw = configManager.getGuiConfig().getStringList(basePath + ".lore");
        if (loreRaw.isEmpty()) {
            loreRaw = configManager.getGuiConfig().getStringList("voting-gui.items.option-item.lore");
        }

        double percentage = ProgressBarUtil.calculatePercentage(votes, totalVotes);
        String progressBar = "";
        if (showVotes) {
            int barLength = configManager.getGuiConfig().getInt("voting-gui.progress_bar.length", 20);
            String filledChar = configManager.getGuiConfig().getString("voting-gui.progress_bar.filled_char", "|");
            String emptyChar = configManager.getGuiConfig().getString("voting-gui.progress_bar.empty_char", "|");
            progressBar = ProgressBarUtil.createProgressBar(votes, totalVotes, barLength, filledChar, emptyChar, configManager.getGuiConfig());
        }

        Map<String, String> replacements = Map.of(
            "option", option.getOptionText(),
            "votes", String.valueOf(votes),
            "percentage", String.format("%.1f", percentage),
            "progress_bar", progressBar
        );

        List<Component> lore = formatLore(loreRaw, replacements, showVotes);
        Material material = resolveOptionMaterial(index);
        boolean glow = configManager.getGuiConfig().getBoolean(basePath + ".glow", false);

        ItemBuilder builder = ItemBuilder.from(material)
            .name(format(nameRaw, replacements))
            .lore(lore);

        if (glow) builder.glow(true);

        return builder.asGuiItem(event -> {
            event.setCancelled(true);
            if (GuiCooldowns.isOnCooldown(player.getUniqueId())) return;
            GuiCooldowns.trigger(player.getUniqueId());
            if (hasVoted) return;
            handleVote(gui, player, poll, option);
        });
    }

    private ItemStack buildOptionItemStack(Poll poll, PollOption option, int votes, int totalVotes,
                                            boolean hasVoted, boolean isPlayerChoice, boolean showVotes, int index) {
        String basePath = isPlayerChoice ? "voting-gui.items.option-item-voted" : "voting-gui.items.option-item";

        String nameRaw = configManager.getGuiConfig().getString(basePath + ".name",
            configManager.getGuiConfig().getString("voting-gui.items.option-item.name", ""));
        List<String> loreRaw = configManager.getGuiConfig().getStringList(basePath + ".lore");
        if (loreRaw.isEmpty()) {
            loreRaw = configManager.getGuiConfig().getStringList("voting-gui.items.option-item.lore");
        }

        double percentage = ProgressBarUtil.calculatePercentage(votes, totalVotes);
        String progressBar = "";
        if (showVotes) {
            int barLength = configManager.getGuiConfig().getInt("voting-gui.progress_bar.length", 20);
            String filledChar = configManager.getGuiConfig().getString("voting-gui.progress_bar.filled_char", "|");
            String emptyChar = configManager.getGuiConfig().getString("voting-gui.progress_bar.empty_char", "|");
            progressBar = ProgressBarUtil.createProgressBar(votes, totalVotes, barLength, filledChar, emptyChar, configManager.getGuiConfig());
        }

        Map<String, String> replacements = Map.of(
            "option", option.getOptionText(),
            "votes", String.valueOf(votes),
            "percentage", String.format("%.1f", percentage),
            "progress_bar", progressBar
        );

        List<Component> lore = formatLore(loreRaw, replacements, showVotes);
        Material material = resolveOptionMaterial(index);
        boolean glow = configManager.getGuiConfig().getBoolean(basePath + ".glow", false);

        ItemBuilder builder = ItemBuilder.from(material)
            .name(format(nameRaw, replacements))
            .lore(lore);

        if (glow) builder.glow(true);
        return builder.build();
    }

    private void handleVote(Gui gui, Player player, Poll poll, PollOption option) {
        cancelUpdateTask(player.getUniqueId());
        pollManager.vote(player.getUniqueId(), poll.getId(), option.getId(), result -> {
            gui.close(player);
            if (result.success()) {
                configManager.sendMessage(player, "vote-success", Map.of("option", option.getOptionText()));
                return;
            }

            if ("error.vote-cooldown".equals(result.messageKey())) {
                long seconds = Math.max(1L, (result.remainingMillis() + 999L) / 1000L);
                configManager.sendMessage(player, result.messageKey(), Map.of("seconds", String.valueOf(seconds)));
                return;
            }

            if (result.messageKey() != null && !result.messageKey().isEmpty()) {
                configManager.sendMessage(player, result.messageKey());
            } else {
                configManager.sendMessage(player, "error.database");
            }
        });
    }

    private void addDecoration(Gui gui, int rows) {
        Material material = parseMaterial(configManager.getGuiConfig().getString("voting-gui.decoration.material", "GREEN_STAINED_GLASS_PANE"));
        String name = configManager.getGuiConfig().getString("voting-gui.decoration.name", " ");
        GuiItem item = ItemBuilder.from(material).name(format(name, Map.of())).asGuiItem();

        int totalSlots = rows * 9;
        for (int i = 0; i < 9; i++) {
            gui.setItem(i, item);
        }
        for (int i = totalSlots - 9; i < totalSlots; i++) {
            gui.setItem(i, item);
        }
        for (int row = 1; row < rows - 1; row++) {
            gui.setItem(row * 9, item);
            gui.setItem(row * 9 + 8, item);
        }
    }

    private void addBackButton(Gui gui, Player player, int rows) {
        int slot = rows * 9 - 5;
        Material material = parseMaterial(configManager.getGuiConfig().getString("voting-gui.navigation.back.material", "ARROW"));
        String nameRaw = configManager.getGuiConfig().getString("voting-gui.navigation.back.name", "Back");
        List<String> loreRaw = configManager.getGuiConfig().getStringList("voting-gui.navigation.back.lore");

                gui.setItem(slot, ItemBuilder.from(material)
                    .name(format(nameRaw, Map.of()))
                    .lore(formatLore(loreRaw, Map.of(), true))
                    .asGuiItem(event -> {
                        event.setCancelled(true);
                        if (GuiCooldowns.isOnCooldown(player.getUniqueId())) return;
                        GuiCooldowns.trigger(player.getUniqueId());
                        cancelUpdateTask(player.getUniqueId());
                        new PollListGUI(plugin).open(player);
                    }));
    }

    private void addQuestionInfo(Gui gui, Poll poll, int totalVotes, boolean showVotes, Player player, int rows) {
        int slot = 4;
        Material material = parseMaterial(configManager.getGuiConfig().getString("voting-gui.items.question-info.material", "BOOK"));
        String nameRaw = configManager.getGuiConfig().getString("voting-gui.items.question-info.name", "");
        List<String> loreRaw = configManager.getGuiConfig().getStringList("voting-gui.items.question-info.lore");

        String remaining = formatDuration(Math.max(0L, poll.getExpiresAt() - System.currentTimeMillis()));
        String creatorName = getCreatorName(poll);

        Map<String, String> replacements = Map.of(
            "question", poll.getQuestion(),
            "remaining", remaining,
            "total_votes", String.valueOf(totalVotes),
            "creator", creatorName
        );

        gui.setItem(slot, ItemBuilder.from(material)
            .name(format(nameRaw, replacements))
            .lore(formatLore(loreRaw, replacements, showVotes))
            .asGuiItem(event -> event.setCancelled(true)));
    }

    private void updateQuestionInfo(Gui gui, Poll poll, int totalVotes, boolean showVotes, Player player, int rows) {
        int slot = 4;
        Material material = parseMaterial(configManager.getGuiConfig().getString("voting-gui.items.question-info.material", "BOOK"));
        String nameRaw = configManager.getGuiConfig().getString("voting-gui.items.question-info.name", "");
        List<String> loreRaw = configManager.getGuiConfig().getStringList("voting-gui.items.question-info.lore");

        String remaining = formatDuration(Math.max(0L, poll.getExpiresAt() - System.currentTimeMillis()));
        String creatorName = getCreatorName(poll);

        Map<String, String> replacements = Map.of(
            "question", poll.getQuestion(),
            "remaining", remaining,
            "total_votes", String.valueOf(totalVotes),
            "creator", creatorName
        );

        ItemStack newItem = ItemBuilder.from(material)
            .name(format(nameRaw, replacements))
            .lore(formatLore(loreRaw, replacements, showVotes))
            .build();

        gui.updateItem(slot, newItem);
    }

    private String getCreatorName(Poll poll) {
        String name = poll.getCreatorName();
        return name != null && !name.isEmpty() ? name : "Unknown";
    }

    private Component format(String input, Map<String, String> replacements) {
        if (input == null) return Component.empty();
        return StringUtils.format(applyReplacements(input, replacements));
    }

    private List<Component> formatLore(List<String> loreLines, Map<String, String> replacements, boolean showVotes) {
        List<Component> components = new ArrayList<>();
        if (loreLines == null) return components;
        for (String line : loreLines) {
            if (!showVotes && (line.contains("{votes}") || line.contains("{percentage}") || line.contains("{progress_bar}") || line.contains("{total_votes}"))) {
                continue;
            }
            components.add(format(line, replacements));
        }
        return components;
    }

    private Material resolveOptionMaterial(int index) {
        List<String> materials = configManager.getGuiConfig().getStringList("voting-gui.items.option-item.materials");
        if (materials != null && !materials.isEmpty()) {
            String materialName = materials.get(index % materials.size());
            return parseMaterial(materialName);
        }
        return parseMaterial(configManager.getGuiConfig().getString("voting-gui.items.option-item.material", "PAPER"));
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(millis));
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        if (hours > 0) builder.append(hours).append("h ");
        if (minutes > 0 || hours > 0) builder.append(minutes).append("m ");
        builder.append(seconds).append("s");
        return builder.toString();
    }

    private String applyReplacements(String input, Map<String, String> replacements) {
        String output = input;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private Material parseMaterial(String materialName) {
        if (materialName == null || materialName.isEmpty()) return Material.PAPER;
        try { return Material.valueOf(materialName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return Material.PAPER; }
    }

    public static void cancelUpdateTask(UUID playerId) {
        ScheduledTask task = updateTasks.remove(playerId);
        if (task != null) task.cancel();
    }
}
