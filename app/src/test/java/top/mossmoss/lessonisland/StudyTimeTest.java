package top.mossmoss.lessonisland;

import static org.junit.Assert.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class StudyTimeTest {
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

  private static long wall(String iso) {
    return Instant.parse(iso).toEpochMilli();
  }

  private static StudyTime.DayConsumer into(Map<String, Long> days) {
    return (date, millis) -> days.merge(date, millis, Long::sum);
  }

  @Test
  public void unfinishedStudyCheckpointsAndTransitionsCountExactlyOnce() throws Exception {
    StudyTime clock = new StudyTime();
    Map<String, Long> days = new LinkedHashMap<>();
    long now = wall("2026-11-15T02:00:00Z");
    assertEquals(0, clock.update(true, 0, now, SHANGHAI, into(days)));
    assertEquals(1234, clock.update(true, 1234, now + 1234, SHANGHAI, into(days)));
    assertEquals(0, clock.update(true, 1234, now + 1234, SHANGHAI, into(days)));
    assertEquals(766, clock.update(false, 2000, now + 2000, SHANGHAI, into(days)));
    // Time on home/parent pages or in the background never enters the ledger.
    assertEquals(0, clock.update(false, 92000, now + 92000, SHANGHAI, into(days)));
    assertEquals(0, clock.update(true, 95000, now + 95000, SHANGHAI, into(days)));
    assertEquals(3000, clock.update(false, 98000, now + 98000, SHANGHAI, into(days)));
    assertEquals(Long.valueOf(5000), days.get("2026-11-15"));
    assertFalse(clock.isStudying());
  }

  @Test
  public void midnightSplitsTheUnfinishedIntervalBetweenBothDays() throws Exception {
    StudyTime clock = new StudyTime();
    Map<String, Long> days = new LinkedHashMap<>();
    long now = wall("2026-11-15T15:59:58.500Z");
    clock.update(true, 100, now, SHANGHAI, into(days));
    assertEquals(5000, clock.update(false, 5100, now + 5000, SHANGHAI, into(days)));
    assertEquals(Long.valueOf(1500), days.get("2026-11-15"));
    assertEquals(Long.valueOf(3500), days.get("2026-11-16"));
  }

  @Test
  public void daylightSavingDatesKeepTheirActualLengths() throws Exception {
    ZoneId newYork = ZoneId.of("America/New_York");
    Map<String, Long> spring = new LinkedHashMap<>();
    StudyTime.allocate(wall("2026-03-08T05:00:00Z"), 24 * 3600000L, newYork, into(spring));
    assertEquals(Long.valueOf(23 * 3600000L), spring.get("2026-03-08"));
    assertEquals(Long.valueOf(3600000L), spring.get("2026-03-09"));
    Map<String, Long> autumn = new LinkedHashMap<>();
    StudyTime.allocate(wall("2026-11-01T04:00:00Z"), 26 * 3600000L, newYork, into(autumn));
    assertEquals(Long.valueOf(25 * 3600000L), autumn.get("2026-11-01"));
    assertEquals(Long.valueOf(3600000L), autumn.get("2026-11-02"));
  }

  @Test
  public void wallClockCorrectionsNeverCreateOrLoseElapsedStudyTime() throws Exception {
    StudyTime clock = new StudyTime();
    Map<String, Long> days = new LinkedHashMap<>();
    long now = wall("2026-11-15T02:00:00Z");
    clock.update(true, 1000, now, SHANGHAI, into(days));
    assertEquals(10000, clock.update(true, 11000, now + 3600000L, SHANGHAI, into(days)));
    assertEquals(10000, clock.update(false, 21000, now - 3600000L, SHANGHAI, into(days)));
    assertEquals(Long.valueOf(20000), days.get("2026-11-15"));
  }

  @Test
  public void changingTimeZoneUsesThePreviousZoneUntilItsCheckpoint() throws Exception {
    StudyTime clock = new StudyTime();
    Map<String, Long> days = new LinkedHashMap<>();
    long now = wall("2026-11-15T23:59:58Z");
    clock.update(true, 0, now, ZoneId.of("UTC"), into(days));
    clock.update(true, 4000, now + 4000, ZoneId.of("America/New_York"), into(days));
    clock.update(false, 7000, now + 7000, ZoneId.of("America/New_York"), into(days));
    assertEquals(Long.valueOf(5000), days.get("2026-11-15"));
    assertEquals(Long.valueOf(2000), days.get("2026-11-16"));
    assertEquals(7000L, days.values().stream().mapToLong(Long::longValue).sum());
  }

  @Test
  public void resettingMonotonicClockDoesNotCreateAPhantomInterval() throws Exception {
    StudyTime clock = new StudyTime();
    Map<String, Long> days = new LinkedHashMap<>();
    long now = wall("2026-11-15T02:00:00Z");
    clock.update(true, 90000, now, SHANGHAI, into(days));
    assertEquals(0, clock.update(true, 50, now + 10000, SHANGHAI, into(days)));
    assertEquals(950, clock.update(false, 1000, now + 10950, SHANGHAI, into(days)));
    assertEquals(Long.valueOf(950), days.get("2026-11-15"));
  }
}
