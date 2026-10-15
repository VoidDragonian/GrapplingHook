package dev.grapplinghook;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Creates and identifies the Grappling Hook item and owns its crafting recipe. */
public final class HookItem {

    /** Model key; must match assets/grapplinghook/items/grappling_hook.json in the resource pack. */
    private static final Key MODEL_KEY = Key.key("grapplinghook", "grappling_hook");

    private final GrapplingHookPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey recipeKey;

    public HookItem(GrapplingHookPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "grappling_hook");
        this.recipeKey = new NamespacedKey(plugin, "grappling_hook_recipe");
    }

    public NamespacedKey recipeKey() {
        return recipeKey;
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Grappling Hook", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to latch onto a block", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("and swing yourself toward it.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Range: 50 blocks  |  Sneak to let go", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        if (plugin.getConfig().getBoolean("durability.enabled", true)
                && meta instanceof Damageable damageable) {
            damageable.setMaxDamage(Math.max(1, plugin.getConfig().getInt("durability.max-uses", 256)));
        }
        item.setItemMeta(meta);

        if (plugin.getConfig().getBoolean("use-resource-pack-model", true)) {
            item.setData(DataComponentTypes.ITEM_MODEL, MODEL_KEY);
        }
        return item;
    }

    public boolean isHook(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /**
     * Recipe:
     * <pre>
     *  Iron   Redstone  Iron
     *  String Dispenser String
     *  -      String    -
     * </pre>
     */
    public ShapedRecipe buildRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, create());
        recipe.shape("IRI", "SDS", " S ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('D', Material.DISPENSER);
        recipe.setCategory(CraftingBookCategory.EQUIPMENT);
        return recipe;
    }
}
