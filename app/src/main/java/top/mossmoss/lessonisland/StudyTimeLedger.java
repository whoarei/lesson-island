package top.mossmoss.lessonisland;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Persistent milliseconds by local date. The presence of FIELD is the one-time migration marker. */
public final class StudyTimeLedger {
  public static final String FIELD = "studyMillisByDay";

  private StudyTimeLedger() {}

  /** Call before migrating/removing legacy session fields, and before discarding imported sessions. */
  public static boolean initialize(JSONObject state, long wallNow, ZoneId zone) throws JSONException {
    if (state.has(FIELD)) {
      validate(state);
      return false;
    }
    JSONObject days = new JSONObject();
    JSONArray history = state.optJSONArray("history");
    if (history != null) {
      for (int i = 0; i < history.length(); i++) {
        JSONObject record = history.optJSONObject(i);
        if (record == null) continue;
        long time = record.optLong("time", -1);
        long seconds = record.optLong("seconds", 0);
        if (time >= 0 && seconds > 0)
          addTo(days, StudyTime.dateKey(time, zone), secondsToMillis(seconds));
      }
    }
    // Old history only knows completion dates. A pending legacy session only knows its start date;
    // preserve these totals without inventing an unrecoverable historical distribution across days.
    Set<String> credited = new HashSet<>();
    seedSession(days, state.optJSONObject("session"), wallNow, zone, credited);
    for (String field : new String[] {"courseSessions", "reviewSessions"}) {
      JSONObject sessions = state.optJSONObject(field);
      if (sessions == null) continue;
      for (Iterator<String> keys = sessions.keys(); keys.hasNext(); )
        seedSession(days, sessions.optJSONObject(keys.next()), wallNow, zone, credited);
    }
    state.put(FIELD, days);
    return true;
  }

  private static void seedSession(
      JSONObject days, JSONObject session, long wallNow, ZoneId zone, Set<String> credited)
      throws JSONException {
    if (session == null) return;
    long millis = session.optLong("activeMillis", 0);
    if (millis <= 0) return;
    long started = session.optLong("started", wallNow);
    String identity =
        session.optString("course")
            + "/"
            + session.optInt("stage", -1)
            + "/"
            + started
            + "/"
            + session.optBoolean("review");
    if (credited.add(identity)) addTo(days, StudyTime.dateKey(started, zone), millis);
  }

  private static long secondsToMillis(long seconds) throws JSONException {
    try {
      return Math.multiplyExact(seconds, 1000);
    } catch (ArithmeticException e) {
      throw new JSONException("Study-time history is too large");
    }
  }

  public static void add(JSONObject state, String date, long millis) throws JSONException {
    if (millis <= 0) return;
    addTo(state.getJSONObject(FIELD), date, millis);
  }

  private static void addTo(JSONObject days, String date, long millis) throws JSONException {
    if (millis <= 0) return;
    try {
      days.put(date, Math.addExact(days.optLong(date, 0), millis));
    } catch (ArithmeticException e) {
      throw new JSONException("Daily study time is too large");
    }
  }

  public static long todayMillis(JSONObject state, long wallNow, ZoneId zone) throws JSONException {
    return state.getJSONObject(FIELD).optLong(StudyTime.dateKey(wallNow, zone), 0);
  }

  public static int studyDays(JSONObject state) throws JSONException {
    JSONObject days = state.getJSONObject(FIELD);
    int count = 0;
    for (Iterator<String> dates = days.keys(); dates.hasNext(); )
      if (days.optLong(dates.next(), 0) > 0) count++;
    return count;
  }

  /** Validate imported ledgers before any on-device restore writes. Missing means legacy format. */
  public static void validate(JSONObject state) throws JSONException {
    if (!state.has(FIELD)) return;
    JSONObject days = state.getJSONObject(FIELD);
    for (Iterator<String> dates = days.keys(); dates.hasNext(); ) {
      String date = dates.next();
      try {
        if (!LocalDate.parse(date).toString().equals(date))
          throw new JSONException("Study-time date must use yyyy-MM-dd");
      } catch (DateTimeParseException e) {
        throw new JSONException("Invalid study-time date");
      }
      Object value = days.get(date);
      if (!(value instanceof Number)
          || ((Number) value).longValue() < 0
          || ((Number) value).doubleValue() != ((Number) value).longValue())
        throw new JSONException("Daily study time must be a nonnegative integer");
    }
  }
}
