package fi.alavesa.lighting;

import fi.alavesa.lighting.render.Anim;
import fi.alavesa.lighting.render.Quality;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Snapshot of config.yml; rebuilt on /lighting reload. */
public final class LightingConfig {

    public record Preset(String name, int rgb) {
    }

    public enum Condition { ALWAYS, LIT, CHARGED }

    /** An emissive block's coloured light. {@code solid}: the block is opaque, so projection starts outside it. */
    public record BlockProfile(Material material, int rgb, double radius, double intensity, Condition condition,
                               boolean solid, Anim anim) {
    }

    /** A held/worn light item: {@code rgb} -1 = brightness only (vanilla-coloured light). */
    public record HeldProfile(Material material, int rgb, double radius, int level) {
    }

    // wand lights
    public final double radiusMin;
    public final double radiusMax;
    public final double radiusStep;
    public final double defaultRadius;
    public final double defaultIntensity;
    public final int defaultColor;
    public final double placeDistance;
    public final double selectDistance;
    public final double selectTolerance;
    public final boolean vanillaLight;
    public final String lightBlockMode;
    public final int maxLightsPerWorld;
    public final List<Preset> presets;

    // wand vfx
    public final int previewInterval;
    public final double previewPointsPerBlock;
    public final int previewMaxPoints;
    public final double previewShowLightsWithin;
    public final boolean trail;
    public final double trailVisibleTo;
    public final boolean swingArc;

    // renderer
    public final Quality defaultQuality;
    private final Map<Quality, Quality.Profile> profiles = new EnumMap<>(Quality.class);
    public final double opacity;
    public final double minAlpha;
    public final int poolSize;
    public final double frustumDegrees;
    public final double collectRange;
    public final int projectionCacheTicks;
    public final double beamLength;
    public final double fakeLightDistance;
    public final int maxFakeLights;
    public final int fakeResendTicks;
    public final boolean fallbackGlyphs;

    // emissive blocks
    public final boolean blocksEnabled;
    public final int blocksScanRadius;
    public final int blocksScansPerTick;
    public final int blocksRefreshTicks;
    public final long blocksRescanTicks;
    public final boolean blockHalos;
    public final Map<Material, BlockProfile> blockProfiles;

    // held / worn
    public final boolean heldEnabled;
    public final Map<Material, HeldProfile> heldProfiles;

    // flashlights
    public final boolean flashlightsEnabled;
    public final double flashRange;
    public final double flashCone;
    public final int flashColor;
    public final double flashBeamStep;
    public final int flashBeamLevel;
    public final boolean flashToggleWithDrop;
    public final boolean firearmsEnabled;
    public final String firearmsFlag;
    public final Set<String> firearmsFlashlightGuns;

    // pack
    public final String packDelivery;
    public final int packHttpPort;
    public final String packHttpBind;
    public final String packPublicHost;
    public final String packUrl;
    public final boolean packRequired;
    public final String packPrompt;
    public final String packSourceFolder;
    public final boolean joinMessage;

    public LightingConfig(FileConfiguration c, Logger log) {
        radiusMin = Math.max(0.5, c.getDouble("lights.radius.min", 1.0));
        radiusMax = Math.max(radiusMin, c.getDouble("lights.radius.max", 24.0));
        radiusStep = Math.max(0.125, c.getDouble("lights.radius.step", 0.5));
        defaultRadius = clamp(c.getDouble("lights.radius.default", 6.0), radiusMin, radiusMax);
        defaultIntensity = clamp(c.getDouble("lights.intensity.default", 1.0), 0.05, 3.9);
        defaultColor = parseColor(c.getString("lights.color.default", "#FFB347"), 0xFFB347);
        placeDistance = c.getDouble("wand.place-distance", 6.0);
        selectDistance = c.getDouble("wand.select-distance", 12.0);
        selectTolerance = c.getDouble("wand.select-tolerance", 0.6);
        lightBlockMode = c.getString("lights.light-block", "real").toLowerCase(Locale.ROOT);
        vanillaLight = !lightBlockMode.equals("none");
        maxLightsPerWorld = Math.max(1, c.getInt("lights.max-per-world", 512));

        List<Preset> p = new ArrayList<>();
        ConfigurationSection ps = c.getConfigurationSection("lights.color.presets");
        Map<String, Object> raw = ps == null ? new LinkedHashMap<>() : ps.getValues(false);
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            int rgb = parseColor(String.valueOf(e.getValue()), -1);
            if (rgb >= 0) p.add(new Preset(e.getKey(), rgb));
        }
        if (p.isEmpty()) p.add(new Preset("warm", defaultColor));
        presets = List.copyOf(p);

