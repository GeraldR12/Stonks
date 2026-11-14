package me.geraldr12.data.sources.yml.implementations;

import me.geraldr12.data.entities.PlayerEntity;
import me.geraldr12.data.sources.yml.YmlDataSourceImpl;

import java.io.File;

public class PlayersYml extends YmlDataSourceImpl<PlayerEntity> {

    public PlayersYml(File pluginDataFolder) {
        super(pluginDataFolder);
    }

}
