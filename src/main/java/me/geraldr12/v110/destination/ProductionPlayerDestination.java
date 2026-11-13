package me.geraldr12.v110.destination;

import me.geraldr12.data.dao.PlayerDao;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.enums.DataFilePath;
import me.geraldr12.v110.MigrationState.MigrationException;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

public final class ProductionPlayerDestination implements PlayerDestination {

    private final PlayersRepository repository;
    private final File dataDirectory;

    public ProductionPlayerDestination(PlayersService service, File dataDirectory) {
        this.repository = (PlayersRepository) service.getRepository();
        this.dataDirectory = dataDirectory;
    }

    @Override
    public Optional<PlayerDao> findById(UUID playerId) {
        Optional<PlayerDao> player = repository.getById(playerId).map(entity -> (PlayerDao) new PlayerDao().fromEntity(entity));
        if (player.isEmpty() && new File(dataDirectory, DataFilePath.PLAYERS.getFullPathById(playerId.toString())).exists()) {
            throw new MigrationException("Destination player is unreadable: " + playerId, null);
        }
        return player;
    }

    @Override
    public boolean save(PlayerDao player) {
        return repository.save(player.toEntity());
    }
}
