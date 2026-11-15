package xin.bbtt.backtothebase;

import org.yaml.snakeyaml.Yaml;
import xin.bbtt.mcbot.LangManager;
import xin.bbtt.mcbot.command.Command;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads the plugin the way xinbot does (onLoad), runs the real console command
 * executor and checks that the packaged plugin.yml / commands.yml resources
 * point at loadable classes. Does not connect to a server.
 */
public final class CommandSelfTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("bttb-command");
        System.setProperty("backtothebase.dataFile", temp.resolve("backtothebase.json").toString());
        LangManager.init();

        checkPackagedDescriptors();

        BackToTheBasePlugin plugin = new BackToTheBasePlugin();
        plugin.onLoad();
        String accepted = LangManager.get("backtothebase.msg.accepted");
        check(!accepted.equals("backtothebase.msg.accepted") && !accepted.isBlank(),
                "plugin language loaded: " + accepted);
        check(plugin.store().account().equals(""), "no bot account before login");
        plugin.onEnable(); // MovementSync is absent in this isolated run: must stay inert.
        check(plugin.returnService() == null, "service stays off without MovementSync");

        BackCommandExecutor executor = new BackCommandExecutor();
        Command command = new Command("backtothebase", new String[]{"bttb", "back"}, "d", "u");

        executor.onCommand(command, "backtothebase",
                new String[]{"add", "Steve", "100", "64", "200"});
        check(plugin.store().point("steve", 1) != null
                && plugin.store().point("Steve", 1).x == 100, "add default base");

        executor.onCommand(command, "backtothebase",
                new String[]{"add", "Steve", "101", "65", "201", "2"});
        check(plugin.store().point("STEVE", 2).z == 201, "add explicit base");

        executor.onCommand(command, "backtothebase",
                new String[]{"add", "Steve", "102", "66", "202", "2"});
        check(plugin.store().point("Steve", 2).x == 102, "replace existing base");

        executor.onCommand(command, "backtothebase",
                new String[]{"add", "Steve", "abc", "66", "202"});
        check(plugin.store().countBases() == 2, "invalid coordinate rejected");

        executor.onCommand(command, "backtothebase", new String[]{"list"});
        executor.onCommand(command, "backtothebase", new String[]{"list", "Steve"});
        executor.onCommand(command, "backtothebase", new String[]{"account", "TestBot"});
        check(plugin.store().account().equals("TestBot"), "account persisted");

        executor.onCommand(command, "backtothebase", new String[]{"status"});
        executor.onCommand(command, "backtothebase", new String[]{"cancel"});
        executor.onCommand(command, "backtothebase", new String[]{"remove", "Steve", "2"});
        check(plugin.store().countBases() == 1, "remove base");
        executor.onCommand(command, "backtothebase", new String[]{"remove", "Steve"});
        check(plugin.store().countPlayers() == 0, "remove player");

        executor.onCommand(command, "backtothebase", new String[]{"reload"});
        check(plugin.store().countPlayers() == 0, "reload keeps empty config");

        plugin.onDisable();
        plugin.onUnload();
        deleteRecursively(temp);
        System.out.println("CommandSelfTest OK");
    }

    private static void checkPackagedDescriptors() throws Exception {
        try (InputStream in = CommandSelfTest.class.getClassLoader()
                .getResourceAsStream("plugin.yml")) {
            check(in != null, "plugin.yml packaged");
            Map<String, Object> yaml = new Yaml().load(in);
            check("backtothebase".equals(yaml.get("name")), "plugin name");
            check("xin.bbtt.backtothebase.BackToTheBasePlugin".equals(yaml.get("main")), "main class");
            check(List.of("MovementSync").equals(yaml.get("depend")), "depends on MovementSync");
        }
        try (InputStream in = CommandSelfTest.class.getClassLoader()
                .getResourceAsStream("commands.yml")) {
            check(in != null, "commands.yml packaged");
            Map<String, Object> yaml = new Yaml().load(in);
            Map<?, ?> command = (Map<?, ?>) yaml.get("backtothebase");
            check(command != null, "command entry");
            String executor = String.valueOf(command.get("executor"));
            check(Class.forName(executor).getDeclaredConstructor().newInstance() != null,
                    "executor instantiable: " + executor);
            check(String.valueOf(command.get("aliases")).contains("bttb"), "bttb alias");
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAILED: " + what);
        System.out.println("ok - " + what);
    }
}
