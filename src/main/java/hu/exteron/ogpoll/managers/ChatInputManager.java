package hu.exteron.ogpoll.managers;

import com.artillexstudios.axapi.packet.FriendlyByteBuf;
import com.artillexstudios.axapi.packet.PacketEvent;
import com.artillexstudios.axapi.packet.PacketEvents;
import com.artillexstudios.axapi.packet.PacketListener;
import com.artillexstudios.axapi.packet.ServerboundPacketTypes;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.scheduler.ScheduledTask;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axapi.utils.Title;
import hu.exteron.ogpoll.config.ConfigManager;
import hu.exteron.ogpoll.utils.InputValidator;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ChatInputManager implements Listener {
    private static final Map<UUID, ChatInputRequest> pending = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledTask> timeouts = new ConcurrentHashMap<>();
    private static final Map<UUID, Title> activeTitles = new ConcurrentHashMap<>();
    private static final int FADE_IN = 10;
    private static final int STAY = 72000;
    private static final int FADE_OUT = 10;
    private static final long TIMEOUT_TICKS = 20L * 60L;
    private final ConfigManager configManager;

    public ChatInputManager(ConfigManager configManager) {
        this.configManager = configManager;
        registerPacketListener();
    }

    public static void request(Player player, Predicate<String> validator, Consumer<String> onValid, Runnable onInvalid) {
        request(player, null, validator, onValid, onInvalid);
    }

    public static void request(
        Player player,
        String prompt,
        Predicate<String> validator,
        Consumer<String> onValid,
        Runnable onInvalid
    ) {
        if (player == null || validator == null || onValid == null) {
            return;
        }
        pending.put(player.getUniqueId(), new ChatInputRequest(validator, onValid, onInvalid, prompt));
        scheduleTimeout(player.getUniqueId(), onInvalid);
        if (prompt != null && !prompt.isEmpty()) {
            showInputTitle(player, prompt);
        }
    }

    public static boolean handle(Player player, String message) {
        if (player == null) {
            return false;
        }

        ChatInputRequest request = pending.remove(player.getUniqueId());
        if (request == null) {
            return false;
        }

        cancelTimeout(player.getUniqueId());
        clearInputTitle(player);
        String sanitized = InputValidator.sanitize(message);
        Scheduler.get().run(() -> {
            if (sanitized.isEmpty() || !request.validator.test(sanitized)) {
                if (request.onInvalid != null) {
                    request.onInvalid.run();
                }
                return;
            }
            request.onValid.accept(sanitized);
        });

        return true;
    }

    public static void clear(Player player) {
        if (player == null) {
            return;
        }
        cancelTimeout(player.getUniqueId());
        pending.remove(player.getUniqueId());
        clearInputTitle(player);
    }

    public static boolean hasPending(Player player) {
        return player != null && pending.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (handle(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    private static void showInputTitle(Player player, String prompt) {
        Title title = buildTitle(prompt);
        if (title == null) {
            return;
        }
        title.send(player);
        activeTitles.put(player.getUniqueId(), title);
    }

    private static void clearInputTitle(Player player) {
        Title title = activeTitles.remove(player.getUniqueId());
        if (title != null) {
            title.clear(player);
        }
    }

    private static Title buildTitle(String prompt) {
        ConfigManager config = getConfigManager();
        if (config == null) {
            return null;
        }
        String titleText = config.getMessagesConfig().getString("chat-input.title", "");
        String subtitleText = config.getMessagesConfig().getString("chat-input.subtitle", "");
        if (titleText.isEmpty() && subtitleText.isEmpty()) {
            return null;
        }
        String resolvedSubtitle = subtitleText.replace("{prompt}", prompt);
        Component titleComponent = StringUtils.format(titleText);
        Component subtitleComponent = StringUtils.format(resolvedSubtitle);
        return Title.create(titleComponent, subtitleComponent, FADE_IN, STAY, FADE_OUT);
    }

    private static ConfigManager getConfigManager() {
        return ChatInputManagerHolder.configManager;
    }

    public static void init(ConfigManager configManager) {
        ChatInputManagerHolder.configManager = configManager;
    }

    private static final class ChatInputRequest {
        private final Predicate<String> validator;
        private final Consumer<String> onValid;
        private final Runnable onInvalid;
        private final String prompt;

        private ChatInputRequest(
            Predicate<String> validator,
            Consumer<String> onValid,
            Runnable onInvalid,
            String prompt
        ) {
            this.validator = validator;
            this.onValid = onValid;
            this.onInvalid = onInvalid;
            this.prompt = prompt;
        }
    }

    private static final class ChatInputManagerHolder {
        private static ConfigManager configManager;
    }

    private static void scheduleTimeout(UUID playerId, Runnable onInvalid) {
        cancelTimeout(playerId);
        ScheduledTask task = Scheduler.get().runLater(() -> {
            pending.remove(playerId);
            if (onInvalid != null) {
                onInvalid.run();
            }
        }, TIMEOUT_TICKS);
        timeouts.put(playerId, task);
    }

    private static void cancelTimeout(UUID playerId) {
        ScheduledTask existing = timeouts.remove(playerId);
        if (existing != null) {
            existing.cancel();
        }
    }

    private void registerPacketListener() {
        PacketEvents.INSTANCE.addListener(new PacketListener() {
            @Override
            public void onPacketReceive(PacketEvent event) {
                if (event.type() != ServerboundPacketTypes.CHAT) {
                    return;
                }

                Player player = event.player();
                if (!hasPending(player)) {
                    return;
                }

                FriendlyByteBuf copy = event.in().copy();
                try {
                    String message = copy.readUTF(256);
                    if (handle(player, message)) {
                        event.cancelled(true);
                    }
                } catch (Exception ignored) {
                    // Ignore malformed packets
                } finally {
                    copy.release();
                }
            }
        });
    }
}
