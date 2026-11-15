package xin.bbtt.backtothebase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.backtothebase.config.ConfigStore;
import xin.bbtt.backtothebase.config.PluginConfig;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.LangManager;
import xin.bbtt.mcbot.plugin.Plugin;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point of the {@code backtothebase} plugin.
 *
 * <p>Whitelisted players whisper {@code back [base]} to the bot. The bot walks to
 * the configured base trapdoor (not to the player) and right-clicks it through
 * MovementSync.</p>
 */
public final class BackToTheBasePlugin implements Plugin {
    private static final Logger log = LoggerFactory.getLogger("backtothebase");
    private static volatile BackToTheBasePlugin instance;

    private ConfigStore store;
    private ChatNotifier notifier;
    private ReturnService returnService;

    public static BackToTheBasePlugin instance() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
        initLanguage(getClass().getClassLoader());

        notifier = new ChatNotifier();
        store = new ConfigStore(resolveDataFile());
        store.load();

        String botAccount = currentBotAccount();
        PluginConfig config = store.config();
        if (config.account == null || config.account.isBlank()) {
            if (!botAccount.isBlank()) store.setAccount(botAccount);
        } else if (!botAccount.isBlank() && !config.account.equalsIgnoreCase(botAccount)) {
            log.warn(LangManager.get("backtothebase.console.account_warn", botAccount));
        }

        log.info("backtothebase loaded: {} player(s), {} base(s), data={}",
                store.countPlayers(), store.countBases(), store.file());
    }

    @Override
    public void onEnable() {
        if (!Bot.INSTANCE.getPluginManager().isPluginLoaded("MovementSync")) {
            log.error(LangManager.get("backtothebase.error.movementsync_missing"));
            return;
        }
        returnService = new ReturnService(this);
        Bot.INSTANCE.getPluginManager().events()
                .registerEvents(new PrivateChatListener(this), this);
        log.info("backtothebase enabled: whisper \"back [base]\" from a whitelisted player to start");
    }

    @Override
    public void onDisable() {
        if (returnService != null) {
            returnService.shutdown();
            returnService = null;
        }
    }

    @Override
    public void onUnload() {
        if (instance == this) instance = null;
    }

    private static void initLanguage(ClassLoader loader) {
        // Chinese is the built-in default; English (or the current locale) can override it.
        LangManager.loadFromClassLoader(loader, "zh_cn");
        String current = LangManager.getCurrentLanguage();
        if (current != null && !current.startsWith("zh")) {
            LangManager.loadFromClassLoader(loader, current);
            LangManager.loadFromClassLoader(loader, "en_us");
        }
    }

    private Path resolveDataFile() {
        String override = System.getProperty("backtothebase.dataFile");
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        String directory = "plugin";
        try {
            String configured = Bot.INSTANCE.getConfig().getConfigData().getPlugin().getDirectory();
            if (configured != null && !configured.isBlank()) directory = configured;
        } catch (RuntimeException ignored) {
            // Config is not available (unit tests / unusual bootstrap): use the default.
        }
        return Paths.get(directory).resolve("backtothebase.json");
    }

    private static String currentBotAccount() {
        try {
            String name = Bot.INSTANCE.getConfig().getConfigData().getAccount().getName();
            return name == null ? "" : name;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public ConfigStore store() {
        return store;
    }

    public PluginConfig config() {
        return store.config();
    }

    public ChatNotifier notifier() {
        return notifier;
    }

    public ReturnService returnService() {
        return returnService;
    }

    /** Looks up a translation, falling back to the key itself when missing. */
    public String message(String key, Object... args) {
        return args.length == 0 ? LangManager.get(key) : LangManager.get(key, args);
    }

    public void notifyPlayer(String player, String key, Object... args) {
        if (notifier != null) notifier.send(player, message(key, args));
    }
}
