package me.geraldr12.data.sources.yml.implementations;

import me.geraldr12.data.entities.CompanyEntity;
import me.geraldr12.data.sources.yml.YmlDataSourceImpl;

import java.io.File;

public class CompaniesYml extends YmlDataSourceImpl<CompanyEntity> {

    public CompaniesYml(File pluginDataFolder) {
        super(pluginDataFolder);
    }

}
