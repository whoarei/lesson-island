package top.mossmoss.lessonisland;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Foreground study time, independent of quiz completion and resumable quiz state. */
public final class StudyTime {
  @FunctionalInterface
  public interface DayConsumer {
    void add(String date, long millis) throws Exception;
  }

  private boolean anchored;
  private boolean studying;
  private long elapsedAt;
  private long wallAt;
  private ZoneId zoneAt;

  /**
   * Settle time since the previous checkpoint using its study state, then select the next state.
   * elapsedNow must be monotonic (SystemClock.elapsedRealtime on Android); wallNow only assigns dates.
   * A changed wall clock or time zone applies to subsequent intervals, never to their duration.
   */
  public long update(
      boolean nextStudying, long elapsedNow, long wallNow, ZoneId zone, DayConsumer consumer)
      throws Exception {
    long duration = anchored && studying && elapsedNow >= elapsedAt ? elapsedNow - elapsedAt : 0;
    long start = wallAt;
    ZoneId intervalZone = zoneAt;
    // Advance the checkpoint even if a consumer fails, so retrying cannot duplicate a credited span.
    anchored = true;
    studying = nextStudying;
    elapsedAt = elapsedNow;
    wallAt = wallNow;
    zoneAt = zone;
    if (duration > 0) allocate(start, duration, intervalZone, consumer);
    return duration;
  }

  public boolean isStudying() {
    return studying;
  }

  public static String dateKey(long wallMillis, ZoneId zone) {
    return Instant.ofEpochMilli(wallMillis).atZone(zone).toLocalDate().toString();
  }

  /** Divide an actual elapsed duration at local midnight, including short/long daylight-saving days. */
  public static void allocate(long startWallMillis, long duration, ZoneId zone, DayConsumer consumer)
      throws Exception {
    long cursor = startWallMillis;
    long remaining = Math.max(0, duration);
    while (remaining > 0) {
      LocalDate date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
      long midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
      long part = Math.min(remaining, midnight - cursor);
      if (part <= 0) throw new IllegalArgumentException("Invalid study-time date boundary");
      consumer.add(date.toString(), part);
      remaining -= part;
      if (remaining > 0) cursor = Math.addExact(cursor, part);
    }
  }
}
