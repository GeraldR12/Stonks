package me.geraldr12.data.services;

import me.geraldr12.data.repositories.Repository;

public interface Service {

    Repository<?, ?> getRepository();

}
