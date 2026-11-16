package fi.alavesa.lighting;

import fi.alavesa.lighting.light.LightRegistry;
import fi.alavesa.lighting.light.LightSource;
import fi.alavesa.lighting.render.Anim;
import fi.alavesa.lighting.render.Palette;
import fi.alavesa.lighting.render.Quality;
import fi.alavesa.lighting.wand.LightDialogs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * /lighting give | create | list | info | remove | tp | removeall | settings | diagnose | pack | reload.
 * settings and diagnose (for yourself) are for everyone; the rest is op.
 */
public final class LightingCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("settings", "diagnose", "give", "create", "list", "info", "remove",
            "tp", "removeall", "pack", "reload");

    private final LightingPlugin plugin;

    LightingCommand(LightingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(s, label);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!s.hasPermission("lighting.command." + sub) && !s.hasPermission("lighting.admin")) {
            s.sendMessage(LightDialogs.mm("<red>No permission."));
            return true;
        }
        LightRegistry reg = plugin.registry();
        switch (sub) {
            case "settings" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(LightDialogs.mm("<red>Players only."));
                    return true;
                }
                if (a.length >= 3) {
                    if (plugin.ui().setting(p, a[1], a[2])) {
                        s.sendMessage(LightDialogs.mm("<gold>Lighting</gold> <gray>» saved: "
                                + PlayerUi.summary(plugin.engine().viewer(p).settings)));
                    } else {
                        s.sendMessage(LightDialogs.mm("<red>Usage: /" + label + " settings [enabled|quality|animations|palette|flashlight] <value>"));
                    }
                } else {
                    plugin.ui().openSettings(p);
                }
            }
            case "diagnose" -> {
                Player target = a.length > 1 && s.hasPermission("lighting.admin") ? Bukkit.getPlayerExact(a[1])
                        : s instanceof Player p ? p : null;
                if (target == null) {
                    s.sendMessage(LightDialogs.mm("<red>Player not found."));
                    return true;
                }
                plugin.ui().diagnose(s, target);
            }
            case "give" -> {
                // /lighting give [wand|flashlight|headlamp] [player]
                String what = a.length > 1 && List.of("wand", "flashlight", "headlamp").contains(a[1].toLowerCase(Locale.ROOT))
                        ? a[1].toLowerCase(Locale.ROOT) : "wand";
                int pi = a.length > 1 && what.equals(a[1].toLowerCase(Locale.ROOT)) ? 2 : 1;
                Player target = a.length > pi ? Bukkit.getPlayerExact(a[pi]) : s instanceof Player p ? p : null;
                if (target == null) {
                    s.sendMessage(LightDialogs.mm("<red>Player not found."));
                    return true;
                }
                ItemStack it = switch (what) {
                    case "flashlight" -> plugin.wand().flashlight(plugin.flashlights().tag());
                    case "headlamp" -> plugin.wand().headlamp(plugin.flashlights().tag());
                    default -> plugin.wand().create();
                };
                target.getInventory().addItem(it);
                s.sendMessage(LightDialogs.mm("<gray>Gave a " + what + " to <white>" + target.getName()));
            }
            case "create" -> {
                // /lighting create <world> <x> <y> <z> <#hex> <radius> [intensity] [animation]
                if (a.length < 7) {
                    s.sendMessage(LightDialogs.mm("<red>Usage: /" + label
                            + " create <world> <x> <y> <z> <#hex> <radius> [intensity] [none|pulse|flicker|strobe]"));
                    return true;
                }
                World w = Bukkit.getWorld(a[1]);
                int rgb = LightingConfig.parseColor(a[5], -1);
                if (w == null || rgb < 0) {
                    s.sendMessage(LightDialogs.mm("<red>Unknown world or colour."));
                    return true;
                }
                Anim anim = a.length > 8 ? Anim.parse(a[8]) : Anim.NONE;
                Location at = new Location(w, parse(a[2], 0), parse(a[3], 0), parse(a[4], 0));
                LightSource l = reg.create(at, rgb, parse(a[6], 6), a.length > 7 ? parse(a[7], 1) : 1, true,
                        anim == null ? Anim.NONE : anim, null, 40, null);
                if (l == null) s.sendMessage(LightDialogs.mm("<red>That world is full (lights.max-per-world)."));
                else {
                    plugin.vfx().placed(at, l.radius(), l.rgb());
                    s.sendMessage(line(l));
                }
            }
            case "list" -> {
                List<LightSource> list;
                if (s instanceof Player p && !(a.length > 1 && a[1].equalsIgnoreCase("all"))) {
                    double within = a.length > 1 ? parse(a[1], 64) : 64;
                    list = reg.near(p.getLocation(), within);
                    s.sendMessage(LightDialogs.mm("<gold>" + list.size() + " lights within " + (int) within
                            + " blocks <gray>(of " + reg.all().size() + ")"));
                } else {
                    list = new ArrayList<>(reg.all());
                    s.sendMessage(LightDialogs.mm("<gold>" + list.size() + " lights"));
                }
                for (LightSource l : list.subList(0, Math.min(20, list.size()))) s.sendMessage(line(l));
                if (list.size() > 20) s.sendMessage(LightDialogs.mm("<dark_gray>... " + (list.size() - 20) + " more"));
            }
            case "info" -> {
                LightSource l = pick(s, a);
                if (l == null) return true;
                s.sendMessage(line(l));
                s.sendMessage(LightDialogs.mm("<gray>  intensity <white>" + LightDialogs.fmt(l.intensity())
                        + "</white>, animation <white>" + l.anim().name().toLowerCase(Locale.ROOT)
                        + "</white>, light block <white>" + l.vanillaBlock() + "</white>, owner <white>"
                        + (l.owner() == null ? "-" : Bukkit.getOfflinePlayer(l.owner()).getName())));
            }
            case "remove" -> {
                LightSource l = pick(s, a);
                if (l == null) return true;
                Location at = l.location();
                reg.remove(l);
                if (at != null) plugin.vfx().removed(at, l.rgb());
                s.sendMessage(LightDialogs.mm("<gray>Removed light <white>" + l.shortId()));
            }
            case "tp" -> {
                LightSource l = pick(s, a);
                if (l == null || !(s instanceof Player p)) return true;
                Location at = l.location();
                if (at != null) p.teleport(at.add(0, 0.5, 0).setDirection(p.getLocation().getDirection()));
            }
            case "removeall" -> {
                World w = a.length > 1 ? Bukkit.getWorld(a[1]) : s instanceof Player p ? p.getWorld() : null;
                if (w == null) {
                    s.sendMessage(LightDialogs.mm("<red>Usage: /" + label + " removeall <world> confirm"));
                    return true;
                }
                if (!(a.length > 2 && a[2].equalsIgnoreCase("confirm"))) {
                    s.sendMessage(LightDialogs.mm("<yellow>This removes all " + reg.count(w) + " lights in " + w.getName()
                            + ". Run <white>/" + label + " removeall " + w.getName() + " confirm</white>."));
                    return true;
                }
                int n = 0;
                for (LightSource l : new ArrayList<>(reg.all())) {
                    if (l.worldId.equals(w.getUID())) {
                        reg.remove(l);
                        n++;
                    }
                }
                s.sendMessage(LightDialogs.mm("<gray>Removed " + n + " lights from " + w.getName()));
            }
            case "pack" -> {
                if (a.length > 1) {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t != null) {
                        plugin.pack().send(t);
                        s.sendMessage(LightDialogs.mm("<gray>Pack sent to <white>" + t.getName()));
                    }
                    return true;
                }
                s.sendMessage(LightDialogs.mm("<gray>pack sha1 <white>" + plugin.pack().sha1() + "</white>, delivery <white>"
                        + plugin.pack().delivery()));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    s.sendMessage(LightDialogs.mm("<gray>  " + p.getName() + ": <white>" + plugin.pack().status(p)
                            + "</white> " + PlayerUi.summary(plugin.engine().viewer(p).settings)));
                }
            }
            case "reload" -> {
                plugin.reloadAll();
                s.sendMessage(LightDialogs.mm("<gray>Lighting reloaded."));
            }
            default -> help(s, label);
        }
        return true;
    }

    private @Nullable LightSource pick(CommandSender s, String[] a) {
        LightRegistry reg = plugin.registry();
        LightSource l = null;
        if (a.length > 1) l = reg.find(a[1]);
        else if (s instanceof Player p) {
            l = reg.raycast(p.getEyeLocation(), 32, 1.0);
            if (l == null) {
                List<LightSource> near = reg.near(p.getLocation(), 16);
                l = near.isEmpty() ? null : near.getFirst();
            }
        }
        if (l == null) s.sendMessage(LightDialogs.mm("<red>No light found (give an id, or stand near / aim at one)."));
        return l;
    }

    private static net.kyori.adventure.text.Component line(LightSource l) {
        World w = l.world();
        return LightDialogs.mm("<gray> <white>" + l.shortId() + "</white> " + LightDialogs.swatch(l.rgb()) + " r "
                + LightDialogs.fmt(l.radius()) + " <dark_gray>" + (w == null ? "?" : w.getName())
                + String.format(Locale.ROOT, " %.1f %.1f %.1f", l.x(), l.y(), l.z())
                + (l.spotDir() != null ? " spot" : "") + (l.anim() != Anim.NONE ? " " + l.anim().name().toLowerCase(Locale.ROOT) : ""));
    }

    private static double parse(String v, double fallback) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void help(CommandSender s, String label) {
        s.sendMessage(LightDialogs.mm("<gold>Lighting <gray>/" + label + " settings | diagnose [player] | give [wand|flashlight|headlamp] [player] | "
                + "create <world> <x> <y> <z> <#hex> <radius> [intensity] [animation] | list [radius|all] | info [id] | "
                + "remove [id] | tp <id> | removeall <world> confirm | pack [player] | reload"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) {
            return SUBS.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT)))
                    .filter(x -> s.hasPermission("lighting.command." + x) || s.hasPermission("lighting.admin")).toList();
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            return switch (sub) {
                case "give" -> List.of("wand", "flashlight", "headlamp");
                case "pack", "diagnose" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                case "info", "remove", "tp" -> plugin.registry().all().stream().map(LightSource::shortId).toList();
                case "create", "removeall" -> Bukkit.getWorlds().stream().map(World::getName).toList();
                case "settings" -> List.of("enabled", "quality", "animations", "palette", "flashlight");
                default -> List.of();
            };
        }
        if (a.length == 3) {
            if (sub.equals("give")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            if (sub.equals("settings")) {
                return switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "quality" -> Arrays.stream(Quality.values()).map(q -> q.name().toLowerCase(Locale.ROOT)).toList();
                    case "palette" -> Arrays.stream(Palette.values()).map(q -> q.name().toLowerCase(Locale.ROOT)).toList();
                    default -> List.of("on", "off");
                };
            }
        }
        return List.of();
    }
}
