package fi.alavesa.lighting;

import fi.alavesa.lighting.effect.LightEffect;
import fi.alavesa.lighting.light.LightRegistry;
import fi.alavesa.lighting.light.LightSource;
import fi.alavesa.lighting.pack.PackService;
import fi.alavesa.lighting.wand.LightDialogs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** /lighting give | list | info | remove | tp | removeall | shader | pack | reload */
public final class LightingCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("give", "create", "list", "info", "remove", "tp", "removeall", "shader",
            "pack", "reload");

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
            case "give" -> {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
                if (target == null) {
                    s.sendMessage(LightDialogs.mm("<red>Player not found."));
                    return true;
                }
                target.getInventory().addItem(plugin.wand().create());
                s.sendMessage(LightDialogs.mm("<gray>Gave a Lighting Wand to <white>" + target.getName()));
            }
            case "create" -> {
                // /lighting create <world> <x> <y> <z> <#hex> <radius> [intensity]  (console and command blocks too)
                if (a.length < 7) {
                    s.sendMessage(LightDialogs.mm("<red>Usage: /" + label + " create <world> <x> <y> <z> <#hex> <radius> [intensity]"));
                    return true;
                }
                World w = Bukkit.getWorld(a[1]);
                int rgb = LightingConfig.parseColor(a[5], -1);
                if (w == null || rgb < 0) {
                    s.sendMessage(LightDialogs.mm("<red>Unknown world or colour."));
                    return true;
                }
                Location at = new Location(w, parse(a[2], 0), parse(a[3], 0), parse(a[4], 0));
                LightSource l = reg.create(at, rgb, parse(a[6], 6), a.length > 7 ? parse(a[7], 1) : 1, true, null);
                if (l == null) s.sendMessage(LightDialogs.mm("<red>That world is full (max-per-world)."));
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
                        + "</white>, vanilla light block <white>" + l.vanillaBlock() + "</white>, owner <white>"
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
            case "shader" -> {
                if (!(s instanceof Player p)) return true;
                if (!LightEffect.available()) {
                    s.sendMessage(LightDialogs.mm("<yellow>This server has no per-player post effects (needs Minecraft 26.3)."
                            + " Lights show as vanilla light + particles."));
                    return true;
                }
                boolean on = a.length > 1 ? a[1].equalsIgnoreCase("on") : !LightEffect.has(p);
                if (on) LightEffect.enable(p);
                else LightEffect.disable(p);
                s.sendMessage(LightDialogs.mm("<gray>Coloured light shading <white>" + (on ? "on" : "off")));
            }
            case "pack" -> {
                PackService pack = plugin.pack();
                if (a.length > 1) {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t != null) {
                        pack.send(t);
                        s.sendMessage(LightDialogs.mm("<gray>Pack sent to <white>" + t.getName()));
                    }
                    return true;
                }
                s.sendMessage(LightDialogs.mm("<gray>pack sha1 <white>" + pack.sha1() + "</white>, post effects "
                        + (LightEffect.available() ? "<green>available" : "<yellow>not available (26.2)")));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    s.sendMessage(LightDialogs.mm("<gray>  " + p.getName() + ": <white>" + pack.status(p)
                            + "</white> shading " + (LightEffect.has(p) ? "<green>on" : "<red>off")));
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
                + String.format(Locale.ROOT, " %.1f %.1f %.1f", l.x(), l.y(), l.z()) + " slot "
                + (l.slot() < 0 ? "-" : l.slot()));
    }

    private static double parse(String v, double fallback) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void help(CommandSender s, String label) {
        s.sendMessage(LightDialogs.mm("<gold>Lighting <gray>/" + label + " give [player] | create <world> <x> <y> <z> <#hex> <radius> [intensity] | list [radius|all] | info [id] | "
                + "remove [id] | tp <id> | removeall <world> confirm | shader [on|off] | pack [player] | reload"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return SUBS.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2) {
            return switch (a[0].toLowerCase(Locale.ROOT)) {
                case "give", "pack" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                case "info", "remove", "tp" -> plugin.registry().all().stream().map(LightSource::shortId).toList();
                case "create", "removeall" -> Bukkit.getWorlds().stream().map(World::getName).toList();
                case "shader" -> List.of("on", "off");
                default -> List.of();
            };
        }
        return List.of();
    }
}
