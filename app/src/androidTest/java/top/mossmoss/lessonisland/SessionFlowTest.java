package top.mossmoss.lessonisland;

import android.test.InstrumentationTestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;

/** Regression flows for independent mainline, review and temporary replay sessions. */
public class SessionFlowTest extends InstrumentationTestCase {
  AppFlowTest flow;
  MainActivity app;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    flow = new AppFlowTest();
    flow.injectInstrumentation(getInstrumentation());
    flow.setUp();
    app = flow.app;
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      if (flow != null) flow.tearDown();
    } finally {
      super.tearDown();
    }
  }

  void seedFrontier(String id, int completed) throws Exception {
    flow.fixture(id);
    flow.ui(() -> {
      app.store.progress(id).put("completed", completed);
      app.store.save();
    });
  }

  public void testReviewAndMainlineResumeIndependently() throws Exception {
    seedFrontier("session_review_course", 1);
    flow.ui(() -> {
      app.store.state.put("reviews", new JSONObject());
      app.store.state.put("reviewSessions", new JSONObject());
      app.newSession("session_review_course", 1);
    });
    flow.answerCorrect();
    flow.ui(app::goBack);
    assertEquals(1, app.store.courseSession("session_review_course").getInt("position"));
    flow.ui(() -> {
      for (Learning.Question question : app.store.questions(app.store.course("session_review_course"), 1).subList(0, 2))
        app.store.record("session_review_course", question, false, true);
      app.startReview(false);
    });
    assertTrue(app.reviewing);
    assertEquals("quiz", app.page);
    flow.answerCorrect();
    flow.ui(app::goBack);
    assertEquals(1, app.store.reviewSession(false).getInt("position"));
    assertEquals(1, app.store.courseSession("session_review_course").getInt("position"));
    flow.ui(() -> app.begin("session_review_course", 1));
    assertFalse(app.reviewing);
    assertEquals(1, app.position);
    flow.ui(app::goBack);
    flow.ui(() -> app.startReview(false));
    assertTrue(app.reviewing);
    assertEquals(1, app.position);
    while (app.page.equals("quiz")) flow.answerCorrect();
    assertNull(app.store.reviewSession(false));
    assertEquals(1, app.store.courseSession("session_review_course").getInt("position"));
  }

  public void testOldStageReplayPreservesMainlineAndFormalRecords() throws Exception {
    seedFrontier("session_replay_course", 2);
    flow.ui(() -> app.newSession("session_replay_course", 2));
    flow.answerCorrect();
    flow.ui(app::goBack);
    JSONObject progress = Store.copy(app.store.progress("session_replay_course"));
    String reviews = app.store.state.getJSONObject("reviews").toString();
    String history = app.store.state.getJSONArray("history").toString();
    int xp = app.store.state.optInt("xp");
    flow.ui(() -> app.begin("session_replay_course", 0));
    assertTrue(app.replaying);
    assertEquals("quiz", app.page);
    flow.ui(app::finishReadingLesson);
    assertEquals("result", app.page);
    assertEquals(progress.toString(), app.store.progress("session_replay_course").toString());
    assertEquals(reviews, app.store.state.getJSONObject("reviews").toString());
    assertEquals(history, app.store.state.getJSONArray("history").toString());
    assertEquals(xp, app.store.state.optInt("xp"));
    assertEquals(1, app.store.courseSession("session_replay_course").getInt("position"));
    flow.ui(() -> app.begin("session_replay_course", 1));
    assertTrue(app.replaying);
    flow.ui(() -> app.submit("a deliberately wrong replay answer"));
    flow.ui(app::goBack);
    assertEquals(progress.toString(), app.store.progress("session_replay_course").toString());
    assertEquals(reviews, app.store.state.getJSONObject("reviews").toString());
    assertEquals(history, app.store.state.getJSONArray("history").toString());
    flow.ui(() -> app.begin("session_replay_course", 2));
    assertFalse(app.replaying);
    assertEquals(1, app.position);
  }

  public void testEachCourseKeepsItsOwnFrontier() throws Exception {
    seedFrontier("session_course_a", 1);
    seedFrontier("session_course_b", 1);
    flow.ui(() -> app.begin("session_course_a", 1));
    flow.answerCorrect();
    flow.ui(app::goBack);
    flow.ui(() -> app.begin("session_course_b", 1));
    flow.answerCorrect();
    flow.ui(app::goBack);
    Store reopened = new Store(app);
    assertEquals(1, reopened.courseSession("session_course_a").getInt("position"));
    assertEquals(1, reopened.courseSession("session_course_b").getInt("position"));
    flow.ui(() -> app.begin("session_course_a", 1));
    assertEquals("quiz", app.page);
    assertEquals("session_course_a", app.sessionCourse);
    assertEquals(1, app.position);
  }

  public void testLegacySessionMigrationAndBackupDiscardPending() throws Exception {
    seedFrontier("session_legacy_course", 1);
    flow.ui(() -> app.begin("session_legacy_course", 1));
    flow.answerCorrect();
    flow.ui(app::goBack);
    flow.ui(() -> {
      JSONObject legacy = Store.copy(app.store.courseSession("session_legacy_course"));
      app.store.state.remove("courseSessions");
      app.store.state.remove("reviewSessions");
      app.store.state.remove("lastLearningCourse");
      app.store.state.put("session", legacy);
      app.store.save();
      app.store = new Store(app);
    });
    assertFalse(app.store.state.has("session"));
    assertEquals(1, app.store.courseSession("session_legacy_course").getInt("position"));
    flow.ui(() -> {
      app.store.state.put("session", new JSONObject().put("review", true).put("reviewOnlyMistakes", true)
          .put("course", "review").put("stage", -1).put("position", 2).put("queue", new JSONArray()));
      app.store.save();
      app.store = new Store(app);
    });
    assertEquals(2, app.store.reviewSession(true).getInt("position"));
    assertEquals(1, app.store.courseSession("session_legacy_course").getInt("position"));
    ByteArrayOutputStream backup = new ByteArrayOutputStream();
    app.store.exportZip(backup);
    app.store.importZip(new ByteArrayInputStream(backup.toByteArray()));
    assertNull(app.store.courseSession("session_legacy_course"));
    assertNull(app.store.reviewSession(true));
    assertFalse(app.store.state.has("session"));
    assertEquals(1, app.store.progress("session_legacy_course").optInt("completed"));
  }

  public void testUnfinishedStudyTimeIsCreditedAndBackgroundIsExcluded() throws Exception {
    seedFrontier("session_time_course", 0);
    long before = StudyTimeLedger.todayMillis(app.store.state, System.currentTimeMillis(), ZoneId.systemDefault());
    int historyCount = app.store.state.getJSONArray("history").length();
    flow.ui(() -> app.begin("session_time_course", 0));
    Thread.sleep(1250);
    flow.ui(app::goBack);
    long unfinished = StudyTimeLedger.todayMillis(app.store.state, System.currentTimeMillis(), ZoneId.systemDefault());
    assertTrue("Leaving an unfinished lesson must credit its foreground time", unfinished >= before + 1000);
    assertEquals(historyCount, app.store.state.getJSONArray("history").length());
    assertNotNull(app.store.courseSession("session_time_course"));
    flow.ui(() -> app.begin("session_time_course", 0));
    flow.ui(app::onPause);
    long paused = StudyTimeLedger.todayMillis(app.store.state, System.currentTimeMillis(), ZoneId.systemDefault());
    Thread.sleep(1250);
    flow.ui(app::onResume);
    flow.ui(app::onPause);
    long resumed = StudyTimeLedger.todayMillis(app.store.state, System.currentTimeMillis(), ZoneId.systemDefault());
    assertTrue("Background waiting must not be added", resumed - paused < 1000);
    String ledger = app.store.state.getJSONObject(StudyTimeLedger.FIELD).toString();
    assertEquals(ledger, new Store(app).state.getJSONObject(StudyTimeLedger.FIELD).toString());
  }
}