        previewInterval = Math.max(1, c.getInt("vfx.preview.interval-ticks", 2));
        previewPointsPerBlock = c.getDouble("vfx.preview.points-per-block", 3.0);
        previewMaxPoints = c.getInt("vfx.preview.max-points-per-ring", 96);
        previewShowLightsWithin = c.getDouble("vfx.preview.show-lights-within", 32.0);
        trail = c.getBoolean("vfx.trail.enabled", true);
        trailVisibleTo = c.getDouble("vfx.trail.visible-to", 32.0);
        swingArc = c.getBoolean("vfx.trail.swing-arc", true);

        Quality dq = Quality.parse(c.getString("render.default-quality", "high"));
        defaultQuality = dq == null ? Quality.HIGH : dq;
        profiles.put(Quality.LOW, profile(c, "low", 0, 32, 4, false, false, false, false, 4));
        profiles.put(Quality.MEDIUM, profile(c, "medium", 8, 32, 4, true, false, false, false, 4));
        profiles.put(Quality.HIGH, profile(c, "high", 16, 48, 2, true, true, true, false, 2));
        profiles.put(Quality.ULTRA, profile(c, "ultra", 32, 64, 1, true, true, true, true, 1));
        opacity = clamp(c.getDouble("render.opacity", 0.5), 0.05, 1.0);
        minAlpha = clamp(c.getDouble("render.min-alpha", 0.05), 0.0, 0.5);
        poolSize = Math.max(0, c.getInt("render.pool-size", 24));
        frustumDegrees = clamp(c.getDouble("render.frustum-degrees", 130), 60, 360);
        double maxProfile = 0;
        for (Quality.Profile pr : profiles.values()) maxProfile = Math.max(maxProfile, pr.maxDistance());
        collectRange = maxProfile;
        projectionCacheTicks = Math.max(1, c.getInt("render.projection-cache-ticks", 100));
        beamLength = c.getDouble("render.beam-length", 16);
        fakeLightDistance = c.getDouble("render.fake-light-distance", 48);
        maxFakeLights = Math.max(0, c.getInt("render.max-fake-lights", 48));
        fakeResendTicks = Math.max(20, c.getInt("render.fake-resend-ticks", 100));
        fallbackGlyphs = c.getBoolean("render.fallback-glyphs", false);

