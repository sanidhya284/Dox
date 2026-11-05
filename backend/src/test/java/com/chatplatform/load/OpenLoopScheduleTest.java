package com.chatplatform.load;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenLoopScheduleTest {

    @Test
    void intendedTimesAreFixedByTheRateNotByResponses() {
        OpenLoopSchedule s = new OpenLoopSchedule(1_000L, 200.0);
        assertThat(s.intendedNanos(0)).isEqualTo(1_000L);
        assertThat(s.intendedNanos(1)).isEqualTo(1_000L + 5_000_000L);
        assertThat(s.intendedNanos(200)).isEqualTo(1_000L + 1_000_000_000L);
    }

    @Test
    void nonIntegerIntervalsDoNotDrift() {
        OpenLoopSchedule s = new OpenLoopSchedule(0L, 3.0); // 333.33 ms apart
        assertThat(s.intendedNanos(3)).isEqualTo(1_000_000_000L);
        assertThat(s.intendedNanos(3_000)).isEqualTo(1_000_000_000_000L);
    }

    @Test
    void countDueByAnInstant() {
        OpenLoopSchedule s = new OpenLoopSchedule(0L, 10.0);
        assertThat(s.countDueBy(-1)).isEqualTo(0);
        assertThat(s.countDueBy(0)).isEqualTo(1);
        assertThat(s.countDueBy(999_999_999L)).isEqualTo(10);
        assertThat(s.countDueBy(1_000_000_000L)).isEqualTo(11);
    }

    @Test
    void rateMustBePositive() {
        assertThatThrownBy(() -> new OpenLoopSchedule(0L, 0.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
