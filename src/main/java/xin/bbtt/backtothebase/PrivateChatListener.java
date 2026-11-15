package xin.bbtt.backtothebase;

import org.geysermc.mcprotocollib.auth.GameProfile;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.EventPriority;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.PrivateChatEvent;

/**
 * Receives private messages. Parsing the raw server chat into
 * {@link PrivateChatEvent} is the job of the server's MetaPlugin (for example
 * {@code 3c3uMetaPlugin}); this plugin only consumes the resulting event.
 */
public final class PrivateChatListener implements Listener {
    private final BackToTheBasePlugin plugin;

    public PrivateChatListener(BackToTheBasePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrivateChat(PrivateChatEvent event) {
        GameProfile sender = event.getSender();
        if (sender == null || sender.getName() == null || sender.getName().isBlank()) return;
        ReturnService service = plugin.returnService();
        if (service == null) return;
        service.handleMessage(sender.getName(), event.getMessage());
    }
}
