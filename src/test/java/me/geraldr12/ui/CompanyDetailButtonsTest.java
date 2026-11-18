package me.geraldr12.ui;

import java.util.List;
import java.util.UUID;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.ui.guis.CompanyDetailGui;
import me.geraldr12.ui.items.CompanyItem;
import me.geraldr12.utils.Messages;
import me.geraldr12.utils.SizedStack;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanyDetailButtonsTest {
    private final GuiManager manager = mock(GuiManager.class);
    private final Messages messages = mock(Messages.class, RETURNS_MOCKS);
    private final CompaniesService companies = mock(CompaniesService.class);
    private final PlayersService players = mock(PlayersService.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final CompanyDao company = CompanyDao.builder().id(42).name("Example")
            .initialSharePrice(5).currentSharePrice(5).risk(2).availableShares(100)
            .historic(new SizedStack<>(10)).build();

    private CompanyDetailGui gui(int shares) {
        when(player.getUniqueId()).thenReturn(playerId);
        when(manager.getCompaniesService()).thenReturn(companies);
        when(manager.getPlayersService()).thenReturn(players);
        when(companies.getCompanyById(42)).thenReturn(company);
        when(players.getInvestments(playerId)).thenReturn(List.of(new InvestmentDao(42, shares, 5)));
        return new CompanyDetailGui(player, manager, messages, 42);
    }

    @Test
    void saleButtonsFollowCurrentHoldings() {
        for (int shares : new int[]{0, 5, 12}) {
            var layout = gui(shares).build();
            assertEquals(45, layout.size());
            var buttons = layout.getPage(0);
            assertEquals(shares > 0, buttons.containsKey(28));
            assertEquals(shares >= 10, buttons.containsKey(29));
            assertEquals(shares > 0, buttons.containsKey(30));
        }
    }

    @Test
    void purchasesOpenTheExactQuote() {
        var buttons = gui(0).build().getPage(0);
        for (int slot : new int[]{10, 11, 12}) buttons.get(slot).getAction().accept(player, ClickType.LEFT);
        for (int quantity : new int[]{1, 10, 100}) {
            verify(manager).openTradeQuote(player, 42, quantity, true);
        }
    }

    @Test
    void salesOpenQuotesAndSellAllReadsFreshHoldings() {
        CompanyDetailGui gui = gui(12);
        var buttons = gui.build().getPage(0);
        buttons.get(28).getAction().accept(player, ClickType.LEFT);
        buttons.get(29).getAction().accept(player, ClickType.LEFT);
        when(players.getInvestments(playerId)).thenReturn(List.of(new InvestmentDao(42, 7, 5)));
        buttons.get(30).getAction().accept(player, ClickType.LEFT);
        for (int quantity : new int[]{1, 10, 7}) {
            verify(manager).openTradeQuote(player, 42, quantity, false);
        }
        when(players.getInvestments(playerId)).thenReturn(List.of());
        assertFalse(gui.build().getPage(0).containsKey(28));
        assertFalse(gui.build().getPage(0).containsKey(30));
        buttons.get(30).getAction().accept(player, ClickType.LEFT);
        verify(manager, times(3)).openTradeQuote(eq(player), eq(42L), anyInt(), eq(false));
    }

    @Test
    void missingCompanyLeavesOnlyNavigation() {
        CompanyDetailGui gui = gui(12);
        when(companies.getCompanyById(42)).thenReturn(null);
        var buttons = gui.build().getPage(0);
        assertFalse(buttons.containsKey(10));
        assertFalse(buttons.containsKey(28));
        assertNotNull(buttons.get(40));
    }

    @Test
    void marketDetailsAlsoOfferManagementToOwner() {
        var plugin = mock(me.geraldr12.Stonks.class);
        when(plugin.getGuiManager()).thenReturn(manager);
        gui(0);
        var item = new CompanyItem(plugin, messages, companies, 42);
        item.getAction().accept(player, ClickType.LEFT);
        verify(manager).openCompanyDetailMenu(player, 42);
        CompanyDao owned = CompanyDao.builder().id(42).name("Owned").ownerUuid(playerId.toString())
                .currentSharePrice(5).initialSharePrice(5).historic(new SizedStack<>(10)).build();
        when(companies.getCompanyById(42)).thenReturn(owned);
        item.getAction().accept(player, ClickType.LEFT);
        verify(manager, times(2)).openCompanyDetailMenu(player, 42);
        new CompanyDetailGui(player, manager, messages, 42).build().getPage(0)
                .get(24).getAction().accept(player, ClickType.LEFT);
        verify(manager).openCompanyManagementMenu(player, 42);
    }

    @Test
    void bankruptcyLeavesHistoryAndHidesTradingActions() {
        CompanyDetailGui gui = gui(12);
        company.setBankrupt(true);
        company.setCurrentSharePrice(0);
        var buttons = gui.build().getPage(0);
        assertNotNull(buttons.get(20));
        assertFalse(buttons.containsKey(10));
        assertFalse(buttons.containsKey(28));
        assertFalse(buttons.containsKey(30));
    }
}
