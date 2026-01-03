package hu.exteron.ogpoll.managers;

import hu.exteron.ogpoll.gui.FinishedPollsGUI;
import hu.exteron.ogpoll.gui.PollListGUI;
import hu.exteron.ogpoll.gui.PollVotingGUI;
import hu.exteron.ogpoll.utils.GuiCooldowns;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

// In an ideal world where java doesn't suck we would not need this

public final class CleanupManager implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        
        ChatInputManager.clear(event.getPlayer());
        
        PollListGUI.cancelUpdateTask(playerId);
        PollVotingGUI.cancelUpdateTask(playerId);
        FinishedPollsGUI.cancelUpdateTask(playerId);
        
        GuiCooldowns.remove(playerId);
    }
}
