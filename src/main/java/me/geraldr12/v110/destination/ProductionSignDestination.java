package me.geraldr12.v110.destination;

import me.geraldr12.data.dao.SignDao;
import me.geraldr12.data.repositories.implementations.SignsRepository;
import me.geraldr12.data.services.SignsService;
import me.geraldr12.enums.DataFilePath;
import me.geraldr12.v110.MigrationState.MigrationException;

import java.io.File;
import java.util.Optional;

public final class ProductionSignDestination implements SignDestination {

    private final SignsRepository repository;
    private final File dataDirectory;

    public ProductionSignDestination(SignsService service, File dataDirectory) {
        this.repository = service.repository;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public Optional<SignDao> findById(int signId) {
        Optional<SignDao> sign = repository.getById(signId).map(entity -> (SignDao) new SignDao().fromEntity(entity));
        if (sign.isEmpty() && new File(dataDirectory, DataFilePath.SIGNS.getFullPathById(Integer.toString(signId))).exists()) {
            throw new MigrationException("Destination sign is unreadable: " + signId, null);
        }
        return sign;
    }

    @Override
    public boolean save(SignDao sign) {
        return repository.save(sign.toEntity());
    }
}
