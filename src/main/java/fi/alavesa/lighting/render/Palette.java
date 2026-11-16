package fi.alavesa.lighting.render;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Colour-vision-friendly remapping of light colours, applied per viewer to the tint of their decals.
 * Protan/deutan/tritan use daltonisation: simulate the deficiency (Machado et al. 2009, severity 1), take the
 * colour information that would be lost, and move it into channels the viewer does see. High contrast pushes
 * every light to its fully saturated, full-brightness hue.
 */
public enum Palette {
    NORMAL, PROTANOPIA, DEUTERANOPIA, TRITANOPIA, HIGH_CONTRAST;

    private static final double[][] PROTAN = {
            {0.152286, 1.052583, -0.204868}, {0.114503, 0.786281, 0.099216}, {-0.003882, -0.048116, 1.051998}};
    private static final double[][] DEUTAN = {
            {0.367322, 0.860646, -0.227968}, {0.280085, 0.672501, 0.047413}, {-0.011820, 0.042940, 0.968881}};
    private static final double[][] TRITAN = {
            {1.255528, -0.076749, -0.178779}, {-0.078411, 0.930809, 0.147602}, {0.004733, 0.691367, 0.303900}};

    public static @Nullable Palette parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String label() {
        return switch (this) {
            case NORMAL -> "Normal";
            case PROTANOPIA -> "Protanopia";
            case DEUTERANOPIA -> "Deuteranopia";
            case TRITANOPIA -> "Tritanopia";
            case HIGH_CONTRAST -> "High contrast";
        };
    }

    public int remap(int rgb) {
        if (this == NORMAL) return rgb;
        double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
        if (this == HIGH_CONTRAST) {
            double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            if (max - min < 0.08) return 0xFFFFFF; // near-white stays white
            double[] s = {(r - min) / (max - min), (g - min) / (max - min), (b - min) / (max - min)};
            return pack(s[0], s[1], s[2]);
        }
        double[][] m = this == PROTANOPIA ? PROTAN : this == DEUTERANOPIA ? DEUTAN : TRITAN;
        double sr = m[0][0] * r + m[0][1] * g + m[0][2] * b;
        double sg = m[1][0] * r + m[1][1] * g + m[1][2] * b;
        double sb = m[2][0] * r + m[2][1] * g + m[2][2] * b;
        double er = r - sr, eg = g - sg, eb = b - sb;
        if (this == TRITANOPIA) {
            // lost blue/yellow information goes into red and green
            return pack(r + 0.7 * eb, g + 0.7 * eb, b);
        }
        // lost red/green information goes into green and blue
        return pack(r, g + 0.7 * er + eg, b + 0.7 * er + eb);
    }

    private static int pack(double r, double g, double b) {
        return (c(r) << 16) | (c(g) << 8) | c(b);
    }

    private static int c(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }
}
