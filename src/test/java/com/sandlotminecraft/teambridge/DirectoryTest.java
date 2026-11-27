package com.sandlotminecraft.teambridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectoryTest {

    static final UUID A = UUID.fromString("224a6d9c-0000-4000-8000-00000000000a");
    static final UUID B = UUID.fromString("456983c5-0000-4000-8000-00000000000b");

    final Harness h = new Harness();
    final Directory directory = h.directory;

    @Test
    void idOfMatchesCaseInsensitivelyAfterColourStripping() {
        directory.put(A, "Snowballs");
        assertEquals(Optional.of(A), directory.idOf("snowballs"));
        assertEquals(Optional.of(A), directory.idOf("SNOWBALLS"));
        assertEquals(Optional.of(A), directory.idOf("§aSnow§lballs"));
        assertEquals(Optional.of(A), directory.idOf("&bSnowballs"));
        assertEquals(Optional.empty(), directory.idOf("Snow"));
        assertEquals(Optional.empty(), directory.idOf(""));
        assertEquals(Optional.empty(), directory.idOf(null));
    }

    @Test
    void ambiguousIdOfReturnsEmptyAndLogs() {
        directory.put(A, "Snowballs");
        directory.put(B, "SNOWBALLS");
        assertEquals(Optional.empty(), directory.idOf("snowballs"));
        assertTrue(h.logged("ambiguous team name 'snowballs': 2 parties match"));
    }

    @Test
    void ambiguityAfterColourStripping() {
        directory.put(A, "Elves");
        directory.put(B, "§cElves");
        assertEquals(Optional.empty(), directory.idOf("elves"));
        assertTrue(h.logged("ambiguous"));
    }

    @Test
    void tombstoneIsNeverPutBack() {
        directory.put(A, "Alpha");
        assertTrue(directory.delete(A));
        assertFalse(directory.put(A, "Alpha"));
        assertFalse(directory.fill(A, "Alpha"));
        assertFalse(directory.rename(A, "Beta"));
        assertFalse(directory.has(A));
        assertFalse(directory.delete(A));
        assertTrue(directory.isTombstoned(A));
    }

    @Test
    void fillKeepsAnExistingEntry() {
        directory.put(A, "Alpha");
        assertFalse(directory.fill(A, "Old"));
        assertEquals(Optional.of("Alpha"), directory.nameOf(A));
    }

    @Test
    void snapshotIsPublishedAfterEveryChangeAndImmutable() {
        assertEquals(List.of(), directory.snapshot());
        directory.put(A, "Alpha");
        List<String> first = directory.snapshot();
        assertEquals(List.of("Alpha"), first);
        directory.rename(A, "Beta");
        assertEquals(List.of("Beta"), directory.snapshot());
        assertEquals(List.of("Alpha"), first);
        directory.fill(B, "Bravo");
        assertEquals(2, directory.snapshot().size());
        directory.delete(A);
        assertEquals(List.of("Bravo"), directory.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> directory.snapshot().add("x"));
    }

    @Test
    void nullNameShowsShortHex() {
        directory.put(A, null);
        assertEquals(Optional.of("#224a6d9c"), directory.nameOf(A));
        assertEquals("#224a6d9c", Directory.displayName(A, null));
        assertEquals("Alpha", Directory.displayName(A, "Alpha"));
    }
}
