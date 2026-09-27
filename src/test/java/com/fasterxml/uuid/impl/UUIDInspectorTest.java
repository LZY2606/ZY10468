package com.fasterxml.uuid.impl;

import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.uuid.EthernetAddress;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.UUIDClock;
import com.fasterxml.uuid.UUIDTimer;
import com.fasterxml.uuid.UUIDType;
import com.fasterxml.uuid.impl.UUIDInspector.Fields;
import com.fasterxml.uuid.impl.UUIDInspector.Relation;
import com.fasterxml.uuid.impl.UUIDInspector.SequenceSource;

/**
 * Unit tests for {@link UUIDInspector} field decoding and pair
 * classification, using fixed inputs only.
 */
public class UUIDInspectorTest
{
    private static final EthernetAddress NODE = new EthernetAddress("00:11:22:33:44:55");

    static final class FixedClock extends UUIDClock {
        private final long _time;
        FixedClock(long time) { _time = time; }
        @Override public long currentTimeMillis() { return _time; }
    }

    private static UUIDTimer timer(long seed, long millis) {
        try {
            return new UUIDTimer(new Random(seed), null, new FixedClock(millis));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void inspectVersion1Fields()
    {
        UUIDTimer timer = timer(42L, 1_000_000L);
        UUID uuid = Generators.timeBasedGenerator(NODE, timer).generate();

        Fields f = UUIDInspector.inspect(uuid);
        assertEquals(UUIDType.TIME_BASED, f.type());
        assertEquals(1, f.version());
        assertEquals(2, f.variant());
        assertTrue(f.hasTimestamp());
        assertEquals(1_000_000L, f.epochMillis());
        assertTrue(f.hasClockSequence());
        // Generator writes the 16-bit sequence; variant bits mask it to 14
        assertEquals(timer.getClockSequence() & 0x3FFF, f.clockSequence());
        assertEquals(NODE, f.node());
    }

    @Test
    public void inspectVersion6Fields()
    {
        UUIDTimer timer = timer(42L, 1_000_000L);
        UUID uuid = Generators.timeBasedReorderedGenerator(NODE, timer).generate();

        Fields f = UUIDInspector.inspect(uuid);
        assertEquals(UUIDType.TIME_BASED_REORDERED, f.type());
        assertEquals(6, f.version());
        assertEquals(2, f.variant());
        assertEquals(1_000_000L, f.epochMillis());
        assertEquals(NODE, f.node());
    }

    @Test
    public void inspectVersion7Fields()
    {
        UUID uuid = Generators.timeBasedEpochGenerator(new Random(42L),
                new FixedClock(1_000_000L)).generate();

        Fields f = UUIDInspector.inspect(uuid);
        assertEquals(UUIDType.TIME_BASED_EPOCH, f.type());
        assertEquals(7, f.version());
        assertEquals(2, f.variant());
        assertTrue(f.hasTimestamp());
        assertEquals(1_000_000L, f.epochMillis());
        assertFalse(f.hasClockSequence());
        assertEquals(-1, f.clockSequence());
        assertNull(f.node());
        assertEquals(SequenceSource.NOT_APPLICABLE, f.sequenceSource());
    }

    @Test
    public void inspectNonTimeBasedUuid()
    {
        UUID uuid = Generators.randomBasedGenerator(new Random(42L)).generate();

        Fields f = UUIDInspector.inspect(uuid);
        assertEquals(UUIDType.RANDOM_BASED, f.type());
        assertEquals(4, f.version());
        assertEquals(2, f.variant());
        assertFalse(f.hasTimestamp());
        assertEquals(0L, f.rawTimestamp());
        assertEquals(0L, f.epochMillis());
        assertFalse(f.hasClockSequence());
        assertNull(f.node());
        assertEquals(SequenceSource.NOT_APPLICABLE, f.sequenceSource());
    }

    @Test
    public void inspectNilUuid()
    {
        Fields f = UUIDInspector.inspect(UUIDUtil.nilUUID());
        assertEquals(UUIDType.UNKNOWN, f.type());
        assertEquals(0, f.version());
        assertEquals(0, f.variant());
        assertFalse(f.hasTimestamp());
    }

    @Test
    public void inspectNullFails()
    {
        try {
            UUIDInspector.inspect(null);
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }
        try {
            UUIDInspector.describe((UUID) null);
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }
    }

    @Test
    public void relateDetectsNodeChange()
    {
        UUIDTimer timer = timer(42L, 1_000_000L);
        UUID a = Generators.timeBasedGenerator(NODE, timer).generate();
        UUID b = Generators.timeBasedGenerator(new EthernetAddress("00:11:22:33:44:66"),
                timer).generate();
        assertEquals(Relation.NODE_CHANGED, UUIDInspector.relate(a, b));
    }

    @Test
    public void relateDetectsNonMonotonicPair()
    {
        // Direct construct() calls bypass UUIDTimer monotonicity: an
        // out-of-order pair is classified as NON_MONOTONIC.
        UUIDTimer timer = timer(42L, 1_000_000L);
        TimeBasedGenerator gen = Generators.timeBasedGenerator(NODE, timer);
        UUID later = gen.generate();
        UUID earlier = gen.construct(UUIDInspector.inspect(later).rawTimestamp() - 1L);
        assertEquals(Relation.NON_MONOTONIC, UUIDInspector.relate(later, earlier));
    }

    @Test
    public void relateRejectsMixedLayouts()
    {
        UUID v1 = Generators.timeBasedGenerator(NODE, timer(42L, 1_000_000L)).generate();
        UUID v4 = Generators.randomBasedGenerator(new Random(42L)).generate();
        UUID v7 = Generators.timeBasedEpochGenerator(new Random(42L),
                new FixedClock(1_000_000L)).generate();
        assertEquals(Relation.NOT_COMPARABLE, UUIDInspector.relate(v1, v4));
        assertEquals(Relation.NOT_COMPARABLE, UUIDInspector.relate(v7, v1));
    }

    @Test
    public void describeIsDeterministicAndComplete()
    {
        UUIDTimer timer = timer(42L, 1_000_000L);
        UUID uuid = Generators.timeBasedGenerator(NODE, timer).generate();
        String description = UUIDInspector.describe(uuid);
        assertEquals(description, UUIDInspector.describe(uuid));
        assertEquals(description, UUIDInspector.inspect(uuid).toString());
        assertTrue(description.startsWith("UUID{version=1"), description);
        assertTrue(description.contains("variant=2"), description);
        assertTrue(description.contains("clockSequence="), description);
        assertTrue(description.contains("sequenceSource="), description);
    }
}