        blocksEnabled = c.getBoolean("blocks.enabled", true);
        blocksScanRadius = Math.max(1, c.getInt("blocks.scan-radius-chunks", 5));
        blocksScansPerTick = Math.max(1, c.getInt("blocks.scans-per-tick", 2));
        blocksRefreshTicks = Math.max(1, c.getInt("blocks.refresh-ticks", 10));
        blocksRescanTicks = Math.max(0, c.getInt("blocks.rescan-seconds", 30)) * 20L;
        blockHalos = c.getBoolean("blocks.halos", true);
        Map<Material, BlockProfile> bp = new EnumMap<>(Material.class);
        ConfigurationSection bs = c.getConfigurationSection("blocks.profiles");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                ConfigurationSection s = bs.getConfigurationSection(k);
                if (m == null || !m.isBlock() || s == null) {
                    log.warning("blocks.profiles." + k + ": not a block, skipped");
                    continue;
                }
                Condition cond;
                try {
                    cond = Condition.valueOf(s.getString("when", "always").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    cond = Condition.ALWAYS;
                }
                Anim anim = Anim.parse(s.getString("animation", "none"));
                bp.put(m, new BlockProfile(m, parseColor(s.getString("color"), 0xFFFFFF),
                        clamp(s.getDouble("radius", 5), 1, 32), clamp(s.getDouble("intensity", 1), 0.05, 3.9), cond,
                        m.isOccluding(), anim == null ? Anim.NONE : anim));
            }
        }
        blockProfiles = bp;

        heldEnabled = c.getBoolean("held.enabled", true);
        Map<Material, HeldProfile> hp = new EnumMap<>(Material.class);
        ConfigurationSection hs = c.getConfigurationSection("held.items");
        if (hs != null) {
            for (String k : hs.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                ConfigurationSection s = hs.getConfigurationSection(k);
                if (m == null || s == null) {
                    log.warning("held.items." + k + ": unknown item, skipped");
                    continue;
                }
                String col = s.getString("color", "none");
                hp.put(m, new HeldProfile(m, col.equalsIgnoreCase("none") ? -1 : parseColor(col, -1),
                        clamp(s.getDouble("radius", 5), 1, 16), (int) clamp(s.getInt("level", 12), 0, 15)));
            }
        }
        heldProfiles = hp;

        flashlightsEnabled = c.getBoolean("flashlights.enabled", true);
        flashRange = clamp(c.getDouble("flashlights.max-distance", 32), 4, 96);
        flashCone = clamp(c.getDouble("flashlights.cone-degrees", 34), 5, 120);
        flashColor = parseColor(c.getString("flashlights.color", "#FFF4D6"), 0xFFF4D6);
        flashBeamStep = Math.max(2, c.getDouble("flashlights.beam-light-step", 5));
        flashBeamLevel = (int) clamp(c.getInt("flashlights.beam-light-level", 9), 0, 15);
        flashToggleWithDrop = c.getBoolean("flashlights.toggle-with-drop-key", true);
        firearmsEnabled = c.getBoolean("firearms.enabled", true);
        firearmsFlag = c.getString("firearms.flashlight-flag", "firearms:flashlight");
        Set<String> guns = new HashSet<>();
        for (String g : c.getStringList("firearms.flashlight-guns")) guns.add(g.toLowerCase(Locale.ROOT));
        firearmsFlashlightGuns = guns;

        packDelivery = c.getString("pack.delivery", "auto").toLowerCase(Locale.ROOT);
        packHttpPort = c.getInt("pack.http.port", 8165);
        packHttpBind = c.getString("pack.http.bind", "0.0.0.0");
        packPublicHost = c.getString("pack.http.public-host", "");
        packUrl = c.getString("pack.url", "");
        packRequired = c.getBoolean("pack.strict-pack-required", false);
        packPrompt = c.getString("pack.prompt", "");
        packSourceFolder = c.getString("pack.source-folder", "");
        joinMessage = c.getBoolean("pack.join-message", true);
    }

    private static Quality.Profile profile(FileConfiguration c, String name, int lights, double dist, int interval,
                                           boolean decals, boolean halos, boolean spill, boolean beams, int interp) {
        String p = "quality." + name + ".";
        return new Quality.Profile(Math.max(0, c.getInt(p + "max-lights", lights)),
                c.getDouble(p + "max-distance", dist), Math.max(1, c.getInt(p + "interval-ticks", interval)),
                c.getBoolean(p + "decals", decals), c.getBoolean(p + "halos", halos),
                c.getBoolean(p + "flashlight-spill", spill), c.getBoolean(p + "beam-cone", beams),
                Math.max(0, c.getInt(p + "flashlight-interpolation-ticks", interp)));
    }

    public Quality.Profile profile(Quality q) {
        return profiles.get(q);
    }

    public double clampRadius(double r) {
        return clamp(r, radiusMin, radiusMax);
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** "#RRGGBB", "RRGGBB" or "0xRRGGBB"; {@code fallback} when it doesn't parse. */
    public static int parseColor(String s, int fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.toLowerCase(Locale.ROOT).startsWith("0x")) t = t.substring(2);
        if (t.length() != 6) return fallback;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }
}
