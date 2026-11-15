package xin.bbtt.backtothebase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;

/**
 * Sends private messages from the bot through the regular chat pipeline
 * ({@code /w <player> <message>}).
 */
public final class ChatNotifier {
    private static final Logger log = LoggerFactory.getLogger("backtothebase");
    private static final int MAX_LENGTH = 240;

    public void send(String player, String message) {
        if (player == null || player.isBlank() || message == null || message.isBlank()) return;
        String text = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        // sendCommand prefixes the slash and goes through SendCommandEvent.
        Bot.INSTANCE.sendCommand("w " + player + " " + text);
        log.info("[backtothebase -> {}] {}", player, text);
    }
}
