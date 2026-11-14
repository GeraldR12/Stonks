package me.geraldr12.data.sources.yml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.geraldr12.data.entities.DataEntity;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Log4j2
public class YmlDataSourceImpl<T extends DataEntity> implements YmlDataSource<T> {

    private static final ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    protected final File pluginDataFolder;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock;

    public YmlDataSourceImpl(File pluginDataFolder) {
        this.pluginDataFolder = pluginDataFolder;
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.mapper.findAndRegisterModules();
        this.lock = LOCKS.computeIfAbsent(pluginDataFolder.toPath().toAbsolutePath().normalize(),
                ignored -> new ReentrantReadWriteLock(true));
    }

    @Override
    public T load(String fileName, Class<T> dataEntityClass) {
        if (fileName == null || dataEntityClass == null) return null;

        lock.readLock().lock();
        try {
            File file = new File(pluginDataFolder, fileName);
            if (!file.exists()) return null;
            return mapper.readValue(file, dataEntityClass);
        } catch (IOException e) {
            log.warn("Error while loading data from file: " + new File(pluginDataFolder, fileName).getPath(), e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean save(String fileName, T data) {
        if (fileName == null || data == null) return false;

        lock.writeLock().lock();
        Path temporaryFile = null;
        try {
            File target = new File(pluginDataFolder, fileName);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) return false;

            temporaryFile = new File(parent, target.getName() + "." + UUID.randomUUID() + ".tmp").toPath();
            mapper.writeValue(temporaryFile.toFile(), data);
            moveIntoPlace(temporaryFile, target.toPath());
            temporaryFile = null;
            return true;
        } catch (IOException e) {
            log.warn("Error while saving data to file: " + fileName, e);
            return false;
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException e) {
                    log.warn("Error while cleaning temporary data file: " + temporaryFile, e);
                }
            }
            lock.writeLock().unlock();
        }
    }

    private void moveIntoPlace(Path temporaryFile, Path target) throws IOException {
        try {
            Files.move(temporaryFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public boolean exists(String fileName) {
        if (fileName == null) return false;
        lock.readLock().lock();
        try {
            return new File(pluginDataFolder, fileName).exists();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean delete(String fileName) {
        if (fileName == null) return false;
        lock.writeLock().lock();
        try {
            return !new File(pluginDataFolder, fileName).exists()
                    || new File(pluginDataFolder, fileName).delete();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<String> getAllIds(String directoryName) {
        if (directoryName == null) return Collections.emptyList();
        lock.readLock().lock();
        try {
            File[] filesInDataDir = new File(pluginDataFolder.getAbsolutePath(), directoryName).listFiles();
            if (filesInDataDir == null) return Collections.emptyList();

            return Arrays.stream(filesInDataDir)
                    .map(File::getName)
                    .filter(fileName -> fileName.lastIndexOf('.') > 0)
                    .map(fileName -> fileName.substring(0, fileName.lastIndexOf('.')))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Long getNextId(String directoryName) {
        List<String> existentIds = getAllIds(directoryName);

        if (existentIds.isEmpty()) return 1L;

        boolean allIdsAreParseable = existentIds.stream()
                .filter(stringId -> stringId.matches("\\d+"))
                .count() == existentIds.size();

        if (!allIdsAreParseable) {
            log.warn("Unable to get next id for directory " + directoryName + ". Not all ids are Integer parseable.");
            return null;
        }

        return existentIds.stream()
                .map(Long::parseLong)
                .max(Long::compareTo)
                .orElse(0L) + 1;
    }

}
