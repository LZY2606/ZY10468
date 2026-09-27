package com.fasterxml.uuid.impl;

import java.util.UUID;

import com.fasterxml.uuid.EthernetAddress;
import com.fasterxml.uuid.UUIDTimer;
import com.fasterxml.uuid.UUIDType;

/**
 * Diagnostic tool that decodes the field layout of {@link UUID} instances
 * produced by JUG generators (versions 1, 6 and 7 in particular) and
 * classifies boundary behavior observed between consecutive UUIDs.
 *<p>
 * The intent is to make the clock / sequence / node chain of time-based
 * UUIDs observable for diagnostics and tests, without changing any
 * generation behavior: this class is read-only and side-effect free.
 *<p>
 * NOTE: uniqueness of time-based UUIDs that rely on random clock sequences
 * (rather than on an external
 * {@link com.fasterxml.uuid.TimestampSynchronizer}) is probabilistic;
 * diagnostics exposed here describe the signals used, they do not turn
 * that probabilistic uniqueness into an absolute guarantee.
 *
 * @since 5.2
 */
public final class UUIDInspector
{
    private final static long SUB_MILLIS_UNITS_PER_MILLIS = 10000L;

    private UUIDInspector() { }

    /*
    /**********************************************************************
    /* Enums
    /**********************************************************************
     */

    /**
     * Classification of the mechanism that supplied the sub-tick ordering
     * information embedded in a time-based UUID.
     */
    public enum SequenceSource {
        /**
         * UUID version does not carry an in-process sequence (for example
         * random- or name-based UUIDs, or version 7 where remaining bits
         * are filled from a random source).
         */
        NOT_APPLICABLE,

        /**
         * Timestamp is exactly aligned to a clock tick: the in-process
         * sub-millisecond counter of {@link UUIDTimer} was at zero.
         */
        CLOCK_TICK,

        /**
         * Sub-millisecond bits were supplied by the in-process counter of
         * {@link UUIDTimer}: either multiple UUIDs were generated within
         * the same millisecond, or the wall clock moved backwards and the
         * last-used (virtual) timestamp was reused.
         */
        SUB_MILLIS_COUNTER
    }

    /**
     * Failure / boundary classification for an ordered pair of UUIDs that
     * share the version 1 / version 6 field layout (clock sequence and
     * node in the second long). Used to diagnose which part of the
     * clock / sequence / node chain explains observed ordering.
     */
    public enum Relation {
        /**
         * At least one of the UUIDs does not carry comparable clock
         * sequence / node fields (for example version 4 or 7 UUIDs).
         */
        NOT_COMPARABLE,

        /**
         * Node fields differ: UUIDs came from different locations
         * (different hosts, NICs, or explicitly configured addresses).
         */
        NODE_CHANGED,

        /**
         * Same node but clock sequences differ: generator restart,
         * counter overflow re-seeding, or a separate process on the same
         * node without external synchronization. Uniqueness across such
         * processes is probabilistic, not guaranteed.
         */
        CLOCK_SEQUENCE_CHANGED,

        /**
         * Same node and clock sequence, but the second timestamp is lower
         * than the first: monotonicity violation. Not expected from
         * {@link UUIDTimer#getTimestamp()} but possible when
         * {@code construct(long)} is called directly with arbitrary
         * timestamps.
         */
        NON_MONOTONIC,

        /**
         * Same node, clock sequence and timestamp value: the time fields
         * are identical.
         */
        SAME_TIMESTAMP,

        /**
         * Same millisecond tick, ordering provided by the in-process
         * sub-millisecond counter (same-millisecond burst or absorbed
         * wall-clock rollback).
         */
        SAME_TICK_SEQUENCE,

        /**
         * Timestamp advanced normally: the wall clock tick advanced
         * between the two UUIDs.
         */
        CLOCK_ADVANCED
    }

    /*
    /**********************************************************************
    /* Decoded fields
    /**********************************************************************
     */

