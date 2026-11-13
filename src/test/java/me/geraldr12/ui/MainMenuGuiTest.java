package me.geraldr12.ui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.ui.guis.*;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainMenuGuiTest {
    private final GuiManager manager = mock(GuiManager.class);
    private final Player player = mock(Player.class);
    private final Messages messages = mock(Messages.class, RETURNS_MOCKS);
    private final PlayersService players = mock(PlayersService.class);
    private final CompaniesService companies = mock(CompaniesService.class);

    private MainMenuGui gui() {
        when(manager.getPlayersService()).thenReturn(players);
        when(manager.getCompaniesService()).thenReturn(companies);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(players.getInvestments(any())).thenReturn(List.of());
        return new MainMenuGui(player, manager, messages);
    }

    @Test
    void buttonsNavigateToTheirScreensAndClose() {
        var layout = gui().build();
        assertEquals(27, layout.size());
        var buttons = layout.getPage(0);
        Map<Integer, PluginGuiType> destinations = Map.of(10, PluginGuiType.COMPANIES_GUI,
                12, PluginGuiType.PORTFOLIO_GUI, 14, PluginGuiType.PLAYER_COMPANIES_GUI,
                16, PluginGuiType.NOTIFICATIONS_GUI);
        destinations.forEach((slot, destination) -> {
            buttons.get(slot).getAction().accept(player, ClickType.LEFT);
            verify(manager).navigate(player, destination);
        });
        assertNull(buttons.get(22).getAction());
        buttons.get(26).getAction().accept(player, ClickType.LEFT);
        verify(manager).close(player);
    }

    @Test
    void portfolioLoreUsesOnlyExistingCompaniesAndCurrentValues() {
        MainMenuGui gui = gui();
        when(messages.getUiPortfolioSummaryItemCompanies()).thenReturn("Companies: {0}");
        when(messages.getUiPortfolioSummaryItemTotalValue()).thenReturn("Value: {0}");
        when(players.getInvestments(any())).thenReturn(List.of(new InvestmentDao(1, 5, 1), new InvestmentDao(2, 8, 2)));
        when(companies.companyExists(1)).thenReturn(true);
        CompanyDao company = CompanyDao.builder().currentSharePrice(4).build();
        when(companies.getCompanyById(1)).thenReturn(company);
        var lore = gui.build().getPage(0).get(12).getIcon().lore();
        assertTrue(lore.contains("Companies: 1"));
        assertTrue(lore.contains("Value: " + me.geraldr12.utils.FormattingUtils.formatDouble(20)));
        company.setCurrentSharePrice(6);
        assertTrue(gui.build().getPage(0).get(12).getIcon().lore()
                .contains("Value: " + me.geraldr12.utils.FormattingUtils.formatDouble(30)));
    }

    @Test
    void childScreenBackButtonsReturnToMainMenuEvenWithoutHistory() {
        gui();
        when(companies.getAllCompanies()).thenReturn(List.of());
        for (AbstractPluginGui child : List.of(new CompaniesGui(player, manager, messages),
                new PortfolioGui(player, manager, messages),
                new NotificationsGui(player, manager, messages),
                new PlayerCompaniesGui(player, manager, companies, messages))) {
            var back = child.build().getPage(0).values().stream()
                    .filter(button -> button.getIcon().material() == org.bukkit.Material.REDSTONE_TORCH)
                    .findFirst().orElseThrow();
            back.getAction().accept(player, ClickType.LEFT);
        }
        verify(manager, times(4)).navigateMainMenu(player);
    }
}
