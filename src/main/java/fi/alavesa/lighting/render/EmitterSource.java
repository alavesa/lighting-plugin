package fi.alavesa.lighting.render;

import java.util.List;
import org.bukkit.World;

/** Something that produces lights. Called once per render tick on the main thread; results are shared by all viewers. */
public interface EmitterSource {

    String name();

    /** Adds this source's current lights in {@code world} near any of the given points (x, y, z triples). */
    void collect(World world, double[] viewers, double range, long tick, List<Emitter> out);
}