    /**
     * Immutable holder for fields decoded from a single {@link UUID}.
     */
    public static final class Fields {
        private final UUIDType _type;
        private final int _version;
        private final int _variant;
        private final long _rawTimestamp;
        private final long _epochMillis;
        private final int _clockSequence;
        private final EthernetAddress _node;
        private final SequenceSource _sequenceSource;

        Fields(UUIDType type, int version, int variant,
                long rawTimestamp, long epochMillis,
                int clockSequence, EthernetAddress node,
                SequenceSource sequenceSource)
        {
            _type = type;
            _version = version;
            _variant = variant;
            _rawTimestamp = rawTimestamp;
            _epochMillis = epochMillis;
            _clockSequence = clockSequence;
            _node = node;
            _sequenceSource = sequenceSource;
        }

        /**
         * @return {@link UUIDType} as decoded from the version nibble
         *   ({@link UUIDType#UNKNOWN} for the Nil UUID, may be null for
         *   unrecognized version nibbles)
         */
        public UUIDType type() { return _type; }

        /**
         * @return Raw version nibble (bits 12-15 of the most significant
         *   long)
         */
        public int version() { return _version; }

        /**
         * @return Variant as encoded by the most significant bits of byte 8:
         *   0 = NCS reserved, 2 = RFC 4122/9562, 6 = Microsoft reserved,
         *   7 = reserved for future definition
         */
        public int variant() { return _variant; }

        /**
         * @return True if this UUID version embeds a timestamp
         */
        public boolean hasTimestamp() {
            return _type == UUIDType.TIME_BASED
                    || _type == UUIDType.TIME_BASED_REORDERED
                    || _type == UUIDType.TIME_BASED_EPOCH;
        }

        /**
         * @return Raw timestamp as embedded: 100-ns units since 15-Oct-1582
         *   UTC for versions 1 and 6, milliseconds since 01-Jan-1970 UTC for
         *   version 7, 0 for other versions
         */
        public long rawTimestamp() { return _rawTimestamp; }

        /**
         * @return Timestamp converted to milliseconds since 01-Jan-1970 UTC,
         *   or 0 if the version carries no timestamp
         */
        public long epochMillis() { return _epochMillis; }

        /**
         * @return True if this UUID version embeds a clock sequence and node
         *   (versions 1 and 6)
         */
        public boolean hasClockSequence() { return _clockSequence >= 0; }

        /**
         * @return 14-bit clock sequence for versions 1 and 6, -1 otherwise
         */
        public int clockSequence() { return _clockSequence; }

        /**
         * @return Node (MAC-style address) for versions 1 and 6, null otherwise
         */
        public EthernetAddress node() { return _node; }

        /**
         * @return Classification of the sub-tick ordering mechanism
         */
        public SequenceSource sequenceSource() { return _sequenceSource; }

        @Override
        public String toString() { return describe(this); }
    }

    /*
    /**********************************************************************
    /* Public API
    /**********************************************************************
     */

