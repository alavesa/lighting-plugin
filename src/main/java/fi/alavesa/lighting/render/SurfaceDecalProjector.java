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
public final class SurfaceDecalProjector {

    /** Lift off the surface against z-fighting. Shadows sit above every glow (glow lift is at most 0.0054). */
    public static final double SURFACE_OFFSET = 0.003;
    private static final double SHADOW_LIFT = 0.007;

    private static final BlockFace[] AXES = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};

    private record Patch(double x, double y, double z, Vector normal, double diameter, double falloff,
                         List<Shadow> shadows) {
    }

    /** A dark rectangle on a surface where the light is blocked; {@code alpha} is relative to the light's opacity. */
    private record Shadow(double x, double y, double z, Vector normal, Vector axis, double w, double h, double alpha) {
    }

    /** Shadow settings, from config.yml shadows.*. */
    public record ShadowOptions(boolean enabled, double strength, boolean flashlights, int maxCells) {
    }

    private ShadowOptions shadows = new ShadowOptions(true, 0.9, true, 400);

    public void shadows(ShadowOptions o) {
        this.shadows = o;
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
            out.add(new Decal(e.key() + "#" + i, Decal.Kind.GLOW, p.x, p.y, p.z,
                    flat(p.normal, null, p.diameter, p.diameter), a, false, 0));
            int k = 0;
            for (Shadow sh : p.shadows) {
                out.add(new Decal(e.key() + "#" + i + "s" + k++, Decal.Kind.SHADOW, sh.x, sh.y, sh.z,
                        flat(sh.normal, sh.axis, sh.w, sh.h), a * sh.alpha, false, 0));
            }
            i++;
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
            List<Shadow> sh = shadows.enabled()
                    ? shadowCells(e.world(), new Vector(e.x(), e.y(), e.z()), e.blockSource(), hp, n, half,
                    rn -> {
                        // a shadow only where the pool actually has light, fading with it
                        double g = glowProfile(rn);
                        return g < 0.1 ? 0 : g;
                    })
                    : List.of();
            out.add(new Patch(hp.getX() + n.getX() * lift, hp.getY() + n.getY() * lift, hp.getZ() + n.getZ() * lift,
                    n, diameter, falloff, sh));
        }
        return out;
    }

    /** Alpha of the glow texture at r (0 centre .. 1 edge): must match tools/gen_pack.py's 'glow' profile. */
    static double glowProfile(double r) {
        return Math.pow(1 - smooth(Math.min(1, r)), 2.2);
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /**
     * Shadows on one lit surface. Each block cell of the surface within {@code half} of {@code hp} checks its line of
     * sight to the light: one ray from the cell centre, and where neighbours disagree (a shadow edge) four rays from
     * the cell's corners, so edges get a soft quarter-step penumbra. Blocked cells, weighted by how much light would
     * have reached them ({@code weight} of the distance from the centre, 0..1), are merged into rectangles of equal
     * strength. Block resolution, like vanilla's smooth lighting.
     */
    List<Shadow> shadowCells(World w, Vector light, boolean blockSource, Vector hp, Vector n, double half,
                             java.util.function.DoubleUnaryOperator weight) {
        int nx = (int) Math.round(n.getX()), ny = (int) Math.round(n.getY()), nz = (int) Math.round(n.getZ());
        // in-plane axes a and b (as unit block steps) and the plane's block layer (the solid blocks the surface is on)
        int[] a = nx != 0 ? new int[]{0, 1, 0} : new int[]{1, 0, 0};
        int[] b = nz != 0 ? new int[]{0, 1, 0} : new int[]{0, 0, 1};
        if (ny == 0 && nx == 0) {
            a = new int[]{1, 0, 0};
            b = new int[]{0, 1, 0};
        }
        int lx = (int) Math.floor(hp.getX() - nx * 0.01), ly = (int) Math.floor(hp.getY() - ny * 0.01),
                lz = (int) Math.floor(hp.getZ() - nz * 0.01);
        double ca = hp.getX() * a[0] + hp.getY() * a[1] + hp.getZ() * a[2];
        double cb = hp.getX() * b[0] + hp.getY() * b[1] + hp.getZ() * b[2];
        int r = (int) Math.ceil(half);
        int size = 2 * r + 1;
        if (size * size > shadows.maxCells()) {
            r = (int) Math.floor((Math.sqrt(shadows.maxCells()) - 1) / 2);
            size = 2 * r + 1;
        }
        int a0 = (int) Math.floor(ca) - r, b0 = (int) Math.floor(cb) - r;
        // the block at grid (i, j): plane layer coordinate stays, a/b coordinates vary
        double[] vis = new double[size * size];
        boolean[] surf = new boolean[size * size];
        double[] wgt = new double[size * size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int ga = a0 + i, gb = b0 + j;
                int x = lx * (1 - a[0] - b[0]) + ga * a[0] + gb * b[0];
                int y = ly * (1 - a[1] - b[1]) + ga * a[1] + gb * b[1];
                int z = lz * (1 - a[2] - b[2]) + ga * a[2] + gb * b[2];
                double da = ga + 0.5 - ca, db = gb + 0.5 - cb;
                double rn = Math.sqrt(da * da + db * db) / half;
                int idx = i * size + j;
                if (rn >= 1) continue;
                if (!solid(w, x, y, z) || solid(w, x + nx, y + ny, z + nz)) continue; // no surface here
                surf[idx] = true;
                wgt[idx] = weight.applyAsDouble(rn);
                vis[idx] = sees(w, light, blockSource, faceCentre(x, y, z, nx, ny, nz, 0, 0, a, b)) ? 1 : 0;
            }
        }
        // soften shadow edges: cells whose neighbours disagree sample four corners
        double[] frac = vis.clone();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int idx = i * size + j;
                if (!surf[idx]) continue;
                boolean edge = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int ii = i + d[0], jj = j + d[1];
                    if (ii < 0 || jj < 0 || ii >= size || jj >= size) continue;
                    int o = ii * size + jj;
                    if (surf[o] && vis[o] != vis[idx]) edge = true;
                }
                if (!edge) continue;
                int ga = a0 + i, gb = b0 + j;
                int x = lx * (1 - a[0] - b[0]) + ga * a[0] + gb * b[0];
                int y = ly * (1 - a[1] - b[1]) + ga * a[1] + gb * b[1];
                int z = lz * (1 - a[2] - b[2]) + ga * a[2] + gb * b[2];
                int seen = 0;
                for (double[] o : new double[][]{{-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}}) {
                    if (sees(w, light, blockSource, faceCentre(x, y, z, nx, ny, nz, o[0], o[1], a, b))) seen++;
                }
                frac[idx] = seen / 4.0;
            }
        }
        // shadow strength per cell in quarters (coarse steps merge into fewer, bigger rectangles)
        int[] lvl = new int[size * size];
        for (int idx = 0; idx < lvl.length; idx++) {
            if (!surf[idx]) continue;
            double s = (1 - frac[idx]) * wgt[idx] * shadows.strength();
            lvl[idx] = (int) Math.round(Math.min(1, s) * 4) * 2;
        }
        // greedy rectangles of equal strength
        List<Shadow> out = new ArrayList<>();
        boolean[] used = new boolean[lvl.length];
        Vector axisA = new Vector(a[0], a[1], a[2]);
        double planeLift = SHADOW_LIFT;
        double planeCoord = nx != 0 ? hp.getX() : ny != 0 ? hp.getY() : hp.getZ();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int idx = i * size + j;
                if (used[idx] || lvl[idx] == 0) continue;
                int L = lvl[idx];
                int jEnd = j;
                while (jEnd + 1 < size && !used[i * size + jEnd + 1] && lvl[i * size + jEnd + 1] == L) jEnd++;
                int iEnd = i;
                grow:
                while (iEnd + 1 < size) {
                    for (int jj = j; jj <= jEnd; jj++) {
                        int o = (iEnd + 1) * size + jj;
                        if (used[o] || lvl[o] != L) break grow;
                    }
                    iEnd++;
                }
                for (int ii = i; ii <= iEnd; ii++) for (int jj = j; jj <= jEnd; jj++) used[ii * size + jj] = true;
                double ma = a0 + (i + iEnd + 1) / 2.0, mb = b0 + (j + jEnd + 1) / 2.0;
                double px = nx != 0 ? planeCoord : ma * a[0] + mb * b[0];
                double py = ny != 0 ? planeCoord : ma * a[1] + mb * b[1];
                double pz = nz != 0 ? planeCoord : ma * a[2] + mb * b[2];
                out.add(new Shadow(px + nx * planeLift, py + ny * planeLift, pz + nz * planeLift, n, axisA,
                        iEnd - i + 1, jEnd - j + 1, L / 8.0));
            }
        }
        return out;
    }

    /** A point on the face of block (x,y,z) facing n, offset (u, v) from the face centre along axes a, b. */
    private static Vector faceCentre(int x, int y, int z, int nx, int ny, int nz, double u, double v, int[] a, int[] b) {
        return new Vector(x + 0.5 + nx * 0.52 + u * a[0] + v * b[0], y + 0.5 + ny * 0.52 + u * a[1] + v * b[1],
                z + 0.5 + nz * 0.52 + u * a[2] + v * b[2]);
    }

    /** Line of sight from a surface point to the light (stopping short of a lamp's own block). */
    private static boolean sees(World w, Vector light, boolean blockSource, Vector from) {
        Vector d = light.clone().subtract(from);
        double len = d.length() - (blockSource ? 0.55 : 0.05);
        if (len <= 0.01) return true;
        RayTraceResult hit = w.rayTraceBlocks(from.toLocation(w), d.normalize(), len, FluidCollisionMode.NEVER, true);
        return hit == null;
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
            // like point-light pools, a spot never hangs off the edge of the surface it landed on (a pillar side)
            double ext = extent(e.world(), s.hit(), s.normal(), Math.max(0.3, d * Math.tan(half)) * stretch);
            out.add(new Decal(e.key() + "#hot", Decal.Kind.HOTSPOT, h.getX(), h.getY(), h.getZ(),
                    flat(s.normal(), major, Math.min(2 * rh * stretch, 2 * ext), Math.min(2 * rh, 2 * ext)),
                    alpha * Math.pow(far, 0.6), false, interp));
            if (shadows.enabled() && shadows.flashlights()) {
                double rs = Math.min(ext, Math.max(0.3, d * Math.tan(half)) * stretch);
                int k = 0;
                for (Shadow sh : shadowCells(e.world(), s.origin(), false, s.hit(), s.normal(), rs,
                        r -> r < 0.45 ? 1.0 : 1 - smooth((r - 0.45) / 0.55))) {
                    out.add(new Decal(e.key() + "#sh" + k++, Decal.Kind.SHADOW, sh.x, sh.y, sh.z,
                            flat(sh.normal, sh.axis, sh.w, sh.h), alpha * Math.pow(far, 0.6) * sh.alpha, false, interp));
                }
            }
            if (spill) {
                double rs = Math.max(0.3, d * Math.tan(half));
                Vector h2 = s.hit().clone().add(s.normal().clone().multiply(SURFACE_OFFSET * 0.5));
                out.add(new Decal(e.key() + "#spill", Decal.Kind.SPILL, h2.getX(), h2.getY(), h2.getZ(),
                        flat(s.normal(), major, Math.min(2 * rs * stretch, 2 * ext), Math.min(2 * rs, 2 * ext)),
                        alpha * 0.8 * Math.pow(far, 0.8), false, interp));
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
