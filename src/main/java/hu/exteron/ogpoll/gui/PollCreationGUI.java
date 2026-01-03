package hu.exteron.ogpoll.gui;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.StringUtils;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import hu.exteron.ogpoll.OGPoll;
import hu.exteron.ogpoll.commands.PollCreationSession;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.utils.GuiCooldowns;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PollCreationGUI {
    private final OGPoll plugin;
    private final Player player;
    private final String question;
    private final long durationMillis;
    private final ConfigManager configManager;

    public PollCreationGUI(OGPoll plugin, Player player, String question, long durationMillis) {
        this.plugin = plugin;
        this.player = player;
        this.question = question;
        this.durationMillis = durationMillis;
        this.configManager = plugin.getConfigManager();
    }

    public void open() {
        int rows = configManager.getGuiConfig().getInt("creation-template-gui.layout.rows", 5);
        String titleRaw = configManager.getGuiConfig().getString("creation-template-gui.layout.title", "Select Poll Type");
        Gui gui = Gui.gui()
            .rows(rows)
            .title(format(titleRaw))
            .disableAllInteractions()
            .create();

        addDecoration(gui);
        addTemplate(gui, "yes-no", 2, true);
        addTemplate(gui, "two-options", 2, false);
        addTemplate(gui, "multiple-choice-3", 3, false);
        addTemplate(gui, "multiple-choice-4", 4, false);
        addTemplate(gui, "multiple-choice-6", 6, false);
        addCancelButton(gui);

        Scheduler.get().run(() -> gui.open(player));
    }

    private void addDecoration(Gui gui) {
        String materialName = configManager.getGuiConfig().getString("creation-template-gui.decoration.material", "ORANGE_STAINED_GLASS_PANE");
        Material material = parseMaterial(materialName);
        String name = configManager.getGuiConfig().getString("creation-template-gui.decoration.name", " ");
        List<String> slots = configManager.getGuiConfig().getStringList("creation-template-gui.decoration.slots");

        ItemBuilder builder = ItemBuilder.from(material).name(format(name));

        for (String slotStr : slots) {
            if (slotStr.contains("-")) {
                String[] parts = slotStr.split("-");
                int start = Integer.parseInt(parts[0]);
                int end = Integer.parseInt(parts[1]);
                for (int i = start; i <= end; i++) {
                    gui.setItem(i, builder.asGuiItem(event -> event.setCancelled(true)));
                }
            } else {
                int slot = Integer.parseInt(slotStr);
                gui.setItem(slot, builder.asGuiItem(event -> event.setCancelled(true)));
            }
        }
    }

    private void addCancelButton(Gui gui) {
        String base = "creation-template-gui.cancel";
        int slot = configManager.getGuiConfig().getInt(base + ".slot", -1);
        if (slot < 0) return;

        String materialName = configManager.getGuiConfig().getString(base + ".material", "BARRIER");
        Material material = parseMaterial(materialName);
        String name = configManager.getGuiConfig().getString(base + ".name", "Cancel");
        List<String> loreRaw = configManager.getGuiConfig().getStringList(base + ".lore");

        ItemBuilder builder = ItemBuilder.from(material)
            .name(format(name))
            .lore(formatLore(loreRaw));

        gui.setItem(slot, builder.asGuiItem(event -> {
            event.setCancelled(true);
            if (GuiCooldowns.isOnCooldown(player.getUniqueId())) return;
            GuiCooldowns.trigger(player.getUniqueId());
            player.closeInventory();
            configManager.sendMessage(player, "chat-input.cancelled");
        }));
    }

    private void addTemplate(Gui gui, String key, int maxOptions, boolean isYesNo) {
        String base = "creation-template-gui.templates." + key;
        int slot = configManager.getGuiConfig().getInt(base + ".slot", -1);
        if (slot < 0) {
            return;
        }

        String materialName = configManager.getGuiConfig().getString(base + ".material", "PAPER");
        Material material = parseMaterial(materialName);
        String name = configManager.getGuiConfig().getString(base + ".name", "");
        List<String> loreRaw = configManager.getGuiConfig().getStringList(base + ".lore");

        ItemBuilder builder = ItemBuilder.from(material)
            .name(format(name))
            .lore(formatLore(loreRaw));

        boolean glow = configManager.getGuiConfig().getBoolean(base + ".glow", false);
        if (glow) {
            builder.glow(true);
        }

        gui.setItem(slot, builder.asGuiItem(event -> {
            event.setCancelled(true);
            if (GuiCooldowns.isOnCooldown(player.getUniqueId())) return;
            GuiCooldowns.trigger(player.getUniqueId());
            player.closeInventory();
            new PollCreationSession(plugin, player, question, durationMillis, maxOptions, isYesNo).start();
        }));
    }

    private Material parseMaterial(String materialName) {
        if (materialName == null || materialName.isEmpty()) {
            return Material.PAPER;
        }
        try {
            return Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Material.PAPER;
        }
    }

    private Component format(String input) {
        if (input == null) {
            return Component.empty();
        }
        return StringUtils.format(input);
    }

    private List<Component> formatLore(List<String> lines) {
        List<Component> components = new ArrayList<>();
        if (lines == null) {
            return components;
        }
        for (String line : lines) {
            components.add(format(line));
        }
        return components;
    }
}
