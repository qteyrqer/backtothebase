package xin.bbtt.backtothebase.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads and persists {@link PluginConfig}. All access is synchronized so console
 * commands, the private-chat listener and the movement worker can share it.
 */
public final class ConfigStore {
    private static final Logger log = LoggerFactory.getLogger("backtothebase");
    private static final DateTimeFormatter BROKEN_SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object lock = new Object();

    private PluginConfig config = new PluginConfig();

    public ConfigStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    public void load() {
        synchronized (lock) {
            if (!Files.isRegularFile(file)) {
                config = new PluginConfig();
                saveLocked();
                log.info("Created default configuration at {}", file);
                return;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                PluginConfig loaded = gson.fromJson(reader, PluginConfig.class);
                config = loaded == null ? new PluginConfig() : loaded;
            } catch (Exception e) {
                log.error("Could not read {}; starting with an empty configuration", file, e);
                backupBrokenFile();
                config = new PluginConfig();
            }
            normalize();
            saveLocked();
        }
    }

    private void backupBrokenFile() {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".broken-"
                    + LocalDateTime.now().format(BROKEN_SUFFIX));
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Moved unreadable configuration to {}", backup);
        } catch (IOException e) {
            log.warn("Could not back up unreadable configuration {}", file, e);
        }
    }

    private void normalize() {
        if (config.account != null) config.account = config.account.trim();
        config.requestTimeoutSeconds = clamp(config.requestTimeoutSeconds, 30, 3600);
        config.pathfindExpansions = clamp(config.pathfindExpansions, 500, 50_000);
        if (config.whitelist == null) config.whitelist = new LinkedHashMap<>();

        Map<String, Map<String, PluginConfig.Point>> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, PluginConfig.Point>> entry : config.whitelist.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isEmpty() || entry.getValue() == null) continue;
            Map<String, PluginConfig.Point> bases = new LinkedHashMap<>();
            for (Map.Entry<String, PluginConfig.Point> baseEntry : entry.getValue().entrySet()) {
                Integer base = parseBase(baseEntry.getKey());
                PluginConfig.Point point = baseEntry.getValue();
                if (base == null || point == null) continue;
                bases.put(String.valueOf(base), point);
            }
            if (!bases.isEmpty()) cleaned.put(name, bases);
        }
        config.whitelist = cleaned;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Integer parseBase(String raw) {
        if (raw == null) return null;
        try {
            int base = Integer.parseInt(raw.trim());
            return base > 0 ? base : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public void save() {
        synchronized (lock) {
            saveLocked();
        }
    }

    private void saveLocked() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(config, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Could not save configuration to {}", file, e);
        }
    }

    public PluginConfig config() {
        synchronized (lock) {
            return config;
        }
    }

    public void setAccount(String account) {
        synchronized (lock) {
            config.account = account == null ? "" : account.trim();
            saveLocked();
        }
    }

    public String account() {
        synchronized (lock) {
            return config.account == null ? "" : config.account;
        }
    }

    public boolean isWhitelisted(String player) {
        synchronized (lock) {
            return canonicalNameLocked(player) != null;
        }
    }

    public String canonicalName(String player) {
        synchronized (lock) {
            return canonicalNameLocked(player);
        }
    }

    private String canonicalNameLocked(String player) {
        if (player == null) return null;
        String trimmed = player.trim();
        if (trimmed.isEmpty()) return null;
        for (String name : config.whitelist.keySet()) {
            if (name.equalsIgnoreCase(trimmed)) return name;
        }
        return null;
    }

    public PluginConfig.Point point(String player, int base) {
        synchronized (lock) {
            String name = canonicalNameLocked(player);
            if (name == null) return null;
            Map<String, PluginConfig.Point> bases = config.whitelist.get(name);
            if (bases == null) return null;
            return bases.get(String.valueOf(base));
        }
    }

    /** Copy of the base -> point map for one player, or {@code null}. */
    public Map<String, PluginConfig.Point> pointsFor(String player) {
        synchronized (lock) {
            String name = canonicalNameLocked(player);
            if (name == null) return null;
            Map<String, PluginConfig.Point> bases = config.whitelist.get(name);
            return bases == null ? null : new LinkedHashMap<>(bases);
        }
    }

    /**
     * Adds or replaces the trapdoor coordinate of one base.
     *
     * @return the previous point, or {@code null} when this base was new
     */
    public PluginConfig.Point addPoint(String player, int base, int x, int y, int z) {
        if (player == null || player.isBlank() || base < 1) return null;
        synchronized (lock) {
            String canonical = canonicalNameLocked(player);
            if (canonical == null) canonical = player.trim();
            Map<String, PluginConfig.Point> bases =
                    config.whitelist.computeIfAbsent(canonical, ignored -> new LinkedHashMap<>());
            PluginConfig.Point previous = bases.put(String.valueOf(base),
                    new PluginConfig.Point(x, y, z));
            saveLocked();
            return previous;
        }
    }

    /**
     * Removes one base, or every base of the player when {@code base} is {@code null}.
     *
     * @return number of removed base entries, 0 when nothing matched
     */
    public int removePoint(String player, Integer base) {
        if (player == null || player.isBlank()) return 0;
        synchronized (lock) {
            String canonical = canonicalNameLocked(player);
            if (canonical == null) return 0;
            if (base == null) {
                Map<String, PluginConfig.Point> removed = config.whitelist.remove(canonical);
                if (removed == null || removed.isEmpty()) return 0;
                saveLocked();
                return removed.size();
            }
            Map<String, PluginConfig.Point> bases = config.whitelist.get(canonical);
            if (bases == null || bases.remove(String.valueOf(base)) == null) return 0;
            if (bases.isEmpty()) config.whitelist.remove(canonical);
            saveLocked();
            return 1;
        }
    }

    public int countPlayers() {
        synchronized (lock) {
            return config.whitelist.size();
        }
    }

    public int countBases() {
        synchronized (lock) {
            int total = 0;
            for (Map<String, PluginConfig.Point> bases : config.whitelist.values()) {
                total += bases.size();
            }
            return total;
        }
    }

    /** Case-insensitive sorted snapshot used by the {@code list} command. */
    public List<Map.Entry<String, Map<String, PluginConfig.Point>>> snapshot() {
        synchronized (lock) {
            TreeMap<String, Map<String, PluginConfig.Point>> sorted =
                    new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, Map<String, PluginConfig.Point>> entry : config.whitelist.entrySet()) {
                sorted.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
            return new ArrayList<>(sorted.entrySet());
        }
    }

    public List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Map<String, PluginConfig.Point>> entry : snapshot()) {
            names.add(entry.getKey());
        }
        return names;
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return String.format(Locale.ROOT, "ConfigStore[%s, %d players, %d bases]",
                    file, countPlayers(), countBases());
        }
    }
}
