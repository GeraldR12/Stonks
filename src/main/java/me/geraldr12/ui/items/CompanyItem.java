package me.geraldr12.ui.items;

import me.geraldr12.Stonks;
import me.geraldr12.commands.BuyCommand;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.VisualizationUtils;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AutoUpdateItem;

import java.text.MessageFormat;
import java.util.List;

public class CompanyItem extends AutoUpdateItem {

    private final Stonks plugin;
    private final CompanyDao company;

    public CompanyItem(Stonks plugin, Messages messages, CompaniesService companiesService, int companyId) {
        super(20 * 5, () -> getItemProvider(companiesService.getCompanyById(companyId), messages));
        this.plugin = plugin;
        this.company = companiesService.getCompanyById(companyId);
    }

    public static ItemProvider getItemProvider(CompanyDao company, Messages messages) {
        if (company == null) return new ItemBuilder(Material.AIR);

        double marketCap = company.getCurrentSharePrice() * company.getTotalShares();
        double totalVariation = (company.getCurrentSharePrice() - company.getInitialSharePrice()) / company.getInitialSharePrice() * 100;
        double lastVariation = !company.getHistoric().isEmpty() ? company.getHistoric().peek().getVariation() * 100 : 0;

        ItemBuilder builder = new ItemBuilder(company.getIcon() != null ? company.getIcon() : Material.EMERALD)
                .setDisplayName(ChatColor.GOLD + company.getName() + (!company.isBankrupt() ? MessageFormat.format(messages.getUiCompanyItemLastVariation(), VisualizationUtils.formatCompanyVariation(lastVariation)) : ""))
                .setItemFlags(List.of(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_POTION_EFFECTS))
                .addLoreLines(
                        company.isBankrupt() ? messages.getCompanyStatusBankrupt() : messages.getCompanyStatusTrading(),
                        "",
                        MessageFormat.format(messages.getUiCompanyItemRisk(), company.getRisk()),
                        MessageFormat.format(messages.getUiCompanyItemSharePrice(), FormattingUtils.formatDouble(company.getCurrentSharePrice())),
                        MessageFormat.format(messages.getUiCompanyItemMarketCap(), FormattingUtils.formatDouble(marketCap)),
                        MessageFormat.format(messages.getUiCompanyItemAllTimeVariation(), VisualizationUtils.formatCompanyVariation(totalVariation)),
                        "",
                        messages.getUiCompanyItemBuyOneShare(),
                        messages.getUiCompanyItemBuyTenShares(),
                        messages.getUiCompanyItemBuyHundredShares()
                );

        return builder;
    }

    @Override
    public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent inventoryClickEvent) {

        // OWNER MANAGEMENT REDIRECT
        if (clickType == ClickType.LEFT) {
            String viewerUuid = player.getUniqueId().toString();
            if (company.getOwnerUuid() != null && company.getOwnerUuid().equals(viewerUuid)) {
                plugin.getGuiManager().openCompanyManagementMenu(player, (long) company.getId());
                return;
            }
        }

        // BUY LOGIC
        int sharesToBuy = -1;
        if (clickType == ClickType.RIGHT) {
            sharesToBuy = 1;
        } else if (clickType == ClickType.SHIFT_RIGHT) {
            sharesToBuy = 10;
        } else if (clickType == ClickType.SHIFT_LEFT) {
            sharesToBuy = 100;
        }

        if (sharesToBuy <= 0) return;

        BuyCommand buyLogic = new BuyCommand(plugin);
        buyLogic.onCommand(player, new String[]{String.valueOf(company.getId()), String.valueOf(sharesToBuy)});
    }
}