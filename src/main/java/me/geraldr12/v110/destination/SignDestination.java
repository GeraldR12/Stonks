package me.geraldr12.v110.destination;

import me.geraldr12.data.dao.SignDao;

import java.util.Optional;

public interface SignDestination {

    Optional<SignDao> findById(int signId);

    boolean save(SignDao sign);
}
