package xin.bbtt.backtothebase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.backtothebase.config.ConfigStore;
import xin.bbtt.backtothebase.config.PluginConfig;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.LangManager;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;
import xin.bbtt.mcbot.command.SubCommandExecutor;
import xin.bbtt.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Console command {@code /backtothebase} (aliases {@code bttb}, {@code back}).
 *
 * <pre>
 * bttb account &lt;name&gt;                       set the bot account
 * bttb add &lt;player&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [base]     add/replace a trapdoor coordinate
 * bttb remove &lt;player&gt; [base]               remove one base or the whole player
 * bttb list [player]                          show the whitelist
 * bttb test &lt;player&gt; [base]                  trigger a request without a whisper
 * bttb status                                 show the current request
 * bttb cancel                                 cancel the current request
 * bttb reload                                 reload backtothebase.json
 * </pre>
 */
public final class BackCommandExecutor extends SubCommandExecutor {
    private static final Logger log = LoggerFactory.getLogger("backtothebase");
    private static final Pattern ACCOUNT_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    public BackCommandExecutor() {
        registerSubCommand("add", new AddExecutor());
        registerSubCommand("remove", new RemoveExecutor());
        registerSubCommand("list", new ListExecutor());
        registerSubCommand("account", new AccountExecutor());
        registerSubCommand("test", new TestExecutor());
        registerSubCommand("status", new StatusExecutor());
        registerSubCommand("cancel", new CancelExecutor());
        registerSubCommand("reload", new ReloadExecutor());
        registerSubCommand("help", new HelpExecutor());
    }

    private BackToTheBasePlugin plugin() {
        BackToTheBasePlugin plugin = BackToTheBasePlugin.instance();
        if (plugin == null) log.error(LangManager.get("backtothebase.console.not_ready"));
        return plugin;
    }

    private ConfigStore store() {
        BackToTheBasePlugin plugin = plugin();
        return plugin == null ? null : plugin.store();
    }

    private static Integer parseInteger(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void usage(String key) {
        log.info(LangManager.get(key));
    }

    @Override
    protected void onNoSubCommand(Command command, String label) {
        log.info(LangManager.get("backtothebase.console.help"));
    }

    @Override
    protected void onUnknownSubCommand(Command command, String label, String unknownSub) {
        log.warn(LangManager.get("backtothebase.console.unknown_sub", unknownSub));
        log.info(LangManager.get("backtothebase.console.help"));
    }

    private final class AddExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            ConfigStore store = store();
            if (store == null) return;
            if (args.length < 4) {
                usage("backtothebase.console.usage_add");
                return;
            }
            String player = args[0].trim();
            Integer x = parseInteger(args[1]);
            Integer y = parseInteger(args[2]);
            Integer z = parseInteger(args[3]);
            if (x == null || y == null || z == null) {
                log.warn(LangManager.get("backtothebase.console.invalid_number", args[1] + " " + args[2] + " " + args[3]));
                return;
            }
            int base = 1;
            if (args.length >= 5) {
                Integer parsed = parseInteger(args[4]);
                if (parsed == null || parsed < 1) {
                    log.warn(LangManager.get("backtothebase.console.invalid_base"));
                    return;
                }
                base = parsed;
            }
            if (!World.isWithinWorldBounds(y)) {
                log.warn(LangManager.get("backtothebase.console.out_of_bounds", y,
                        World.getMinWorldY(), World.getMaxWorldY()));
            }
            boolean existed = store.point(player, base) != null;
            store.addPoint(player, base, x, y, z);
            log.info(LangManager.get(existed
                            ? "backtothebase.console.updated"
                            : "backtothebase.console.added",
                    player, base, x, y, z));
        }

