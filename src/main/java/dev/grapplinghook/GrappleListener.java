package dev.grapplinghook;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.CraftingRecipe;

public final class GrappleListener implements Listener {

    private final GrapplingHookPlugin plugin;

    public GrappleListener(GrapplingHookPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.hookItem().isHook(event.getItem())) {
            return;
        }

        // Clicking a chest/door/etc: let the block work normally, just never cast a fishing bobber.
        Block clicked = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null
                && clicked.getType().isInteractable() && !player.isSneaking()) {
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }

        event.setCancelled(true); // stops the vanilla fishing bobber

        if (!player.hasPermission("grapplinghook.use")) {
            player.sendActionBar(plugin.message("no-permission"));
            return;
        }
        plugin.grapples().toggle(player);
    }

    /** Safety net: the hook must never cast a vanilla bobber. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (plugin.hookItem().isHook(player.getInventory().getItemInMainHand())
                || plugin.hookItem().isHook(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && plugin.grapples().isGrappling(event.getPlayer())) {
            plugin.grapples().release(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && event.getEntity() instanceof Player player
                && plugin.grapples().hasFallProtection(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        plugin.grapples().forget(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        plugin.grapples().forget(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.grapples().forgetAll(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(plugin.hookItem().recipeKey());
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe instanceof CraftingRecipe crafting
                && crafting.getKey().equals(plugin.hookItem().recipeKey())
                && event.getView().getPlayer() instanceof Player player
                && !player.hasPermission("grapplinghook.craft")) {
            event.getInventory().setResult(null);
        }
    }
}
