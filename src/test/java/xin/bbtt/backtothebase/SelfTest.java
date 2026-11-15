package xin.bbtt.backtothebase;

import xin.bbtt.backtothebase.config.ConfigStore;
import xin.bbtt.backtothebase.config.PluginConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

/** Dependency-free smoke test: {@code java -cp ... xin.bbtt.backtothebase.SelfTest}. */
public final class SelfTest {
    public static void main(String[] args) throws Exception {
        parseBack();
        configStore();
        System.out.println("SelfTest OK");
    }

    private static void parseBack() {
        check(BackRequest.parse("back").orElse(-1) == 1, "default base is 1");
        check(BackRequest.parse(" back 2 ").orElse(-1) == 2, "explicit base");
        check(BackRequest.parse("BACK 12").orElse(-1) == 12, "case-insensitive");
        check(BackRequest.parse("/back 3").orElse(-1) == 3, "leading slash tolerated");
        check(BackRequest.parse("\u00a7rback\u00a7r 4").orElse(-1) == 4, "colour codes stripped");
        check(BackRequest.parse("back 0").isEmpty(), "base 0 rejected");
        check(BackRequest.parse("back two").isEmpty(), "non-numeric rejected");
        check(BackRequest.parse("bak 1").isEmpty(), "unknown command ignored");
        check(BackRequest.parse("").isEmpty(), "empty ignored");
        check(BackRequest.parse("back 1 extra").isEmpty(), "trailing words rejected");
        OptionalInt parsed = BackRequest.parse("back 9999");
        check(parsed.isPresent() && parsed.getAsInt() == 9999, "large base accepted");
    }

    private static void configStore() throws Exception {
        Path dir = Files.createTempDirectory("bttb-selftest");
        Path file = dir.resolve("backtothebase.json");
        ConfigStore store = new ConfigStore(file);
        store.load();
        check(Files.isRegularFile(file), "default config created");
        check(store.countPlayers() == 0 && store.countBases() == 0, "empty whitelist");

        store.setAccount("Iloveyou_liuyan");
        store.addPoint("Steve", 1, 100, 64, 200);
        store.addPoint("steve", 2, 120, 70, -30);
        check(store.countPlayers() == 1, "case-insensitive player merge");
        check(store.countBases() == 2, "two bases");
        check(store.point("STEVE", 2).y == 70, "point lookup case-insensitive");
        check(store.isWhitelisted("sTeVe") && !store.isWhitelisted("Alex"), "whitelist gating");

        store.addPoint("Steve", 1, 101, 65, 201);
        check(store.point("Steve", 1).x == 101, "replace existing base");
        check(store.removePoint("Steve", 9) == 0, "remove unknown base");
        check(store.removePoint("Steve", 2) == 1, "remove one base");
        check(store.countBases() == 1, "one base left");
        store.addPoint("Alex", 1, 0, 64, 0);

        ConfigStore reloaded = new ConfigStore(file);
        reloaded.load();
        check(reloaded.countPlayers() == 2, "players persisted");
        check("Iloveyou_liuyan".equals(reloaded.account()), "account persisted");
        check(reloaded.point("Alex", 1) != null, "point persisted");

        check(reloaded.removePoint("Alex", null) == 1, "remove all bases of a player");
        check(reloaded.countPlayers() == 1, "player removed when last base goes");

        // Broken JSON must not prevent startup.
        Files.writeString(file, "{ not json");
        ConfigStore broken = new ConfigStore(file);
        broken.load();
        check(broken.countPlayers() == 0, "broken config resets to empty");
        check(Files.list(dir).anyMatch(p -> p.getFileName().toString().contains(".broken-")),
                "broken config backed up");

        for (int i = 0; i < 10; i++) {
            broken.addPoint("P" + i, 1, i, 64, i);
        }
        ConfigStore concurrent = new ConfigStore(file);
        concurrent.load();
        check(concurrent.countPlayers() == 10, "many players persisted");
        check(PluginConfig.class.getDeclaredFields().length >= 4, "config fields present");

        deleteRecursively(dir);
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
