package com.fasterxml.uuid.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.uuid.EthernetAddress;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.TimestampSynchronizer;
import com.fasterxml.uuid.UUIDClock;
import com.fasterxml.uuid.UUIDComparator;
import com.fasterxml.uuid.UUIDTimer;
import com.fasterxml.uuid.UUIDType;
import com.fasterxml.uuid.ext.FileBasedTimestampSynchronizer;
import com.fasterxml.uuid.impl.UUIDInspector.Fields;
import com.fasterxml.uuid.impl.UUIDInspector.Relation;
import com.fasterxml.uuid.impl.UUIDInspector.SequenceSource;

/**
 * Boundary-vector tests for the clock / sequence / node chain of
 * time-based UUID generation (versions 1, 6 and 7).
 *<p>
 * All vectors use a fixed {@link UUIDClock}, a fixed
 * {@link EthernetAddress} node and a synchronizer stand-in, so results
 * do not depend on wall-clock scheduling, random seeds drawn at runtime,
 * or retry counts. No background threads are started and no temporary
 * files are created.
 */
public class TimeBasedBoundaryTest
{
    // Fixed boundary inputs shared by the vectors
    private static final EthernetAddress FIXED_NODE = new EthernetAddress("00:11:22:33:44:55");
    private static final EthernetAddress OTHER_NODE = new EthernetAddress("00:11:22:33:44:66");
    private static final long FIXED_MILLIS = 1_000_000L;

    // Offset between 15-Oct-1582 and 01-Jan-1970 in 100-ns units;
    // mirrors UUIDTimer internals, used to compute the wrap-around vector.
    private static final long GREGORIAN_OFFSET = 0x01b21dd213814000L;

    /*
    /**********************************************************************
    /* Test doubles: fixed clock and synchronizer stand-in
    /**********************************************************************
     */

    static final class FixedClock extends UUIDClock {
        private long _time;
        FixedClock(long time) { _time = time; }
        void set(long time) { _time = time; }
        @Override public long currentTimeMillis() { return _time; }
    }

    static final class StubSynchronizer extends TimestampSynchronizer {
        long initialValue;
        long nextUnsafe = Long.MAX_VALUE;
        int initializeCalls;
        final List<Long> updates = new ArrayList<>();
        IOException initializeFailure;
        IOException updateFailure;

        StubSynchronizer(long initialValue) { this.initialValue = initialValue; }

        @Override
        protected long initialize() throws IOException {
            ++initializeCalls;
            if (initializeFailure != null) { throw initializeFailure; }
            return initialValue;
        }

        @Override
        protected long update(long now) throws IOException {
            if (updateFailure != null) { throw updateFailure; }
            updates.add(now);
            return nextUnsafe;
        }

        @Override
        protected void deactivate() { }
    }

    private static TimeBasedGenerator v1Generator(EthernetAddress node, UUIDTimer timer) {
        return Generators.timeBasedGenerator(node, timer);
    }

