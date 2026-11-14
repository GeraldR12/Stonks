package me.geraldr12.data.sources.yml;

import me.geraldr12.data.entities.DataEntity;
import me.geraldr12.data.sources.DataSource;

import java.util.List;

public interface YmlDataSource<T extends DataEntity> extends DataSource<T> {

    List<String> getAllIds(String directoryName);
    Long getNextId(String directoryName);

}
