package io.github.damienmiheev.monobank.testkit

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [Clock] that tests can move forward, used by [FakeMonobankAcquiring] for invoice validity,
 * the 9-day hold period and every date it reports.
 *
 * - [system] follows real time plus an adjustable offset: time passes normally, and [advance]
 *   jumps ahead (e.g. 9 days to let a hold lapse).
 * - [fixed] stands still until [advance] or [set] moves it.
 *
 * Thread-safe. Copies made with [withZone] share the same time.
 */
public class TestClock private constructor(
    private val state: State,
    private val zone: ZoneId,
) : Clock() {
    private class State(
        var fixed: Instant?,
        var offset: Duration,
    )

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(state, zone)

    override fun instant(): Instant = synchronized(state) { state.fixed ?: Instant.now().plus(state.offset) }

    /**
     * Moves the clock forward by [duration].
     *
     * @throws IllegalArgumentException when [duration] is negative.
     */
    public fun advance(duration: Duration) {
        require(!duration.isNegative) { "duration must not be negative" }
        synchronized(state) {
            state.fixed = state.fixed?.plus(duration)
            state.offset = state.offset.plus(duration)
        }
    }

    /** Sets the current time to [instant] (it may move backwards; dates the fake reports never do). */
    public fun set(instant: Instant) {
        synchronized(state) {
            if (state.fixed != null) {
                state.fixed = instant
            } else {
                state.offset = Duration.between(Instant.now(), instant)
            }
        }
    }

    override fun toString(): String = "TestClock(${instant()})"

    public companion object {
        /** A clock that follows real time (UTC) and can be moved forward with [advance]. */
        @JvmStatic
        public fun system(): TestClock = TestClock(State(null, Duration.ZERO), ZoneOffset.UTC)

        /** A clock frozen at [instant] (UTC) until [advance] or [set] moves it. */
        @JvmStatic
        public fun fixed(instant: Instant): TestClock = TestClock(State(instant, Duration.ZERO), ZoneOffset.UTC)
    }
}
