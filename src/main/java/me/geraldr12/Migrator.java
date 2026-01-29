package me.geraldr12;

public interface Migrator {

    void migrate();

    void archiveOldData();

    String getOldDataVersion();

}