    private static UUIDTimer timer(Random rnd, TimestampSynchronizer sync, UUIDClock clock) {
        try {
            return new UUIDTimer(rnd, sync, clock);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /*
    /**********************************************************************
    /* Same-millisecond burst: in-process sub-millisecond sequence
    /**********************************************************************
     */

    @Test
    public void sameMillisecondBurstUsesInProcessSequence()
    {
        FixedClock clock = new FixedClock(FIXED_MILLIS);
        UUIDTimer timer = timer(new Random(42L), null, clock);
        TimeBasedGenerator gen = v1Generator(FIXED_NODE, timer);

        Set<UUID> seen = new HashSet<>();
        UUID previous = null;
        boolean counterUsed = false;
        for (int i = 0; i < 200; ++i) {
            UUID uuid = gen.generate();
            assertTrue(seen.add(uuid), "duplicate UUID in same-millisecond burst");
            Fields f = UUIDInspector.inspect(uuid);
            assertEquals(UUIDType.TIME_BASED, f.type());
            assertEquals(FIXED_MILLIS, f.epochMillis());
            if (f.sequenceSource() == SequenceSource.SUB_MILLIS_COUNTER) {
                counterUsed = true;
            }
            if (previous != null) {
                Fields p = UUIDInspector.inspect(previous);
                assertTrue(f.rawTimestamp() > p.rawTimestamp(),
                        "timestamps must increase within one millisecond");
                assertEquals(Relation.SAME_TICK_SEQUENCE, UUIDInspector.relate(previous, uuid));
            }
            previous = uuid;
        }
        assertTrue(counterUsed, "sub-millisecond counter never engaged");
    }

    /*
    /**********************************************************************
    /* Wall-clock rollback: virtual time stays monotonic
    /**********************************************************************
     */

    @Test
    public void wallClockRollbackKeepsMonotonicVirtualTime()
    {
        FixedClock clock = new FixedClock(5000L);
        UUIDTimer timer = timer(new Random(42L), null, clock);
        TimeBasedGenerator gen = v1Generator(FIXED_NODE, timer);

        UUID before = gen.generate();
        assertEquals(5000L, UUIDInspector.inspect(before).epochMillis());

        // Wall clock moves one full second backwards
        clock.set(4000L);
        UUID after1 = gen.generate();
        UUID after2 = gen.generate();

        Fields fBefore = UUIDInspector.inspect(before);
        Fields fAfter1 = UUIDInspector.inspect(after1);
        Fields fAfter2 = UUIDInspector.inspect(after2);

        // Virtual time does not follow the wall clock backwards...
        assertTrue(fAfter1.rawTimestamp() > fBefore.rawTimestamp());
        assertTrue(fAfter2.rawTimestamp() > fAfter1.rawTimestamp());
        // ...it reuses the last-used millisecond and disambiguates with the counter
        assertEquals(5000L, fAfter1.epochMillis());
        assertEquals(SequenceSource.SUB_MILLIS_COUNTER, fAfter1.sequenceSource());
        assertEquals(Relation.SAME_TICK_SEQUENCE, UUIDInspector.relate(before, after1));
        assertEquals(Relation.SAME_TICK_SEQUENCE, UUIDInspector.relate(after1, after2));
    }

    /*
    /**********************************************************************
    /* Cross-JVM same node: probabilistic uniqueness via clock sequence
    /**********************************************************************
     */

    @Test
    public void crossJvmSameNodeReliesOnRandomClockSequence()
    {
        // Two independent "processes" (separate timers, no shared
        // synchronizer) on the same node and the same fixed clock.
        UUIDTimer timerA = timer(new Random(1L), null, new FixedClock(FIXED_MILLIS));
        UUIDTimer timerB = timer(new Random(2L), null, new FixedClock(FIXED_MILLIS));
        UUID uuidA = v1Generator(FIXED_NODE, timerA).generate();
        UUID uuidB = v1Generator(FIXED_NODE, timerB).generate();

        Fields a = UUIDInspector.inspect(uuidA);
        Fields b = UUIDInspector.inspect(uuidB);

        // Same node, same millisecond: the random clock sequence is the
        // only signal separating the two processes. With these fixed
        // seeds the sequences differ; in general this is probabilistic
        // and NOT an absolute uniqueness guarantee.
        assertEquals(FIXED_NODE, a.node());
        assertEquals(FIXED_NODE, b.node());
        assertNotEquals(a.clockSequence(), b.clockSequence());
        assertNotEquals(uuidA, uuidB);
        assertEquals(Relation.CLOCK_SEQUENCE_CHANGED, UUIDInspector.relate(uuidA, uuidB));
    }

    /*
    /**********************************************************************
    /* Synchronizer stand-in: cross-process floor and failure handling
    /**********************************************************************
     */

    @Test
    public void synchronizerStandInProvidesCrossProcessFloor()
    {
        // Simulates a previous incarnation (possibly another JVM) that
        // persisted 2_000_000 ms as the last-used timestamp, while the
        // local wall clock is still at 1_000_000 ms.
        StubSynchronizer sync = new StubSynchronizer(2_000_000L);
        sync.nextUnsafe = 10_000_000L;
        FixedClock clock = new FixedClock(FIXED_MILLIS);
        UUIDTimer timer = timer(new Random(7L), sync, clock);
        TimeBasedGenerator gen = v1Generator(FIXED_NODE, timer);

        UUID uuid = gen.generate();
        Fields f = UUIDInspector.inspect(uuid);

        // The synchronized floor wins over the (lower) wall clock
        assertEquals(2_000_000L, f.epochMillis());
        assertEquals(1, sync.initializeCalls);
        assertEquals(1, sync.updates.size());
        assertEquals(2_000_000L, sync.updates.get(0).longValue());
    }

    @Test
    public void synchronizerUnavailableFailsFast()
    {
        // Negative vector 1: synchronizer initialization fails ->
        // factory wraps it as IllegalArgumentException, no UUID produced.
        StubSynchronizer brokenInit = new StubSynchronizer(0L);
        brokenInit.initializeFailure = new IOException("simulated unreadable state file");
        try {
            Generators.timeBasedGenerator(FIXED_NODE, brokenInit);
            fail("Expected IllegalArgumentException for broken synchronizer");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Failed to create UUIDTimer"));
        }

        // Negative vector 2: update fails mid-stream -> generation fails
        // instead of silently emitting unsynchronized UUIDs.
        StubSynchronizer brokenUpdate = new StubSynchronizer(0L);
        brokenUpdate.updateFailure = new IOException("simulated lock loss");
        UUIDTimer timer = timer(new Random(7L), brokenUpdate, new FixedClock(FIXED_MILLIS));
        TimeBasedGenerator gen = v1Generator(FIXED_NODE, timer);
        try {
            gen.generate();
            fail("Expected RuntimeException for failing synchronizer update");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Failed to synchronize timestamp"));
        }
    }

    @Test
    public void fileBasedSynchronizerWithMissingDirectoryFailsWithoutArtifacts() throws Exception
    {
        // Negative vector: lock files in a nonexistent directory cannot be
        // created; construction must fail and must not create anything.
        File missingDir = new File("jug-no-such-dir-boundary-test");
        assertFalse(missingDir.exists(), "precondition: directory must not exist");
        try {
            new FileBasedTimestampSynchronizer(
                    new File(missingDir, "uuid1.lck"), new File(missingDir, "uuid2.lck"));
            fail("Expected IOException for lock files in missing directory");
        } catch (IOException e) {
            // expected
        }
        assertFalse(missingDir.exists(), "test must not leave directories behind");
    }

    /*
    /**********************************************************************
    /* Custom UUIDClock: deterministic timestamps end up in the UUID
    /**********************************************************************
     */

    @Test
    public void customClockDeterminesTimestamp()
    {
        FixedClock clock = new FixedClock(123456789L);
        UUIDTimer timer = timer(new Random(42L), null, clock);
        UUID uuid = v1Generator(FIXED_NODE, timer).generate();

        Fields f = UUIDInspector.inspect(uuid);
        assertEquals(123456789L, f.epochMillis());
        assertEquals(FIXED_NODE, f.node());
        assertEquals(2, f.variant());
        // Diagnostic output exposes the full clock/sequence/node chain
        String description = UUIDInspector.describe(uuid);
        assertTrue(description.contains("version=1"), description);
        assertTrue(description.contains("epochMillis=123456789"), description);
        assertTrue(description.contains("node=" + FIXED_NODE.toString()), description);
    }

    /*
    /**********************************************************************
    /* Version bit layout reuse: v1 and v6 share clock-seq/node layout
    /**********************************************************************
     */

    @Test
    public void versions1And6ShareSequenceAndNodeLayout()
    {
        FixedClock clock = new FixedClock(FIXED_MILLIS);
        UUIDTimer timer = timer(new Random(42L), null, clock);
        UUID v1 = v1Generator(FIXED_NODE, timer).generate();
        UUID v6 = Generators.timeBasedReorderedGenerator(FIXED_NODE, timer).generate();

        Fields f1 = UUIDInspector.inspect(v1);
        Fields f6 = UUIDInspector.inspect(v6);
        assertEquals(1, f1.version());
        assertEquals(6, f6.version());
        // Same reused layout: variant, clock sequence and node identical
        assertEquals(2, f1.variant());
        assertEquals(2, f6.variant());
        assertEquals(f1.clockSequence(), f6.clockSequence());
        assertEquals(timer.getClockSequence() & 0x3FFF, f1.clockSequence());
        assertEquals(f1.node(), f6.node());
        // Same time unit (100 ns since 15-Oct-1582), same millisecond
        assertEquals(FIXED_MILLIS, f1.epochMillis());
        assertEquals(FIXED_MILLIS, f6.epochMillis());
    }

    /*
    /**********************************************************************
    /* Ordering: v1 string order diverges from time order; v6/v7 do not
    /**********************************************************************
     */

    @Test
    public void version1StringOrderDivergesFromTimeOrder()
    {
        // Pick two consecutive millisecond ticks such that the low 32 bits
        // of the 100-ns timestamp (time_low, printed first in v1 strings)
        // wrap around between them.
        long low32 = GREGORIAN_OFFSET & 0xFFFFFFFFL;
        long wrapMillis = ((0x1_0000_0000L - low32) + 9999L) / 10000L;

        FixedClock clock = new FixedClock(wrapMillis - 1L);
        UUIDTimer timer = timer(new Random(42L), null, clock);
        TimeBasedGenerator gen = v1Generator(FIXED_NODE, timer);

        UUID earlier = gen.generate();
        clock.set(wrapMillis);
        UUID later = gen.generate();

        // Sanity: time really advanced
        assertTrue(UUIDUtil.extractTimestamp(later) > UUIDUtil.extractTimestamp(earlier));
        assertEquals(Relation.CLOCK_ADVANCED, UUIDInspector.relate(earlier, later));
        // ...and the dedicated comparator sees the time order...
        assertTrue(new UUIDComparator().compare(earlier, later) < 0);
        // ...but the canonical string order is reversed for version 1,
        // because time_low (the fast-changing bits) is printed first.
        assertTrue(later.toString().compareTo(earlier.toString()) < 0,
                "v1 string order should diverge from time order at time_low wrap");
    }

    @Test
    public void versions6And7StringOrderFollowsTimeOrder()
    {
        FixedClock clock = new FixedClock(FIXED_MILLIS);
        UUIDTimer timer = timer(new Random(42L), null, clock);
        TimeBasedReorderedGenerator v6 = Generators.timeBasedReorderedGenerator(FIXED_NODE, timer);

        UUID v6Earlier = v6.generate();
        clock.set(FIXED_MILLIS + 10L);
        UUID v6Later = v6.generate();
        assertTrue(UUIDUtil.extractTimestamp(v6Later) > UUIDUtil.extractTimestamp(v6Earlier));
        assertTrue(v6Later.toString().compareTo(v6Earlier.toString()) > 0);

        FixedClock clock7 = new FixedClock(FIXED_MILLIS);
        TimeBasedEpochGenerator v7 = Generators.timeBasedEpochGenerator(new Random(42L), clock7);
        UUID v7Earlier = v7.generate();
        clock7.set(FIXED_MILLIS + 10L);
        UUID v7Later = v7.generate();
        assertEquals(7, UUIDInspector.inspect(v7Earlier).version());
        assertTrue(UUIDUtil.extractTimestamp(v7Later) > UUIDUtil.extractTimestamp(v7Earlier));
        assertTrue(v7Later.toString().compareTo(v7Earlier.toString()) > 0);
    }

    /*
    /**********************************************************************
    /* v7 same-millisecond behavior (documented boundary)
    /**********************************************************************
     */

    @Test
    public void version7SameMillisecondStaysDistinct()
    {
        FixedClock clock = new FixedClock(FIXED_MILLIS);
        TimeBasedEpochGenerator gen = Generators.timeBasedEpochGenerator(new Random(42L), clock);
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 100; ++i) {
            UUID uuid = gen.generate();
            assertTrue(seen.add(uuid), "v7 duplicate within same millisecond");
            Fields f = UUIDInspector.inspect(uuid);
            assertEquals(FIXED_MILLIS, f.epochMillis());
            // v7 carries no clock sequence or node: remaining bits are random
            assertFalse(f.hasClockSequence());
            assertNull(f.node());
            assertEquals(SequenceSource.NOT_APPLICABLE, f.sequenceSource());
        }
    }
}
