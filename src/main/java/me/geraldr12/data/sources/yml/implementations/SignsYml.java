package me.geraldr12.data.sources.yml.implementations;

import me.geraldr12.data.entities.SignEntity;
import me.geraldr12.data.sources.yml.YmlDataSourceImpl;

import java.io.File;

public class SignsYml extends YmlDataSourceImpl<SignEntity> {

    public SignsYml(File pluginDataFolder) {
        super(pluginDataFolder);
    }

}
