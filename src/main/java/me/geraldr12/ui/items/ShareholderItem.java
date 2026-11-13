package me.geraldr12.ui.items;

import java.util.List;
import java.util.UUID;
import me.geraldr12.Stonks;
import me.geraldr12.data.entities.PlayerEntity;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public class ShareholderItem extends MenuButton {
    private final OfflinePlayer shareholder;

    public ShareholderItem(UUID shareholderUuid, int sharesOwned) {
        this(Bukkit.getOfflinePlayer(shareholderUuid), sharesOwned);
    }

    private ShareholderItem(OfflinePlayer shareholder, int sharesOwned) {
        super(new MenuIcon(Material.PLAYER_HEAD,
                ChatColor.YELLOW + shareholderName(shareholder),
                List.of(ChatColor.GRAY + "Shares Owned: " + ChatColor.WHITE + sharesOwned)), null);
        this.shareholder = shareholder;
    }

    private static String shareholderName(OfflinePlayer shareholder) {
        String offlineName = shareholder.getName();
        if (offlineName != null) return offlineName;
        if (Bukkit.getPluginManager().getPlugin("Stonks") instanceof Stonks plugin) {
            PlayersRepository repository = (PlayersRepository) plugin.getPlayersService().getRepository();
            return repository.getById(shareholder.getUniqueId()).map(PlayerEntity::getName)
                    .filter(name -> !name.isBlank() && !name.equalsIgnoreCase("Unknown"))
                    .orElse("Unknown Investor");
        }
        return "Unknown Investor";
    }

    @Override
    public ItemStack createItemStack() {
        ItemStack item = super.createItemStack();
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(shareholder);
            item.setItemMeta(meta);
        }
        return item;
    }
}
