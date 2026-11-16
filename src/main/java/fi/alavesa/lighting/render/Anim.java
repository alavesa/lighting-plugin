package fi.alavesa.lighting.render;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Light animations, as an intensity multiplier over time. */
public enum Anim {
    NONE, PULSE, FLICKER, STROBE;

    public static @Nullable Anim parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Multiplier at game tick {@code t}: pulse 0.55..1 over 2 s, flicker = noisy 0.45..1, strobe on/off every 3 ticks. */
    public double factor(long t, long seed) {
        return switch (this) {
            case NONE -> 1.0;
            case PULSE -> 0.775 + 0.225 * Math.sin((t + (seed & 63)) * (2 * Math.PI / 40.0));
            case FLICKER -> {
                double n = noise(t / 2, seed) * 0.6 + noise(t / 7, seed * 31) * 0.4;
                yield n < 0.12 ? 0.45 : 0.8 + 0.2 * n;
            }
            case STROBE -> ((t + seed) / 3) % 2 == 0 ? 1.0 : 0.0;
        };
    }

    private static double noise(long t, long seed) {
        long h = t * 0x9E3779B97F4A7C15L ^ seed * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }
}
