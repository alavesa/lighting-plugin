package fi.alavesa.lighting.light;

import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.render.Anim;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * lights.yml. Saved from a main-thread snapshot, written off-thread (temp file + atomic move), at most once per
 * {@link #SAVE_DELAY_TICKS}; {@link #saveNow()} on shutdown.
 *
 * <pre>
 * lights:
 *   2f1c...:
 *     world: 0d3e...        # world uid
 *     world-name: world     # fallback when the uid changed (world re-created)
 *     x: 10.5
 *     y: 65.5
 *     z: -3.5
 *     color: "#FFB347"
 *     radius: 6.0
 *     intensity: 1.0
 *     vanilla-block: true
 *     animation: none       # none | pulse | flicker | strobe
 *     spot: [0.0, -1.0, 0.0]  # spotlight direction; absent = point light
 *     cone: 40.0            # spotlight cone, degrees
 *     owner: 8a7b...
 *     created: 1763100000000
 * </pre>
 */
public final class LightStore {

    private static final long SAVE_DELAY_TICKS = 40;

    private final Plugin plugin;
    private final Logger log;
    private final Path file;
    private boolean dirty;
    private boolean scheduled;

    public LightStore(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.file = plugin.getDataFolder().toPath().resolve("lights.yml");
    }

    public int load(LightRegistry registry, LightingConfig cfg) {
        File f = file.toFile();
        if (!f.exists()) return 0;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = y.getConfigurationSection("lights");
        if (root == null) return 0;
        int n = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                UUID world = UUID.fromString(s.getString("world", ""));
                if (Bukkit.getWorld(world) == null && Bukkit.getWorld(s.getString("world-name", "")) != null) {
                    world = Bukkit.getWorld(s.getString("world-name", "")).getUID();
                }
                String owner = s.getString("owner");
                LightSource l = new LightSource(UUID.fromString(key), world, s.getDouble("x"), s.getDouble("y"),
                        s.getDouble("z"), LightingConfig.parseColor(s.getString("color"), cfg.defaultColor),
                        cfg.clampRadius(s.getDouble("radius", cfg.defaultRadius)),
                        LightingConfig.clamp(s.getDouble("intensity", 1.0), 0.05, 3.9),
                        s.getBoolean("vanilla-block", true), owner == null ? null : UUID.fromString(owner),
                        s.getLong("created"));
                Anim anim = Anim.parse(s.getString("animation", "none"));
                l.anim = anim == null ? Anim.NONE : anim;
                java.util.List<Double> spot = s.getDoubleList("spot");
                if (spot.size() == 3) l.spotDir = new org.bukkit.util.Vector(spot.get(0), spot.get(1), spot.get(2)).normalize();
                l.coneDeg = s.getDouble("cone", 40);
                registry.adoptBlock(l);
                registry.add(l);
                n++;
            } catch (IllegalArgumentException e) {
                log.warning("lights.yml: skipping light " + key + " (" + e.getMessage() + ")");
            }
        }
        return n;
    }

    /** Marks the store dirty; the save runs a moment later so a burst of edits is one write. */
    public void markDirty(LightRegistry registry) {
        dirty = true;
        if (scheduled || !plugin.isEnabled()) return;
        scheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduled = false;
            if (!dirty) return;
            dirty = false;
            String text = snapshot(registry);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(text));
        }, SAVE_DELAY_TICKS);
    }

    public void saveNow(LightRegistry registry) {
        dirty = false;
        write(snapshot(registry));
    }

    private String snapshot(LightRegistry registry) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of("Lighting: placed lights. Edit only while the server is stopped."));
        for (LightSource l : registry.all()) {
            String p = "lights." + l.id + ".";
            y.set(p + "world", l.worldId.toString());
            var w = l.world();
            if (w != null) y.set(p + "world-name", w.getName());
            y.set(p + "x", l.x);
            y.set(p + "y", l.y);
            y.set(p + "z", l.z);
            y.set(p + "color", LightingConfig.hex(l.rgb));
            y.set(p + "radius", l.radius);
            y.set(p + "intensity", l.intensity);
            y.set(p + "vanilla-block", l.vanillaBlock);
            y.set(p + "animation", l.anim.name().toLowerCase(java.util.Locale.ROOT));
            if (l.spotDir != null) {
                y.set(p + "spot", java.util.List.of(l.spotDir.getX(), l.spotDir.getY(), l.spotDir.getZ()));
                y.set(p + "cone", l.coneDeg);
            }
            if (l.owner != null) y.set(p + "owner", l.owner.toString());
            y.set(p + "created", l.created);
        }
        return y.saveToString();
    }

    private synchronized void write(String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("lights.yml.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not save lights.yml", e);
        }
    }
}
