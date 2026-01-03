package hu.exteron.ogpoll.utils;

import com.artillexstudios.axapi.utils.Cooldown;
import java.util.UUID;

public final class GuiCooldowns {
    private static final long COOLDOWN_MILLIS = 165L;
    private static final Cooldown<UUID> COOLDOWN = Cooldown.createSynchronized();

    private GuiCooldowns() {
    }

    public static boolean isOnCooldown(UUID playerId) {
        return COOLDOWN.hasCooldown(playerId);
    }

    public static void trigger(UUID playerId) {
        COOLDOWN.addCooldown(playerId, COOLDOWN_MILLIS);
    }

    public static void remove(UUID playerId) {
        COOLDOWN.remove(playerId);
    }
}
