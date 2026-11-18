package me.geraldr12.ui.guis;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.geraldr12.commands.BuyCommand;
import me.geraldr12.commands.SellCommand;
import me.geraldr12.data.services.MarketPricing;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class TradeQuoteGui extends AbstractPluginGui {
    private static final long VALID_NANOS = Duration.ofSeconds(30).toNanos();
    private final long companyId;
    private final int quantity;
    private final boolean buy;
    private MarketPricing.Quote shown;
    private long quotedAt;
    private boolean consumed;

    public TradeQuoteGui(Player player, GuiManager manager, Messages messages,
                         long companyId, int quantity, boolean buy) {
        super(messages.getMessageByKey("uiQuoteTitle"), "stonks.use", player, manager, messages);
        this.companyId = companyId;
        this.quantity = quantity;
        this.buy = buy;
        refreshQuote();
    }

    private void refreshQuote() {
        shown = null;
        var companies = guiManager.getCompaniesService();
        var company = companies.getCompanyById(companyId);
        var economy = guiManager.getPlugin().getEconomy();
        if (company != null && company.isTrading() && economy != null) {
            try {
                shown = MarketPricing.quote(company.getCurrentSharePrice(), quantity, buy,
                        economy.fractionalDigits(), companies);
            } catch (RuntimeException unavailable) {
                guiManager.getPlugin().getLogger().warning("Unable to quote trade for company " + companyId + ": "
                        + unavailable.getClass().getSimpleName());
            }
        }
        quotedAt = System.nanoTime();
    }

    @Override
    public PagedLayout build() {
        Map<Character, MenuButton> buttons = new HashMap<>();
        var company = guiManager.getCompaniesService().getCompanyById(companyId);
        boolean available = company != null && company.isTrading() && shown != null && shown.total() > 0;
        List<String> lore = new ArrayList<>();
        if (available) {
            lore.add(format("uiQuoteAverage", shown.gross() / quantity));
            lore.add(format("uiQuoteFee", shown.fee()));
            lore.add(format(buy ? "uiQuoteCharge" : "uiQuotePayout", shown.total()));
            lore.add(format("uiQuoteAfter", shown.priceAfter()));
            buttons.put('y', new MenuButton(new MenuIcon(Material.LIME_CONCRETE,
                    messages.getMessageByKey("uiQuoteConfirm"), List.of()), (who, click) -> confirm(who)));
        } else {
            lore.add(messages.getMessageByKey("uiQuoteUnavailable"));
        }
        String name = company == null ? "#" + companyId : company.getName();
        buttons.put('i', new MenuButton(new MenuIcon(Material.PAPER, MessageFormat.format(
                messages.getMessageByKey(buy ? "uiQuoteBuy" : "uiQuoteSell"), quantity, name), lore), null));
        buttons.put('n', new MenuButton(new MenuIcon(Material.RED_CONCRETE,
                messages.getMessageByKey("uiQuoteCancel"), List.of()), (who, click) -> guiManager.navigateBack(who)));
        return new PagedLayout(new String[]{
                ". . . . i . . . .",
                ". . y . . . n . .",
                ". . . . . . . . ."
        }, buttons, List.of());
    }

    private String format(String key, double value) {
        return MessageFormat.format(messages.getMessageByKey(key), FormattingUtils.formatDouble(value));
    }

    private void confirm(Player who) {
        if (consumed || !who.getUniqueId().equals(player.getUniqueId()) || !canOpen()) return;
        if (System.nanoTime() - quotedAt >= VALID_NANOS) {
            who.sendMessage(messages.getMessageByKey("uiQuoteExpired"));
            refreshQuote();
            return;
        }
        consumed = true;
        var plugin = guiManager.getPlugin();
        boolean finished = buy ? new BuyCommand(plugin).confirm(who, companyId, quantity, shown)
                : new SellCommand(plugin).confirm(who, companyId, quantity, shown);
        if (finished) guiManager.navigateBack(who);
        else {
            refreshQuote();
            consumed = false;
        }
    }
}
