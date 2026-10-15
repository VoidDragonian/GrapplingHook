package dev.grapplinghook;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs every active grapple from ONE repeating task (not one task per player),
 * so cost scales with the number of players currently swinging, not with server size.
 */
public final class GrappleManager {

    /** Absolute upper bound; config can lower the range but never raise it past this. */
    public static final double HARD_MAX_DISTANCE = 50.0;

    private static final double BODY_CENTER_OFFSET = 0.9;

    private final GrapplingHookPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();
    private final Map<UUID, Integer> noFallUntilTick = new HashMap<>();
    private BukkitTask task;

    // cached config
    private double maxDistance;
    private int cooldownTicks;
    private double maxSpeed;
    private double minSpeed;
    private double acceleration;
    private double arrivalDistance;
    private int maxDurationTicks;
    private boolean releaseOnSwap;
    private double arrivalBoost;
    private int fallGraceTicks;
    private boolean ropeEnabled;
    private double ropeSpacing;
    private int ropeInterval;
    private Particle.DustOptions ropeDust;
    private boolean impactParticles;
    private boolean durabilityEnabled;
    private boolean denyGliding;
    private boolean denyRiding;
    private final Set<String> disabledWorlds = new HashSet<>();

    private static final class Session {
        final World world;
        final Vector anchor;      // where the player is pulled to
        final Vector hitPoint;    // where the rope visually ends
        final BlockFace face;
        final int startTick;
        Vector velocity;
        double lastCheckDistance = Double.MAX_VALUE;
        int stalls = 0;
        boolean breakOnEnd = false;

        Session(World world, Vector anchor, Vector hitPoint, BlockFace face, int startTick, Vector velocity) {
            this.world = world;
            this.anchor = anchor;
            this.hitPoint = hitPoint;
            this.face = face;
            this.startTick = startTick;
            this.velocity = velocity;
        }
    }

    public GrappleManager(GrapplingHookPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        maxDistance = Math.min(Math.max(c.getDouble("max-distance", 50.0), 1.0), HARD_MAX_DISTANCE);
        cooldownTicks = Math.max(0, c.getInt("cooldown-ticks", 20));
        maxSpeed = Math.max(0.1, c.getDouble("pull.max-speed", 1.15));
        minSpeed = Math.min(maxSpeed, Math.max(0.05, c.getDouble("pull.min-speed", 0.45)));
        acceleration = Math.min(1.0, Math.max(0.01, c.getDouble("pull.acceleration", 0.22)));
        arrivalDistance = Math.max(0.5, c.getDouble("pull.arrival-distance", 2.0));
        maxDurationTicks = Math.max(10, c.getInt("pull.max-duration-ticks", 70));
        releaseOnSwap = c.getBoolean("pull.release-on-swap", true);
        arrivalBoost = Math.max(0.0, c.getDouble("pull.arrival-boost", 0.30));
        fallGraceTicks = Math.max(0, c.getInt("fall-damage-grace-ticks", 40));
        ropeEnabled = c.getBoolean("rope.enabled", true);
        ropeSpacing = Math.max(0.5, c.getDouble("rope.spacing", 1.2));
        ropeInterval = Math.max(1, c.getInt("rope.update-interval-ticks", 2));
        ropeDust = new Particle.DustOptions(parseColor(c.getString("rope.color", "#B8864B")), 0.9f);
        impactParticles = c.getBoolean("effects.impact-particles", true);
        durabilityEnabled = c.getBoolean("durability.enabled", true);
        denyGliding = c.getBoolean("restrictions.deny-while-gliding", true);
        denyRiding = c.getBoolean("restrictions.deny-while-riding", true);
        disabledWorlds.clear();
        for (String w : c.getStringList("restrictions.disabled-worlds")) {
            disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        }
    }

