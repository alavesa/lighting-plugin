package fi.alavesa.lighting.render;

import org.joml.Matrix4f;

/**
 * One display entity's worth of light: an item model ({@code kind}) at {@code x,y,z} with {@code transform},
 * {@code alpha} 0..1 before the viewer's opacity steps.
 */
public record Decal(String id, Kind kind, double x, double y, double z, Matrix4f transform, double alpha,
                    boolean billboard, int interp) {

    public enum Kind {
        GLOW("glow"), HOTSPOT("hotspot"), SPILL("spill"), HALO("halo"), BEAM("beam"), SHADOW("shadow"),
        /** wide soft billboard: outer halo layers and the beam's pearls */
        ORB("orb"),
        /** camera-filling vignette while the viewer stands inside a coloured light */
        AMBIENT("ambient");

        public final String model;

        Kind(String model) {
            this.model = model;
        }
    }
}
