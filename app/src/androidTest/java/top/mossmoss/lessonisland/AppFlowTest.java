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
    seedTestFixtures();
    ui(() -> app.home());
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      ui(
          () -> {
            app.pauseClock();
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

  InputStream fixtureAsset(String name) throws Exception {
    return getInstrumentation().getContext().getAssets().open("fixtures/" + name);
  }

  JSONObject sample(int number) throws Exception {
    return new JSONObject(Store.read(fixtureAsset("courses/lesson" + number + ".json")));
  }

  void seedTestFixtures() throws Exception {
    String[] audio = getInstrumentation().getContext().getAssets().list("fixtures/audio");
    for (String name : audio) {
      try (InputStream input = fixtureAsset("audio/" + name)) {
        java.nio.file.Files.copy(
            input,
            new File(app.store.root, "audio/" + name).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
    for (int number = 1; number <= 4; number++) {
      JSONObject course = sample(number);
      JSONArray sources = course.getJSONArray("sources"), local = new JSONArray();
      for (int i = 0; i < sources.length(); i++) {
        String name = sources.getString(i);
        String destination = "images/fixture_" + name.substring(name.lastIndexOf('/') + 1);
        try (InputStream input = fixtureAsset(name)) {
          java.nio.file.Files.copy(
              input,
              new File(app.store.root, destination).toPath(),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        local.put(destination);
      }
      course.put("sources", local);
      app.store.saveCourse(course);
    }
  }

  Context isolatedContext(String prefix) {
    File directory = new File(app.getCacheDir(), prefix + UUID.randomUUID());
    directory.mkdirs();
    return new ContextWrapper(app) {
      @Override
      public File getFilesDir() {
        return directory;
      }

      @Override
      public SharedPreferences getSharedPreferences(String name, int mode) {
        return app.getSharedPreferences(directory.getName() + "_" + name, mode);
      }
    };
  }

  List<View> views(View root) {
    List<View> all = new ArrayList<>();
    all.add(root);
    if (root instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) all.addAll(views(g.getChildAt(i)));
    return all;
  }

  Button findButton(String label) {
    for (View v : views(app.shell))
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
    AccessibilityNodeInfo root = activeRoot();
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
      if ((android.text.TextUtils.equals(text, node.getText())
              || android.text.TextUtils.equals(text, node.getContentDescription()))
          && node.isClickable()) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        getInstrumentation().waitForIdleSync();
        return;
      }
    fail("Missing accessible control: " + text);
  }

  AccessibilityNodeInfo activeRoot() throws Exception {
    AccessibilityNodeInfo root = null;
    long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
    while (root == null && android.os.SystemClock.elapsedRealtime() < deadline) {
      root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (root == null) Thread.sleep(50);
    }
    assertNotNull("An active accessibility window must become available", root);
    return root;
  }

  void answerCorrect() throws Exception {
    Learning.Question q = app.current();
    switch (q.type) {
      case "read" -> {
        click("完成本关  →");
        return;
      }
      case "flash" -> click("我读懂了，继续  →");
      case "dictation" -> {
        ui(() -> app.answerInput.setText(q.answer));
        click("检查拼写");
      }
      case "order" -> {
        for (String token : Learning.tokens(q.answer)) click(token);
        click("检查答案");
      }
      default -> click(q.answer);
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
    ui(() -> app.scroll.scrollTo(0, app.dp(370)));
    shot("02-learning-route-bend");
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

  public void testFreshInstallAndDirectCourseCreation() throws Exception {
    Context context = isolatedContext("onboarding_");
    Store fresh = new Store(context);
    assertEquals("Fresh installs must have no courses", 0, fresh.courses(false).size());
    ByteArrayOutputStream emptyBackup = new ByteArrayOutputStream();
    fresh.exportZip(emptyBackup);
    fresh.importZip(new ByteArrayInputStream(emptyBackup.toByteArray()));
    assertEquals(0, fresh.courses(false).size());
    assertEquals(0, app.getAssets().list("courses").length);
    assertEquals(0, app.getAssets().list("audio").length);
    Store original = app.store;
    Audio originalAudio = app.audio;
    Secrets originalSecrets = app.secrets;
    Audio temporaryAudio = new Audio(app, fresh);
    try {
      ui(
          () -> {
            app.store = fresh;
            app.audio = temporaryAudio;
            app.secrets = new Secrets(context);
            app.parent = false;
            app.home();
          });
      ui(
          () -> {
            Button create = findButton("家长创建课程");
            assertNotNull(create);
            Rect visible = new Rect();
            assertTrue(
                "Creation must be visible on first launch without scrolling",
                create.getGlobalVisibleRect(visible));
            assertTrue(visible.height() >= app.dp(48));
          });
      shot("00-empty-first-launch");
      click("家长创建课程");
      assertFalse("Parent authentication must come before course creation", app.parent);
      getInstrumentation().waitForIdleSync();
      setPinFields(getInstrumentation().getUiAutomation().getRootInActiveWindow(), "1357");
      accessibilityClick("设置并进入");
      assertTrue(app.parent);
      assertEquals("import", app.page);
      assertNotNull(findButton("配置制课服务"));
      shot("00-create-course");
      click("配置制课服务");
      assertEquals("settings", app.page);
      ui(() -> app.dashboard());
      click("＋  创建课程");
      assertEquals("import", app.page);
    } finally {
      ui(
          () -> {
            temporaryAudio.close();
            app.store = original;
            app.audio = originalAudio;
            app.secrets = originalSecrets;
            app.parent = false;
          });
    }
  }

  void setPinFields(AccessibilityNodeInfo node, String value) {
    if ("android.widget.EditText".contentEquals(node.getClassName())) {
      android.os.Bundle arguments = new android.os.Bundle();
      arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
      assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null) setPinFields(child, value);
    }
  }

  public void testLegacySamplesArchivePreservesUserCourses() throws Exception {
    Context context = isolatedContext("migration_");
    Store previous = new Store(context);
    for (int number = 1; number <= 4; number++) previous.saveCourse(sample(number));
    JSONObject own = sample(2);
    own.put("id", "course_saved").put("title", "家长自己制作的课程");
    previous.saveCourse(own);
    JSONObject edited = sample(3);
    edited.put("title", "已由家长修改的内容");
    previous.saveCourse(edited);
    previous.progress("course_saved").put("completed", 2);
    previous.state.put("session", new JSONObject().put("course", "lesson1"));
    previous.state.remove("legacySamplesArchived");
    previous.save();
    Store upgraded = new Store(context);
    assertEquals(2, upgraded.courses(false).size());
    assertEquals("家长自己制作的课程", upgraded.course("course_saved").getString("title"));
    assertEquals(2, upgraded.progress("course_saved").getInt("completed"));
    assertEquals("已由家长修改的内容", upgraded.course("lesson3").getString("title"));
    assertFalse(upgraded.state.has("session"));
    assertTrue(upgraded.state.has("archivedSampleSession"));
    for (int number : new int[] {1, 2, 4}) {
      assertTrue(
          new File(upgraded.root, "legacy-sample-archive/lesson" + number + ".json").exists());
      assertFalse(new File(upgraded.root, "courses/lesson" + number + ".json").exists());
    }
    assertEquals(2, new Store(context).courses(false).size());
  }

  public void testApiKeyVisibilityDoesNotRevealSavedValue() throws Exception {
    Context context = isolatedContext("key_visibility_");
    Secrets isolated = new Secrets(context);
    String saved = "test-only-visibility-value";
    String changed = "test-only-edited-value";
    isolated.set(saved);
    Secrets original = app.secrets;
    try {
      ui(
          () -> {
            app.secrets = isolated;
            app.parent = true;
            app.settings();
            assertEquals("", app.apiKeyInput.getText().toString());
            assertEquals("已保存密钥；留空保持当前密钥", app.apiKeyInput.getHint());
            assertTrue(
                app.apiKeyInput.getTransformationMethod()
                    instanceof android.text.method.PasswordTransformationMethod);
            app.apiKeyToggle.performClick();
            assertTrue(app.apiKeyVisible);
            assertNull(app.apiKeyInput.getTransformationMethod());
            assertEquals("", app.apiKeyInput.getText().toString());
            assertEquals(saved, isolated.get());
            assertEquals("隐藏 API 密钥", app.apiKeyToggle.getContentDescription());
            app.apiKeyInput.setText(changed);
            app.apiKeyInput.setSelection(3);
            app.apiKeyToggle.performClick();
            assertFalse(app.apiKeyVisible);
            assertEquals(3, app.apiKeyInput.getSelectionStart());
          });
      click("保存 API 设置");
      assertEquals(changed, isolated.get());
      ui(
          () -> {
            assertEquals("", app.apiKeyInput.getText().toString());
            assertFalse(app.apiKeyVisible);
            app.apiKeyToggle.performClick();
            assertEquals("", app.apiKeyInput.getText().toString());
          });
      click("保存 API 设置");
      assertEquals("Blank input must keep the saved key", changed, isolated.get());
      ui(
          () -> {
            assertEquals("", app.apiKeyInput.getText().toString());
            app.apiKeyInput.setText("test-only-unsaved-input");
            app.apiKeyToggle.performClick();
            assertTrue(app.apiKeyVisible);
            getInstrumentation().callActivityOnPause(app);
            assertFalse(app.apiKeyVisible);
            assertTrue(
                app.apiKeyInput.getTransformationMethod()
                    instanceof android.text.method.PasswordTransformationMethod);
            getInstrumentation().callActivityOnResume(app);
            EditText oldField = app.apiKeyInput;
            app.apiKeyToggle.performClick();
            app.courses();
            assertEquals("", oldField.getText().toString());
            assertNull(app.apiKeyInput);
            assertEquals(changed, isolated.get());
          });
    } finally {
      ui(
          () -> {
            app.secrets = original;
            app.parent = false;
            app.home();
          });
    }
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
    ui(
        () -> {
          assertNotNull(app.passage);
          assertEquals(original, app.passage.sentences.size());
          assertFalse(app.passage.editable);
          assertNull(findButton("我读懂了，继续  →"));
          for (String label : new String[] {"减速", "加速", "完成本关  →"}) {
            Button control = findButton(label);
            assertNotNull(control);
            Rect rect = new Rect();
            assertTrue(
                "Reading controls stay visible without scrolling",
                control.getGlobalVisibleRect(rect));
            assertTrue(rect.height() >= app.dp(48));
            assertEquals("Reading controls must fit on one line", 1, control.getLineCount());
          }
          StringBuilder bilingual = new StringBuilder();
          for (Learning.Question q : app.activeQuestions) {
            if (bilingual.length() > 0) bilingual.append('\n');
            bilingual.append(q.prompt).append('\n').append(q.answer);
          }
          assertEquals(bilingual.toString(), app.passage.getText().toString());
        });
    click("减速");
    assertEquals(.75f, app.readingSpeed, .001f);
    pressPassage(app.passage, 0, false);
    long playingDeadline = android.os.SystemClock.elapsedRealtime() + 3000;
    while (!app.audio.isPlaying() && android.os.SystemClock.elapsedRealtime() < playingDeadline)
      Thread.sleep(20);
    assertTrue("Tapping the passage must play its offline audio", app.audio.isPlaying());
    java.lang.reflect.Field playerField = Audio.class.getDeclaredField("player");
    playerField.setAccessible(true);
    ui(
        () ->
            assertEquals(
                .75f,
                ((android.media.MediaPlayer) playerField.get(app.audio))
                    .getPlaybackParams()
                    .getSpeed(),
                .001f));
    click("加速");
    ui(
        () -> {
          assertEquals(1f, app.readingSpeed, .001f);
          assertEquals(
              "Speed changes must affect the clip already playing",
              1f,
              ((android.media.MediaPlayer) playerField.get(app.audio))
                  .getPlaybackParams()
                  .getSpeed(),
              .001f);
        });
    pressPassage(app.passage, 1, false, true);
    assertEquals(1, app.materialPlaying);
    assertTrue(app.passage.sentences.get(1).playing);
    assertTrue(app.passage.translations.get(1).playing);
    ui(() -> app.goBack());
    assertEquals("path", app.page);
    assertEquals(-1, app.audio.currentIndex());
    Store reopened = new Store(app);
    assertEquals(0, reopened.courseSession("test_flow").getInt("position"));
    assertEquals(1, reopened.courseSession("test_flow").getInt("readingIndex"));
    ui(() -> app.resumeSession());
    assertEquals(original, app.queue.length());
    assertEquals(1, app.readingIndex);
    assertEquals(1f, app.readingSpeed, .001f);
    assertEquals(-1, app.audio.currentIndex());
    while (app.page.equals("quiz")) answerCorrect();
    assertEquals(original, app.position);
    assertEquals(original, app.sessionAttempts);
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
        Button picked = findButton(wrong), correct = findButton(q.answer);
        int scrollBefore = app.scroll.getScrollY();
        click(wrong);
        ui(
            () -> {
              assertTrue("An option tap must immediately submit", app.answered);
              assertEquals(MainActivity.RED, picked.getCurrentTextColor());
              assertEquals(MainActivity.GREEN, correct.getCurrentTextColor());
              assertEquals("✕  " + wrong, picked.getText().toString());
              assertEquals("✓  " + q.answer, correct.getText().toString());
              for (View view : views(app.body))
                if ("option".equals(view.getTag())) assertFalse(view.isEnabled());
              assertNull(findButton("检查答案"));
              assertEquals(
                  "Feedback must not force scrolling", scrollBefore, app.scroll.getScrollY());
              picked.performClick();
              correct.performClick();
              assertEquals("Repeated taps must only count once", 1, app.sessionAttempts);
              assertEquals(1, app.position);
            });
        shot("05-gentle-feedback");
        assertEquals(app.activeQuestions.size() + 1, app.queue.length());
        assertEquals(0, app.sessionCorrect);
        assertTrue(app.store.due(true).size() > 0);
        click("继续  →");
        ui(
            () -> {
              assertFalse(app.answered);
              for (View view : views(app.body))
                if (view instanceof Button b && "option".equals(b.getTag())) {
                  assertTrue(b.isEnabled());
                  assertEquals(MainActivity.INK, b.getCurrentTextColor());
                }
            });
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
    assertNull(app.store.courseSession("test_flow"));
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
        });
    shot("09-growth-journal");
    ui(() -> app.scroll.fullScroll(View.FOCUS_DOWN));
    shot("09-growth-badges");
    ui(
        () -> {
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
          assertTrue(app.passage.editable);
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
    accessibilityClick("校对完成，发布课程");
    accessibilityClick("发布");
    assertFalse(
        "Publishing requires proofreading confirmation",
        app.store.course("test_publish").optBoolean("published"));
    accessibilityClick("我已逐句核对原图、翻译和理解题答案");
    accessibilityClick("发布");
    assertTrue(app.store.course("test_publish").optBoolean("published"));
    ui(() -> app.materials("test_publish", true));
    assertTrue(app.passage.editable);
    assertNull(findButton("复制为新草稿并编辑"));
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

  public void testAdventureRouteAndPinnedActionsAtNarrowWidth() throws Exception {
    JSONObject course = fixture("test_adventure_layout");
    ui(
        () -> {
          app.store.progress(course.getString("id")).put("completed", 1);
          app.path(course.getString("id"));
          // A 320dp phone viewport leaves 280dp for content after horizontal padding.
          app.body.getLayoutParams().width = app.dp(320);
          app.body.requestLayout();
        });
    ui(
        () -> {
          AdventureArt.Trail trail = null;
          for (View view : views(app.body)) if (view instanceof AdventureArt.Trail t) trail = t;
          assertNotNull(trail);
          assertEquals(6, trail.getChildCount());
          for (int i = 0; i < 6; i++) {
            View tile = trail.getChildAt(i);
            assertTrue(
                "Stage must stay inside the narrow trail",
                tile.getLeft() >= 0 && tile.getRight() <= trail.getWidth());
            AdventureArt.StageNode node = null;
            for (View child : views(tile)) {
              assertFalse("Stage icons replace the redundant buttons", child instanceof Button);
              if (child instanceof AdventureArt.StageNode n) node = n;
            }
            assertNotNull(node);
            assertEquals(
                "Only completed and next stages may be started", i <= 1, node.isClickable());
            assertTrue(node.getWidth() >= app.dp(48) && node.getHeight() >= app.dp(48));
            if (i == 1) {
              Rect rect = new Rect();
              node.getDrawingRect(rect);
              node.requestRectangleOnScreen(rect, true);
            }
          }
        });
    shot("10-narrow-route");
    accessibilityClick("第 2 关，词汇探险，开始学习");
    assertEquals("quiz", app.page);
    ui(
        () -> {
          assertNull("An option tap replaces the check action", app.actionFooter);
          assertNull(findButton("检查答案"));
        });
    Learning.Question q = app.current();
    Button correct = findButton(q.answer);
    int scrollBefore = app.scroll.getScrollY();
    click(q.answer);
    ui(
        () -> {
          assertTrue(app.answered);
          assertFalse(correct.isEnabled());
          assertEquals(MainActivity.GREEN, correct.getCurrentTextColor());
          assertEquals("✓  " + q.answer, correct.getText().toString());
          assertEquals(1, app.sessionAttempts);
          assertEquals(1, app.sessionCorrect);
          assertEquals(scrollBefore, app.scroll.getScrollY());
          Button next = findButton(app.position >= app.queue.length() ? "完成本次学习  →" : "继续  →");
          assertNotNull(next);
          assertEquals(app.actionFooter, next.getParent());
          Rect rect = new Rect();
          assertTrue("Continue must be visible without scrolling", next.getGlobalVisibleRect(rect));
          assertTrue(rect.height() >= app.dp(48));
        });
    shot("10-pinned-answer-action");
    ui(
        () -> {
          app.scroll.setSmoothScrollingEnabled(false);
          app.scroll.fullScroll(View.FOCUS_DOWN);
        });
    ui(
        () -> {
          Button next = findButton(app.position >= app.queue.length() ? "完成本次学习  →" : "继续  →");
          assertNotNull(next);
          assertTrue(next.getGlobalVisibleRect(new Rect()));
          boolean feedbackVisible = false;
          for (View view : views(app.body))
            if (view instanceof TextView t && t.getText().toString().equals("太棒啦，找到答案！"))
              feedbackVisible = view.getGlobalVisibleRect(new Rect());
          assertTrue("The optional feedback card remains available by scrolling", feedbackVisible);
        });
    shot("10-positive-feedback");
    // Word tiles still wrap at the available width and remain inside their container.
    ui(
        () -> {
          app.newSession(course.getString("id"), 4);
          int index = 0;
          while (!app.activeQuestions.get(index).type.equals("order")) index++;
          app.position = index;
          app.showQuestion();
          app.body.getLayoutParams().width = app.dp(320);
          app.body.requestLayout();
        });
    ui(
        () -> {
          MainActivity.TokenFlow flow = null;
          for (View view : views(app.body)) if (view instanceof MainActivity.TokenFlow f) flow = f;
          assertNotNull(flow);
          for (int i = 0; i < flow.getChildCount(); i++) {
            View token = flow.getChildAt(i);
            assertTrue(token.getLeft() >= 0 && token.getRight() <= flow.getWidth());
          }
        });
    shot("10-word-workshop");
  }

  public void testMaterialTapPlaybackAndLongPressEditing() throws Exception {
    JSONObject course = fixture("test_material_gestures");
    course.put("published", false);
    app.store.saveCourse(course);
    ui(
        () -> {
          app.parent = true;
          app.materialTab = 0;
          app.materials(course.getString("id"), true);
          assertNull(findButton("▶  全文连播"));
          assertNull(findButton("停止朗读"));
          assertNull(findButton("▶  朗读句子"));
          assertNull(findButton("编辑此句"));
          app.passage.sentences.get(0).onClick(app.passage);
          assertEquals(0, app.materialPlaying);
          assertEquals(0, app.audio.currentIndex());
          assertTrue(app.passage.sentences.get(0).playing);
          assertEquals(View.VISIBLE, app.materialPlaybackBar.getVisibility());
          app.passage.sentences.get(0).onClick(app.passage);
          assertEquals(-1, app.materialPlaying);
          assertEquals(View.GONE, app.materialPlaybackBar.getVisibility());
          app.passage.sentences.get(0).onClick(app.passage);
          app.passage.sentences.get(2).onClick(app.passage);
          assertEquals(2, app.materialPlaying);
          assertFalse(app.passage.sentences.get(0).playing);
          assertTrue(app.passage.sentences.get(2).playing);
        });
    pressPassage(app.passage, 2, true);
    assertTrue(
        "Playing content cannot open an editor",
        activeRoot().findAccessibilityNodeInfosByText("校对素材").isEmpty());
    ui(
        () -> {
          app.passage.sentences.get(0).onClick(app.passage);
        });
    // Long-press a different, idle sentence: stop the old sequence before editing.
    pressPassage(app.passage, 1, true);
    ui(
        () -> {
          assertEquals(-1, app.audio.currentIndex());
          assertEquals(-1, app.materialPlaying);
        });
    assertFalse(activeRoot().findAccessibilityNodeInfosByText("校对素材").isEmpty());
    setAccessibleField(activeRoot(), "中文翻译", "长按后修改的翻译");
    accessibilityClick("保存");
    assertEquals(
        "长按后修改的翻译",
        app.store
            .course(course.getString("id"))
            .getJSONArray("sentences")
            .getJSONObject(1)
            .getString("zh"));
    ui(() -> app.passage.sentences.get(0).onClick(app.passage));
    shot("11-content-playing");
    Rect viewport = new Rect();
    ui(() -> assertTrue(app.scroll.getGlobalVisibleRect(viewport)));
    swipeOnScreen(
        viewport.centerX(),
        viewport.top + viewport.height() * .75f,
        viewport.top + viewport.height() * .25f);
    ui(
        () ->
            assertFalse(
                "Manual browsing must disable automatic following", app.followMaterialPlayback));
    // The compact stop control works even when the active row is out of view.
    ui(
        () -> {
          ((ViewGroup) app.materialPlaybackBar).getChildAt(0).performClick();
          assertEquals(-1, app.materialPlaying);
          app.materialTab = 1;
          app.materials(course.getString("id"), true);
          assertNull(findButton("▶  朗读"));
          assertNull(findButton("编辑素材"));
          assertTrue(app.materialCards.get(0).isLongClickable());
          app.materialCards.get(0).performClick();
          assertEquals(0, app.materialPlaying);
          app.materialTab = 0;
          app.materials(course.getString("id"), true);
          assertEquals(-1, app.audio.currentIndex());
          app.parent = false;
          app.materials("lesson2", false);
          assertFalse("Students may not edit content by long press", app.passage.editable);
        });
    shot("11-content-idle");
    pressPassage(app.passage, 0, true);
    assertEquals(
        "Holding read-only text must not accidentally start playback",
        -1,
        app.audio.currentIndex());
    ui(
        () -> {
          app.passage.sentences.get(0).onClick(app.passage);
          app.home();
          assertEquals(-1, app.audio.currentIndex());
        });
  }

  void pressPassage(PassageView view, int index, boolean hold) throws Exception {
    pressPassage(view, index, hold, false);
  }

  void pressPassage(PassageView view, int index, boolean hold, boolean translation)
      throws Exception {
    float[] point = new float[2];
    long downTime = android.os.SystemClock.uptimeMillis();
    ui(
        () -> {
          android.text.Spanned text = (android.text.Spanned) view.getText();
          PassageView.SentenceLink sentence =
              (translation ? view.translations : view.sentences).get(index);
          int start = text.getSpanStart(sentence), end = text.getSpanEnd(sentence);
          int offset = Math.min(start + 4, end - 1);
          android.text.Layout layout = view.getLayout();
          int line = layout.getLineForOffset(offset);
          float left = layout.getPrimaryHorizontal(offset),
              right = layout.getPrimaryHorizontal(offset + 1);
          if (right < left) right = layout.getLineRight(line);
          point[0] = view.getTotalPaddingLeft() + (left + right) / 2;
          point[1] =
              view.getTotalPaddingTop()
                  + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f;
          MotionEvent down =
              MotionEvent.obtain(
                  downTime, downTime, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
          assertTrue(view.dispatchTouchEvent(down));
          down.recycle();
        });
    if (hold) Thread.sleep(ViewConfiguration.getLongPressTimeout() + 120);
    ui(
        () -> {
          MotionEvent up =
              MotionEvent.obtain(
                  downTime,
                  android.os.SystemClock.uptimeMillis(),
                  MotionEvent.ACTION_UP,
                  point[0],
                  point[1],
                  0);
          view.dispatchTouchEvent(up);
          up.recycle();
        });
  }

  public void testContinuousPassageHitTargetsAndWrappedEditing() throws Exception {
    JSONObject course = fixture("test_continuous_passage");
    JSONObject original = course.getJSONArray("sentences").getJSONObject(0);
    JSONArray sentences = new JSONArray();
    for (int i = 0; i < 3; i++)
      sentences.put(Store.copy(original).put("speaker", "").put("zh", "译文" + i + "。"));
    course.put("sentences", sentences).put("published", false);
    app.store.saveCourse(course);
    ui(
        () -> {
          app.parent = true;
          app.materialTab = 0;
          app.materials(course.getString("id"), true);
          assertEquals(
              "Each sentence must be followed immediately by its own translation",
              "Excuse me!\n译文0。\nExcuse me!\n译文1。\nExcuse me!\n译文2。",
              app.passage.getText().toString());
          assertEquals(0, app.materialCards.size());
        });
    pressPassage(app.passage, 0, false);
    assertEquals(0, app.materialPlaying);
    pressPassage(app.passage, 1, false);
    assertEquals(
        "Tapping the second identical sentence must select its own position",
        1,
        app.materialPlaying);
    assertTrue(app.passage.sentences.get(1).playing);
    assertTrue(app.passage.translations.get(1).playing);
    pressPassage(app.passage, 1, false);
    assertEquals(-1, app.materialPlaying);
    pressPassage(app.passage, 0, false, true);
    assertEquals(
        "The Chinese line must start playback for its matching English sentence",
        0,
        app.materialPlaying);
    pressPassage(app.passage, 0, false);
    assertEquals(-1, app.materialPlaying);
    pressPassage(app.passage, 2, true, true);
    assertEquals(-1, app.materialPlaying);
    setAccessibleField(activeRoot(), "中文翻译", "第二处长按实际命中的第三句。");
    accessibilityClick("保存");
    JSONArray saved = app.store.course(course.getString("id")).getJSONArray("sentences");
    assertEquals("译文1。", saved.getJSONObject(1).getString("zh"));
    assertEquals("第二处长按实际命中的第三句。", saved.getJSONObject(2).getString("zh"));
    // Both languages wrap without changing the sentence pairing or adding indentation.
    sentences =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("en", "Pumas are large, cat-like animals which are found in America.")
                    .put("zh", "美洲狮是大型的、像猫一样的动物，它们产于美洲。")
                    .put("speaker", ""))
            .put(
                new JSONObject()
                    .put(
                        "en",
                        "When reports came into London Zoo that a wild puma had been spotted"
                            + " forty-five miles south of London, they were not taken seriously.")
                    .put("zh", "当伦敦动物园收到报告说在伦敦以南四十五英里处发现了一只野生美洲狮时，这些报告并没有被认真对待。")
                    .put("speaker", ""));
    course.put("sentences", sentences);
    app.store.saveCourse(course);
    ui(() -> app.materials(course.getString("id"), true));
    ui(
        () -> {
          Rect rect = new Rect();
          app.passage.getDrawingRect(rect);
          app.scroll.offsetDescendantRectToMyCoords(app.passage, rect);
          app.scroll.scrollTo(0, rect.top - app.dp(12));
        });
    shot("12-bilingual-passage-full");
    ui(
        () -> {
          app.body.getLayoutParams().width = app.dp(320);
          app.body.requestLayout();
        });
    ui(
        () -> {
          android.text.Spanned content = (android.text.Spanned) app.passage.getText();
          android.text.Layout layout = app.passage.getLayout();
          for (int i = 0; i < app.passage.sentences.size(); i++) {
            int enStart = content.getSpanStart(app.passage.sentences.get(i));
            int enEnd = content.getSpanEnd(app.passage.sentences.get(i));
            int zhStart = content.getSpanStart(app.passage.translations.get(i));
            int zhEnd = content.getSpanEnd(app.passage.translations.get(i));
            assertEquals("Translation must start on the next line", '\n', content.charAt(enEnd));
            assertEquals(enEnd + 1, zhStart);
            if (i > 0) assertEquals('\n', content.charAt(enStart - 1));
            for (int line = layout.getLineForOffset(enStart);
                line <= layout.getLineForOffset(zhEnd - 1);
                line++)
              assertEquals(
                  "Every English and Chinese line must be flush left",
                  0f,
                  layout.getLineLeft(line),
                  .1f);
          }
          assertTrue(app.passage.getLayout().getLineCount() > 3);
          Rect rect = new Rect();
          app.passage.getDrawingRect(rect);
          app.scroll.offsetDescendantRectToMyCoords(app.passage, rect);
          app.scroll.scrollTo(0, rect.top - app.dp(12));
        });
    shot("12-bilingual-passage");
    pressPassage(app.passage, 1, true);
    setAccessibleField(activeRoot(), "中文翻译", "换行后的第二句也能准确校对。");
    accessibilityClick("保存");
    saved = app.store.course(course.getString("id")).getJSONArray("sentences");
    assertEquals("换行后的第二句也能准确校对。", saved.getJSONObject(1).getString("zh"));
    assertEquals("美洲狮是大型的、像猫一样的动物，它们产于美洲。", saved.getJSONObject(0).getString("zh"));
  }

  void setAccessibleField(AccessibilityNodeInfo node, String hint, String value) {
    if ("android.widget.EditText".contentEquals(node.getClassName())
        && hint.contentEquals(node.getHintText())) {
      android.os.Bundle arguments = new android.os.Bundle();
      arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
      assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null) setAccessibleField(child, hint, value);
    }
  }

  void swipeOnScreen(float x, float from, float to) {
    long start = android.os.SystemClock.uptimeMillis();
    for (int i = 0; i <= 10; i++) {
      int action =
          i == 0
              ? MotionEvent.ACTION_DOWN
              : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
      MotionEvent event =
          MotionEvent.obtain(
              start,
              android.os.SystemClock.uptimeMillis(),
              action,
              x,
              from + (to - from) * i / 10,
              0);
      getInstrumentation().sendPointerSync(event);
      event.recycle();
      android.os.SystemClock.sleep(25);
    }
    getInstrumentation().waitForIdleSync();
  }

  public void testContinuousAudioAdvancementCancellationAndFailure() throws Exception {
    String sentence =
        app.store.course("lesson2").getJSONArray("sentences").getJSONObject(0).getString("en");
    // Identical text at different positions must still play as distinct sentences.
    List<String> items = Arrays.asList(sentence, sentence, sentence);
    List<Integer> events = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    ui(() -> app.audio.playList(items, 0, events::add, errors::add));
    waitForAudioEnd(18000);
    assertEquals(Arrays.asList(0, 1, 2, -1), events);
    assertTrue(errors.toString(), errors.isEmpty());
    List<Integer> interrupted = new ArrayList<>(), replacement = new ArrayList<>();
    ui(
        () -> {
          app.audio.playList(items, 0, interrupted::add, errors::add);
          // Switch while the first MediaPlayer is still preparing.
          app.audio.playList(items, 2, replacement::add, errors::add);
        });
    waitForAudioEnd(10000);
    assertEquals(Arrays.asList(0, -1), interrupted);
    assertEquals(Arrays.asList(2, -1), replacement);
    assertTrue(errors.toString(), errors.isEmpty());
    String corrupt = "test corrupt audio " + UUID.randomUUID();
    File broken = new File(app.store.root, "audio/" + Store.hash(corrupt) + ".mp3");
    try {
      java.nio.file.Files.write(broken.toPath(), new byte[1024]);
      events.clear();
      ui(() -> app.audio.playList(Arrays.asList(corrupt, sentence), 0, events::add, errors::add));
      waitForAudioEnd(6000);
      assertEquals(Arrays.asList(0, -1), events);
      assertEquals("A failed sentence must clear playback and report the error", 1, errors.size());
    } finally {
      broken.delete();
    }
  }

  void waitForAudioEnd(long timeout) throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
    boolean[] ended = {false};
    while (!ended[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
      Thread.sleep(60);
      ui(() -> ended[0] = app.audio.currentIndex() < 0);
    }
    assertTrue("Audio sequence must finish without another tap", ended[0]);
  }

  public void testMaterialHeaderTitleAndIconActions() throws Exception {
    JSONObject course = fixture("test_material_header");
    course.put("published", false).put("title", "待修改标题");
    app.store.saveCourse(course);
    String id = course.getString("id");
    ui(
        () -> {
          app.parent = true;
          app.materialTab = 0;
          app.materials(id, true);
          assertNull(findButton("修改标题与主题"));
          assertNull(findButton("下载 / 补全离线音频"));
          assertNull(findButton("校对完成，发布课程  →"));
          View title = app.body.findViewWithTag("material:title");
          assertNotNull(title);
          assertTrue(title.performClick());
        });
    setAccessibleField(activeRoot(), "课文标题", "My first lesson");
    setAccessibleField(activeRoot(), "中文主题", "我的第一课");
    accessibilityClick("保存");
    assertEquals("My first lesson", app.store.course(id).getString("title"));
    assertEquals("我的第一课", app.store.course(id).getString("subtitle"));
    shot("13-parent-material-toolbar");
    ui(
        () -> {
          ImageButton download = app.body.findViewWithTag("material:audio");
          ImageButton publish = app.body.findViewWithTag("material:publish");
          assertNotNull(download);
          assertNotNull(publish);
          assertTrue(download.getWidth() >= app.dp(48) && download.getHeight() >= app.dp(48));
          assertTrue(publish.getWidth() >= app.dp(48) && publish.getHeight() >= app.dp(48));
          assertNotNull(download.getTooltipText());
          assertNotNull(publish.getTooltipText());
          // Fixtures already have complete audio: this action must be idempotent and offline.
          assertTrue(download.performClick());
          assertTrue(app.busy);
          assertFalse(download.isEnabled());
          assertFalse(publish.isEnabled());
          assertFalse(app.body.findViewWithTag("material:title").isEnabled());
        });
    long deadline = android.os.SystemClock.elapsedRealtime() + 6000;
    boolean[] finished = {false};
    while (!finished[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
      Thread.sleep(40);
      ui(() -> finished[0] = !app.busy);
    }
    assertTrue("Cached audio check must complete", finished[0]);
    ui(
        () -> {
          assertTrue(app.body.findViewWithTag("material:audio").isEnabled());
          assertTrue(app.body.findViewWithTag("material:publish").isEnabled());
        });
    accessibilityClick("校对完成，发布课程");
    assertFalse(
        "Tapping the icon alone must not publish", app.store.course(id).optBoolean("published"));
    accessibilityClick("我已逐句核对原图、翻译和理解题答案");
    accessibilityClick("发布");
    assertTrue(app.store.course(id).optBoolean("published"));
    ui(
        () -> {
          assertTrue(app.body.findViewWithTag("material:publish").isSelected());
          assertTrue(
              "Published titles open editing directly and fork automatically",
              app.body.findViewWithTag("material:title").isClickable());
        });
    shot("13-parent-published-toolbar");
    accessibilityClick("已发布，点击下架");
    accessibilityClick("取消");
    assertTrue(app.store.course(id).optBoolean("published"));
    accessibilityClick("已发布，点击下架");
    accessibilityClick("下架");
    assertFalse(app.store.course(id).optBoolean("published"));
    ui(
        () -> {
          app.parent = false;
          app.materials("lesson2", false);
          assertNull(app.body.findViewWithTag("material:audio"));
          assertNull(app.body.findViewWithTag("material:publish"));
          assertFalse(app.body.findViewWithTag("material:title").isClickable());
        });
  }

  public void testPublishedEditingCreatesTimestampedDraftAutomatically() throws Exception {
    JSONObject original = fixture("test_auto_draft");
    String id = original.getString("id"), originalTitle = original.getString("title");
    ui(
        () -> {
          app.parent = true;
          app.materialTab = 0;
          app.materials(id, true);
          assertTrue(app.passage.editable);
          assertNull(findButton("复制为新草稿并编辑"));
          assertTrue(app.body.findViewWithTag("material:title").performClick());
        });
    String first = app.selectedId;
    assertFalse(first.equals(id));
    JSONObject draft = app.store.course(first);
    assertEquals(id, Store.lessonId(draft));
    assertTrue(app.store.isDraft(draft));
    assertTrue(
        Store.draftName(draft).matches("草稿 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    setAccessibleField(activeRoot(), "课文标题", "自动草稿的新标题");
    accessibilityClick("保存");
    assertEquals("自动草稿的新标题", app.store.course(first).getString("title"));
    assertEquals(originalTitle, app.store.course(id).getString("title"));
    assertTrue(app.store.course(id).optBoolean("published"));
    ui(
        () -> {
          app.materials(id, true);
          app.editMaterial(app.store.course(id), "sentences", 0, 0);
        });
    String second = app.selectedId;
    assertFalse(second.equals(id));
    assertFalse(second.equals(first));
    accessibilityClick("取消");
    assertEquals(3, app.store.versions(original).size());
    assertFalse(
        Store.draftName(app.store.course(first)).equals(Store.draftName(app.store.course(second))));
    JSONObject reopen = new Store(app).course(second);
    assertEquals(id, Store.lessonId(reopen));
    ui(
        () -> {
          app.parent = false;
          app.materials(id, false);
          assertFalse(app.passage.editable);
          assertFalse(app.body.findViewWithTag("material:title").isClickable());
        });
    assertEquals(3, app.store.versions(original).size());
    ui(
        () -> {
          app.parent = true;
          app.courses();
          Set<String> families = new HashSet<>();
          for (JSONObject c : app.store.courses(false)) families.add(Store.lessonId(c));
          long cards =
              views(app.body).stream()
                  .filter(
                      v -> v instanceof Button b && b.getText().toString().equals("查看、校对与发布  →"))
                  .count();
          assertEquals(
              "Each lesson has one card regardless of draft count", families.size(), cards);
        });
  }

  public void testMultipleDraftPublicationDeletionAndBackup() throws Exception {
    Context isolated = isolatedContext("draft-versions-");
    Store store = new Store(isolated);
    JSONObject original = sample(2).put("id", "version_source").put("published", true);
    String ref = "audio/" + "a".repeat(64) + ".mp3";
    original
        .getJSONArray("sentences")
        .getJSONObject(0)
        .put("audioFile", ref)
        .put("audioText", original.getJSONArray("sentences").getJSONObject(0).getString("en"));
    original.put("sources", new JSONArray().put("images/shared.jpg"));
    java.nio.file.Files.write(new File(store.root, ref).toPath(), new byte[] {73, 68, 51, 1});
    try (InputStream image = fixtureAsset("courses/lesson2-page1.jpg")) {
      java.nio.file.Files.copy(image, new File(store.root, "images/shared.jpg").toPath());
    }
    store.saveCourse(original);
    store.progress("version_source").put("completed", 1).put("attempted", 3).put("correct", 2);
    store.save();
    String progress = store.progress("version_source").toString();
    JSONObject first = store.newDraft("version_source"), second = store.newDraft("version_source");
    first.put("title", "第一份可发布草稿");
    store.saveDraft(first);
    assertTrue(store.course("version_source").optBoolean("published"));
    store.setPublished(first.getString("id"), true);
    assertEquals(1, store.courses(true).size());
    assertEquals(first.getString("id"), store.courses(true).get(0).getString("id"));
    assertEquals(progress, store.progress("version_source").toString());
    assertFalse(store.course("version_source").optBoolean("published"));
    assertFalse(store.isDraft(store.course("version_source")));
    boolean protectedVersion = false;
    try {
      store.deleteDraft(first.getString("id"));
    } catch (Exception expected) {
      protectedVersion = true;
    }
    assertTrue(protectedVersion);
    store.deleteDraft(second.getString("id"));
    assertTrue(new File(store.root, ref).isFile());
    assertTrue(new File(store.root, "images/shared.jpg").isFile());
    boolean staleSave = false;
    try {
      store.saveDraft(second);
    } catch (Exception expected) {
      staleSave = true;
    }
    assertTrue(staleSave);
    assertFalse(new File(store.root, "courses/" + second.getString("id") + ".json").exists());
    JSONObject third = store.editableDraft(first.getString("id"));
    assertTrue(store.isDraft(third));
    assertFalse(first.getString("id").equals(third.getString("id")));
    store.setPublished(third.getString("id"), true);
    boolean stalePublishedSave = false;
    try {
      store.saveDraft(third);
    } catch (Exception expected) {
      stalePublishedSave = true;
    }
    assertTrue(stalePublishedSave);
    store.setPublished(third.getString("id"), false);
    assertFalse(store.isDraft(store.course(third.getString("id"))));
    store.setPublished(first.getString("id"), true);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    store.exportZip(out);
    Store restored = new Store(isolatedContext("restored-versions-"));
    restored.importZip(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(3, restored.versions(original).size());
    assertEquals(1, restored.courses(true).size());
    assertEquals(first.getString("id"), restored.courses(true).get(0).getString("id"));
    assertEquals(progress, restored.progress("version_source").toString());
    assertTrue(new File(restored.root, ref).isFile());
    Store reopened = new Store(restored.context);
    assertEquals(first.getString("id"), reopened.courses(true).get(0).getString("id"));
    assertFalse(reopened.isDraft(reopened.course("version_source")));
    // An older backup of another lesson must not hide this family's current published version.
    Store legacy = new Store(isolatedContext("legacy-versions-"));
    JSONObject unrelated = sample(1).put("id", "unrelated_legacy").put("sources", new JSONArray());
    legacy.saveCourse(unrelated);
    ByteArrayOutputStream old = new ByteArrayOutputStream();
    legacy.exportZip(old);
    reopened.importZip(new ByteArrayInputStream(old.toByteArray()));
    assertTrue(reopened.course(first.getString("id")).optBoolean("published"));
  }

  public void testDraftPickerSelectionAndDeletion() throws Exception {
    JSONObject original = fixture("test_draft_picker");
    JSONObject first = app.store.newDraft(original.getString("id"));
    JSONObject second = app.store.newDraft(original.getString("id"));
    ui(
        () -> {
          app.parent = true;
          app.courseVersions(original);
        });
    assertTrue(
        activeRoot().findAccessibilityNodeInfosByText("新建草稿").stream()
            .anyMatch(AccessibilityNodeInfo::isVisibleToUser));
    shot("19-course-draft-picker");
    accessibilityClick("编辑此草稿");
    assertEquals(second.getString("id"), app.selectedId);
    ui(() -> app.courseVersions(original));
    accessibilityClick("删除草稿");
    accessibilityClick("取消");
    assertEquals(3, app.store.versions(original).size());
    accessibilityClick("删除草稿");
    accessibilityClick("删除草稿");
    assertEquals(2, app.store.versions(original).size());
    assertTrue(app.store.course(original.getString("id")).optBoolean("published"));
    accessibilityClick("编辑此草稿");
    assertEquals(first.getString("id"), app.selectedId);
    ui(() -> app.courseVersions(original));
    accessibilityClick("新建草稿");
    assertEquals(3, app.store.versions(original).size());
    assertTrue(app.store.isDraft(app.store.course(app.selectedId)));
  }

  public void testBackupValidationAndRestore() throws Exception {
    fixture("test_backup");
    File sharedImage = new File(app.store.root, "images/test-backup.jpg");
    try (InputStream source = fixtureAsset("courses/lesson2-page1.jpg")) {
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

  void restoreConfig(Map<String, ?> values) {
    SharedPreferences.Editor edit = app.getSharedPreferences("config", 0).edit().clear();
    for (Map.Entry<String, ?> entry : values.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof String s) edit.putString(entry.getKey(), s);
      else if (value instanceof Boolean b) edit.putBoolean(entry.getKey(), b);
      else if (value instanceof Integer i) edit.putInt(entry.getKey(), i);
      else if (value instanceof Long l) edit.putLong(entry.getKey(), l);
    }
    assertTrue(edit.commit());
  }

  <T extends View> T configView(String tag) {
    return app.shell.findViewWithTag("config:" + tag);
  }

  File audioCopy(String english) throws Exception {
    File copy = File.createTempFile("test-audio-", ".mp3", app.getCacheDir());
    java.nio.file.Files.copy(
        app.audio.local(english).toPath(),
        copy.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return copy;
  }

  public void testVoiceSettingsAndUnsavedForm() throws Exception {
    Map<String, ?> before = app.getSharedPreferences("config", 0).getAll();
    Secrets original = app.secrets;
    Secrets isolated = new Secrets(isolatedContext("voice_settings_"));
    isolated.set("test-only-saved-key");
    try {
      ui(
          () -> {
            app.secrets = isolated;
            app.parent = true;
            app.settings();
            for (View v : views(app.shell))
              if (v instanceof TextView t)
                assertFalse(t.getText().toString().contains("导入 llm info.txt"));
            this.<Spinner>configView("voice").setSelection(2);
            this.<Spinner>configView("pace").setSelection(2);
            this.<EditText>configView("tts").setText("mimo-v2.5-tts");
            app.apiKeyInput.setText("test-only-unsaved-key");
            ServiceConfig form =
                app.serviceForm(
                    configView("base"),
                    configView("model"),
                    configView("tts"),
                    app.apiKeyInput,
                    configView("voice"),
                    configView("customVoice"),
                    configView("pace"));
            assertEquals("test-only-unsaved-key", form.key);
            assertEquals("Milo", form.voice);
            assertEquals("fast", form.pace);
            JSONObject request = AiClient.speechRequest("Hello, island!", form);
            assertEquals("Milo", request.getJSONObject("audio").getString("voice"));
            assertEquals(
                "Hello, island!",
                request.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertTrue(
                request
                    .getJSONArray("messages")
                    .getJSONObject(0)
                    .getString("content")
                    .contains("brisk"));
            assertEquals("test-only-saved-key", isolated.get());
          });
      click("保存 API 设置");
      assertEquals("test-only-unsaved-key", isolated.get());
      assertEquals("Milo", app.getSharedPreferences("config", 0).getString("ttsVoice", ""));
      assertEquals("fast", app.getSharedPreferences("config", 0).getString("ttsPace", ""));
      ui(
          () -> {
            assertEquals("", app.apiKeyInput.getText().toString());
            app.apiKeyInput.setText("unsaved-input-stays-on-failure");
            // A missing key fails before any network access and must retain the current form.
            app.testService(
                new ServiceConfig(
                    "https://api.xiaomimimo.com/v1",
                    "mimo-v2.6-flash",
                    "mimo-v2.5-tts",
                    "",
                    "Dean",
                    "normal"),
                "Hello!");
          });
      long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
      while (app.busy && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
      ui(
          () -> {
            assertFalse(app.busy);
            assertTrue(app.status.contains("测试未通过"));
            assertEquals("unsaved-input-stays-on-failure", app.apiKeyInput.getText().toString());
            assertEquals(2, this.<Spinner>configView("voice").getSelectedItemPosition());
            app.apiKeyInput.setText("");
            if (app.lastToast != null) app.lastToast.cancel();
            configView("pace").requestRectangleOnScreen(new Rect(0, 0, 1, app.dp(250)), true);
          });
      shot("15-voice-settings");
    } finally {
      ui(
          () -> {
            app.secrets = original;
            restoreConfig(before);
            app.home();
          });
    }
  }

  public void testItemAudioReplacementSaveCancelAndLearning() throws Exception {
    JSONObject course = fixture("test_item_audio");
    course.put("published", false);
    app.store.saveCourse(course);
    String en = course.getJSONArray("sentences").getJSONObject(0).getString("en");
    File shared = app.audio.local(en);
    long sharedSize = shared.length();
    final String[] ref = {""}, cancelled = {""};
    try {
      ui(
          () -> {
            app.parent = true;
            app.materials(course.getString("id"), true);
            app.editItem(course, "sentences", 0);
            ItemAudioEditor editor = app.itemAudioEditor;
            editor.replacePending(audioCopy(en), en, "tts", "Mia", "slow");
            editor.describe();
            File discarded = editor.pending;
            editor.undo.performClick();
            assertNull(editor.pending);
            assertFalse(discarded.exists());
            editor.replacePending(audioCopy(en), en, "tts", "Mia", "slow");
            editor.describe();
            JSONObject value = Store.copy(course.getJSONArray("sentences").getJSONObject(0));
            editor.attachTo(value);
            cancelled[0] = value.getString("audioFile");
            assertTrue(new File(app.store.root, cancelled[0]).isFile());
            editor.dialog.getButton(-2).performClick();
          });
      ui(
          () -> {
            assertFalse(new File(app.store.root, cancelled[0]).exists());
            assertEquals(
                "",
                Audio.itemFile(
                    app.store
                        .course(course.getString("id"))
                        .getJSONArray("sentences")
                        .getJSONObject(0)));
            app.editItem(course, "sentences", 0);
          });
      ui(
          () -> {
            ItemAudioEditor editor = app.itemAudioEditor;
            editor.replacePending(audioCopy(en), en, "tts", "Milo", "normal");
            editor.describe();
            assertTrue(editor.undo.getVisibility() == View.VISIBLE);
            editor.dialog.getButton(-1).performClick();
          });
      JSONObject saved = app.store.course(course.getString("id"));
      JSONObject item = saved.getJSONArray("sentences").getJSONObject(0);
      ref[0] = item.getString("audioFile");
      assertTrue(Audio.validRef(ref[0]));
      assertFalse(shared.equals(app.audio.local(en, ref[0])));
      assertEquals(sharedSize, shared.length());
      assertEquals("", Audio.itemFile(course.getJSONArray("sentences").getJSONObject(0)));
      assertEquals(ref[0], app.store.questions(saved, 0).get(0).audioFile);
      assertEquals(ref[0], app.store.questions(saved, 3).get(0).audioFile);
      Learning.Question question = app.store.questions(saved, 3).get(0);
      assertEquals(
          ref[0], MainActivity.readQuestion(MainActivity.questionJson(question)).audioFile);
      app.store.record(course.getString("id"), question, false, true);
      assertEquals(
          ref[0],
          app.store
              .state
              .getJSONObject("reviews")
              .getJSONObject(course.getString("id") + "_" + question.id)
              .getString("audioFile"));
      ui(
          () -> {
            app.materials(saved.getString("id"), true);
            assertEquals(ref[0], app.materialAudioFiles.get(0));
            app.editItem(saved, "sentences", 0);
          });
      ui(
          () -> {
            ItemAudioEditor editor = app.itemAudioEditor;
            editor.togglePreview();
            assertTrue(editor.previewing);
            app.audio.stop();
            editor.replacePending(audioCopy(en), en, "tts", "Mia", "slow");
            File outdated = editor.pending;
            editor.english.setText(en + " Again.");
            assertNull(editor.pending);
            assertFalse(outdated.exists());
            editor.dialog.getButton(-1).performClick();
          });
      String editedId = app.selectedId;
      assertFalse(
          "Learning records automatically protect the old course",
          editedId.equals(saved.getString("id")));
      assertEquals(
          "",
          Audio.itemFile(app.store.course(editedId).getJSONArray("sentences").getJSONObject(0)));
      assertEquals(
          ref[0],
          Audio.itemFile(
              app.store.course(saved.getString("id")).getJSONArray("sentences").getJSONObject(0)));
      // Word and phrase editors must expose and save the same independent audio controls.
      for (String kind : new String[] {"word", "phrase"}) {
        JSONObject current = app.store.course(editedId);
        JSONArray vocab = current.getJSONArray("vocabulary");
        int found = -1;
        for (int i = 0; i < vocab.length(); i++)
          if (vocab.getJSONObject(i).getString("kind").equals(kind)) {
            found = i;
            break;
          }
        assertTrue(found >= 0);
        final int index = found;
        String english = vocab.getJSONObject(index).getString("en");
        ui(
            () -> {
              app.editItem(current, "vocabulary", index);
              ItemAudioEditor editor = app.itemAudioEditor;
              editor.replacePending(audioCopy(english), english, "tts", "Dean", "fast");
              editor.describe();
            });
        if (kind.equals("word")) {
          ui(
              () ->
                  app.itemAudioEditor.record.requestRectangleOnScreen(
                      new Rect(0, 0, 1, app.dp(150)), true));
          shot("16-item-audio-editor");
        }
        ui(() -> app.itemAudioEditor.dialog.getButton(-1).performClick());
        JSONObject changed = app.store.course(editedId);
        String vocabRef = Audio.itemFile(changed.getJSONArray("vocabulary").getJSONObject(index));
        try {
          assertFalse(vocabRef.isEmpty());
          assertEquals(
              vocabRef, app.store.questions(changed, kind.equals("word") ? 1 : 2).get(0).audioFile);
          if (kind.equals("word"))
            assertEquals(vocabRef, app.store.questions(changed, 4).get(0).audioFile);
        } finally {
          new File(app.store.root, vocabRef).delete();
        }
      }
    } finally {
      ui(
          () -> {
            if (app.itemAudioEditor != null) app.itemAudioEditor.dialog.dismiss();
          });
      if (!ref[0].isEmpty()) new File(app.store.root, ref[0]).delete();
    }
  }

  public void testCustomAudioBackupValidationAndSampleMigration() throws Exception {
    Context context = isolatedContext("custom_audio_backup_");
    Store target = new Store(context);
    JSONObject course = sample(2);
    JSONObject sentence = course.getJSONArray("sentences").getJSONObject(0);
    String ref = "audio/" + Store.hash(UUID.randomUUID().toString()) + ".mp3";
    File clip = new File(target.root, ref);
    java.nio.file.Files.copy(app.audio.local(sentence.getString("en")).toPath(), clip.toPath());
    sentence.put("audioFile", ref).put("audioText", sentence.getString("en"));
    target.saveCourse(course);
    target.state.remove("legacySamplesArchived");
    target.save();
    target = new Store(context);
    assertEquals(
        "An audio-only edit must protect a sample from migration", 1, target.courses(false).size());
    ByteArrayOutputStream backup = new ByteArrayOutputStream();
    target.exportZip(backup);
    assertTrue(clip.delete());
    target.importZip(new ByteArrayInputStream(backup.toByteArray()));
    Audio.validateClip(clip);
    String state = target.state.toString();
    byte[] original = java.nio.file.Files.readAllBytes(clip.toPath());
    for (int scenario = 0; scenario < 3; scenario++) {
      ByteArrayOutputStream invalid = new ByteArrayOutputStream();
      try (ZipInputStream input =
              new ZipInputStream(new ByteArrayInputStream(backup.toByteArray()));
          ZipOutputStream output = new ZipOutputStream(invalid)) {
        ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) {
          byte[] data = Store.bytes(input, 64 * 1024 * 1024);
          if (scenario == 0 && entry.getName().equals(ref)) continue;
          if (entry.getName().equals("courses/lesson2.json") && scenario > 0) {
            JSONObject edited =
                new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            JSONObject item = edited.getJSONArray("sentences").getJSONObject(0);
            if (scenario == 1) item.put("audioFile", "../escape.m4a");
            else item.put("audioText", "A different sentence.");
            data = edited.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          }
          Store.add(output, entry.getName(), data);
        }
      }
      if (scenario == 0) assertTrue(clip.delete());
      boolean rejected = false;
      try {
        target.importZip(new ByteArrayInputStream(invalid.toByteArray()));
      } catch (Exception expected) {
        rejected = true;
      }
      assertTrue("Invalid custom audio backup must be rejected before writing", rejected);
      assertEquals(state, target.state.toString());
      if (scenario == 0) java.nio.file.Files.write(clip.toPath(), original);
      assertTrue(Arrays.equals(original, java.nio.file.Files.readAllBytes(clip.toPath())));
    }
  }

  public void testMicrophoneRecordingAndCustomAudioBackup() throws Exception {
    // Run only on a dedicated emulator started with -audio none: never capture the host microphone.
    if (!"true"
        .equals(
            ((android.test.InstrumentationTestRunner) getInstrumentation())
                .getArguments()
                .getString("allowRecording"))) return;
    assertEquals(
        android.content.pm.PackageManager.PERMISSION_GRANTED,
        app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO));
    JSONObject course = fixture("test_recording");
    course.put("published", false);
    app.store.saveCourse(course);
    String en = course.getJSONArray("sentences").getJSONObject(0).getString("en");
    final String[] ref = {""};
    try {
      ui(
          () -> {
            app.parent = true;
            app.materials(course.getString("id"), true);
            app.editItem(course, "sentences", 0);
            app.itemAudioEditor.startRecording();
            assertNotNull(app.itemAudioEditor.recorder);
            assertFalse(app.itemAudioEditor.dialog.getButton(-1).isEnabled());
            assertFalse(app.itemAudioEditor.english.isEnabled());
          });
      Thread.sleep(1500);
      ui(
          () -> {
            ItemAudioEditor editor = app.itemAudioEditor;
            editor.stopRecording(true);
            assertNotNull(editor.pending);
            assertEquals("recorded", editor.pendingSource);
            Audio.validateClip(editor.pending);
            editor.togglePreview();
            assertTrue(editor.previewing);
            editor.dialog.getButton(-1).performClick();
          });
      JSONObject saved = app.store.course(course.getString("id"));
      ref[0] = Audio.itemFile(saved.getJSONArray("sentences").getJSONObject(0));
      assertTrue(ref[0].endsWith(".m4a"));
      File clip = app.audio.local(en, ref[0]);
      byte[] original = java.nio.file.Files.readAllBytes(clip.toPath());
      ByteArrayOutputStream backup = new ByteArrayOutputStream();
      app.store.exportZip(backup);
      assertTrue(clip.delete());
      app.store.importZip(new ByteArrayInputStream(backup.toByteArray()));
      assertTrue(Arrays.equals(original, java.nio.file.Files.readAllBytes(clip.toPath())));
      ui(
          () -> {
            app.editItem(saved, "sentences", 0);
            app.itemAudioEditor.startRecording();
            assertNotNull(app.itemAudioEditor.recorder);
          });
      Thread.sleep(350);
      ui(
          () -> {
            ItemAudioEditor editor = app.itemAudioEditor;
            editor.pause();
            assertNull(editor.recorder);
            assertNull(editor.pending);
            editor.dialog.getButton(-2).performClick();
          });
      assertTrue(clip.isFile());
      assertEquals(
          ref[0],
          Audio.itemFile(
              app.store.course(saved.getString("id")).getJSONArray("sentences").getJSONObject(0)));
    } finally {
      ui(
          () -> {
            if (app.itemAudioEditor != null) app.itemAudioEditor.dialog.dismiss();
          });
      if (!ref[0].isEmpty()) new File(app.store.root, ref[0]).delete();
    }
  }

  public void testZLiveSpeechPreviewAndItemRegeneration() throws Exception {
    File config = new File(app.store.root, "speech-test-config.txt");
    if (!config.exists()) return; // Explicitly provisioned for this limited live test only.
    String text = Store.read(new FileInputStream(config));
    assertTrue(config.delete());
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("sk-[A-Za-z0-9_-]+").matcher(text);
    assertTrue(matcher.find());
    String key = matcher.group();
    Map<String, ?> before = app.getSharedPreferences("config", 0).getAll();
    String oldKey = app.secrets.get();
    JSONObject course = fixture("test_live_speech");
    course.put("published", false);
    app.store.saveCourse(course);
    final String[] ref = {""};
    try {
      ui(
          () -> {
            app.parent = true;
            app.settings();
            this.<EditText>configView("base").setText("https://api.xiaomimimo.com/v1");
            this.<EditText>configView("tts").setText("mimo-v2.5-tts");
            this.<Spinner>configView("voice").setSelection(2);
            this.<Spinner>configView("pace").setSelection(1);
            this.<EditText>configView("previewText").setText("Hello, welcome to Lesson Island!");
            app.apiKeyInput.setText(key);
          });
      click("测试语音并试听");
      long deadline = android.os.SystemClock.elapsedRealtime() + 190000;
      while (app.busy && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
      ui(
          () -> {
            assertFalse(app.busy);
            assertTrue("Speech preview must succeed", app.status.startsWith("语音测试成功"));
            assertNotNull(app.voicePreview);
            Audio.validateClip(app.voicePreview);
            assertEquals(oldKey, app.secrets.get());
            assertEquals(before, app.getSharedPreferences("config", 0).getAll());
            app.apiKeyInput.setText("");
            new ServiceConfig(
                    "https://api.xiaomimimo.com/v1",
                    "mimo-v2.6-flash",
                    "mimo-v2.5-tts",
                    key,
                    "Mia",
                    "slow")
                .save(app, app.secrets, key);
            app.materials(course.getString("id"), true);
            app.editItem(course, "vocabulary", 0);
            app.itemAudioEditor.generate();
          });
      ItemAudioEditor editor = app.itemAudioEditor;
      deadline = android.os.SystemClock.elapsedRealtime() + 190000;
      while (editor.generating && android.os.SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(100);
      ui(
          () -> {
            assertFalse(editor.generating);
            assertNotNull("Regenerated item must be staged", editor.pending);
            Audio.validateClip(editor.pending);
            editor.togglePreview();
            assertTrue(editor.previewing);
            editor.dialog.getButton(-1).performClick();
          });
      JSONObject saved =
          app.store.course(course.getString("id")).getJSONArray("vocabulary").getJSONObject(0);
      ref[0] = saved.getString("audioFile");
      assertEquals("Mia", saved.getString("audioVoice"));
      assertEquals("slow", saved.getString("audioPace"));
      assertTrue(new File(app.store.root, ref[0]).isFile());
    } finally {
      ui(
          () -> {
            if (app.itemAudioEditor != null) app.itemAudioEditor.dialog.dismiss();
            app.secrets.set(oldKey);
            restoreConfig(before);
            app.home();
          });
      if (!ref[0].isEmpty()) new File(app.store.root, ref[0]).delete();
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
    try (InputStream source = fixtureAsset("courses/lesson2-page1.jpg")) {
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
    assertFalse(app.passage.sentences.isEmpty());
    ui(
        () -> {
          app.parent = false;
          app.path(course.getString("id"));
          app.newSession(course.getString("id"), 0);
        });
    answerCorrect();
    assertEquals(app.queue.length(), app.position);
  }
}
