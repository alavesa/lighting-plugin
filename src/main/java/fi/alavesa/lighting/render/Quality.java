package fi.alavesa.lighting.render;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Per-player quality presets. Numbers here are defaults; config.yml quality.* overrides them. */
public enum Quality {
    LOW, MEDIUM, HIGH, ULTRA;

    /** What a preset allows; read from config.yml quality.&lt;name&gt;. */
    public record Profile(int maxLights, double maxDistance, int interval, boolean decals, boolean halos,
                          boolean spill, boolean beams, int flashInterp) {
    }

    public static @Nullable Quality parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String label() {
        return this == LOW ? "Low (fallback)" : name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }
}
