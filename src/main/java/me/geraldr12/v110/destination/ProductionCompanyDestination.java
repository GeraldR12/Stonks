package me.geraldr12.v110.destination;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.enums.DataFilePath;
import me.geraldr12.v110.MigrationFileOps;

import java.io.File;
import java.util.Optional;

public final class ProductionCompanyDestination implements CompanyDestination {

    private final CompaniesService service;
    private final CompaniesRepository repository;
    private final File dataDirectory;

    public ProductionCompanyDestination(CompaniesService service, File dataDirectory) {
        this.service = service;
        this.repository = (CompaniesRepository) service.getRepository();
        this.dataDirectory = dataDirectory;
    }

    @Override
    public Optional<CompanyDao> findById(int companyId) {
        return Optional.ofNullable(service.getCompanyById(companyId));
    }

    @Override
    public boolean exists(int companyId) {
        return repository.exists((long) companyId);
    }

    @Override
    public boolean save(CompanyDao company) {
        return !repository.exists((long) company.getId()) && repository.save(company.toEntity());
    }

    @Override
    public boolean delete(int companyId) {
        return repository.delete((long) companyId);
    }

    @Override
    public void raiseNextIdTo(long target) {
        CompanyIdCounter.raiseTo(new File(dataDirectory, DataFilePath.COMPANIES.getFullPathById("data")), target,
                MigrationFileOps.DEFAULT);
    }
}
