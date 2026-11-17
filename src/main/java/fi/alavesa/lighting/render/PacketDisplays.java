package fi.alavesa.lighting.render;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

/**
 * Client-only item_display entities, made of packets: nothing is added to the world, the server never ticks or
 * tracks them, and only the one player they were sent to sees them. Everything goes through reflection on the
 * server's own classes (26.x is unobfuscated), so the plugin still compiles against the API and loads on 26.2 and
 * 26.3. Adapted from Firearms' NmsDisplay, which is client-verified on 26.3.
 *
 * <p>Each entity object here exists only to build packets: its SynchedEntityData tracks what changed since the
 * last send, so {@link #updatePacket} carries exactly the changed fields. {@link #sendAll} sends a list of packets
 * as ONE ClientboundBundlePacket, so the client applies them in the same frame (no half-updated decals).
 */
final class PacketDisplays {

    private static boolean ok;
    private static String problem = "not initialised";
    private static Constructor<?> itemDisplayCtor, addEntityCtor, setDataCtor, removeCtor, transformationCtor, bundleCtor;
    private static Method getUuid, getType, setTransformation, setInterpDuration, setInterpDelay, setBillboard,
            setViewRange, setItemStack, setItemTransform, setBrightness, setShadowRadius, getId, getEntityData,
            nonDefaultValues, packDirty, setPos, asNms, worldHandle, playerHandle, sendMethod;
    private static Field connectionField;
    // moving a part (0.5): billboards turn around their anchor, so a moving billboard has to move its entity
    private static Constructor<?> teleportCtor, pmrCtor, vec3Ctor;
    private static Object posRotDuration;
    private static Method dataSet;
    private static Object itemDisplayType, billboardFixed, billboardCenter, contextNone, fullBright, vec3Zero;

    static {
        try {
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> display = Class.forName("net.minecraft.world.entity.Display");
            Class<?> itemDisplay = Class.forName("net.minecraft.world.entity.Display$ItemDisplay");
            Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
            Class<?> level = Class.forName("net.minecraft.world.level.Level");
            Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> transformation = Class.forName("com.mojang.math.Transformation");
            Class<?> billboard = Class.forName("net.minecraft.world.entity.Display$BillboardConstraints");
            Class<?> context = Class.forName("net.minecraft.world.item.ItemDisplayContext");
            Class<?> brightness = Class.forName("net.minecraft.util.Brightness");
            Class<?> synched = Class.forName("net.minecraft.network.syncher.SynchedEntityData");
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            // 26.3 moved the vanilla entity-type constants to EntityTypes; 26.2 keeps them on EntityType
            for (String holder : new String[]{"net.minecraft.world.entity.EntityTypes", "net.minecraft.world.entity.EntityType"}) {
                if (itemDisplayType != null) break;
                try {
                    itemDisplayType = Class.forName(holder).getField("ITEM_DISPLAY").get(null);
                } catch (ReflectiveOperationException ignored) {
                    // try the next holder
                }
            }
            if (itemDisplayType == null) throw new IllegalStateException("EntityType ITEM_DISPLAY not found");
            itemDisplayCtor = itemDisplay.getConstructor(entityType, level);
            billboardFixed = billboard.getField("FIXED").get(null);
            billboardCenter = billboard.getField("CENTER").get(null);
            contextNone = context.getField("NONE").get(null);
            fullBright = brightness.getField("FULL_BRIGHT").get(null);
            vec3Zero = vec3.getField("ZERO").get(null);
            setTransformation = display.getMethod("setTransformation", transformation);
            setInterpDuration = display.getMethod("setTransformationInterpolationDuration", int.class);
            setInterpDelay = display.getMethod("setTransformationInterpolationDelay", int.class);
            setBillboard = display.getMethod("setBillboardConstraints", billboard);
            setViewRange = display.getMethod("setViewRange", float.class);
            setBrightness = display.getMethod("setBrightnessOverride", brightness);
            setShadowRadius = display.getMethod("setShadowRadius", float.class);
            setItemStack = itemDisplay.getMethod("setItemStack", nmsStack);
            setItemTransform = itemDisplay.getMethod("setItemTransform", context);
            getId = entity.getMethod("getId");
            getUuid = entity.getMethod("getUUID");
            getType = entity.getMethod("getType");
            getEntityData = entity.getMethod("getEntityData");
            setPos = entity.getMethod("setPos", double.class, double.class, double.class);
            nonDefaultValues = synched.getMethod("getNonDefaultValues");
            packDirty = synched.getMethod("packDirty");
            transformationCtor = transformation.getConstructor(Class.forName("org.joml.Matrix4fc"));
            // the explicit-position constructor: the (Entity, int, BlockPos) one would put the entity at 0,0,0
            addEntityCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
                    .getConstructor(int.class, java.util.UUID.class, double.class, double.class, double.class, float.class,
                            float.class, entityType, int.class, vec3, double.class);
            setDataCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                    .getConstructor(int.class, List.class);
            removeCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
                    .getConstructor(int[].class);
            try {
                bundleCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket")
                        .getConstructor(Iterable.class);
            } catch (ReflectiveOperationException ignored) {
                bundleCtor = null; // packets then go one by one
            }
            try {
                Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
                pmrCtor = pmr.getConstructor(vec3, vec3, float.class, float.class);
                vec3Ctor = vec3.getConstructor(double.class, double.class, double.class);
                teleportCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket")
                        .getConstructor(int.class, pmr, java.util.Set.class, boolean.class);
                posRotDuration = display.getField("DATA_POS_ROT_INTERPOLATION_DURATION_ID").get(null);
                dataSet = synched.getMethod("set", Class.forName("net.minecraft.network.syncher.EntityDataAccessor"), Object.class);
            } catch (ReflectiveOperationException ignored) {
                teleportCtor = null; // moving billboards are then re-spawned instead
            }
            asNms = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
            connectionField = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
            ok = true;
            problem = "";
        } catch (Throwable t) {
            ok = false;
            problem = t.toString();
        }
    }

