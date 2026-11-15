package top.mossmoss.lessonisland;

import android.test.InstrumentationTestCase;
import java.time.Instant;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs against Android's real JSON implementation; no user courses or signing keys are touched. */
public final class StudyTimeLedgerTest extends InstrumentationTestCase {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final long NOW = Instant.parse("2026-11-15T02:00:00Z").toEpochMilli();

  public void testLegacyHistoryAndPendingSessionMigrateOnlyOnce() throws Exception {
    JSONObject pending =
        new JSONObject().put("course", "a").put("stage", 2).put("started", NOW).put("activeMillis", 45000);
    JSONObject state =
        new JSONObject()
            .put("history", new JSONArray().put(new JSONObject().put("time", NOW).put("seconds", 4200)))
            .put("session", pending)
            .put("courseSessions", new JSONObject().put("a", new JSONObject(pending.toString())));
    assertTrue(StudyTimeLedger.initialize(state, NOW, ZONE));
    assertEquals(4245000L, StudyTimeLedger.todayMillis(state, NOW, ZONE));
    // New completed-history entries must not re-credit time that already exists in the ledger.
    state.getJSONArray("history").put(new JSONObject().put("time", NOW).put("seconds", 45));
    assertFalse(StudyTimeLedger.initialize(state, NOW, ZONE));
    assertEquals(4245000L, StudyTimeLedger.todayMillis(state, NOW, ZONE));
    JSONObject reopened = new JSONObject(state.toString());
    assertFalse(StudyTimeLedger.initialize(reopened, NOW, ZONE));
    assertEquals(4245000L, StudyTimeLedger.todayMillis(reopened, NOW, ZONE));
  }

  public void testUnfinishedTimePersistsWhenSessionIsDiscardedOrFinished() throws Exception {
    JSONObject state = new JSONObject().put("history", new JSONArray());
    StudyTimeLedger.initialize(state, NOW, ZONE);
    StudyTime clock = new StudyTime();
    clock.update(true, 0, NOW, ZONE, (date, millis) -> StudyTimeLedger.add(state, date, millis));
    clock.update(false, 125000, NOW + 125000, ZONE, (date, millis) -> StudyTimeLedger.add(state, date, millis));
    state.remove("session");
    JSONObject reopened = new JSONObject(state.toString());
    assertEquals(125000L, StudyTimeLedger.todayMillis(reopened, NOW, ZONE));
    assertEquals(1, StudyTimeLedger.studyDays(reopened));
    reopened.getJSONArray("history").put(new JSONObject().put("time", NOW).put("seconds", 125));
    StudyTimeLedger.initialize(reopened, NOW, ZONE);
    assertEquals(125000L, StudyTimeLedger.todayMillis(reopened, NOW, ZONE));
  }

  public void testBackupLedgerIsAuthoritativeAndMalformedValuesAreRejected() throws Exception {
    JSONObject backup =
        new JSONObject()
            .put("history", new JSONArray().put(new JSONObject().put("time", NOW).put("seconds", 300)))
            .put(StudyTimeLedger.FIELD, new JSONObject().put("2026-11-15", 120000));
    assertFalse(StudyTimeLedger.initialize(backup, NOW, ZONE));
    assertEquals(120000L, StudyTimeLedger.todayMillis(backup, NOW, ZONE));
    for (Object bad : new Object[] {-1, 1.5, "1000"}) {
      JSONObject state = new JSONObject().put(StudyTimeLedger.FIELD, new JSONObject().put("2026-11-15", bad));
      try {
        StudyTimeLedger.validate(state);
        fail("Malformed imported study time was accepted");
      } catch (JSONException expected) {
        // Expected: import must reject bad ledgers before writing files.
      }
    }
    try {
      StudyTimeLedger.validate(new JSONObject().put(StudyTimeLedger.FIELD, new JSONObject().put("not-a-date", 1)));
      fail("Malformed imported date was accepted");
    } catch (JSONException expected) {
      // Expected.
    }
  }
}
