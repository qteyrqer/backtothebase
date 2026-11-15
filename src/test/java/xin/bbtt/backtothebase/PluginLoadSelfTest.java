package xin.bbtt.backtothebase;

import xin.bbtt.backtothebase.config.PluginConfig;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.config.BotConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;

/**
 * Loads the packaged plugin through the real xinbot PluginManager together with
 * MovementSync, but never opens a network connection.
 */
public final class PluginLoadSelfTest {
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        Path workspace = project.getParent();
        Path itest = project.resolve("build/itest");
        Path plugins = itest.resolve("plugin");
        Files.createDirectories(plugins);

        Files.copy(workspace.resolve("src/plugin/MovementSync-1.12.0-RELEASE.jar"),
                plugins.resolve("MovementSync-1.12.0-RELEASE.jar"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(project.resolve("build/backtothebase-1.0.0.jar"),
                plugins.resolve("backtothebase-1.0.0.jar"),
                StandardCopyOption.REPLACE_EXISTING);

        Path data = itest.resolve("backtothebase.json");
        Files.deleteIfExists(data);
        System.setProperty("backtothebase.dataFile", data.toString());

        Path configFile = itest.resolve("config.conf");
        Files.writeString(configFile, """
                account {
                  name = "TestBot"
                  onlineMode = false
                  password = ""
                }
                checkForUpdates = false
                enableTranslation = false
                reconnectDelay = 1000
                reconnectTimeout = 1000
                plugin {
                  directory = "%s"
                }
                proxy {
                  enable = false
                }
                """.formatted(plugins.toString().replace("\\", "\\\\")));

        BotConfig config = new BotConfig(configFile.toString());
        Bot.INSTANCE.init(config);
        Bot.INSTANCE.getPluginManager().enableAll();

        BackToTheBasePlugin plugin = BackToTheBasePlugin.instance();
        check(plugin != null, "backtothebase instantiated");
        check(Bot.INSTANCE.getPluginManager().isPluginLoaded("MovementSync"), "MovementSync loaded");
        check(Bot.INSTANCE.getPluginManager().isPluginEnabled("MovementSync"), "MovementSync enabled");
        check(plugin.returnService() != null, "return service started");
        check(Bot.INSTANCE.getPluginManager().commands().getCommandByLabel("bttb") != null,
                "bttb command registered");
        check(Bot.INSTANCE.getPluginManager().commands().getCommandByLabel("backtothebase") != null,
                "backtothebase command registered");

        Bot.INSTANCE.getPluginManager().commands().callCommand("bttb add Steve 100 64 200");
        waitFor(() -> plugin.store().point("Steve", 1) != null, 3000, "console add applied");
        Bot.INSTANCE.getPluginManager().commands().callCommand("bttb add Steve 120 70 -30 2");
        waitFor(() -> plugin.store().point("Steve", 2) != null, 3000, "second base applied");
        Bot.INSTANCE.getPluginManager().commands().callCommand("bttb list");
        Bot.INSTANCE.getPluginManager().commands().callCommand("bttb account TestBot");
        waitFor(() -> plugin.store().account().equals("TestBot"), 3000, "account applied");

        PluginConfig.Point point = plugin.store().point("Steve", 1);
        check(point != null && point.x == 100, "coordinates persisted");

        // No session exists, so a request must fail cleanly instead of hanging.
        plugin.returnService().handleMessage("Steve", "back");
        waitFor(() -> !plugin.returnService().isBusy(), 10000, "request settles without server");

        try {
            Bot.INSTANCE.getPluginManager().disableAll();
            Bot.INSTANCE.getPluginManager().unloadPlugins();
        } catch (Exception e) {
            System.out.println("cleanup warning: " + e);
        }
        check(BackToTheBasePlugin.instance() == null, "plugin unloaded");
        System.out.println("PluginLoadSelfTest OK");
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                System.out.println("ok - " + what);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("FAILED (timeout): " + what);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAILED: " + what);
        System.out.println("ok - " + what);
    }
}
