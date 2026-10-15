package dev.grapplinghook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GrapplingHookPlugin extends JavaPlugin implements TabExecutor {

    private HookItem hookItem;
    private GrappleManager grapples;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        hookItem = new HookItem(this);
        grapples = new GrappleManager(this);

        if (Bukkit.addRecipe(hookItem.buildRecipe())) {
            getLogger().info("Crafting recipe registered (Iron/Redstone/Iron, String/Dispenser/String, _/String/_).");
        } else {
            getLogger().severe("Crafting recipe FAILED to register. Use /ghook give instead and report this.");
        }
        getServer().getPluginManager().registerEvents(new GrappleListener(this), this);
        grapples.start();

        var cmd = getCommand("grapplinghook");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Players already online (e.g. after /reload) should see the recipe too.
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.discoverRecipe(hookItem.recipeKey());
        }
        getLogger().info("GrapplingHook enabled (max range " + (int) GrappleManager.HARD_MAX_DISTANCE + " blocks).");
    }

    @Override
    public void onDisable() {
        if (grapples != null) {
            grapples.shutdown();
        }
        if (hookItem != null) {
            Bukkit.removeRecipe(hookItem.recipeKey());
        }
    }

    public HookItem hookItem() {
        return hookItem;
    }

    public GrappleManager grapples() {
        return grapples;
    }

    public Component message(String key) {
        String raw = getConfig().getString("messages." + key, "");
        return MiniMessage.miniMessage().deserialize(raw);
    }

    /** Message with one {placeholder} substitution (done before MiniMessage parsing). */
    public Component message(String key, String placeholder, String value) {
        String raw = getConfig().getString("messages." + key, "").replace(placeholder, value);
        return MiniMessage.miniMessage().deserialize(raw);
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /" + label + " <give [player]|reload>"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                grapples.reload();
                sender.sendMessage(message("reloaded"));
            }
            case "give" -> {
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Component.text("Player not found: " + args[1]));
                        return true;
                    }
                } else if (sender instanceof Player self) {
                    target = self;
                } else {
                    sender.sendMessage(Component.text("Console must specify a player."));
                    return true;
                }
                Map<Integer, ItemStack> leftover = new HashMap<>(target.getInventory().addItem(hookItem.create()));
                leftover.values().forEach(i -> target.getWorld().dropItem(target.getLocation(), i));
                target.sendMessage(message("given"));
                if (sender != target) {
                    sender.sendMessage(Component.text("Gave a Grappling Hook to " + target.getName() + "."));
                }
            }
            default -> sender.sendMessage(Component.text("Usage: /" + label + " <give [player]|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("give", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
