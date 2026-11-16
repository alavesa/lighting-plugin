package fi.alavesa.lighting.source;

import fi.alavesa.lighting.light.LightRegistry;
import fi.alavesa.lighting.light.LightSource;
import fi.alavesa.lighting.render.Emitter;
import fi.alavesa.lighting.render.EmitterSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Lights placed with the wand. Spotlights aim along their stored direction (raycast cached for a second). */
public final class WandLights implements EmitterSource {

    private record SpotHit(long tick, Emitter.Spot spot) {
    }

    private final LightRegistry registry;
    private final Map<UUID, SpotHit> spots = new HashMap<>();

    public WandLights(LightRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "wand lights";
    }

    @Override
    public void collect(World world, double[] viewers, double range, long tick, List<Emitter> out) {
        for (LightSource l : registry.all()) {
            if (!l.worldId.equals(world.getUID()) || !Sources.near(viewers, l.x(), l.y(), l.z(), range + l.radius())) continue;
            int bx = (int) Math.floor(l.x()), by = (int) Math.floor(l.y()), bz = (int) Math.floor(l.z());
            int fake = registry.packetBlock(l) ? Math.max(1, Math.min(15, (int) Math.round(l.radius()))) : 0;
            Emitter.Spot spot = null;
            Vector dir = l.spotDir();
            if (dir != null) {
                SpotHit c = spots.get(l.id);
                if (c == null || tick - c.tick > 20 || !c.spot.dir().equals(dir)) {
                    double reach = l.radius() * 2;
                    Vector o = new Vector(l.x(), l.y(), l.z());
                    RayTraceResult hit = world.rayTraceBlocks(new Location(world, l.x(), l.y(), l.z()), dir, reach,
                            FluidCollisionMode.NEVER, true);
                    spot = hit == null || hit.getHitBlockFace() == null
                            ? new Emitter.Spot(o, dir, l.coneDeg(), reach, null, null, reach)
                            : new Emitter.Spot(o, dir, l.coneDeg(), reach, hit.getHitPosition(),
                            hit.getHitBlockFace().getDirection(), hit.getHitPosition().distance(o));
                    spots.put(l.id, new SpotHit(tick, spot));
                } else {
                    spot = c.spot;
                }
            }
            out.add(new Emitter("w:" + l.id, world, l.x(), l.y(), l.z(), l.rgb(), true, l.radius(), l.intensity(),
                    true, fake, bx, by, bz, spot, false, l.anim(), l.id.getMostSignificantBits()));
        }
    }
}
