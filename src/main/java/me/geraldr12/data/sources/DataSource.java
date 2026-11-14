package me.geraldr12.data.sources;

import me.geraldr12.data.entities.DataEntity;

public interface DataSource<T extends DataEntity> {

    T load(String fileName, Class<T> dataEntityClass);

    boolean save(String fileName, T data);

    boolean exists(String fileName);

    boolean delete(String fileName);

}
