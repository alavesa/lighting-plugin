package fi.alavesa.lighting.render;

import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * One light as the renderer sees it this tick, from any source (wand lights, emissive blocks, held items,
 * flashlights).
 *
 * @param key        stable id; decal entities are pooled under it, so a light keeps its entities while it changes
 * @param rgb        light colour; {@code colored} false = brightness only (vanilla's warm light, no decals)
 * @param radius     point light: reach in blocks. Spot: unused (the hit carries the size)
 * @param intensity  0.05..4, after animation
 * @param halo       draw a billboard glow at the source itself
 * @param fakeLevel  client-side LIGHT block level at {@code fakeX/Y/Z}; 0 = none
 * @param spot       non-null for flashlights / spotlights
 * @param blockSource true when the light sits inside a solid block (lamps): projection rays start outside it
 * @param anim       animation; applied per viewer (players can turn animations off)
 * @param seed       desynchronises animations of different lights
 */
public record Emitter(String key, World world, double x, double y, double z, int rgb, boolean colored,
                      double radius, double intensity, boolean halo, int fakeLevel, int fakeX, int fakeY, int fakeZ,
                      @Nullable Spot spot, boolean blockSource, Anim anim, long seed) {

    /**
     * A beam. {@code origin} is the lamp, {@code dir} unit length. {@code hit}/{@code normal} are null when the beam
     * reaches its range without hitting anything.
     */
    public record Spot(Vector origin, Vector dir, double coneDeg, double range, @Nullable Vector hit,
                       @Nullable Vector normal, double distance) {
    }

    public double distanceSq(double px, double py, double pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
