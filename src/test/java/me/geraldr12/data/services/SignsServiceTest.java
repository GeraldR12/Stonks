package me.geraldr12.data.services;

import me.geraldr12.data.dao.LocationDao;
import me.geraldr12.data.dao.SignDao;
import me.geraldr12.data.entities.SignEntity;
import me.geraldr12.data.repositories.implementations.SignsRepository;
import me.geraldr12.utils.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Sign records are tracked by block: one per block, removed by position, found per chunk. */
class SignsServiceTest {

    private final TreeMap<Integer, SignEntity> store = new TreeMap<>();
    private final SignsRepository repository = mock(SignsRepository.class);
    private final World world = mock(World.class);
    private SignsService service;
    private int reads;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        when(repository.getAllIds()).thenAnswer(i -> {
            reads++;
            return new ArrayList<>(store.keySet());
        });
        when(repository.getById(anyInt())).thenAnswer(i -> Optional.ofNullable(store.get((Integer) i.getArgument(0))));
        when(repository.exists(anyInt())).thenAnswer(i -> store.containsKey((Integer) i.getArgument(0)));
        when(repository.getNextId()).thenAnswer(i -> (long) (store.isEmpty() ? 1 : store.lastKey() + 1));
        when(repository.save(any())).thenAnswer(i -> {
            SignEntity e = i.getArgument(0);
            store.put(e.getId(), e);
            return true;
        });
        when(repository.delete(anyInt())).thenAnswer(i -> store.remove((Integer) i.getArgument(0)) != null);
        service = new SignsService(repository, mock(CompaniesService.class), mock(Messages.class));
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private void legacyRecord(int id, long company, double x, double y, double z) {
        store.put(id, SignDao.builder().id(id).companyId(company)
                .location(new LocationDao("world", x, y, z)).build().toEntity());
    }

    @Test
    void placingASignWhereOneWasReplacesTheRecord() {
        service.createSign(1, at(10, 64, 10));
        service.createSign(2, at(10, 64, 10));
        assertEquals(1, store.size());
        assertEquals(2, store.firstEntry().getValue().getCompanyId());
    }

    @Test
    void breakingRemovesTheRecordWhateverTheSignSays() {
        service.createSign(1, at(-25700, 71, -14106));
        service.createSign(1, at(5, 70, 5));
        assertEquals(1, service.deleteSignsAt(at(-25700, 71, -14106)));
        assertEquals(1, store.size());
        assertFalse(service.hasSignAt("world", -25700, 71, -14106));
        assertTrue(service.hasSignAt("world", 5, 70, 5));
    }

    @Test
    void duplicatesFromOlderVersionsKeepTheNewest() {
        legacyRecord(3, 7, -25700, 71, -14106);
        legacyRecord(9, 8, -25700, 71, -14106);
        SignDao found = service.getSignByLocation(at(-25699.5, 71, -14105.2));
        assertNotNull(found);
        assertEquals(9, found.getId());
        assertEquals(1, store.size());
    }

    @Test
    void negativeCoordinatesMapToTheRightBlockAndChunk() {
        assertEquals(-1, SignsService.blockToChunk(-0.5));
        assertEquals(-1, SignsService.blockToChunk(-16));
        assertEquals(-2, SignsService.blockToChunk(-17));
        assertEquals(0, SignsService.blockToChunk(15.9));
        assertTrue(SignsService.sameBlock(new LocationDao("world", -3.0, 64, 2.0), "world", -3, 64, 2));
        assertFalse(SignsService.sameBlock(new LocationDao("nether", -3.0, 64, 2.0), "world", -3, 64, 2));
        assertFalse(SignsService.sameBlock(null, "world", 0, 0, 0));
    }

    @Test
    void chunkLookupFindsOnlyThatChunksSigns() {
        service.createSign(1, at(-25700, 71, -14106));
        service.createSign(1, at(100, 70, 100));
        List<SignDao> chunk = service.getSignsInChunk("world", -25700 >> 4, -14106 >> 4);
        assertEquals(1, chunk.size());
        assertTrue(service.getSignsInChunk("world", 50, 50).isEmpty());
        assertTrue(service.getSignsInChunk("nether", -25700 >> 4, -14106 >> 4).isEmpty());
    }

    @Test
    void chunksWithoutSignsAreAnsweredFromMemory() {
        service.createSign(1, at(0, 64, 0));
        service.getSignsInChunk("world", 1, 1);
        int before = reads;
        for (int i = 0; i < 200; i++) service.getSignsInChunk("world", 10 + i, 3);
        assertNull(service.getSignByLocation(at(500, 64, 500)));
        assertEquals(before, reads, "empty chunks and ordinary signs must not read the sign files");
    }

    @Test
    void indexFollowsDeletes() {
        SignDao sign = service.createSign(1, at(1, 2, 3));
        assertTrue(service.hasSignAt("world", 1, 2, 3));
        service.deleteSign(sign.getId());
        assertFalse(service.hasSignAt("world", 1, 2, 3));
        assertFalse(service.hasSignsInChunk("world", 0, 0));
    }
}
