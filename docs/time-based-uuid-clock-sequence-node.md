# Time-based UUIDs: clock, sequence and node chain

Implementation note for the time-based generation paths of
java-uuid-generator (JUG): how `Generators.timeBasedGenerator` and its
v6/v7 siblings turn a wall-clock reading, an in-process sequence and a
node address into a UUID, which alternatives were considered for the
clock / synchronization / bit-layout design, and how the boundary
behavior is verified offline.

Scope rules followed by this change:

- Existing module boundaries are kept: all new code lives in the already
  exported `com.fasterxml.uuid.impl` package; no existing class,
  constructor or factory signature was modified.
- Production callers keep using the public entry points
  (`com.fasterxml.uuid.Generators`); nothing here requires or encourages
  bypassing them.
- The change is purely additive (one read-only diagnostic class, tests,
  this document). Callers that do not use the new diagnostic API see no
  behavioral or performance difference: no fields, locks, threads or
  file handles were added to any existing code path.

## 1. Generation path trace (v1 / v6 / v7)

### Version 1 — `TimeBasedGenerator`

Call graph for one `generate()` call:

1. `Generators.timeBasedGenerator(EthernetAddress, UUIDTimer)` —
   `src/main/java/com/fasterxml/uuid/Generators.java:305` (public entry;
   `null` timer falls back to the lazily created shared timer,
   `Generators.java:362`).
2. `TimeBasedGenerator.generate()` —
   `src/main/java/com/fasterxml/uuid/impl/TimeBasedGenerator.java:90`
   delegates the timestamp to the timer.
3. `UUIDTimer.getTimestamp()` —
   `src/main/java/com/fasterxml/uuid/UUIDTimer.java:242` (synchronized;
   the single in-process serialization point):
   - reads the wall clock via `UUIDClock.currentTimeMillis()`
     (`src/main/java/com/fasterxml/uuid/UUIDClock.java:41`; custom
     `UUIDClock` subclasses are the supported way to inject fixed or
     shifted time),
   - detects wall-clock rollback (`UUIDTimer.java:247`) and reuses the
     last-used virtual timestamp instead of following the clock
     backwards,
   - disambiguates same-millisecond calls with the in-process
     sub-millisecond counter (`_clockCounter`, seeded from a random
     8-bit residual in `initCounters`, `UUIDTimer.java:213`),
   - if the counter exhausts 10000 values within one millisecond, the
     virtual clock is allowed to run at most `kMaxClockAdvance` = 100 ms
     ahead of the wall clock before `slowDown()` throttles
     (`UUIDTimer.java:98`, `UUIDTimer.java:388`),
   - if a `TimestampSynchronizer` is configured, persists/refreshes the
     cross-process floor (`UUIDTimer.java:304`; initial floor read in
     the constructor, `UUIDTimer.java:201`),
   - converts to UUID time units: `millis * 10000 + kClockOffset`
     (100-ns units since 15-Oct-1582; constants at
     `UUIDTimer.java:85` and `UUIDTimer.java:90`).
4. `TimeBasedGenerator.construct(long)` —
   `TimeBasedGenerator.java:109` slices the 60-bit timestamp into
   time_low / time_mid / time_hi_and_version and sets the version
   nibble to 1.
5. Node and clock sequence are fixed at construction
   (`TimeBasedGenerator.java:54`): the `EthernetAddress` is written to
   bytes 10-15 (`TimeBasedGenerator.java:62`), the timer's 16-bit clock
   sequence to bytes 8-9 (`TimeBasedGenerator.java:64`), and
   `UUIDUtil.initUUIDSecondLong`
   (`src/main/java/com/fasterxml/uuid/impl/UUIDUtil.java:218`) forces
   the variant bits to `10`, masking the sequence to 14 bits.

### Version 6 — `TimeBasedReorderedGenerator`

Identical clock / sequence / node chain (same `UUIDTimer`, same
second-long layout, same constructor pattern); only the first-long bit
layout differs: `construct(long)` —
`src/main/java/com/fasterxml/uuid/impl/TimeBasedReorderedGenerator.java:115`
— places the most significant 48 timestamp bits first (version nibble 6
at the same byte offset), so lexicographic order matches time order.

### Version 7 — `TimeBasedEpochGenerator` / `TimeBasedEpochRandomGenerator`

- `TimeBasedEpochGenerator.construct(long)` —
  `src/main/java/com/fasterxml/uuid/impl/TimeBasedEpochGenerator.java:133`:
  48-bit Unix-epoch **millisecond** timestamp (not 100-ns units) in the
  most significant bits; the remaining 74 bits come from a random source
  (`_randomNextBytes`, defaulting to a shared `SecureRandom` —
  `TimeBasedEpochGenerator.java:80`). Within one millisecond a monotonic
  "entropy carry" increments the random field instead of re-randomizing,
  keeping values distinct and ordered.
