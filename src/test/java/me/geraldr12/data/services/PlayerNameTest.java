package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.api.services.AutoUpdateService;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.entities.PlayerEntity;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.enums.NotificationType;
import me.geraldr12.listeners.PlayerJoinListener;
import me.geraldr12.ui.items.ShareholderItem;
import me.geraldr12.utils.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerNameTest {
    private final Stonks plugin = mock(Stonks.class);
    private final PlayersRepository repository = mock(PlayersRepository.class);
    private final PlayersService service = new PlayersService(repository, plugin);
    private final UUID playerId = UUID.randomUUID();

    @Test
    void remembersChangedNameWithoutChangingInvestmentsOrNotifications() {
        PlayerEntity entity = existing("Unknown");
        service.rememberName(playerId, "Investor");

        ArgumentCaptor<PlayerEntity> saved = ArgumentCaptor.forClass(PlayerEntity.class);
        verify(repository).save(saved.capture());
        assertEquals("Investor", saved.getValue().getName());
        assertEquals(entity.getInvestments(), saved.getValue().getInvestments());
        assertEquals(entity.getBlockedNotifications(), saved.getValue().getBlockedNotifications());
        assertEquals(playerId.toString(), saved.getValue().getUniqueId());
    }

    @Test
    void unchangedNameIsNotSaved() {
        existing("Investor");
        service.rememberName(playerId, "Investor");
        verify(repository, never()).save(any());
    }

    @Test
    void missingPlayerIsNotCreated() {
        service.rememberName(playerId, "Investor");
        verify(repository, never()).save(any());
        verifyNoInteractions(plugin);
    }

    @Test
    void nullEmptyAndBlankNamesAreIgnored() {
        existing("Investor");
        service.rememberName(playerId, null);
        service.rememberName(playerId, "");
        service.rememberName(playerId, "   ");
        verify(repository, never()).save(any());
    }

    @Test
    void newPlayerUsesOnlineName() {
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        setServer(server);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(player.getName()).thenReturn("OnlineInvestor");

        service.toggleNotification(playerId, NotificationType.values()[0]);

        ArgumentCaptor<PlayerEntity> saved = ArgumentCaptor.forClass(PlayerEntity.class);
        verify(repository).save(saved.capture());
        assertEquals("OnlineInvestor", saved.getValue().getName());
    }

    @Test
    void absentServerOrPlayerKeepsUnknownFallback() {
        assertNewPlayerName("Unknown");
        Server server = mock(Server.class);
        setServer(server);
        assertNewPlayerName("Unknown");
        Player player = mock(Player.class);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(player.getName()).thenReturn("");
        assertNewPlayerName("Unknown");
    }

    @Test
    void joinRemembersName() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("JoinedInvestor");
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        PlayersService players = mock(PlayersService.class);
        CompaniesService companies = mock(CompaniesService.class);
        when(companies.getAllCompanies()).thenReturn(List.<CompanyDao>of());

        new PlayerJoinListener(plugin, mock(Messages.class), mock(AutoUpdateService.class), players, companies)
                .onPlayerJoinEvent(new PlayerJoinEvent(player, ""));

        verify(players).rememberName(playerId, "JoinedInvestor");
    }

    @Test
    void shareholderUsesOfflineThenRememberedNameThenUnknown() {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(playerId);
        PluginManager manager = mock(PluginManager.class);
        when(manager.getPlugin("Stonks")).thenReturn(plugin);
        when(plugin.getPlayersService()).thenReturn(service);
        PlayerEntity entity = existing("RememberedInvestor");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class,
                withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(playerId)).thenReturn(offline);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertEquals("§eRememberedInvestor", new ShareholderItem(playerId, 3).getIcon().name());
            when(offline.getName()).thenReturn("OfflineInvestor");
            assertEquals("§eOfflineInvestor", new ShareholderItem(playerId, 3).getIcon().name());
            when(offline.getName()).thenReturn(null);
            entity.setName("Unknown");
            assertEquals("§eUnknown Investor", new ShareholderItem(playerId, 3).getIcon().name());
            entity.setName(null);
            assertEquals("§eUnknown Investor", new ShareholderItem(playerId, 3).getIcon().name());
            entity.setName(" ");
            assertEquals("§eUnknown Investor", new ShareholderItem(playerId, 3).getIcon().name());
            when(repository.getById(playerId)).thenReturn(Optional.empty());
            assertEquals("§eUnknown Investor", new ShareholderItem(playerId, 3).getIcon().name());
            verify(repository, never()).save(any());
        }
    }

    private void setServer(Server server) {
        try {
            var field = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(plugin, server);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private PlayerEntity existing(String name) {
        PlayerEntity entity = PlayerDao.builder().uniqueId(playerId.toString()).name(name)
                .investments(new ArrayList<>()).blockedNotifications(new HashSet<>()).build().toEntity();
        when(repository.exists(playerId)).thenReturn(true);
        when(repository.getById(playerId)).thenReturn(Optional.of(entity));
        return entity;
    }

    private void assertNewPlayerName(String expected) {
        clearInvocations(repository);
        service.toggleNotification(playerId, NotificationType.values()[0]);
        ArgumentCaptor<PlayerEntity> saved = ArgumentCaptor.forClass(PlayerEntity.class);
        verify(repository).save(saved.capture());
        assertEquals(expected, saved.getValue().getName());
    }
}
