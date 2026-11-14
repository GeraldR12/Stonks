package me.geraldr12.ui;

import java.util.List;
import java.util.UUID;
import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.ui.guis.PortfolioGui;
import me.geraldr12.ui.items.InvestmentItem;
import me.geraldr12.ui.menu.MenuHolder;
import me.geraldr12.ui.menu.MenuListener;
import me.geraldr12.utils.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeMenuRegressionTest {
    @Test
    void realCloseEventNotifiesManagerWithItsHolder() {
        GuiManager manager = mock(GuiManager.class);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        MenuHolder holder = mock(MenuHolder.class);
        Player player = mock(Player.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(view.getPlayer()).thenReturn(player);
        when(inventory.getHolder()).thenReturn(holder);

        new MenuListener(manager, Runnable::run).onClose(new InventoryCloseEvent(view));

        verify(manager).onClose(player, holder);
    }

    @Test
    void rejectedSaleDoesNotSubtractFromTheInvestment() {
        Stonks plugin = mock(Stonks.class);
        Messages messages = mock(Messages.class, RETURNS_MOCKS);
        CompaniesService companies = mock(CompaniesService.class);
        PlayersService players = mock(PlayersService.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        InvestmentDao investment = new InvestmentDao(1, 12, 5);
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getPlayersService()).thenReturn(players);
        when(player.getUniqueId()).thenReturn(playerId);
        when(players.getInvestments(playerId)).thenReturn(List.of(investment));
        when(companies.getCompanyById(1)).thenReturn(mock(CompanyDao.class));
        InvestmentItem item = new InvestmentItem(plugin, messages, companies, investment);

        item.getAction().accept(player, ClickType.RIGHT);
        assertEquals(12, investment.getSharesAmount());
        item.getAction().accept(player, ClickType.SHIFT_RIGHT);
        assertEquals(12, investment.getSharesAmount());
        verify(player, times(2)).sendMessage(contains("Nothing was charged"));
    }

    @Test
    void portfolioBuildReadsTheCurrentInvestmentsAgain() {
        GuiManager manager = mock(GuiManager.class);
        PlayersService players = mock(PlayersService.class);
        CompaniesService companies = mock(CompaniesService.class);
        Messages messages = mock(Messages.class, RETURNS_MOCKS);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        CompanyDao company = mock(CompanyDao.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(manager.getPlayersService()).thenReturn(players);
        when(manager.getCompaniesService()).thenReturn(companies);
        when(companies.companyExists(1)).thenReturn(true);
        when(companies.getCompanyById(1)).thenReturn(company);
        when(players.getInvestments(playerId)).thenReturn(List.of(new InvestmentDao(1, 12, 5)), List.of());
        PortfolioGui gui = new PortfolioGui(player, manager, messages);

        assertNotNull(gui.build().getPage(0).get(10));
        assertNull(gui.build().getPage(0).get(10));
        verify(players, times(2)).getInvestments(playerId);
    }
}
