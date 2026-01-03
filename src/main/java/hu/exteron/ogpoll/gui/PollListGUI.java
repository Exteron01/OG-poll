package hu.exteron.ogpoll.gui;

import com.artillexstudios.axapi.scheduler.ScheduledTask;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.StringUtils;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.database.DatabaseManager;
import hu.exteron.ogpoll.models.Poll;
import hu.exteron.ogpoll.models.PollOption;
import hu.exteron.ogpoll.utils.GuiCooldowns;
import hu.exteron.ogpoll.utils.ProgressBarUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemFlag;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class PollListGUI {
    private final OGPoll plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private static final Map<UUID, ScheduledTask> updateTasks = new ConcurrentHashMap<>();

    public PollListGUI(OGPoll plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.databaseManager = plugin.getDatabaseManager();
    }

    public void open(Player player) {
        cancelUpdateTask(player.getUniqueId());

        int rows = configManager.getGuiConfig().getInt("poll-list-gui.layout.rows", 6);
        String titleRaw = configManager.getGuiConfig().getString("poll-list-gui.layout.title", "Active Polls");
        List<Integer> decorationSlots = parseSlots(
            configManager.getGuiConfig().getList("poll-list-gui.decoration.slots", List.of())
        );

        int prevSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.previous.slot", -1);
        int nextSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.next.slot", -1);
        int refreshSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.refresh.slot", -1);
        int finishedSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.finished.slot", -1);
        Set<Integer> reservedSlots = new HashSet<>(decorationSlots);
        if (prevSlot >= 0) reservedSlots.add(prevSlot);
        if (nextSlot >= 0) reservedSlots.add(nextSlot);
        if (refreshSlot >= 0) reservedSlots.add(refreshSlot);
        if (finishedSlot >= 0) reservedSlots.add(finishedSlot);

        int pageSize = Math.max(1, rows * 9 - reservedSlots.size());

        PaginatedGui gui = Gui.paginated()
            .rows(rows)
            .title(StringUtils.format(titleRaw.replace("{current_page}", "1").replace("{max_page}", "1")))
            .pageSize(pageSize)
            .disableAllInteractions()
            .create();

        GuiItem decorationItem = buildDecorationItem();
        addDecoration(gui, decorationSlots, decorationItem);

        gui.setCloseGuiAction(event -> cancelUpdateTask(player.getUniqueId()));
        Scheduler.get().run(() -> gui.open(player));

        databaseManager.getActivePolls(polls -> {
            if (polls.isEmpty()) {
                addNoPollsItem(gui);
                updateNavigation(gui, decorationItem, titleRaw, 1, player);
                gui.update();
                return;
            }

            gui.clearPageItems();
            Map<Integer, PollDisplayData> pollDataMap = new ConcurrentHashMap<>();
            Map<Integer, Poll> pollById = new ConcurrentHashMap<>();
            List<Integer> pollOrder = new ArrayList<>();
            int[] remaining = {polls.size()};

            for (Poll poll : polls) {
                loadPollDisplayData(poll, player, data -> {
                    pollDataMap.put(poll.getId(), data);
                    synchronized (remaining) {
                        remaining[0]--;
                        if (remaining[0] == 0) {
                            List<Poll> sortedPolls = new ArrayList<>(polls);
                            for (Poll p : sortedPolls) {
                                PollDisplayData displayData = pollDataMap.get(p.getId());
                                if (displayData != null) {
                                    pollById.put(p.getId(), p);
                                    pollOrder.add(p.getId());
                                    gui.addItem(createPollItem(p, displayData, player, gui, titleRaw, decorationItem, pollDataMap));
                                }
                            }
                            updateNavigation(gui, decorationItem, titleRaw, 0, player);
                            gui.update();
                            startUpdateTask(player, gui, polls, pollDataMap, pollById, pollOrder, titleRaw, decorationItem);
                        }
                    }
                });
            }
        }, throwable -> configManager.sendMessage(player, "error.database"));
    }

    private void loadPollDisplayData(Poll poll, Player player, Consumer<PollDisplayData> callback) {
        databaseManager.hasVoted(poll.getId(), player.getUniqueId(), hasVoted -> {
            databaseManager.getVoteCounts(poll.getId(), counts -> {
                int totalVotes = counts.values().stream().mapToInt(Integer::intValue).sum();

                if (hasVoted && poll.getOptions() != null && !poll.getOptions().isEmpty()) {
                    callback.accept(new PollDisplayData(hasVoted, totalVotes, counts, poll.getOptions()));
                } else if (hasVoted) {
                    databaseManager.getOptions(poll.getId(), options -> {
                        poll.setOptions(options);
                        callback.accept(new PollDisplayData(hasVoted, totalVotes, counts, options));
                    }, t -> callback.accept(new PollDisplayData(hasVoted, totalVotes, counts, List.of())));
                } else {
                    callback.accept(new PollDisplayData(hasVoted, totalVotes, counts, List.of()));
                }
            }, t -> callback.accept(new PollDisplayData(hasVoted, 0, Map.of(), List.of())));
        }, t -> callback.accept(new PollDisplayData(false, 0, Map.of(), List.of())));
    }

    private void startUpdateTask(Player player, PaginatedGui gui, List<Poll> polls,
                                  Map<Integer, PollDisplayData> pollDataMap, Map<Integer, Poll> pollById,
                                  List<Integer> pollOrder, String titleRaw, GuiItem decorationItem) {
        AtomicBoolean updating = new AtomicBoolean(false);
        ScheduledTask task = Scheduler.get().runTimer(() -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != gui.getInventory()) {
                cancelUpdateTask(player.getUniqueId());
                return;
            }

            if (!updating.compareAndSet(false, true)) {
                return;
            }

            databaseManager.getActivePolls(updatedPolls -> {
                updating.set(false);

                Map<Integer, Poll> updatedMap = new HashMap<>();
                for (Poll poll : updatedPolls) {
                    updatedMap.put(poll.getId(), poll);
                }

                boolean removed = false;
                for (int i = pollOrder.size() - 1; i >= 0; i--) {
                    int pollId = pollOrder.get(i);
                    if (!updatedMap.containsKey(pollId)) {
                        pollOrder.remove(i);
                        pollById.remove(pollId);
                        pollDataMap.remove(pollId);
                        List<GuiItem> pageItems = gui.getPageItems();
                        if (i < pageItems.size()) {
                            gui.removePageItem(pageItems.get(i));
                        }
                        removed = true;
                    }
                }
                if (removed) {
                    gui.update();
                }

                for (Poll poll : updatedPolls) {
                    if (!pollById.containsKey(poll.getId())) {
                        pollById.put(poll.getId(), poll);
                        pollOrder.add(poll.getId());
                        loadPollDisplayData(poll, player, data -> {
                            pollDataMap.put(poll.getId(), data);
                            gui.addItem(createPollItem(poll, data, player, gui, titleRaw, decorationItem, pollDataMap));
                            updateNavigation(gui, decorationItem, titleRaw, 0, player);
                            gui.update();
                        });
                    }
                }

                for (int index = 0; index < pollOrder.size(); index++) {
                    int pollId = pollOrder.get(index);
                    Poll poll = updatedMap.getOrDefault(pollId, pollById.get(pollId));
                    if (poll == null) {
                        continue;
                    }
                    pollById.put(pollId, poll);
                    PollDisplayData data = pollDataMap.get(pollId);
                    if (data == null) {
                        continue;
                    }

                    int itemIndex = index;
                    databaseManager.getVoteCounts(poll.getId(), counts -> {
                        int totalVotes = counts.values().stream().mapToInt(Integer::intValue).sum();
                        PollDisplayData updated = new PollDisplayData(data.hasVoted, totalVotes, counts, data.options);
                        pollDataMap.put(poll.getId(), updated);

                        ItemStack newItem = buildPollItemStack(poll, updated);
                        gui.updatePageItem(itemIndex, newItem);
                        gui.update();
                    }, t -> {});
                }

                updateNavigation(gui, decorationItem, titleRaw, 0, player);
            }, t -> updating.set(false));
        }, 20L, 20L);

        updateTasks.put(player.getUniqueId(), task);
    }

    private GuiItem createPollItem(Poll poll, PollDisplayData data, Player player, PaginatedGui gui,
                                    String titleRaw, GuiItem decorationItem, Map<Integer, PollDisplayData> pollDataMap) {
        ItemStack itemStack = buildPollItemStack(poll, data);

        return ItemBuilder.from(itemStack)
            .asGuiItem(event -> {
                event.setCancelled(true);
                if (GuiCooldowns.isOnCooldown(player.getUniqueId())) return;
                GuiCooldowns.trigger(player.getUniqueId());
                if (data.hasVoted) {
                    return;
                }
                databaseManager.getPollById(poll.getId(), latest -> {
                    if (latest == null) {
                        configManager.sendMessage(player, "error.poll-not-found");
                        return;
                    }
                    if (!latest.isActive() || latest.isExpired()) {
                        configManager.sendMessage(player, "error.poll-expired");
                        return;
                    }
                    cancelUpdateTask(player.getUniqueId());
                    new PollVotingGUI(plugin).open(player, latest);
                }, t -> configManager.sendMessage(player, "error.database"));
            });
    }

    private ItemStack buildPollItemStack(Poll poll, PollDisplayData data) {
        long remainingMillis = Math.max(0L, poll.getExpiresAt() - System.currentTimeMillis());
        String remaining = formatDuration(remainingMillis);
        String creatorName = getCreatorName(poll);

        Map<String, String> replacements = new HashMap<>();
        replacements.put("question", poll.getQuestion());
        replacements.put("remaining", remaining);
        replacements.put("total_votes", String.valueOf(data.totalVotes));
        replacements.put("creator", creatorName);

        if (!data.options.isEmpty()) {
            replacements.put("vote_ratios", buildVoteRatios(data.options, data.voteCounts, data.totalVotes));
        }

        String itemKey = data.hasVoted ? "poll-list-gui.items.poll-item-voted" : "poll-list-gui.items.poll-item";
        String nameRaw = configManager.getGuiConfig().getString(itemKey + ".name", "");
        List<String> loreRaw = configManager.getGuiConfig().getStringList(itemKey + ".lore");
        Material material = parseMaterial(configManager.getGuiConfig().getString(itemKey + ".material", "BOOK"));

        List<Component> lore = formatLore(loreRaw, replacements);

        ItemBuilder builder = ItemBuilder.from(material)
            .name(format(nameRaw, replacements))
            .lore(lore);

        boolean glow = configManager.getGuiConfig().getBoolean(itemKey + ".glow", false);
        if (glow) {
            builder.glow(true);
        }

        ItemStack item = builder.build();
        hideBookOriginalTag(item);
        return item;
    }

    private String buildVoteRatios(List<PollOption> options, Map<Integer, Integer> voteCounts, int totalVotes) {
        StringBuilder sb = new StringBuilder();
        int barLength = configManager.getGuiConfig().getInt("voting-gui.progress_bar.length", 20);
        String filledChar = configManager.getGuiConfig().getString("voting-gui.progress_bar.filled_char", "|");
        String emptyChar = configManager.getGuiConfig().getString("voting-gui.progress_bar.empty_char", "|");

        for (int i = 0; i < options.size(); i++) {
            PollOption opt = options.get(i);
            int votes = voteCounts.getOrDefault(opt.getId(), 0);
            double pct = totalVotes > 0 ? (votes * 100.0 / totalVotes) : 0;
            String progressBar = ProgressBarUtil.createProgressBar(
                votes, totalVotes, barLength, filledChar, emptyChar, configManager.getGuiConfig()
            );

            if (i > 0) sb.append("\n");
            sb.append("<gray>").append(opt.getOptionText()).append("</gray> <white>")
                .append(votes).append("</white> <dark_gray>(")
                .append(String.format("%.1f", pct)).append("%)</dark_gray>");
            sb.append("\n").append(progressBar);
        }
        return sb.toString();
    }

    private String getCreatorName(Poll poll) {
        String name = poll.getCreatorName();
        return name != null && !name.isEmpty() ? name : "Unknown";
    }

    private void addNoPollsItem(PaginatedGui gui) {
        String base = "poll-list-gui.items.no-polls";
        int slot = configManager.getGuiConfig().getInt(base + ".slot", 22);
        Material material = parseMaterial(configManager.getGuiConfig().getString(base + ".material", "BARRIER"));
        String name = configManager.getGuiConfig().getString(base + ".name", "No Active Polls");
        List<String> loreRaw = configManager.getGuiConfig().getStringList(base + ".lore");
        List<Component> lore = formatLore(loreRaw, Map.of());
        gui.setItem(slot, ItemBuilder.from(material).name(format(name)).lore(lore).asGuiItem());
    }

    private GuiItem buildDecorationItem() {
        Material material = parseMaterial(configManager.getGuiConfig().getString(
            "poll-list-gui.decoration.material", "GREEN_STAINED_GLASS_PANE"));
        String name = configManager.getGuiConfig().getString("poll-list-gui.decoration.name", " ");
        return ItemBuilder.from(material).name(format(name)).asGuiItem();
    }

    private void addDecoration(PaginatedGui gui, List<Integer> slots, GuiItem item) {
        for (int slot : slots) {
            gui.setItem(slot, item);
        }
    }

    private void updateNavigation(PaginatedGui gui, GuiItem decorationItem, String titleRaw, int maxPagesOverride, Player player) {
        int prevSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.previous.slot", -1);
        int nextSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.next.slot", -1);
        int refreshSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.refresh.slot", -1);
        int finishedSlot = configManager.getGuiConfig().getInt("poll-list-gui.navigation.finished.slot", -1);
        int prevPage = Math.max(1, gui.getCurrentPageNum() - 1);
        int nextPage = Math.min(Math.max(1, gui.getPagesNum()), gui.getCurrentPageNum() + 1);
        Map<String, String> prevReplacements = Map.of("prev_page", String.valueOf(prevPage));
        Map<String, String> nextReplacements = Map.of("next_page", String.valueOf(nextPage));

        if (prevSlot >= 0) {
            if (gui.getCurrentPageNum() > 1) {
                gui.setItem(prevSlot, buildNavItem("poll-list-gui.navigation.previous", prevReplacements, event -> {
                    event.setCancelled(true);
                    if (GuiCooldowns.isOnCooldown(event.getWhoClicked().getUniqueId())) return;
                    GuiCooldowns.trigger(event.getWhoClicked().getUniqueId());
                    if (gui.previous()) updateNavigation(gui, decorationItem, titleRaw, 0, (Player) event.getWhoClicked());
                }));
            } else {
                gui.setItem(prevSlot, decorationItem);
            }
        }

        if (nextSlot >= 0) {
            if (gui.getCurrentPageNum() < gui.getPagesNum()) {
                gui.setItem(nextSlot, buildNavItem("poll-list-gui.navigation.next", nextReplacements, event -> {
                    event.setCancelled(true);
                    if (GuiCooldowns.isOnCooldown(event.getWhoClicked().getUniqueId())) return;
                    GuiCooldowns.trigger(event.getWhoClicked().getUniqueId());
                    if (gui.next()) updateNavigation(gui, decorationItem, titleRaw, 0, (Player) event.getWhoClicked());
                }));
            } else {
                gui.setItem(nextSlot, decorationItem);
            }
        }

        if (refreshSlot >= 0) {
            gui.setItem(refreshSlot, buildNavItem("poll-list-gui.navigation.refresh", Map.of(), event -> {
                event.setCancelled(true);
                if (GuiCooldowns.isOnCooldown(event.getWhoClicked().getUniqueId())) return;
                GuiCooldowns.trigger(event.getWhoClicked().getUniqueId());
                Player p = (Player) event.getWhoClicked();
                cancelUpdateTask(p.getUniqueId());
                open(p);
            }));
        }

        if (finishedSlot >= 0) {
            if (player != null && player.hasPermission("ogpoll.manage")) {
                gui.setItem(finishedSlot, buildNavItem("poll-list-gui.navigation.finished", Map.of(), event -> {
                    event.setCancelled(true);
                    if (GuiCooldowns.isOnCooldown(event.getWhoClicked().getUniqueId())) return;
                    GuiCooldowns.trigger(event.getWhoClicked().getUniqueId());
                    Player p = (Player) event.getWhoClicked();
                    cancelUpdateTask(p.getUniqueId());
                    new FinishedPollsGUI(plugin).open(p);
                }));
            } else {
                gui.setItem(finishedSlot, decorationItem);
            }
        }

        int maxPages = maxPagesOverride > 0 ? maxPagesOverride : Math.max(1, gui.getPagesNum());
        String resolved = titleRaw
            .replace("{current_page}", String.valueOf(gui.getCurrentPageNum()))
            .replace("{max_page}", String.valueOf(maxPages));
        gui.updateTitle(StringUtils.format(resolved));
    }

    private GuiItem buildNavItem(
        String base,
        Map<String, String> replacements,
        Consumer<InventoryClickEvent> onClick
    ) {
        Material material = parseMaterial(configManager.getGuiConfig().getString(base + ".material", "ARROW"));
        String name = configManager.getGuiConfig().getString(base + ".name", "");
        List<String> loreRaw = configManager.getGuiConfig().getStringList(base + ".lore");
        return ItemBuilder.from(material)
            .name(format(name, replacements))
            .lore(formatLore(loreRaw, replacements))
            .asGuiItem(onClick::accept);
    }

    public static void cancelUpdateTask(UUID playerId) {
        ScheduledTask task = updateTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    private List<Integer> parseSlots(List<Object> rawSlots) {
        List<Integer> slots = new ArrayList<>();
        for (Object entry : rawSlots) {
            if (entry == null) continue;
            if (entry instanceof Number number) {
                slots.add(number.intValue());
                continue;
            }
            String value = entry.toString().trim();
            if (value.isEmpty()) continue;
            if (value.contains("-")) {
                String[] parts = value.split("-", 2);
                int start = parseSlotNumber(parts[0]);
                int end = parseSlotNumber(parts[1]);
                if (start < 0 || end < 0) continue;
                for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
                    slots.add(i);
                }
                continue;
            }
            int slot = parseSlotNumber(value);
            if (slot >= 0) slots.add(slot);
        }
        return slots;
    }

    private int parseSlotNumber(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ex) { return -1; }
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

    private Component format(String input) {
        return format(input, Map.of());
    }

    private Component format(String input, Map<String, String> replacements) {
        if (input == null) return Component.empty();
        return StringUtils.format(applyReplacements(input, replacements));
    }

    private List<Component> formatLore(List<String> lines, Map<String, String> replacements) {
        List<Component> components = new ArrayList<>();
        if (lines == null) return components;
        for (String line : lines) {
            if (line.contains("{vote_ratios}")) {
                String ratios = replacements.getOrDefault("vote_ratios", "");
                if (!ratios.isEmpty()) {
                    for (String ratioLine : ratios.split("\n")) {
                        components.add(StringUtils.format(ratioLine));
                    }
                }
            } else {
                components.add(format(line, replacements));
            }
        }
        return components;
    }

    private String applyReplacements(String input, Map<String, String> replacements) {
        String output = input;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private Material parseMaterial(String materialName) {
        if (materialName == null || materialName.isEmpty()) return Material.BOOK;
        try { return Material.valueOf(materialName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return Material.BOOK; }
    }

    private void hideBookOriginalTag(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS);
        item.setItemMeta(meta);
    }

    private record PollDisplayData(boolean hasVoted, int totalVotes, Map<Integer, Integer> voteCounts, List<PollOption> options) {}
}
