package me.geraldr12.v110.destination;

import me.geraldr12.data.dao.PlayerDao;

import java.util.Optional;
import java.util.UUID;

public interface PlayerDestination {

    Optional<PlayerDao> findById(UUID playerId);

    boolean save(PlayerDao player);
}
