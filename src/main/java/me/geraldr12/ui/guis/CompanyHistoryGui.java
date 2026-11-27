package me.geraldr12.ui.guis;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import me.geraldr12.data.dao.QuoteDao;
import me.geraldr12.ui.AbstractPluginGui;
import me.geraldr12.ui.GuiManager;
import me.geraldr12.ui.menu.MenuButton;
import me.geraldr12.ui.menu.MenuIcon;
import me.geraldr12.ui.menu.PagedLayout;
import me.geraldr12.utils.FormattingUtils;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class CompanyHistoryGui extends AbstractPluginGui {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneOffset.UTC);
    private final long companyId;

    public CompanyHistoryGui(Player player, GuiManager manager, Messages messages, long companyId) {
        super(messages.getMessageByKey("uiDetailHistory"), "stonks.ui.companies", player, manager, messages);
        this.companyId = companyId;
    }

    @Override
    public PagedLayout build() {
        var company = guiManager.getCompaniesService().getCompanyById(companyId);
        List<MenuButton> entries = company == null || company.getHistoric() == null ? List.of()
                : company.getHistoric().stream().sorted(Comparator.comparingLong(QuoteDao::getTimestamp).reversed())
                .map(quote -> new MenuButton(new MenuIcon(Material.PAPER, MessageFormat.format(
                        messages.getMessageByKey("uiDetailHistoryEntry"), TIME.format(Instant.ofEpochMilli(quote.getTimestamp())),
                        FormattingUtils.formatDouble(quote.getPrice())), List.of()), null)).toList();
        if (entries.isEmpty()) entries = List.of(new MenuButton(new MenuIcon(Material.PAPER,
                messages.getMessageByKey("uiDetailHistoryEmpty"), List.of()), null));
        return new PagedLayout(new String[]{
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# x x x x x x x #",
                "# # # < - > # # #"
        }, controls(Material.GRAY_STAINED_GLASS_PANE, ""), entries);
    }
}
