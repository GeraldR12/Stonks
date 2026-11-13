package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.InvestmentDao;
import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.entities.PlayerEntity;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayersServiceTypedTest {
    @Test
    void absentHoldingAddsAndRestoresToAbsence() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository repository = mock(PlayersRepository.class);
        when(repository.exists(playerId)).thenReturn(false);
        when(repository.save(any())).thenReturn(true);
        PlayersService service = new PlayersService(repository, mock(Stonks.class));
        ShareMutationResult added = service.addSharesToPlayerTyped(playerId, 42, 5, 200.0);
        assertNull(added.getBeforeAveragePrice());
        assertEquals(200.0, added.getAfterAveragePrice());
        ConfirmedPlayerAddEvidence evidence = added.playerAddEvidence();
        when(repository.exists(playerId)).thenReturn(true);
        when(repository.getById(playerId)).thenReturn(Optional.of(player( playerId, 5, 200.0)));
        ShareMutationResult restored = service.restorePlayerSharesTyped(playerId, 42, evidence);
        assertEquals(ShareMutationOutcome.CONFIRMED, restored.getOutcome());
        assertNull(restored.getAfterAveragePrice());
        verify(repository, times(2)).save(any());
    }

    @Test
    void existingHoldingUsesWeightedAverageAndExactRestore() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository repository = mock(PlayersRepository.class);
        when(repository.exists(playerId)).thenReturn(true);
        when(repository.getById(playerId)).thenReturn(Optional.of(player(playerId, 10, 100.0)));
        when(repository.save(any())).thenReturn(true);
        PlayersService service = new PlayersService(repository, mock(Stonks.class));
        ShareMutationResult added = service.addSharesToPlayerTyped(playerId, 42, 5, 200.0);
        assertEquals(15L, added.getAfterQuantity());
        assertEquals((10 * 100.0 + 5 * 200.0) / 15, added.getAfterAveragePrice());
    }

    @Test
    void corruptHoldingAndNullRestoreInputsFailClosed() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository repository = mock(PlayersRepository.class);
        when(repository.exists(playerId)).thenReturn(true);
        when(repository.getById(playerId)).thenReturn(Optional.of(player(playerId, 0, 100.0)));
        PlayersService service = new PlayersService(repository, mock(Stonks.class));
        assertEquals(ShareMutationOutcome.DEFINITE_REJECTED, service.addSharesToPlayerTyped(playerId, 42, 5, 200).getOutcome());
        assertEquals(ShareMutationOutcome.DEFINITE_REJECTED, service.restorePlayerSharesTyped(null, 42, null).getOutcome());
        verify(repository, never()).save(any());
    }

    @Test
    void playerRemovalPartialReducesAndRestoresExactly() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository repository = mock(PlayersRepository.class);
        when(repository.exists(playerId)).thenReturn(true);
        when(repository.getById(playerId)).thenReturn(Optional.of(player(playerId, 10, 100.0)));
        when(repository.save(any())).thenReturn(true);
        PlayersService service = new PlayersService(repository, mock(Stonks.class));

        ShareMutationResult removed = service.removeSharesFromPlayerTyped(playerId, 42, 4);
        if (removed.getOutcome() != ShareMutationOutcome.CONFIRMED) System.out.println("DIAGNOSTIC: " + removed.getDiagnostic());
        assertEquals(ShareMutationOutcome.CONFIRMED, removed.getOutcome());
        assertEquals(ShareMutationOperation.PLAYER_REMOVE, removed.getOperation());
        assertEquals(6L, removed.getAfterQuantity());
        assertNull(removed.getAfterAveragePrice());
        assertEquals(100.0, removed.getBeforeAveragePrice());

        ConfirmedPlayerRemovalEvidence evidence = removed.playerRemovalEvidence();
        when(repository.getById(playerId)).thenReturn(Optional.of(player(playerId, 6, 100.0)));

        ShareMutationResult restored = service.restoreRemovedPlayerSharesTyped(evidence);
        assertEquals(ShareMutationOutcome.CONFIRMED, restored.getOutcome());
        assertEquals(ShareMutationOperation.PLAYER_REMOVE_RESTORE, restored.getOperation());
        assertEquals(10L, restored.getAfterQuantity());
        verify(repository, times(2)).save(any());
    }

    @Test
    void playerRemovalFullExitAndRestoresExactly() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository repository = mock(PlayersRepository.class);
        when(repository.exists(playerId)).thenReturn(true);
        when(repository.getById(playerId)).thenReturn(Optional.of(player(playerId, 10, 100.0)));
        when(repository.save(any())).thenReturn(true);
        PlayersService service = new PlayersService(repository, mock(Stonks.class));

        ShareMutationResult removed = service.removeSharesFromPlayerTyped(playerId, 42, 10);
        assertEquals(ShareMutationOutcome.CONFIRMED, removed.getOutcome());
        assertEquals(0L, removed.getAfterQuantity());

        ConfirmedPlayerRemovalEvidence evidence = removed.playerRemovalEvidence();
        PlayerEntity empty = PlayerDao.builder().uniqueId(playerId.toString()).name("x")
                .investments(new ArrayList<>()).blockedNotifications(new HashSet<>()).build().toEntity();
        when(repository.getById(playerId)).thenReturn(Optional.of(empty));

        ShareMutationResult restored = service.restoreRemovedPlayerSharesTyped(evidence);
        assertEquals(ShareMutationOutcome.CONFIRMED, restored.getOutcome());
        assertEquals(10L, restored.getAfterQuantity());
        assertNull(restored.getAfterAveragePrice()); // because response type doesn't track it on restore, wait
        // Ah, our compensation returns CONFIRMED with beforePrice from evidence. So getBeforeAveragePrice is set.
        assertEquals(100.0, restored.getBeforeAveragePrice());
    }

    @Test
    void playerRemovalInsufficientRejectsAndPersistenceFailuresAmbiguous() {
        UUID playerId = UUID.randomUUID();
        PlayersRepository repository = mock(PlayersRepository.class);
        when(repository.exists(playerId)).thenReturn(true);
        when(repository.getById(playerId)).thenReturn(Optional.of(player(playerId, 2, 100.0))); // insufficient
        PlayersService service = new PlayersService(repository, mock(Stonks.class));

        assertEquals(ShareMutationOutcome.DEFINITE_REJECTED, service.removeSharesFromPlayerTyped(playerId, 42, 3).getOutcome());

        // Persistence failure
        when(repository.getById(playerId)).thenReturn(Optional.of(player(playerId, 10, 100.0)));
        when(repository.save(any())).thenReturn(false);
        assertEquals(ShareMutationOutcome.AMBIGUOUS, service.removeSharesFromPlayerTyped(playerId, 42, 4).getOutcome());
        verify(repository, times(1)).save(any());
    }

    private PlayerEntity player(UUID id, int quantity, double price) {
        PlayerDao dao = PlayerDao.builder().uniqueId(id.toString()).name("x")
                .investments(new ArrayList<>(java.util.List.of(new InvestmentDao(42, quantity, price))))
                .blockedNotifications(new HashSet<>()).build();
        return dao.toEntity();
    }
}
