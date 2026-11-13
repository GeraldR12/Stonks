package me.geraldr12.ui;

import me.geraldr12.commands.MainCommand.SubCommand;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.ui.guis.ConfirmDeleteGui;
import me.geraldr12.ui.items.DeleteCompanyButtonItem;
import me.geraldr12.utils.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class ConfirmDeleteGuiTest {
    private final Player player = mock(Player.class);
    private final GuiManager manager = mock(GuiManager.class);
    private final Messages messages = mock(Messages.class, RETURNS_MOCKS);
    private final SubCommand delete = mock(SubCommand.class);

    private ConfirmDeleteGui gui() {
        CompaniesService companies = mock(CompaniesService.class);
        when(manager.getCompaniesService()).thenReturn(companies);
        when(companies.getCompanyById(73)).thenReturn(CompanyDao.builder().name("Example").build());
        return new ConfirmDeleteGui(player, manager, messages, 73, delete);
    }

    @Test
    void cancelReturnsToManagementWithoutDeleting() {
        var layout = gui().build();
        assertEquals(27, layout.size());
        assertEquals(Material.RED_CONCRETE, layout.getPage(0).get(15).getIcon().material());
        layout.getPage(0).get(15).getAction().accept(player, ClickType.LEFT);
        verify(manager).navigateBack(player);
        verifyNoInteractions(delete);
        verify(manager, never()).close(any());
    }

    @Test
    void confirmDeletesExactlyOnceWithTheCompanyIdAndCloses() {
        var button = gui().build().getPage(0).get(11);
        assertEquals(Material.LIME_CONCRETE, button.getIcon().material());
        button.getAction().accept(player, ClickType.LEFT);
        verify(delete, times(1)).onCommand(eq(player), aryEq(new String[]{"73"}));
        verifyNoMoreInteractions(delete);
        verify(manager).close(player);
    }

    @Test
    void managementDeleteButtonOnlyOpensConfirmation() {
        var button = new DeleteCompanyButtonItem(manager, messages, 73);
        button.getAction().accept(player, ClickType.LEFT);
        verify(manager).openConfirmDeleteMenu(player, 73);
        verify(manager, never()).close(any());
        verifyNoInteractions(delete);
    }
}
