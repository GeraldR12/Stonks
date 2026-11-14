package me.geraldr12.api.endpoints.implementation;

import me.geraldr12.api.endpoints.APIEndpointImpl;
import me.geraldr12.api.entities.PluginReleaseEntity;

import java.io.IOException;

public class PluginReleaseAPIEndpoint extends APIEndpointImpl<PluginReleaseEntity> {

    public PluginReleaseAPIEndpoint() {
        super("");
    }

    @Override
    public PluginReleaseEntity parseData() throws IOException {
        return null;
    }

}
