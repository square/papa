package tart

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS

class CpuDurationTest {

  @Test fun `uptime with same unit is equal`() {
    val duration = CpuDuration(NANOSECONDS, 42, 1024)
    assertThat(duration.uptime(NANOSECONDS)).isEqualTo(42)
  }

  @Test fun `realtime with same unit is equal`() {
    val duration = CpuDuration(NANOSECONDS, 42, 1024)
    assertThat(duration.realtime(NANOSECONDS)).isEqualTo(1024)
  }

  @Test fun `uptime converted is equal`() {
    val duration = CpuDuration(MILLISECONDS, 42, 1024)
    assertThat(duration.uptime(NANOSECONDS)).isEqualTo(42 * 1_000_000L)
  }

  @Test fun `realtime converted is equal`() {
    val duration = CpuDuration(MILLISECONDS, 42, 1024)
    assertThat(duration.realtime(NANOSECONDS)).isEqualTo(1024 * 1_000_000L)
  }

  @Test fun `minus tracks uptime difference`() {
    val start = CpuDuration(NANOSECONDS, 42, 1024)
    val end = CpuDuration(NANOSECONDS, 42 + 6L, 1024 + 8L)

    val duration = end - start

    assertThat(duration.uptime(NANOSECONDS)).isEqualTo(6)
  }

  @Test fun `minus tracks realtime difference`() {
    val start = CpuDuration(NANOSECONDS, 42, 1024)
    val end = CpuDuration(NANOSECONDS, 42 + 6L, 1024 + 8L)

    val duration = end - start

    assertThat(duration.realtime(NANOSECONDS)).isEqualTo(8)
  }

  @Test fun `minus tracks uptime difference when converted`() {
    val start = CpuDuration(NANOSECONDS, 42 * 1_000_000L, 1024 * 1_000_000L)
    val end = CpuDuration(MILLISECONDS, 42 + 6L, 1024 + 8L)

    val duration = end - start

    assertThat(duration.uptime(MILLISECONDS)).isEqualTo(6)
  }

  @Test fun `minus tracks realtime difference when converted`() {
    val start = CpuDuration(NANOSECONDS, 42 * 1_000_000L, 1024 * 1_000_000L)
    val end = CpuDuration(MILLISECONDS, 42 + 6L, 1024 + 8L)

    val duration = end - start

    assertThat(duration.realtime(MILLISECONDS)).isEqualTo(8)
  }

  @Test fun `minus converts to smallest unit`() {
    val start = CpuDuration(NANOSECONDS, 42 * 1_000_000L, 1024 * 1_000_000L)
    val end = CpuDuration(MILLISECONDS, 42 + 6L, 1024 + 8L)

    assertThat((end - start).unit).isEqualTo(NANOSECONDS)
    assertThat((start - end).unit).isEqualTo(NANOSECONDS)
  }

  @Test fun `minus conserves uptime precision of smallest unit`() {
    val start = CpuDuration(MILLISECONDS, 42, 1024)
    val end = CpuDuration(NANOSECONDS, 42_000_006L, 1024_000_008L)

    val duration = end - start

    assertThat(duration.uptime(NANOSECONDS)).isEqualTo(6)
  }

  @Test fun `minus conserves realtime precision of smallest unit`() {
    val start = CpuDuration(MILLISECONDS, 42, 1024)
    val end = CpuDuration(NANOSECONDS, 42_000_006L, 1024_000_008L)

    val duration = end - start

    assertThat(duration.realtime(NANOSECONDS)).isEqualTo(8)
  }
}