    private static Color parseColor(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return Color.fromRGB(Integer.parseInt(h, 16) & 0xFFFFFF);
        } catch (RuntimeException e) {
            return Color.fromRGB(0xB8864B);
        }
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        sessions.clear();
        cooldownUntilMs.clear();
        noFallUntilTick.clear();
    }

    public boolean isGrappling(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** True if fall damage should be ignored for this player right now. */
    public boolean hasFallProtection(Player player) {
        Integer until = noFallUntilTick.get(player.getUniqueId());
        return until != null && Bukkit.getCurrentTick() <= until;
    }

    // ------------------------------------------------------------ player actions

    /** Right-click: start a grapple, or let go if already swinging. */
    public void toggle(Player player) {
        if (isGrappling(player)) {
            release(player);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (disabledWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            player.sendActionBar(plugin.message("disabled-world"));
            return;
        }
        if ((denyGliding && player.isGliding()) || (denyRiding && player.isInsideVehicle())) {
            player.sendActionBar(plugin.message("not-now"));
            return;
        }

        boolean bypassCooldown = player.hasPermission("grapplinghook.bypass.cooldown");
        long now = System.currentTimeMillis();
        Long until = cooldownUntilMs.get(player.getUniqueId());
        if (!bypassCooldown && until != null && now < until) {
            double seconds = (until - now) / 1000.0;
            player.sendActionBar(plugin.message("cooldown", "{seconds}", String.format(Locale.ROOT, "%.1f", seconds)));
            return;
        }

        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
                eye, eye.getDirection(), maxDistance, FluidCollisionMode.NEVER, true);

        if (hit == null || hit.getHitBlock() == null) {
            player.sendActionBar(plugin.message("no-anchor"));
            player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 0.5f, 0.6f);
            setCooldown(player, 10);
            return;
        }

        Vector hitPoint = hit.getHitPosition();
        BlockFace face = hit.getHitBlockFace();
        Vector anchor = hitPoint.clone();
        if (face != null) {
            // Aim slightly off the surface so the player's hitbox isn't pulled into the block.
            anchor.add(face.getDirection().multiply(0.6));
        }

        int nowTick = Bukkit.getCurrentTick();
        Session session = new Session(
                player.getWorld(), anchor, hitPoint, face, nowTick, player.getVelocity().clone());
        session.breakOnEnd = consumeUse(player);
        sessions.put(player.getUniqueId(), session);
        noFallUntilTick.put(player.getUniqueId(), nowTick + maxDurationTicks + fallGraceTicks);

        Location hitLoc = hitPoint.toLocation(player.getWorld());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 0.8f, 1.2f);
        player.getWorld().playSound(hitLoc, Sound.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.9f, 0.8f);
        if (impactParticles) {
            Block block = hit.getHitBlock();
            player.getWorld().spawnParticle(Particle.BLOCK, hitLoc, 14, 0.15, 0.15, 0.15, 0.0, block.getBlockData());
        }
    }

    /** Ends a grapple early (sneak / second right-click). Safe to call anytime. */
    public void release(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s != null) {
            end(player, s, false);
        }
    }

    /** Drop the session only (teleport/death). No sounds, no velocity changes. */
    public void forget(Player player) {
        sessions.remove(player.getUniqueId());
    }

    /** Drop all state for a player (quit). */
    public void forgetAll(UUID id) {
        sessions.remove(id);
        cooldownUntilMs.remove(id);
        noFallUntilTick.remove(id);
    }

    // ----------------------------------------------------------------- internals

    /**
     * Uses up one durability point. Returns true if this was the last use
     * (the item is removed once the swing ends, so the pull is not cut short).
     */
    private boolean consumeUse(Player player) {
        if (!durabilityEnabled || player.hasPermission("grapplinghook.bypass.durability")) {
            return false;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!plugin.hookItem().isHook(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return false;
        }
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
        int newDamage = damageable.getDamage() + 1;
        damageable.setDamage(newDamage);
        item.setItemMeta(damageable);
        return newDamage >= max;
    }

    private void end(Player player, Session s, boolean arrived) {
        noFallUntilTick.put(player.getUniqueId(), Bukkit.getCurrentTick() + fallGraceTicks);
        if (!player.hasPermission("grapplinghook.bypass.cooldown")) {
            setCooldown(player, cooldownTicks);
        }

        if (arrived) {
            Vector v = s.velocity.clone().multiply(0.5);
            boolean sideFace = s.face != null && s.face != BlockFace.UP && s.face != BlockFace.DOWN;
            if (sideFace && arrivalBoost > 0) {
                v.setY(Math.max(v.getY(), arrivalBoost));
            }
            player.setVelocity(v);
        }
        player.setFallDistance(0f);
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 0.6f, 1.4f);

        if (s.breakOnEnd && plugin.hookItem().isHook(player.getInventory().getItemInMainHand())) {
            player.getInventory().setItemInMainHand(null);
            // Sound id was renamed between versions; play both ids, the client ignores unknown ones.
            for (String id : new String[] {"item.break", "entity.item.break"}) {
                player.playSound(net.kyori.adventure.sound.Sound.sound(
                        net.kyori.adventure.key.Key.key(id),
                        net.kyori.adventure.sound.Sound.Source.PLAYER, 0.9f, 1.0f));
            }
            player.sendActionBar(plugin.message("broken"));
        }
    }

    private void setCooldown(Player player, int ticks) {
        if (ticks > 0) {
            cooldownUntilMs.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        } else {
            cooldownUntilMs.remove(player.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        int nowTick = Bukkit.getCurrentTick();

        if (nowTick % 100 == 0 && (!noFallUntilTick.isEmpty() || !cooldownUntilMs.isEmpty())) {
            noFallUntilTick.values().removeIf(t -> t < nowTick);
            long nowMs = System.currentTimeMillis();
            cooldownUntilMs.values().removeIf(t -> t < nowMs);
        }
        if (sessions.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Session s = entry.getValue();

            if (player == null || !player.isOnline() || player.isDead()
                    || !player.getWorld().equals(s.world)) {
                it.remove();
                continue;
            }
            if (releaseOnSwap && !s.breakOnEnd
                    && !plugin.hookItem().isHook(player.getInventory().getItemInMainHand())) {
                it.remove();
                end(player, s, false);
                continue;
            }

            int age = nowTick - s.startTick;
            Vector body = player.getLocation().toVector().add(new Vector(0, BODY_CENTER_OFFSET, 0));
            Vector toAnchor = s.anchor.clone().subtract(body);
            double dist = toAnchor.length();

            if (dist <= arrivalDistance) {
                it.remove();
                end(player, s, true);
                continue;
            }
            if (age > maxDurationTicks) {
                it.remove();
                end(player, s, false);
                continue;
            }

            // Stall detection: if we barely closed distance over ~10 ticks twice in a row, we're stuck.
            if (age > 0 && age % 10 == 0) {
                if (s.lastCheckDistance - dist < 0.3) {
                    s.stalls++;
                } else {
                    s.stalls = 0;
                }
                s.lastCheckDistance = dist;
                if (s.stalls >= 2) {
                    it.remove();
                    end(player, s, false);
                    continue;
                }
            }

            // Speed ramps up with distance, and eases off on approach for a smooth finish.
            double speed = Math.min(maxSpeed, minSpeed + dist * 0.08);
            speed = Math.min(speed, dist * 0.45 + 0.25);
            Vector desired = toAnchor.multiply(speed / dist);

            // Exponential smoothing = no jerky velocity changes.
            s.velocity = s.velocity.multiply(1.0 - acceleration).add(desired.multiply(acceleration));
            player.setVelocity(s.velocity);
            player.setFallDistance(0f);
            noFallUntilTick.put(player.getUniqueId(), nowTick + fallGraceTicks + 5);

            if (ropeEnabled && nowTick % ropeInterval == 0) {
                drawRope(player, s);
            }
        }
    }

    private void drawRope(Player player, Session s) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        // roughly where the player's hand is
        Vector start = eye.toVector().add(dir.clone().multiply(0.5)).add(new Vector(0, -0.35, 0));
        Vector delta = s.hitPoint.clone().subtract(start);
        double length = delta.length();
        if (length < 0.5) {
            return;
        }
        int steps = (int) Math.min(60, Math.ceil(length / ropeSpacing));
        Vector step = delta.multiply(1.0 / steps);
        Vector p = start.clone();
        World w = s.world;
        for (int i = 0; i <= steps; i++) {
            w.spawnParticle(Particle.DUST, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0, ropeDust);
            p.add(step);
        }
    }
}
