package me.geraldr12.ui.guis;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneOffset.UTC);

    public CompanyDetailGui(Player player, GuiManager guiManager, Messages messages, long companyId) {
        super(messages.getMessageByKey("uiDetailTitle"), "stonks.ui.companies", player, guiManager, messages);
        this.companyId = companyId;
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
            List<String> lore = new ArrayList<>();
            lore.add(MessageFormat.format(messages.getUiCompanyItemStatus(), company.isBankrupt()
                    ? messages.getCompanyStatusBankrupt() : company.isTrading() ? messages.getCompanyStatusTrading()
                    : messages.getMessageByKey("uiCompanySuspended")));
            if (company.isBankrupt()) {
                Long closedAt = company.getBankruptcyTime();
                lore.add(closedAt == null ? messages.getMessageByKey("uiBankruptcyUnknown")
                        : MessageFormat.format(messages.getMessageByKey("uiBankruptAt"), TIME.format(Instant.ofEpochMilli(closedAt))));
                lore.add(messages.getMessageByKey("uiBankruptcyLegacyReason"));
                Double previous = company.getBankruptcyPreviousPrice();
                if (previous != null) lore.add(MessageFormat.format(messages.getMessageByKey("uiBankruptPreviousPrice"),
                        FormattingUtils.formatDouble(previous)));
            } else if (company.isTrading()) {
                lore.add(MessageFormat.format(messages.getUiCompanyItemRisk(), company.getRisk()));
                lore.add(MessageFormat.format(messages.getUiCompanyItemSharePrice(),
                        FormattingUtils.formatDouble(company.getCurrentSharePrice())));
                lore.add(MessageFormat.format(messages.getMessageByKey("uiDetailAvailableShares"), company.getAvailableShares()));
            }
            lore.add(MessageFormat.format(messages.getUiInvestmentItemShares(), shares));
            var plugin = guiManager.getPlugin();
            if (company.isTrading() && plugin != null && plugin.getCompanyFundsService() != null
                    && plugin.getCompanyFundsService().enabled()) {
                lore.add(MessageFormat.format(messages.getMessageByKey("uiCompanyFunds"),
                        FormattingUtils.formatDouble(plugin.getCompanyFundsService().balance(companyId))));
            }
            if (company.isTrading() && plugin != null && plugin.getShopLinksService() != null
                    && !plugin.getShopLinksService().linksOf(companyId).isEmpty()) {
                long count = plugin.linkedShopTradeCount(companyId);
                if (count >= 0) {
                    lore.add(MessageFormat.format(messages.getMessageByKey("uiShopObserved"), count));
                    lore.add(messages.getMessageByKey("uiShopObservedHint"));
                }
            }
            buttons.put('i', new MenuButton(new MenuIcon(company.getIcon() != null ? company.getIcon() : Material.EMERALD,
                    MessageFormat.format(messages.getMessageByKey("uiDetailCompanyName"), company.getName()),
                    lore), null));
            buttons.put('h', new MenuButton(new MenuIcon(Material.BOOK,
                    messages.getMessageByKey("uiDetailHistory"), List.of()),
                    (who, click) -> guiManager.openCompanyHistory(who, companyId)));
            if (company.isTrading()) {
                buttons.put('a', tradeButton("uiDetailBuyOne", Material.LIME_DYE, true, 1));
                buttons.put('b', tradeButton("uiDetailBuyTen", Material.LIME_DYE, true, 10));
                buttons.put('c', tradeButton("uiDetailBuyHundred", Material.LIME_DYE, true, 100));
                buttons.put('u', customAmount(true));
            }
            if (company.isTrading() && player.getUniqueId().toString().equals(company.getOwnerUuid())) {
                buttons.put('m', new MenuButton(new MenuIcon(Material.CHEST,
                        messages.getMessageByKey("uiManageCompany"), List.of()),
                        (who, click) -> guiManager.openCompanyManagementMenu(who, companyId)));
            }
            if (company.isTrading() && shares > 0) {
                buttons.put('d', tradeButton("uiDetailSellOne", Material.RED_DYE, false, 1));
                buttons.put('f', new MenuButton(new MenuIcon(Material.RED_DYE,
                        messages.getMessageByKey("uiDetailSellAll"), List.of()), (who, click) -> {
                    int currentShares = ownedShares(who);
                    if (currentShares > 0) guiManager.openTradeQuote(who, companyId, currentShares, false);
                }));
                if (shares >= 10) buttons.put('e', tradeButton("uiDetailSellTen", Material.RED_DYE, false, 10));
                buttons.put('v', customAmount(false));
            }
        }
        return new PagedLayout(new String[]{
                ". . . . . . . . .",
                ". a b c . u . . .",
                ". . h . i . m . .",
                ". d e f . v . . .",
                ". . . . - . . . ."
        }, buttons, List.of());
    }

    private MenuButton tradeButton(String key, Material material, boolean buy, int quantity) {
        return new MenuButton(new MenuIcon(material, messages.getMessageByKey(key), List.of()),
                (who, click) -> guiManager.openTradeQuote(who, companyId, quantity, buy));
    }

    private MenuButton customAmount(boolean buy) {
        return new MenuButton(new MenuIcon(Material.PAPER,
                messages.getMessageByKey(buy ? "uiTradeCustomBuy" : "uiTradeCustomSell"), List.of()), (who, click) ->
                guiManager.prompt(this, "uiTradeAmountPrompt", input -> {
                    try {
                        int quantity = Integer.parseInt(input);
                        if (quantity <= 0) throw new NumberFormatException();
                        guiManager.openTradeQuote(who, companyId, quantity, buy);
                    } catch (NumberFormatException invalid) {
                        who.sendMessage(messages.getMessageByKey("uiInputInvalid"));
                    }
                }));
    }
}
