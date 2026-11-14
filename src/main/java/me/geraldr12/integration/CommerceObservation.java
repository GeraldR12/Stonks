package me.geraldr12.integration;

import java.time.Instant;

record CommerceObservation(Instant observedAt, long quantity, double volume,
                           boolean volumeAvailable) {
}