    private PacketDisplays() {
    }

    static boolean available() {
        return ok;
    }

    static String problem() {
        return problem;
    }

    static boolean bundles() {
        return bundleCtor != null;
    }

    /** One client-side item_display. {@code x,y,z} is its fixed anchor; the decal's real place is in its matrix. */
    static final class Part {
        final Object entity;
        final int id;
        double x, y, z;

        Part(Object entity, int id, double x, double y, double z) {
            this.entity = entity;
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static Part create(Player viewer, double x, double y, double z) throws ReflectiveOperationException {
        Object world = viewer.getWorld();
        if (worldHandle == null) worldHandle = world.getClass().getMethod("getHandle");
        Object e = itemDisplayCtor.newInstance(itemDisplayType, worldHandle.invoke(world));
        setItemTransform.invoke(e, contextNone);
        setBrightness.invoke(e, fullBright);
        setShadowRadius.invoke(e, 0f);
        setViewRange.invoke(e, 4f);
        setBillboard.invoke(e, billboardFixed);
        // width/height stay 0: the client never frustum-culls it (a decal is far bigger than an entity box)
        setPos.invoke(e, x, y, z);
        return new Part(e, (Integer) getId.invoke(e), x, y, z);
    }

    static boolean canMove() {
        return teleportCtor != null;
    }

    /** Move the anchor (teleport packet); the client glides there over {@code interpTicks}. Pending metadata goes first,
     *  so the glide duration is set before the move arrives. */
    static void move(Part p, double x, double y, double z, int interpTicks, List<Object> out) throws ReflectiveOperationException {
        dataSet.invoke(getEntityData.invoke(p.entity), posRotDuration, Math.max(0, interpTicks));
        updatePackets(p, out);
        out.add(teleportCtor.newInstance(p.id, pmrCtor.newInstance(vec3Ctor.newInstance(x, y, z), vec3Zero, 0f, 0f),
                java.util.Set.of(), false));
        setPos.invoke(p.entity, x, y, z);
        p.x = x;
        p.y = y;
        p.z = z;
    }

    static void item(Part p, ItemStack stack) throws ReflectiveOperationException {
        setItemStack.invoke(p.entity, asNms.invoke(null, stack));
    }

    static void billboard(Part p, boolean center) throws ReflectiveOperationException {
        setBillboard.invoke(p.entity, center ? billboardCenter : billboardFixed);
    }

    /** The transformation (relative to the anchor) and how many ticks the client blends into it. */
    static void transform(Part p, Matrix4f m, int interpTicks) throws ReflectiveOperationException {
        setTransformation.invoke(p.entity, transformationCtor.newInstance(new Matrix4f(m)));
        setInterpDuration.invoke(p.entity, interpTicks);
        setInterpDelay.invoke(p.entity, 0);
    }

    /** Spawn packet + all its metadata, for a bundle: the client creates and configures it in one go. */
    static void spawnPackets(Part p, List<Object> out) throws ReflectiveOperationException {
        out.add(addEntityCtor.newInstance(p.id, getUuid.invoke(p.entity), p.x, p.y, p.z, 0f, 0f, getType.invoke(p.entity),
                0, vec3Zero, 0.0));
        Object data = getEntityData.invoke(p.entity);
        List<?> values = (List<?>) nonDefaultValues.invoke(data);
        if (values != null && !values.isEmpty()) out.add(setDataCtor.newInstance(p.id, values));
        packDirty.invoke(data); // those are sent now
    }

    /** Metadata packet with what changed since the last send, or nothing. */
    static void updatePackets(Part p, List<Object> out) throws ReflectiveOperationException {
        List<?> values = (List<?>) packDirty.invoke(getEntityData.invoke(p.entity));
        if (values != null && !values.isEmpty()) out.add(setDataCtor.newInstance(p.id, values));
    }

    static void removePacket(int[] ids, List<Object> out) throws ReflectiveOperationException {
        if (ids.length > 0) out.add(removeCtor.newInstance((Object) ids));
    }

    /** Sends the packets as one bundle (the client applies them together), or one by one without bundle support. */
    static void sendAll(Player viewer, List<Object> packets) throws ReflectiveOperationException {
        if (packets.isEmpty()) return;
        if (playerHandle == null) playerHandle = viewer.getClass().getMethod("getHandle");
        Object conn = connectionField.get(playerHandle.invoke(viewer));
        if (sendMethod == null) {
            Class<?> base = Class.forName("net.minecraft.network.protocol.Packet");
            for (Class<?> c = conn.getClass(); c != null && sendMethod == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(base)) {
                        m.setAccessible(true);
                        sendMethod = m;
                        break;
                    }
                }
            }
        }
        if (packets.size() > 1 && bundleCtor != null) {
            // a bundle holds at most 4096 packets
            for (int i = 0; i < packets.size(); i += 4000) {
                sendMethod.invoke(conn, bundleCtor.newInstance(packets.subList(i, Math.min(packets.size(), i + 4000))));
            }
            return;
        }
        for (Object pk : packets) sendMethod.invoke(conn, pk);
    }
}
