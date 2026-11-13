package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.ShopLinksService;
import me.geraldr12.integration.QuickShopAdapter;
import me.geraldr12.integration.ShopLookup;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** {@code /invest company shop link <companyId> | unlink | list}. */
public class ShopLinkCommand implements MainCommand.SubCommand {

    static final String PERMISSION = "stonks.command.company.shop";
    private static final String ADMIN_PERMISSION = "stonks.admin.reconcile";
    private static final int REACH = 6;
    private static final List<String> ACTIONS = List.of("link", "unlink", "list");

    private final Stonks plugin;

    public ShopLinkCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            tell(player, "You don't have permission to do that.");
            return true;
        }
        ShopLookup shops = plugin.getShopLookup();
        ShopLinksService links = plugin.getShopLinksService();
        if (shops == null || links == null || !shops.isAvailable()) {
            tell(player, "Chest shop integration is not available on this server.");
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase();
        switch (action) {
            case "link" -> link(player, args, shops, links);
            case "unlink" -> unlink(player, shops, links);
            case "list" -> list(player, links);
            default -> tell(player, "Usage: /invest company shop <link <companyId>|unlink|list>");
        }
        return true;
    }

    private void link(Player player, String[] args, ShopLookup shops, ShopLinksService links) {
        long companyId;
        try {
            companyId = Long.parseLong(args.length > 1 ? args[1] : "");
        } catch (NumberFormatException e) {
            tell(player, "Usage: /invest company shop link <companyId>");
            return;
        }
        Optional<QuickShopAdapter.ShopInfo> shop = targetShop(player, shops);
        if (shop.isEmpty()) {
            tell(player, "Look at one of your chest shops (within 6 blocks).");
            return;
        }
        if (!shop.get().owner().equals(player.getUniqueId())) {
            tell(player, "You don't own this chest shop.");
            return;
        }
        CompanyDao company = plugin.getCompaniesService().getCompanyById(companyId);
        if (company == null) {
            tell(player, "That company does not exist.");
            return;
        }
        if (company.isBankrupt()) {
            tell(player, "That company is bankrupt.");
            return;
        }
        if (!player.getUniqueId().toString().equals(company.getOwnerUuid())) {
            tell(player, "You don't own that company.");
            return;
        }
        long shopId = shop.get().id();
        switch (links.link(shopId, companyId, player.getUniqueId())) {
            case LINKED -> tell(player, "Chest shop #" + shopId + " is now linked to " + company.getName() + ".");
            case ALREADY_LINKED -> tell(player, "This chest shop is already linked to " + company.getName() + ".");
            case LINKED_ELSEWHERE -> {
                long current = links.companyOf(shopId).orElse(-1L);
                CompanyDao other = plugin.getCompaniesService().getCompanyById(current);
                String name = other == null ? "company #" + current : other.getName();
                tell(player, "This chest shop is already linked to " + name + ". Unlink it first.");
            }
        }
    }

    private void unlink(Player player, ShopLookup shops, ShopLinksService links) {
        Optional<QuickShopAdapter.ShopInfo> shop = targetShop(player, shops);
        if (shop.isEmpty()) {
            tell(player, "Look at one of your chest shops (within 6 blocks).");
            return;
        }
        long shopId = shop.get().id();
        if (links.linkOf(shopId).isEmpty()) {
            tell(player, "This chest shop is not linked to a company.");
            return;
        }
        if (!shop.get().owner().equals(player.getUniqueId()) && !player.hasPermission(ADMIN_PERMISSION)) {
            tell(player, "You don't own this chest shop.");
            return;
        }
        links.unlink(shopId);
        tell(player, "Chest shop #" + shopId + " is no longer linked.");
    }

    private void list(Player player, ShopLinksService links) {
        CompaniesService companies = plugin.getCompaniesService();
        String me = player.getUniqueId().toString();
        List<CompanyDao> mine = companies.getAllCompanies().stream()
                .filter(company -> company != null && me.equals(company.getOwnerUuid()))
                .collect(Collectors.toList());
        boolean any = false;
        for (CompanyDao company : mine) {
            for (ShopLinksService.ShopLink link : links.linksOf(company.getId())) {
                tell(player, "Shop #" + link.shopId() + " -> " + company.getName() + " (#" + company.getId() + ")");
                any = true;
            }
        }
        if (!any) tell(player, "You have no linked chest shops.");
    }

    private Optional<QuickShopAdapter.ShopInfo> targetShop(Player player, ShopLookup shops) {
        Block block = player.getTargetBlockExact(REACH);
        return block == null ? Optional.empty() : shops.shopAt(block.getLocation());
    }

    private void tell(Player player, String message) {
        player.sendMessage(plugin.getMessages().getPluginPrefix() + message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase();
            return ACTIONS.stream().filter(action -> action.startsWith(typed)).collect(Collectors.toList());
        }
        return List.of();
    }
}
