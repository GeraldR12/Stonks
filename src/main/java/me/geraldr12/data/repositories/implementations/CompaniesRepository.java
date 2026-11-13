package me.geraldr12.data.repositories.implementations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.repositories.Repository;
import me.geraldr12.data.sources.DataSource;
import me.geraldr12.data.sources.yml.implementations.CompaniesYml;
import me.geraldr12.enums.DataFilePath;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class CompaniesRepository implements Repository<Long, CompanyEntity> {

    private static final java.util.concurrent.ConcurrentMap<java.nio.file.Path, Object> ALLOCATOR_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object UNRESOLVED_ALLOCATOR_LOCK = new Object();

    private final File pluginDataFolder;
    private final DataSource<CompanyEntity> dataSource;

    public CompaniesRepository(File pluginDataFolder, DataSource<CompanyEntity> dataSource) {
        this.pluginDataFolder = pluginDataFolder;
        this.dataSource = dataSource;
    }

    @Override
    public Optional<CompanyEntity> getById(Long id) {
        if (!isDataSourceValid()) return Optional.empty();
        return Optional.ofNullable(dataSource.load(DataFilePath.COMPANIES.getFullPathById(String.valueOf(id)), CompanyEntity.class));
    }

    @Override
    public boolean exists(Long id) {
        if (!isDataSourceValid()) return false;
        return dataSource.exists(DataFilePath.COMPANIES.getFullPathById(String.valueOf(id)));
    }

    @Override
    public boolean save(CompanyEntity dataEntity) {
        if (!isDataSourceValid() || dataEntity == null) return false;
        return dataSource.save(DataFilePath.COMPANIES.getFullPathById(String.valueOf(dataEntity.getId())), dataEntity);
    }

    @Override
    public boolean delete(Long id) {
        if (!isDataSourceValid() || id == null) return false;
        return dataSource.delete(DataFilePath.COMPANIES.getFullPathById(String.valueOf(id)));
    }

    public List<Long> getAllIds() {
        if (!isDataSourceValid()) return null;
        CompaniesYml companiesDataSourceYML = (CompaniesYml) dataSource;
        return companiesDataSourceYML.getAllIds(DataFilePath.COMPANIES.getDataPath())
                .stream()
                .filter(stringId -> stringId.matches("\\d+"))
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    public Long allocateNextId() {
        synchronized (allocatorLock(pluginDataFolder)) {
            return allocateNextIdLocked();
        }
    }

    private static Object allocatorLock(File pluginDataFolder) {
        java.nio.file.Path normalized = pluginDataFolder.toPath().toAbsolutePath().normalize();
        try {
            return ALLOCATOR_LOCKS.computeIfAbsent(normalized.toRealPath(), ignored -> new Object());
        } catch (java.io.IOException ignored) {
            return UNRESOLVED_ALLOCATOR_LOCK;
        }
    }

    private Long allocateNextIdLocked() {
        if (!isDataSourceValid()) return null;

        File dataFile = new File(this.pluginDataFolder,
                DataFilePath.COMPANIES.getFullPathById("data"));
        File parent = dataFile.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())
                || !parent.isDirectory()) {
            return null;
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        java.nio.file.Path temporaryFile = null;
        try {
            CompanyDataYml companyDataYml;
            if (dataFile.exists()) {
                companyDataYml = mapper.readValue(dataFile, CompanyDataYml.class);
                if (companyDataYml == null || companyDataYml.getNextId() == null) {
                    return null;
                }
            } else {
                Long nextId = recoverNextIdFromCompanies();
                if (nextId == null) return null;
                companyDataYml = new CompanyDataYml(nextId);
            }

            Long assignedId = companyDataYml.getNextId();
            if (assignedId <= 0 || assignedId == Long.MAX_VALUE) {
                return null;
            }

            companyDataYml.setNextId(assignedId + 1);
            temporaryFile = new File(parent,
                    dataFile.getName() + "." + java.util.UUID.randomUUID() + ".tmp").toPath();
            mapper.writeValue(temporaryFile.toFile(), companyDataYml);

            try {
                java.nio.file.Files.move(temporaryFile, dataFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temporaryFile, dataFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            temporaryFile = null;
            return assignedId;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        } finally {
            if (temporaryFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temporaryFile);
                } catch (java.io.IOException ignored) {
                    // The allocation already failed closed; orphan cleanup is best effort.
                }
            }
        }
    }

    private Long recoverNextIdFromCompanies() {
        List<String> ids = ((CompaniesYml) dataSource).getAllIds(DataFilePath.COMPANIES.getDataPath());
        long maxId = 0L;
        for (String id : ids) {
            if (!id.matches("\\d+")) continue;
            try {
                long parsedId = Long.parseLong(id);
                if (parsedId <= 0) continue;
                maxId = Math.max(maxId, parsedId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return maxId == Long.MAX_VALUE ? null : maxId + 1;
    }

    @Override
    public boolean isDataSourceValid() {
        return dataSource instanceof CompaniesYml;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class CompanyDataYml {
        private Long nextId;

        public CompanyDataYml(Long nextId) {
            this.nextId = nextId;
        }
    }
}