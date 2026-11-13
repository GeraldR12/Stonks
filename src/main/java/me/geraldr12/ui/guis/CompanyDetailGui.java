package me.geraldr12.ui.guis;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.geraldr12.commands.BuyCommand;
import me.geraldr12.commands.MainCommand.SubCommand;
import me.geraldr12.commands.SellCommand;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.items.NavigateBackItem;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class CompanyDetailGui extends AbstractPluginGui {
    private final long companyId;
    private final SubCommand buy;
    private final SubCommand sell;

    public CompanyDetailGui(Player player, GuiManager guiManager, Messages messages, long companyId) {
        this(player, guiManager, messages, companyId,
                new BuyCommand(guiManager.getPlugin()), new SellCommand(guiManager.getPlugin()));
    }

    public CompanyDetailGui(Player player, GuiManager guiManager, Messages messages, long companyId,
                            SubCommand buy, SubCommand sell) {
        super(messages.getMessageByKey("uiDetailTitle"), "stonks.ui.companies", player, guiManager, messages);
        this.companyId = companyId;
        this.buy = buy;
        this.sell = sell;
    }

    private int ownedShares(Player who) {
        return guiManager.getPlayersService().getInvestments(who.getUniqueId()).stream()
                .filter(investment -> investment.getCompanyId() == companyId)
                .mapToInt(InvestmentDao::getSharesAmount).findFirst().orElse(0);
    }

    @Override
    public PagedLayout build() {
        Map<Character, MenuButton> buttons = new HashMap<>();
        buttons.put('-', new NavigateBackItem(guiManager, messages, true));
        var company = guiManager.getCompaniesService().getCompanyById(companyId);
        if (company != null) {
            int shares = ownedShares(player);
            buttons.put('i', new MenuButton(new MenuIcon(company.getIcon() != null ? company.getIcon() : Material.EMERALD,
                    MessageFormat.format(messages.getMessageByKey("uiDetailCompanyName"), company.getName()),
                    List.of(MessageFormat.format(messages.getUiCompanyItemStatus(), company.isBankrupt()
                                    ? messages.getCompanyStatusBankrupt() : messages.getCompanyStatusTrading()),
                            MessageFormat.format(messages.getUiCompanyItemRisk(), company.getRisk()),
                            MessageFormat.format(messages.getUiCompanyItemSharePrice(), FormattingUtils.formatDouble(company.getCurrentSharePrice())),
                            MessageFormat.format(messages.getMessageByKey("uiDetailAvailableShares"), company.getAvailableShares()),
                            MessageFormat.format(messages.getUiInvestmentItemShares(), shares))), null));
            buttons.put('a', tradeButton("uiDetailBuyOne", Material.LIME_DYE, buy, 1));
            buttons.put('b', tradeButton("uiDetailBuyTen", Material.LIME_DYE, buy, 10));
            buttons.put('c', tradeButton("uiDetailBuyHundred", Material.LIME_DYE, buy, 100));
            if (shares > 0) {
                buttons.put('d', tradeButton("uiDetailSellOne", Material.RED_DYE, sell, 1));
                buttons.put('f', new MenuButton(new MenuIcon(Material.RED_DYE,
                        messages.getMessageByKey("uiDetailSellAll"), List.of()), (who, click) -> {
                    int currentShares = ownedShares(who);
                    if (currentShares > 0) sell.onCommand(who,
                            new String[]{String.valueOf(companyId), String.valueOf(currentShares)});
                }));
            }
            if (shares >= 10) buttons.put('e', tradeButton("uiDetailSellTen", Material.RED_DYE, sell, 10));
        }
        return new PagedLayout(new String[]{
                ". . . . . . . . .",
                ". a b c . . . . .",
                ". . . . i . . . .",
                ". d e f . . . . .",
                ". . . . - . . . ."
        }, buttons, List.of());
    }

    private MenuButton tradeButton(String key, Material material, SubCommand command, int quantity) {
        return new MenuButton(new MenuIcon(material, messages.getMessageByKey(key), List.of()),
                (who, click) -> command.onCommand(who, new String[]{String.valueOf(companyId), String.valueOf(quantity)}));
    }
}
