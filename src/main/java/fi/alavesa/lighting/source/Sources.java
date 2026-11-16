package fi.alavesa.lighting.source;

/** Small helpers shared by the sources. */
final class Sources {

    private Sources() {
    }

    /** True when (x, y, z) is within {@code range} of any viewer point (x, y, z triples). */
    static boolean near(double[] viewers, double x, double y, double z, double range) {
        double r2 = range * range;
        for (int i = 0; i < viewers.length; i += 3) {
            double dx = viewers[i] - x, dy = viewers[i + 1] - y, dz = viewers[i + 2] - z;
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }
}