- `TimeBasedEpochRandomGenerator.construct(long)` —
  `src/main/java/com/fasterxml/uuid/impl/TimeBasedEpochRandomGenerator.java:119`:
  same layout, but re-randomizes on every call (guarded by a
  `ReentrantLock`), trading per-call randomness for weaker same-ms
  similarity.
- Neither version carries a clock sequence or node field; there is no
  cross-process synchronization hook on this path.
- Type/variant bit encoding is shared through
  `UUIDUtil.constructUUID(UUIDType, long, long)` —
  `UUIDUtil.java:195`.

### Field encoding summary

| Version | Time unit | Timestamp bits | Clock sequence | Node | Random source |
|---------|-----------|----------------|----------------|------|----------------|
| v1 | 100 ns since 1582-10-15 | 60, shuffled (low/mid/hi) | 14 bits, bytes 8-9 | bytes 10-15 | `Random` for sequence + counter seed (`UUIDTimer`) |
| v6 | 100 ns since 1582-10-15 | 60, reordered MSB-first | 14 bits, bytes 8-9 | bytes 10-15 | same as v1 |
| v7 | 1 ms since 1970-01-01 | 48, MSB-first | none | none | `Random`/`SecureRandom` fills 74 bits |

Decoding mirrors encoding in `UUIDUtil.extractTimestamp`
(`UUIDUtil.java:368`) and the `_getRawTimestampFromUuidV1/V6/V7`
helpers (`UUIDUtil.java:402`, `UUIDUtil.java:420`, `UUIDUtil.java:430`);
the new `UUIDInspector` (see section 4) builds on those.

## 2. Alternatives considered and final trade-offs

### 2a. Time monotonicity vs. real-time accuracy

Options:

1. **Strict real time**: always trust `currentTimeMillis()`; on rollback,
   fail or block until the wall clock catches up.
2. **Strict monotonicity (chosen)**: never emit a timestamp lower than
   the last used one; absorb rollbacks by reusing the last-used
   millisecond plus the sub-millisecond counter, and allow the virtual
   clock to drift ahead of the wall clock by a bounded amount
   (`kMaxClockAdvance` = 100 ms) before throttling in `slowDown()`.

Chosen: option 2. Uniqueness and per-generator ordering are the
properties UUID consumers rely on; timestamps may be up to ~100 ms ahead
of real time under sustained bursts, and stale (reused) milliseconds for
the duration of a rollback. Evidence:
`TimeBasedBoundaryTest.wallClockRollbackKeepsMonotonicVirtualTime`
shows a 1-second rollback producing monotonically increasing timestamps
that stay on the last-used (virtual) millisecond.

### 2b. In-process sequence vs. cross-process synchronization

Options:

1. **In-process only (default)**: `UUIDTimer.getTimestamp()` is
   `synchronized`; the 10000-values-per-millisecond counter (9500
   usable after the random 8-bit residual) plus a random clock sequence
   make collisions across *separate JVMs on the same node* unlikely but
   **not impossible** — this is probabilistic uniqueness, not a
   guarantee.
2. **File-based cross-process sync (opt-in)**:
   `com.fasterxml.uuid.ext.FileBasedTimestampSynchronizer` reserves
   10-second timestamp windows through two alternating lock files, so
   the guarantee extends across JVMs and restarts on one machine.
3. **Custom `TimestampSynchronizer`**: the abstract SPI
   (`src/main/java/com/fasterxml/uuid/TimestampSynchronizer.java`)
   allows other stores (database, coordination service).

Chosen: keep option 1 as the zero-dependency default, keep option 2/3
as explicit opt-ins via `Generators.timeBasedGenerator(addr, sync)`.
Failure handling is fail-fast: a synchronizer that cannot initialize
aborts generator construction (`IllegalArgumentException` from the
factory); a synchronizer whose `update` fails aborts generation
(`RuntimeException`) rather than silently emitting unsynchronized
UUIDs. Evidence:
`TimeBasedBoundaryTest.synchronizerStandInProvidesCrossProcessFloor`,
`synchronizerUnavailableFailsFast`,
`fileBasedSynchronizerWithMissingDirectoryFailsWithoutArtifacts`.

### 2c. Version bit-layout reuse

Options:

1. Duplicate the second-long assembly per generator class.
2. **Share the layout code (chosen)**: v1 and v6 reuse the same
   constructor pattern (node at bytes 10-15, clock sequence at bytes
   8-9) and the same variant/type bit setters
   (`UUIDUtil.initUUIDSecondLong`, `UUIDUtil.constructUUID`); v7 reuses
   `UUIDUtil.constructUUID(UUIDType, long, long)`. Decoding reuses
   `UUIDUtil._getRawTimestampFromUuidV*`.

