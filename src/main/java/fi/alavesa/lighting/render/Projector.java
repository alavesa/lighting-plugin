package fi.alavesa.lighting.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Turns emitters into decals: where a light lands on the surrounding blocks and how big, tilted and stretched each
 * soft decal is. Viewer independent, so the (raycast) work for point lights is cached and shared by all viewers.
 *
 * <p>Decal models are a unit square in the model's XY plane (normal = +Z). A decal transform maps model X to the
 * ellipse's major axis, Y to its minor axis and Z to the surface normal.
 */
public final class Projector {

    /** Lift off the surface against z-fighting. */
    public static final double SURFACE_OFFSET = 0.002;

    private static final BlockFace[] AXES = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};

    private record Patch(double x, double y, double z, Vector normal, double diameter, double falloff) {
    }

    private record Cached(double x, double y, double z, double radius, long tick, List<Patch> patches) {
    }

    private final Map<String, Cached> cache = new HashMap<>();
    private int cacheTicks = 100;

    public void cacheTicks(int t) {
        this.cacheTicks = t;
    }

    /** Forget cached projections near a block change (a wall appeared or vanished). */
    public void invalidate(World w, double x, double y, double z, double within) {
        cache.values().removeIf(c -> {
            double dx = c.x - x, dy = c.y - y, dz = c.z - z;
            double r = c.radius + within;
            return dx * dx + dy * dy + dz * dz <= r * r;
        });
    }

    public void clear() {
        cache.clear();
    }

    /** Point light: a soft pool on the nearest surface in each of the six axis directions within the radius. */
    public void point(Emitter e, long tick, double alpha, List<Decal> out) {
        Cached c = cache.get(e.key());
        if (c == null || tick - c.tick > cacheTicks || Math.abs(c.x - e.x()) > 0.01 || Math.abs(c.y - e.y()) > 0.01
                || Math.abs(c.z - e.z()) > 0.01 || c.radius != e.radius()) {
            c = new Cached(e.x(), e.y(), e.z(), e.radius(), tick, cast(e));
            cache.put(e.key(), c);
        }
        int i = 0;
        for (Patch p : c.patches) {
            double a = alpha * p.falloff;
            out.add(new Decal(e.key() + "#" + i++, Decal.Kind.GLOW, p.x, p.y, p.z,
                    flat(p.normal, null, p.diameter, p.diameter), a, false, 0));
        }
    }

    private List<Patch> cast(Emitter e) {
        List<Patch> out = new ArrayList<>(6);
        double r = e.radius();
        for (BlockFace f : AXES) {
            Vector dir = f.getDirection();
            Vector hp;
            Vector n;
            if (e.blockSource()) {
                // a block light starts at the edge of its own block cell: a solid neighbour gets the pool right on
                // the face touching the light (floor under a lantern), otherwise the ray starts in the next cell
                int bx = (int) Math.floor(e.x()), by = (int) Math.floor(e.y()), bz = (int) Math.floor(e.z());
                var nb = e.world().getBlockAt(bx + f.getModX(), by + f.getModY(), bz + f.getModZ());
                Vector c = new Vector(bx + 0.5, by + 0.5, bz + 0.5);
                if (nb.getType().isOccluding()) {
                    hp = c.clone().add(dir.clone().multiply(0.5));
                    n = dir.clone().multiply(-1);
                } else {
                    Location from = c.clone().add(dir.clone().multiply(0.501)).toLocation(e.world());
                    RayTraceResult hit = e.world().rayTraceBlocks(from, dir, r - 0.501, FluidCollisionMode.NEVER, true);
                    if (hit == null || hit.getHitBlockFace() == null) continue;
                    hp = hit.getHitPosition();
                    n = hit.getHitBlockFace().getDirection();
                }
            } else {
                Location from = new Location(e.world(), e.x(), e.y(), e.z());
                RayTraceResult hit = e.world().rayTraceBlocks(from, dir, r, FluidCollisionMode.NEVER, true);
                if (hit == null || hit.getHitBlockFace() == null) continue;
                hp = hit.getHitPosition();
                n = hit.getHitBlockFace().getDirection();
            }
            double d = hp.distance(new Vector(e.x(), e.y(), e.z()));
            if (d >= r) continue;
            // the sphere of reach cut by the surface plane, but never hanging off the edge of the surface into air
            double half = Math.sqrt(r * r - d * d);
            half = Math.min(half, extent(e.world(), hp, n, half));
            if (half < 0.2) continue;
            double diameter = 2.0 * half;
            double falloff = Math.pow(1 - d / r, 0.9);
            // each axis gets its own tiny extra lift so pools of two lights on one wall don't z-fight
            double lift = SURFACE_OFFSET + (Math.floorMod(e.key().hashCode(), 7)) * 0.0004;
            out.add(new Patch(hp.getX() + n.getX() * lift, hp.getY() + n.getY() * lift, hp.getZ() + n.getZ() * lift,
                    n, diameter, falloff));
        }
        return out;
    }

    /**
     * How far the surface at {@code hp} (facing {@code n}) continues in-plane before it ends in open air, the smallest of
     * the four in-plane directions, up to {@code max}. A decal reaching past that would hang in the air as a visible
     * sheet. Running into a solid block (a wall meeting the floor) doesn't count: depth hides that part.
     */
    static double extent(World w, Vector hp, Vector n, double max) {
        int nx = (int) Math.round(n.getX()), ny = (int) Math.round(n.getY()), nz = (int) Math.round(n.getZ());
        int bx = (int) Math.floor(hp.getX() - nx * 0.01), by = (int) Math.floor(hp.getY() - ny * 0.01),
                bz = (int) Math.floor(hp.getZ() - nz * 0.01);
        int[][] dirs = nx != 0 ? new int[][]{{0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}
                : ny != 0 ? new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}
                : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        double best = max;
        int steps = (int) Math.ceil(max) + 1;
        for (int[] d : dirs) {
            // distance from the hit point to the edge of its own block in this direction
            double inCell = d[0] != 0 ? (d[0] > 0 ? bx + 1 - hp.getX() : hp.getX() - bx)
                    : d[1] != 0 ? (d[1] > 0 ? by + 1 - hp.getY() : hp.getY() - by)
                    : (d[2] > 0 ? bz + 1 - hp.getZ() : hp.getZ() - bz);
            for (int k = 1; k <= steps && inCell + k - 1 < best; k++) {
                int x = bx + d[0] * k, y = by + d[1] * k, z = bz + d[2] * k;
                boolean behind = solid(w, x, y, z), front = solid(w, x + nx, y + ny, z + nz);
                if (!behind && !front) {
                    best = Math.min(best, inCell + k - 1);
                    break;
                }
            }
        }
        return best;
    }

    private static boolean solid(World w, int x, int y, int z) {
        return y >= w.getMinHeight() && y < w.getMaxHeight() && w.getBlockAt(x, y, z).getType().isOccluding();
    }

    /**
     * Flashlight / spotlight: hotspot + (optional) spill ring where the beam lands, scaled by distance
     * (r = d * tan(theta)) and stretched by 1/cos(incidence) along the beam's direction on the surface, plus the
     * optional volumetric beam cone.
     */
    public void spot(Emitter e, double alpha, boolean spill, boolean beam, int interp, double beamMax, List<Decal> out) {
        Emitter.Spot s = e.spot();
        if (s == null) return;
        double half = Math.toRadians(s.coneDeg() / 2);
        if (s.hit() != null && s.normal() != null) {
            double d = Math.max(0.3, s.distance());
            double far = Math.max(0.05, 1 - d / s.range());
            double cos = Math.max(0.4, Math.abs(s.dir().dot(s.normal()))); // stretch capped at 2.5x
            double stretch = 1 / cos;
            Vector major = s.dir().clone().subtract(s.normal().clone().multiply(s.dir().dot(s.normal())));
            Vector h = s.hit().clone().add(s.normal().clone().multiply(SURFACE_OFFSET));
            double rh = Math.max(0.15, d * Math.tan(half * 0.5));
            out.add(new Decal(e.key() + "#hot", Decal.Kind.HOTSPOT, h.getX(), h.getY(), h.getZ(),
                    flat(s.normal(), major, 2 * rh * stretch, 2 * rh), alpha * Math.pow(far, 0.6), false, interp));
            if (spill) {
                double rs = Math.max(0.3, d * Math.tan(half));
                Vector h2 = s.hit().clone().add(s.normal().clone().multiply(SURFACE_OFFSET * 0.5));
                out.add(new Decal(e.key() + "#spill", Decal.Kind.SPILL, h2.getX(), h2.getY(), h2.getZ(),
                        flat(s.normal(), major, 2 * rs * stretch, 2 * rs), alpha * 0.8 * Math.pow(far, 0.8), false, interp));
            }
        }
        if (beam) {
            double len = Math.min(beamMax, s.hit() != null ? s.distance() : s.range());
            if (len > 0.5) {
                double w = 2 * len * Math.tan(half);
                Quaternionf rot = new Quaternionf().rotationTo(0, 0, 1, (float) s.dir().getX(), (float) s.dir().getY(),
                        (float) s.dir().getZ());
                // model z 0..16 is lamp..far end; centred models span -0.5..0.5, so shift by half a length first
                Matrix4f m = new Matrix4f().rotate(rot).scale((float) w, (float) w, (float) len).translate(0, 0, 0.5f);
                out.add(new Decal(e.key() + "#beam", Decal.Kind.BEAM, s.origin().getX(), s.origin().getY(),
                        s.origin().getZ(), m, alpha * 0.6, false, interp));
            }
        }
    }

    /** Billboard glow around the source itself. */
    public void halo(Emitter e, double alpha, List<Decal> out) {
        float size = (float) Math.max(0.6, Math.min(2.5, 0.5 + e.radius() * 0.12));
        out.add(new Decal(e.key() + "#halo", Decal.Kind.HALO, e.x(), e.y(), e.z(),
                new Matrix4f().scale(size, size, 1), alpha * 0.6, true, 0));
    }

    /**
     * Transform for a flat decal lying on a surface: model X -> {@code major} (projected into the plane) scaled
     * {@code sx}, model Y -> the in-plane perpendicular scaled {@code sy}, model Z -> the normal.
     */
    static Matrix4f flat(Vector normal, Vector major, double sx, double sy) {
        Vector3f n = new Vector3f((float) normal.getX(), (float) normal.getY(), (float) normal.getZ()).normalize();
        Vector3f u;
        if (major != null && major.lengthSquared() > 1e-4) {
            u = new Vector3f((float) major.getX(), (float) major.getY(), (float) major.getZ());
            u.sub(new Vector3f(n).mul(u.dot(n))).normalize();
        } else {
            u = new Vector3f(Math.abs(n.y) > 0.9f ? 1 : 0, Math.abs(n.y) > 0.9f ? 0 : 1, 0);
            u.sub(new Vector3f(n).mul(u.dot(n))).normalize();
        }
        Vector3f v = new Vector3f(n).cross(u).normalize();
        return new Matrix4f(
                u.x * (float) sx, u.y * (float) sx, u.z * (float) sx, 0,
                v.x * (float) sy, v.y * (float) sy, v.z * (float) sy, 0,
                n.x, n.y, n.z, 0,
                0, 0, 0, 1);
    }
}