    /**
     * Decode the field layout of the given UUID.
     *
     * @param uuid UUID to inspect (must not be null)
     *
     * @return Decoded fields
     */
    public static Fields inspect(UUID uuid)
    {
        if (uuid == null) {
            throw new NullPointerException("Can not inspect null UUID");
        }
        final UUIDType type = UUIDUtil.typeOf(uuid);
        final long msb = uuid.getMostSignificantBits();
        final long lsb = uuid.getLeastSignificantBits();
        final int version = (int) (msb >>> 12) & 0xF;
        final int variant = decodeVariant((int) (lsb >>> 56));

        long rawTimestamp = 0L;
        long epochMillis = 0L;
        int clockSequence = -1;
        EthernetAddress node = null;
        SequenceSource sequenceSource = SequenceSource.NOT_APPLICABLE;

        if (type == UUIDType.TIME_BASED || type == UUIDType.TIME_BASED_REORDERED) {
            // Versions 1 and 6 share the second-long layout (clock sequence
            // + node); only the timestamp bit order in the first long differs.
            rawTimestamp = (type == UUIDType.TIME_BASED)
                    ? UUIDUtil._getRawTimestampFromUuidV1(uuid)
                    : UUIDUtil._getRawTimestampFromUuidV6(uuid);
            epochMillis = UUIDTimer.timestampToEpoch(rawTimestamp);
            clockSequence = ((int) (lsb >>> 56) & 0x3F) << 8
                    | ((int) (lsb >>> 48) & 0xFF);
            node = new EthernetAddress(lsb & 0xFFFFFFFFFFFFL);
            sequenceSource = (rawTimestamp % SUB_MILLIS_UNITS_PER_MILLIS == 0L)
                    ? SequenceSource.CLOCK_TICK
                    : SequenceSource.SUB_MILLIS_COUNTER;
        } else if (type == UUIDType.TIME_BASED_EPOCH) {
            rawTimestamp = UUIDUtil._getRawTimestampFromUuidV7(uuid);
            epochMillis = rawTimestamp;
        }
        return new Fields(type, version, variant, rawTimestamp, epochMillis,
                clockSequence, node, sequenceSource);
    }

    /**
     * Classify the boundary relationship between two UUIDs that are
     * expected to share the version 1 / version 6 field layout.
     *
     * @param first UUID generated (or observed) first
     * @param second UUID generated (or observed) after the first
     *
     * @return Failure / boundary classification for the pair
     */
    public static Relation relate(UUID first, UUID second)
    {
        Fields a = inspect(first);
        Fields b = inspect(second);
        if (!a.hasClockSequence() || !b.hasClockSequence()) {
            return Relation.NOT_COMPARABLE;
        }
        if (a.node().toLong() != b.node().toLong()) {
            return Relation.NODE_CHANGED;
        }
        if (a.clockSequence() != b.clockSequence()) {
            return Relation.CLOCK_SEQUENCE_CHANGED;
        }
        long diff = b.rawTimestamp() - a.rawTimestamp();
        if (diff < 0L) {
            return Relation.NON_MONOTONIC;
        }
        if (diff == 0L) {
            return Relation.SAME_TIMESTAMP;
        }
        if (a.epochMillis() == b.epochMillis()) {
            return Relation.SAME_TICK_SEQUENCE;
        }
        return Relation.CLOCK_ADVANCED;
    }

    /**
     * Produce a deterministic single-line diagnostic description of the
     * given UUID, covering version/variant layout, timestamp, clock
     * sequence, node and sequence source.
     *
     * @param uuid UUID to describe (must not be null)
     *
     * @return Diagnostic description
     */
    public static String describe(UUID uuid) {
        return describe(inspect(uuid));
    }

    /**
     * Produce a deterministic single-line diagnostic description of
     * previously decoded fields.
     *
     * @param f Fields as returned by {@link #inspect(UUID)}
     *
     * @return Diagnostic description
     */
    public static String describe(Fields f)
    {
        StringBuilder sb = new StringBuilder(128);
        sb.append("UUID{version=").append(f.version())
          .append(", variant=").append(f.variant())
          .append(", type=").append(f.type());
        if (f.hasTimestamp()) {
            sb.append(", epochMillis=").append(f.epochMillis())
              .append(", rawTimestamp=").append(f.rawTimestamp());
        }
        if (f.hasClockSequence()) {
            sb.append(", clockSequence=").append(f.clockSequence())
              .append(", node=").append(f.node());
        }
        sb.append(", sequenceSource=").append(f.sequenceSource());
        return sb.append('}').toString();
    }

    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */

    private static int decodeVariant(int mostSignificantByte)
    {
        if ((mostSignificantByte & 0x80) == 0x00) {
            return 0; // NCS backward compatibility
        }
        if ((mostSignificantByte & 0xC0) == 0x80) {
            return 2; // RFC 4122 / RFC 9562
        }
        if ((mostSignificantByte & 0xE0) == 0xC0) {
            return 6; // Microsoft reserved
        }
        return 7; // reserved for future definition
    }
}
