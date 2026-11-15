package fi.alavesa.lighting.effect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Turns the colour pass (post effect lighting:colored_lights) on per player via Paper's {@code Player.postEffects()},
 * which exists from Minecraft 26.3. Bound by reflection so the jar also loads on 26.2, where lights are vanilla
 * light + particles only.
 *
 * <p>When that API cannot be bound (a Paper build that renamed it) but the vanilla {@code /posteffect} command exists,
 * the console runs {@code posteffect add|remove <player> lighting:colored_lights} instead.
 *
 * <p>Other plugins (CCTV, Firearms) keep their own effects in the same list; ours is kept FIRST so their screen
 * looks grade the already-lit frame, and only our id is ever added or removed.
 */
public final class LightEffect {

    public static final Key ID = Key.key("lighting", "colored_lights");

    private static final @Nullable Method POST_EFFECTS;
    private static final @Nullable Method VALUES;
    private static final @Nullable Method SET;

    static {
        Method pe = null, values = null, set = null;
        try {
            pe = Player.class.getMethod("postEffects");
            Class<?> api = Class.forName("io.papermc.paper.entity.PlayerPostEffects");
            values = api.getMethod("values");
            set = api.getMethod("set", java.util.SequencedCollection.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            pe = null;
        }
        POST_EFFECTS = pe;
        VALUES = values;
        SET = set;
    }

    private LightEffect() {
    }

    private static final java.util.Set<java.util.UUID> BY_COMMAND = new java.util.HashSet<>();

    public static boolean available() {
        return POST_EFFECTS != null || commandAvailable();
    }

    /** "paper" (Player.postEffects), "command" (/posteffect) or "none" (Minecraft 26.2). */
    public static String backend() {
        return POST_EFFECTS != null ? "paper" : commandAvailable() ? "command" : "none";
    }

    private static boolean commandAvailable() {
        try {
            return org.bukkit.Bukkit.getCommandMap().getCommand("minecraft:posteffect") != null
                    || org.bukkit.Bukkit.getCommandMap().getCommand("posteffect") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void command(String op, Player p) {
        org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "minecraft:posteffect " + op + " " + p.getName() + " " + ID.asString());
    }

    /** The player left: the command path re-adds the effect on the next join. */
    public static void forget(Player p) {
        BY_COMMAND.remove(p.getUniqueId());
    }

    public static boolean has(Player p) {
        if (POST_EFFECTS == null) return BY_COMMAND.contains(p.getUniqueId());
        return values(p).contains(ID);
    }

    public static void enable(Player p) {
        if (POST_EFFECTS == null) {
            if (commandAvailable() && BY_COMMAND.add(p.getUniqueId())) command("add", p);
            return;
        }
        List<Key> current = values(p);
        if (!current.isEmpty() && current.getFirst().equals(ID)) return;
        List<Key> next = new ArrayList<>(current);
        next.remove(ID);
        next.addFirst(ID);
        set(p, next);
    }

    public static void disable(Player p) {
        if (POST_EFFECTS == null) {
            if (BY_COMMAND.remove(p.getUniqueId()) && p.isOnline()) command("remove", p);
            return;
        }
        List<Key> current = values(p);
        if (!current.contains(ID)) return;
        List<Key> next = new ArrayList<>(current);
        next.remove(ID);
        set(p, next);
    }

    @SuppressWarnings("unchecked")
    private static List<Key> values(Player p) {
        if (POST_EFFECTS == null) return List.of();
        try {
            return (List<Key>) VALUES.invoke(POST_EFFECTS.invoke(p));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    private static void set(Player p, List<Key> keys) {
        if (POST_EFFECTS == null) return;
        try {
            SET.invoke(POST_EFFECTS.invoke(p), keys);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // lights still work as vanilla light + particles
        }
    }
}
