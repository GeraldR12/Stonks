package me.geraldr12.v110.destination;

import me.geraldr12.data.dao.CompanyDao;

import java.util.Optional;

public interface CompanyDestination {

    Optional<CompanyDao> findById(int companyId);

    boolean exists(int companyId);

    boolean save(CompanyDao company);

    boolean delete(int companyId);

    void raiseNextIdTo(long target);
}