Chosen: option 2 — one source of truth for variant/version bits.
Evidence: `TimeBasedBoundaryTest.versions1And6ShareSequenceAndNodeLayout`
decodes identical variant, clock sequence and node from v1 and v6 UUIDs
built on one timer.

## 3. Boundary scenarios and failure classification

The five boundary scenarios are covered by deterministic vectors (fixed
`UUIDClock`, fixed `EthernetAddress`, synchronizer stand-in; no
background threads, no temporary files, no dependence on random
scheduling or retry counts):

| Scenario | Expected behavior | Test |
|----------|-------------------|------|
| Same-millisecond burst | In-process sub-millisecond counter keeps timestamps unique and increasing | `sameMillisecondBurstUsesInProcessSequence` |
| Wall-clock rollback | Virtual time stays monotonic on the last-used millisecond | `wallClockRollbackKeepsMonotonicVirtualTime` |
| Cross-JVM, same node, no sync | Random clock sequence is the only separator; probabilistic, **not** guaranteed | `crossJvmSameNodeReliesOnRandomClockSequence` |
| Synchronizer unavailable | Fail fast at construction or at generation; no silent fallback, no artifacts | `synchronizerUnavailableFailsFast`, `fileBasedSynchronizerWithMissingDirectoryFailsWithoutArtifacts` |
| Custom `UUIDClock` | Injected time flows verbatim into the timestamp fields | `customClockDeterminesTimestamp` |

## 4. Diagnostic output: `UUIDInspector`

New read-only diagnostics live in
`src/main/java/com/fasterxml/uuid/impl/UUIDInspector.java`:

- `inspect(UUID)` decodes version, variant, raw timestamp, epoch
  millis, 14-bit clock sequence, node address and a `SequenceSource`
  classification (`CLOCK_TICK` vs `SUB_MILLIS_COUNTER` — i.e. whether
  the in-process sequence was engaged) for v1/v6/v7, with explicit
  "not applicable" values for other versions.
- `relate(UUID, UUID)` classifies an ordered pair into the failure
  taxonomy: `NODE_CHANGED`, `CLOCK_SEQUENCE_CHANGED`, `NON_MONOTONIC`,
  `SAME_TIMESTAMP`, `SAME_TICK_SEQUENCE`, `CLOCK_ADVANCED`,
  `NOT_COMPARABLE` — covering the in-process-sequence and
  cross-process-sync signals from section 3.
- `describe(UUID)` renders a deterministic one-line diagnostic, e.g.
  `UUID{version=1, variant=2, type=TIME_BASED, epochMillis=123456789,
  rawTimestamp=..., clockSequence=..., node=00:11:22:33:44:55,
  sequenceSource=SUB_MILLIS_COUNTER}`.

The class is stateless and side-effect free: it starts no threads,
opens no files and holds no locks, so there is no shutdown path to
manage. (Contrast: `FileBasedTimestampSynchronizer` holds file locks by
design and offers `deactivate()`; callers that never opt into it —
including all users of this new API — are unaffected.)

## 5. Ordering differences between versions

- **v1**: canonical string order is **not** time order — the
  fast-changing `time_low` is printed first.
  `TimeBasedBoundaryTest.version1StringOrderDivergesFromTimeOrder`
  constructs two consecutive-millisecond UUIDs straddling a `time_low`
  32-bit wrap: the later UUID sorts *before* the earlier one as a
  string, while `UUIDComparator`
  (`src/main/java/com/fasterxml/uuid/UUIDComparator.java:22`) and
  `UUIDUtil.extractTimestamp` still see the true time order.
- **v6**: reordered MSB-first timestamp; string order matches time
  order across clock ticks.
- **v7**: 48-bit epoch millis MSB-first; string order matches time
  order across millisecond ticks (within one millisecond, order depends
  on the random/entropy bits).

Evidence: `versions6And7StringOrderFollowsTimeOrder`.

## 6. Verification evidence (reproducible offline)

All evidence is produced without network access:

```
./mvnw -q -DskipTests package   # preparation
./mvnw -q test                  # acceptance
```

- `com.fasterxml.uuid.impl.TimeBasedBoundaryTest` — 11 boundary
  vectors (section 3 and 5), all with fixed clock/node/synchronizer
  stand-ins.
- `com.fasterxml.uuid.impl.UUIDInspectorTest` — 10 tests for field
  decoding, pair classification and negative inputs (null UUID,
  non-time-based UUID, Nil UUID, mixed-layout pairs).
- Pre-existing suites (including `UUIDTimerTest`, `UUIDComparatorTest`,
  `ext.LockedFileTest`) remain green, confirming no regression for
  callers that do not use the new API.
- Tests exit without leaving background work or temporary artifacts:
  the file-based negative vector points at a nonexistent directory and
  asserts nothing is created; no threads are started by any new code.
