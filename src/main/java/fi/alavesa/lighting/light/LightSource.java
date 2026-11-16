package fi.alavesa.lighting.light;

import fi.alavesa.lighting.render.Anim;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/** One placed light. Position is the light centre (block centre of the vanilla light block when there is one). */
public final class LightSource {

    public final UUID id;
    public final UUID worldId;
    double x;
    double y;
    double z;
    int rgb;
    double radius;
    double intensity;
    boolean vanillaBlock;
    Anim anim = Anim.NONE;
    /** Spotlight direction (unit), or null for a point light. */
    @Nullable Vector spotDir;
    double coneDeg = 40;
    final @Nullable UUID owner;
    final long created;

    // runtime only
    /** True when the LIGHT block at the centre was put there by us (only then is it removed with the light). */
    boolean ownsBlock;

    LightSource(UUID id, UUID worldId, double x, double y, double z, int rgb, double radius, double intensity,
                boolean vanillaBlock, @Nullable UUID owner, long created) {
        this.id = id;
        this.worldId = worldId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rgb = rgb;
        this.radius = radius;
        this.intensity = intensity;
        this.vanillaBlock = vanillaBlock;
        this.owner = owner;
        this.created = created;
    }

    public @Nullable World world() {
        return Bukkit.getWorld(worldId);
    }

    public @Nullable Location location() {
        World w = world();
        return w == null ? null : new Location(w, x, y, z);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public int rgb() {
        return rgb;
    }

    public double radius() {
        return radius;
    }

    public double intensity() {
        return intensity;
    }

    public boolean vanillaBlock() {
        return vanillaBlock;
    }

    public Anim anim() {
        return anim;
    }

    public @Nullable Vector spotDir() {
        return spotDir == null ? null : spotDir.clone();
    }

    public double coneDeg() {
        return coneDeg;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }

    long chunkKey() {
        return chunkKey((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Vanilla light level for the light block: reaches about as far as the coloured radius. */
    int vanillaLevel() {
        return (int) Math.max(1, Math.min(15, Math.round(radius * Math.min(1.0, 0.5 + intensity * 0.5))));
    }
}