        @Override
        public List<String> onTabComplete(Command command, String label, String[] args) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (org.geysermc.mcprotocollib.auth.GameProfile profile : Bot.INSTANCE.players.values()) {
                    if (profile.getName() != null) names.add(profile.getName());
                }
                return names;
            }
            return List.of();
        }
    }

    private final class RemoveExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            ConfigStore store = store();
            if (store == null) return;
            if (args.length < 1) {
                usage("backtothebase.console.usage_remove");
                return;
            }
            String player = args[0].trim();
            Integer base = null;
            if (args.length >= 2) {
                base = parseInteger(args[1]);
                if (base == null || base < 1) {
                    log.warn(LangManager.get("backtothebase.console.invalid_base"));
                    return;
                }
            }
            int removed = store.removePoint(player, base);
            if (removed == 0) {
                log.warn(LangManager.get("backtothebase.console.not_found"));
            } else if (base == null) {
                log.info(LangManager.get("backtothebase.console.removed_all", player));
            } else {
                log.info(LangManager.get("backtothebase.console.removed", player, base));
            }
        }

        @Override
        public List<String> onTabComplete(Command command, String label, String[] args) {
            if (args.length == 1) return store() == null ? List.of() : store().playerNames();
            return List.of();
        }
    }

    private final class ListExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            ConfigStore store = store();
            if (store == null) return;
            String filter = args.length >= 1 ? args[0].trim() : null;
            List<Map.Entry<String, Map<String, PluginConfig.Point>>> entries = new ArrayList<>();
            for (Map.Entry<String, Map<String, PluginConfig.Point>> entry : store.snapshot()) {
                if (filter == null || entry.getKey().equalsIgnoreCase(filter)) entries.add(entry);
            }
            if (entries.isEmpty()) {
                log.info(LangManager.get("backtothebase.console.list_empty"));
                return;
            }
            log.info(LangManager.get("backtothebase.console.list_header", entries.size()));
            for (Map.Entry<String, Map<String, PluginConfig.Point>> entry : entries) {
                log.info(LangManager.get("backtothebase.console.list_entry", entry.getKey()));
                List<Map.Entry<String, PluginConfig.Point>> bases =
                        new ArrayList<>(entry.getValue().entrySet());
                bases.sort(Comparator.comparingInt(e -> parseBaseOrDefault(e.getKey())));
                for (Map.Entry<String, PluginConfig.Point> base : bases) {
                    PluginConfig.Point point = base.getValue();
                    log.info(LangManager.get("backtothebase.console.list_base",
                            base.getKey(), point.x, point.y, point.z));
                }
            }
        }

        private int parseBaseOrDefault(String raw) {
            Integer parsed = parseInteger(raw);
            return parsed == null ? Integer.MAX_VALUE : parsed;
        }

        @Override
        public List<String> onTabComplete(Command command, String label, String[] args) {
            if (args.length == 1) return store() == null ? List.of() : store().playerNames();
            return List.of();
        }
    }

    private final class AccountExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            ConfigStore store = store();
            if (store == null) return;
            if (args.length < 1) {
                usage("backtothebase.console.usage_account");
                return;
            }
            String account = args[0].trim();
            if (!ACCOUNT_NAME.matcher(account).matches()) {
                log.warn(LangManager.get("backtothebase.console.invalid_account", account));
                return;
            }
            store.setAccount(account);
            log.info(LangManager.get("backtothebase.console.account_set", account));
            String current = currentBotAccount();
            if (!current.isBlank() && !current.equalsIgnoreCase(account)) {
                log.warn(LangManager.get("backtothebase.console.account_warn", current));
            }
        }

        private String currentBotAccount() {
            try {
                String name = Bot.INSTANCE.getConfig().getConfigData().getAccount().getName();
                return name == null ? "" : name;
            } catch (RuntimeException ignored) {
                return "";
            }
        }
    }

    private final class TestExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            BackToTheBasePlugin plugin = plugin();
            if (plugin == null || plugin.returnService() == null) return;
            if (args.length < 1) {
                usage("backtothebase.console.usage_test");
                return;
            }
            int base = 1;
            if (args.length >= 2) {
                Integer parsed = parseInteger(args[1]);
                if (parsed == null || parsed < 1) {
                    log.warn(LangManager.get("backtothebase.console.invalid_base"));
                    return;
                }
                base = parsed;
            }
            plugin.returnService().handleMessage(args[0], BackRequest.format(base));
        }

        @Override
        public List<String> onTabComplete(Command command, String label, String[] args) {
            if (args.length == 1) return store() == null ? List.of() : store().playerNames();
            return List.of();
        }
    }

    private final class StatusExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            BackToTheBasePlugin plugin = plugin();
            if (plugin == null || plugin.returnService() == null) return;
            log.info(plugin.returnService().status());
        }
    }

    private final class CancelExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            BackToTheBasePlugin plugin = plugin();
            if (plugin == null || plugin.returnService() == null) return;
            if (plugin.returnService().cancel()) {
                log.info(LangManager.get("backtothebase.console.cancelled"));
            } else {
                log.info(LangManager.get("backtothebase.console.idle"));
            }
        }
    }

    private final class ReloadExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            ConfigStore store = store();
            if (store == null) return;
            store.load();
            log.info(LangManager.get("backtothebase.console.reloaded",
                    store.countPlayers(), store.countBases()));
        }
    }

    private final class HelpExecutor extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            log.info(LangManager.get("backtothebase.console.help"));
            log.info(LangManager.get("backtothebase.console.help_add"));
            log.info(LangManager.get("backtothebase.console.help_remove"));
            log.info(LangManager.get("backtothebase.console.help_list"));
            log.info(LangManager.get("backtothebase.console.help_account"));
            log.info(LangManager.get("backtothebase.console.help_test"));
            log.info(LangManager.get("backtothebase.console.help_status"));
        }
    }
}
