package me.geraldr12.ui.guis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import me.geraldr12.commands.CreateCommand;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class CreateCompanyGui extends AbstractPluginGui {
    private String name = "";
    private int risk = 1;
    private int shares = 100;
    private double price = 40;

    public CreateCompanyGui(Player player, GuiManager manager, Messages messages) {
        super(messages.getMessageByKey("uiCreateTitle"), "stonks.use", player, manager, messages);
    }

    @Override
    public PagedLayout build() {
        var plugin = guiManager.getPlugin();
        var config = plugin.getConfig();
        int risks = config.getDoubleList("Stonks.Taxes.CompanyCreation").size();
        var buttons = controls(Material.GRAY_STAINED_GLASS_PANE, "");
        buttons.put('n', field(Material.NAME_TAG, "uiCreateName", name.isEmpty()
                ? messages.getMessageByKey("uiCreateUnset") : name, "uiCreateNamePrompt", value -> {
            if (value.isBlank() || value.length() > 16 || value.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException();
            }
            name = value;
        }));
        buttons.put('r', new MenuButton(new MenuIcon(Material.REDSTONE, format("uiCreateRisk", risk),
                List.of(messages.getMessageByKey("uiCreateRiskHint"))), (who, click) -> {
            risk = Math.max(1, Math.min(risks, risk + (click.isRightClick() ? -1 : 1)));
        }));
        buttons.put('s', field(Material.PAPER, "uiCreateShares", shares, "uiCreateSharesPrompt", value -> {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new IllegalArgumentException();
            shares = parsed;
        }));
        buttons.put('p', field(Material.GOLD_INGOT, "uiCreatePrice", FormattingUtils.formatDouble(price),
                "uiCreatePricePrompt", value -> {
            double parsed = Double.parseDouble(value);
            double min = config.getDouble("Stonks.Limits.MinSharePrice", 30);
            double max = config.getDouble("Stonks.Limits.MaxSharePrice", 50);
            if (!Double.isFinite(parsed) || parsed <= 0 || (min >= 0 && parsed < min) || (max >= 0 && parsed > max)) {
                throw new IllegalArgumentException();
            }
            price = parsed;
        }));
        double rawCost = guiManager.getCompaniesService().getCompanyCreationTax(shares, price, risk);
        if (!name.isBlank() && plugin.getEconomy() != null && Double.isFinite(rawCost) && rawCost > 0) {
            int digits = plugin.getEconomy().fractionalDigits();
            double cost = digits < 0 ? rawCost
                    : BigDecimal.valueOf(rawCost).setScale(digits, RoundingMode.DOWN).doubleValue();
            buttons.put('y', new MenuButton(new MenuIcon(Material.LIME_CONCRETE, messages.getMessageByKey("uiCreateConfirm"),
                    List.of(format("uiCreateCost", FormattingUtils.formatDouble(cost)))), (who, click) -> {
                if (!canOpen()) return;
                new CreateCommand(plugin).onCommand(who, new String[]{name, Integer.toString(risk),
                        Integer.toString(shares), Double.toString(price)});
                guiManager.navigateBack(who);
            }));
        }
        return new PagedLayout(new String[]{
                "# # # # # # # # #",
                "# n # r # s # p #",
                "# # # # y # # # -"
        }, buttons, List.of());
    }

    private MenuButton field(Material material, String key, Object value, String prompt, Consumer<String> update) {
        return new MenuButton(new MenuIcon(material, format(key, value), List.of()), (who, click) ->
                guiManager.prompt(this, prompt, input -> {
                    try {
                        update.accept(input);
                        holder.refresh();
                    } catch (IllegalArgumentException invalid) {
                        player.sendMessage(messages.getMessageByKey("uiInputInvalid"));
                    }
                }));
    }

    private String format(String key, Object value) {
        return MessageFormat.format(messages.getMessageByKey(key), value);
    }
}
