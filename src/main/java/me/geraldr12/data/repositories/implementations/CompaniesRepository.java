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
    public void save(CompanyEntity dataEntity) {
        if (!isDataSourceValid()) return;
        dataSource.save(DataFilePath.COMPANIES.getFullPathById(String.valueOf(dataEntity.getId())), dataEntity);
    }

    @Override
    public void delete(Long id) {
        if (!isDataSourceValid()) return;
        dataSource.delete(DataFilePath.COMPANIES.getFullPathById(String.valueOf(id)));
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

    public Long getNextId() {
        if (!isDataSourceValid()) return 1L;

        File dataFile = new File(this.pluginDataFolder, DataFilePath.COMPANIES.getFullPathById("data"));

        // Ensure parent directories exist
        if (!dataFile.getParentFile().exists()) {
            dataFile.getParentFile().mkdirs();
        }

        if (!dataFile.exists()) {
            return 1L; // Return 1 if the file doesn't exist yet
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            CompanyDataYml companyDataYml = mapper.readValue(dataFile, CompanyDataYml.class);
            return companyDataYml != null && companyDataYml.nextId != null ? companyDataYml.nextId : 1L;
        } catch (IOException e) {
            return 1L; // Default to 1 on error
        }
    }

    public void incrementNextId() {
        if (!isDataSourceValid()) return;

        File dataFile = new File(this.pluginDataFolder, DataFilePath.COMPANIES.getFullPathById("data"));
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        try {
            CompanyDataYml companyDataYml;
            if (dataFile.exists()) {
                companyDataYml = mapper.readValue(dataFile, CompanyDataYml.class);
                if (companyDataYml == null) companyDataYml = new CompanyDataYml(1L);
            } else {
                companyDataYml = new CompanyDataYml(1L);
            }

            companyDataYml.nextId++;
            mapper.writeValue(dataFile, companyDataYml);
        } catch (IOException e) {
            e.printStackTrace();
        }
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