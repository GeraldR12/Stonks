package me.geraldr12.data.sources;

import me.geraldr12.data.entities.DataEntity;

public interface DataSource<T extends DataEntity> {

    T load(String fileName, Class<T> dataEntityClass);

    void save(String fileName, T data);

    boolean exists(String fileName);

    void delete(String fileName);

}
