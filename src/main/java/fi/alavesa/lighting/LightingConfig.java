package fi.alavesa.lighting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Snapshot of config.yml; rebuilt on /lighting reload. */
public final class LightingConfig {

    public record Preset(String name, int rgb) {
    }

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
    public final int maxLightsPerWorld;
    public final float markerViewRange;
    public final List<Preset> presets;

    public final int previewInterval;
    public final double previewPointsPerBlock;
    public final int previewMaxPoints;
    public final double previewShowLightsWithin;
    public final boolean trail;
    public final double trailVisibleTo;
    public final boolean swingArc;

    public final boolean effectEnabled;
    public final boolean requirePackForEffect;

    public final String packDelivery;
    public final int packHttpPort;
    public final String packHttpBind;
    public final String packPublicHost;
    public final String packUrl;
    public final boolean packRequired;
    public final String packPrompt;
    public final String packSourceFolder;

    public LightingConfig(FileConfiguration c) {
        radiusMin = Math.max(0.5, c.getDouble("lights.radius.min", 1.0));
        // 31.875 is the most the shader can carry (radius * 8 in one byte)
        radiusMax = Math.min(31.5, Math.max(radiusMin, c.getDouble("lights.radius.max", 24.0)));
        radiusStep = Math.max(0.125, c.getDouble("lights.radius.step", 0.5));
        defaultRadius = clamp(c.getDouble("lights.radius.default", 6.0), radiusMin, radiusMax);
        defaultIntensity = clamp(c.getDouble("lights.intensity.default", 1.0), 0.05, 3.9);
        defaultColor = parseColor(c.getString("lights.color.default", "#FFB347"), 0xFFB347);
        placeDistance = c.getDouble("wand.place-distance", 6.0);
        selectDistance = c.getDouble("wand.select-distance", 12.0);
        selectTolerance = c.getDouble("wand.select-tolerance", 0.6);
        vanillaLight = c.getBoolean("lights.vanilla-light-block", true);
        maxLightsPerWorld = (int) clamp(c.getInt("lights.max-per-world", 64), 1, 64);
        markerViewRange = (float) c.getDouble("lights.marker-view-range", 2.0);

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

        effectEnabled = c.getBoolean("shader.enabled", true);
        requirePackForEffect = c.getBoolean("shader.require-pack", true);

        packDelivery = c.getString("pack.delivery", "auto").toLowerCase(Locale.ROOT);
        packHttpPort = c.getInt("pack.http.port", 8165);
        packHttpBind = c.getString("pack.http.bind", "0.0.0.0");
        packPublicHost = c.getString("pack.http.public-host", "");
        packUrl = c.getString("pack.url", "");
        packRequired = c.getBoolean("pack.required", false);
        packPrompt = c.getString("pack.prompt", "");
        packSourceFolder = c.getString("pack.source-folder", "");
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
