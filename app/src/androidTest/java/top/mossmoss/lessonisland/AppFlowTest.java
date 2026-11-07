package top.mossmoss.lessonisland;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Runs against a real emulator, exercising the displayed native views and on-device files. */
public class AppFlowTest extends InstrumentationTestCase {
  MainActivity app;
  String stateBefore;
  List<String> coursesBefore;

  interface Checked {
    void run() throws Exception;
  }

  void ui(Checked action) throws Exception {
    final Throwable[] failure = {null};
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                action.run();
              } catch (Throwable t) {
                failure[0] = t;
              }
            });
    getInstrumentation().waitForIdleSync();
    if (failure[0] != null) throw new Exception(failure[0]);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Intent intent =
        new Intent(Intent.ACTION_MAIN)
            .setClassName("top.mossmoss.lessonisland", "top.mossmoss.lessonisland.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    app = (MainActivity) getInstrumentation().startActivitySync(intent);
    getInstrumentation().waitForIdleSync();
    assertNotNull(app.store);
    stateBefore = app.store.state.toString();
    coursesBefore = new ArrayList<>();
    for (JSONObject c : app.store.courses(false)) coursesBefore.add(c.getString("id"));
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      ui(
          () -> {
            app.store.state = new JSONObject(stateBefore);
            app.store.save();
            for (JSONObject c : app.store.courses(false))
              if (!coursesBefore.contains(c.getString("id")))
                new File(app.store.root, "courses/" + Store.safe(c.getString("id")) + ".json")
                    .delete();
            app.finish();
          });
    } finally {
      super.tearDown();
    }
  }

  JSONObject fixture(String id) throws Exception {
    JSONObject c = Store.copy(app.store.course("lesson2"));
    c.put("id", id).put("published", true);
    app.store.saveCourse(c);
    return c;
  }

  List<View> views(View root) {
    List<View> all = new ArrayList<>();
    all.add(root);
    if (root instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) all.addAll(views(g.getChildAt(i)));
    return all;
  }

  Button findButton(String label) {
    for (View v : views(app.body))
      if (v instanceof Button b && b.getText().toString().equals(label) && b.isEnabled()) return b;
    return null;
  }

  void click(String label) throws Exception {
    ui(
        () -> {
          Button b = findButton(label);
          assertNotNull("Missing displayed button: " + label, b);
          assertTrue(b.performClick());
        });
  }

  void shot(String name) throws Exception {
    getInstrumentation().waitForIdleSync();
    Thread.sleep(350);
    Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    File folder = new File(app.getExternalFilesDir(null), "test-screenshots");
    folder.mkdirs();
    try (OutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    }
    bitmap.recycle();
  }

  void accessibilityClick(String text) throws Exception {
    getInstrumentation().waitForIdleSync();
    AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
      if (text.contentEquals(node.getText()) && node.isClickable()) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        getInstrumentation().waitForIdleSync();
        return;
      }
    fail("Missing accessible control: " + text);
  }

  void answerCorrect() throws Exception {
    Learning.Question q = app.current();
    switch (q.type) {
      case "read", "flash" -> click("我读懂了，继续  →");
      case "dictation" -> {
        ui(() -> app.answerInput.setText(q.answer));
        click("检查拼写");
      }
      case "order" -> {
        for (String token : Learning.tokens(q.answer)) click(token);
        click("检查答案");
      }
      default -> {
        click(q.answer);
        click("检查答案");
      }
    }
    click(app.position >= app.queue.length() ? "完成本次学习  →" : "继续  →");
  }

  public void testAssetsAndOfflineAudio() throws Exception {
    shot("01-student-home");
    assertEquals(4, app.store.courses(true).size());
    int sentenceCount = 0;
    Set<String> seen = new HashSet<>();
    for (JSONObject c : app.store.courses(true)) {
      Store.validate(c, true);
      sentenceCount += c.getJSONArray("sentences").length();
      assertEquals(app.audio.texts(c).size(), app.audio.cached(c));
      for (String text : app.audio.texts(c)) {
        if (!seen.add(text)) continue;
        File file = app.audio.local(text);
        assertNotNull(file);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
          retriever.setDataSource(file.getAbsolutePath());
          long length =
              Long.parseLong(
                  retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
          assertTrue("Audio too short: " + text, length > 100);
        } finally {
          retriever.release();
        }
      }
    }
    assertEquals(54, sentenceCount);
    assertEquals(115, seen.size());
    ui(
        () -> {
          app.audio.setSpeed(.78f);
          app.audio.play("Good afternoon, boys and girls.", app::toast);
        });
    boolean[] playing = {false};
    for (int i = 0; i < 30 && !playing[0]; i++) {
      Thread.sleep(100);
      ui(() -> playing[0] = app.audio.isPlaying());
    }
    assertTrue("Cached MP3 must play through the Android media engine", playing[0]);
    ui(
        () -> {
          app.audio.stop();
          app.path("lesson1");
        });
    shot("02-learning-route");
    ui(
        () -> {
          app.path("lesson1");
          assertEquals("path", app.page);
          app.materialTab = 1;
          app.materials("lesson1", false);
          assertEquals("materials", app.page);
        });
    shot("03-key-materials");
  }

  public void testCompleteSixStagesWrongAnswerRetryAndReplayXp() throws Exception {
    fixture("test_flow");
    int baseline = app.store.state.optInt("xp");
    ui(
        () -> {
          app.newSession("test_flow", 0);
          assertEquals("quiz", app.page);
        });
    shot("04-read-and-listen");
    int original = app.queue.length();
    answerCorrect();
    assertEquals(1, app.position);
    ui(() -> app.goBack());
    assertEquals("path", app.page);
    Store reopened = new Store(app);
    assertEquals(1, reopened.state.getJSONObject("session").getInt("position"));
    ui(() -> app.resumeSession());
    assertEquals(original, app.queue.length());
    while (app.page.equals("quiz")) answerCorrect();
    assertEquals(1, app.store.progress("test_flow").optInt("completed"));
    boolean denied = false;
    try {
      ui(() -> app.begin("test_flow", 3));
    } catch (Exception expected) {
      denied = true;
    }
    assertTrue("Locked stages must be rejected", denied);
    for (int stage = 1; stage < 6; stage++) {
      int s = stage;
      ui(() -> app.newSession("test_flow", s));
      if (stage == 3) shot("05-listening-challenge");
      if (stage == 4) shot("06-dictation");
      if (stage == 1) {
        Learning.Question q = app.current();
        String wrong = q.options.stream().filter(x -> !x.equals(q.answer)).findFirst().get();
        click(wrong);
        click("检查答案");
        assertEquals(app.activeQuestions.size() + 1, app.queue.length());
        assertEquals(0, app.sessionCorrect);
        assertTrue(app.store.due(true).size() > 0);
        click("继续  →");
      }
      int guard = 0;
      while (app.page.equals("quiz")) {
        answerCorrect();
        if (++guard > 100) fail("Session did not terminate");
      }
      assertEquals(stage + 1, app.store.progress("test_flow").optInt("completed"));
    }
    assertEquals(baseline + 120, app.store.state.optInt("xp"));
    shot("07-stage-complete");
    assertFalse(app.store.state.has("session"));
    assertEquals(6, new Store(app).progress("test_flow").optInt("completed"));
    ui(() -> app.newSession("test_flow", 0));
    while (app.page.equals("quiz")) answerCorrect();
    assertEquals("Replay must not award XP twice", baseline + 120, app.store.state.optInt("xp"));
  }

  public void testReviewReschedulingAndAnswerPrivacy() throws Exception {
    JSONObject c = fixture("test_review");
    Learning.Question q = app.store.questions(c, 1).get(0);
    app.store.record("test_review", q, false, true);
    assertTrue(app.store.due(true).size() > 0);
    ui(() -> app.reviews());
    shot("08-review-station");
    ui(() -> app.startReview(true));
    assertEquals("quiz", app.page);
    assertEquals(0, app.sessionCorrect);
    answerCorrect();
    assertEquals("result", app.page);
    JSONObject r = app.store.state.getJSONObject("reviews").getJSONObject("test_review_" + q.id);
    assertTrue(r.getLong("due") > System.currentTimeMillis() + Learning.DAY - 10000);
    assertEquals(1, r.getInt("level"));
    assertEquals(1, r.getInt("mistakes"));
    ui(
        () -> {
          app.reviews();
          app.profile();
          app.parent = true;
          app.dashboard();
        });
    shot("09-parent-dashboard");
    assertEquals("dashboard", app.page);
  }

  public void testPublicationEditingAndModeGate() throws Exception {
    JSONObject c = fixture("test_publish");
    c.put("published", false);
    app.store.saveCourse(c);
    assertFalse(
        app.store.courses(true).stream().anyMatch(x -> x.optString("id").equals("test_publish")));
    ui(
        () -> {
          app.parent = false;
          app.settings();
          assertEquals("home", app.page);
          app.parent = true;
          app.materialTab = 0;
          app.materials("test_publish", true);
          assertNotNull(findButton("编辑此句"));
        });
    c.getJSONArray("sentences").getJSONObject(0).put("zh", "");
    boolean rejected = false;
    try {
      Store.validate(c, true);
    } catch (Exception expected) {
      rejected = true;
    }
    assertTrue(rejected);
    c.getJSONArray("sentences").getJSONObject(0).put("zh", "打扰一下！");
    Store.validate(c, true);
    app.store.saveCourse(c);
    ui(() -> app.materials("test_publish", true));
    click("校对完成，发布课程  →");
    accessibilityClick("发布");
    assertFalse(
        "Publishing requires proofreading confirmation",
        app.store.course("test_publish").optBoolean("published"));
    accessibilityClick("我已逐句核对原图、翻译和理解题答案");
    accessibilityClick("发布");
    assertTrue(app.store.course("test_publish").optBoolean("published"));
    ui(() -> app.materials("test_publish", true));
    assertNull(findButton("编辑此句"));
    assertNotNull(findButton("复制为新草稿并编辑"));
    Secrets secrets = new Secrets(app);
    String originalSecret = secrets.get();
    secrets.set("temporary-test-secret");
    assertEquals("temporary-test-secret", secrets.get());
    assertFalse(
        new String(
                java.nio.file.Files.readAllBytes(
                    new File(app.getApplicationInfo().dataDir, "shared_prefs/secure.xml").toPath()))
            .contains("temporary-test-secret"));
    secrets.set(originalSecret);
  }

  public void testBackupValidationAndRestore() throws Exception {
    fixture("test_backup");
    File sharedImage = new File(app.store.root, "images/test-backup.jpg");
    try (InputStream source = app.getAssets().open("courses/lesson2-page1.jpg")) {
      java.nio.file.Files.copy(
          source, sharedImage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    for (String id : new String[] {"test_backup", "test_backup_clone"}) {
      JSONObject copy = fixture(id);
      copy.put("sources", new JSONArray().put("images/test-backup.jpg"));
      app.store.saveCourse(copy);
    }
    app.store.state.put("name", "备份前昵称");
    app.store.save();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    app.store.exportZip(bytes);
    assertTrue(bytes.size() > 1000);
    app.store.state.put("name", "临时昵称");
    app.store.importZip(new ByteArrayInputStream(bytes.toByteArray()));
    assertEquals("备份前昵称", app.store.state.getString("name"));
    ByteArrayOutputStream malicious = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(malicious)) {
      Store.add(zip, "../escape.json", "{}".getBytes());
    }
    boolean rejected = false;
    String state = app.store.state.toString();
    try {
      app.store.importZip(new ByteArrayInputStream(malicious.toByteArray()));
    } catch (Exception expected) {
      rejected = true;
    }
    assertTrue(rejected);
    assertEquals(state, app.store.state.toString());
    assertFalse(new File(app.store.root.getParentFile(), "escape.json").exists());
    File backup = new File(app.store.root, "restore-backup");
    backup.mkdirs();
    Store.write(new File(backup, "state.json"), state);
    Store.write(
        new File(app.store.root, "restore-journal.json"),
        new JSONArray()
            .put(new JSONObject().put("path", "state.json").put("existed", true))
            .toString());
    Store.write(new File(app.store.root, "state.json"), "interrupted restore");
    Store recovered = new Store(app);
    assertEquals("备份前昵称", recovered.state.getString("name"));
    assertFalse(new File(app.store.root, "restore-journal.json").exists());
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        assertFalse(e.getName().contains("secure"));
        assertFalse(e.getName().contains("key"));
      }
    }
  }

  public void testZLiveImageToPublishedCourse() throws Exception {
    File config = new File(app.store.root, "api-test-config.txt");
    assertTrue("Real API test configuration must be provisioned", config.exists());
    String text = Store.read(new FileInputStream(config));
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("sk-[A-Za-z0-9_-]+").matcher(text);
    assertTrue(matcher.find());
    new Secrets(app).set(matcher.group());
    assertTrue(config.delete());
    app.ai.test();
    File image = new File(app.store.root, "images/test-live.jpg");
    try (InputStream source = app.getAssets().open("courses/lesson2-page1.jpg")) {
      java.nio.file.Files.copy(
          source, image.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    JSONObject course =
        app.ai.create(Collections.singletonList(image), "Live API validation", message -> {});
    assertEquals(7, course.getJSONArray("sentences").length());
    Store.validate(course, true);
    app.store.saveCourse(course);
    app.audio.download(course, message -> {});
    assertEquals(app.audio.texts(course).size(), app.audio.cached(course));
    course.put("published", true).put("reviewed", true);
    app.store.saveCourse(course);
    ui(
        () -> {
          app.parent = true;
          app.materialTab = 0;
          app.materials(course.getString("id"), true);
        });
    assertNotNull(findButton("全文连播") == null ? findButton("▶  全文连播") : findButton("全文连播"));
    ui(
        () -> {
          app.parent = false;
          app.path(course.getString("id"));
          app.newSession(course.getString("id"), 0);
        });
    answerCorrect();
    assertEquals(1, app.position);
  }
}
