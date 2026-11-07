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
        shot("05-gentle-feedback");
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
            boolean hasAction = false;
            for (View child : views(tile)) if (child instanceof Button) hasAction = true;
            assertEquals("Only completed and next stages may be started", i <= 1, hasAction);
          }
          Button next = findButton("开始第 2 关");
          assertNotNull(next);
          Rect rect = new Rect();
          next.getDrawingRect(rect);
          next.requestRectangleOnScreen(rect, true);
        });
    shot("10-narrow-route");
    click("开始第 2 关");
    assertEquals("quiz", app.page);
    ui(
        () -> {
          assertNotNull(app.actionFooter);
          Button check = findButton("检查答案");
          assertNotNull(check);
          Rect rect = new Rect();
          assertTrue(
              "Check button must be visible without scrolling", check.getGlobalVisibleRect(rect));
          assertTrue("Pinned action must have a full touch target", rect.height() >= app.dp(48));
          assertEquals(app.actionFooter, check.getParent());
          app.scroll.fullScroll(View.FOCUS_DOWN);
        });
    ui(
        () -> {
          Rect rect = new Rect();
          assertTrue(findButton("检查答案").getGlobalVisibleRect(rect));
          assertTrue(rect.height() >= app.dp(48));
        });
    shot("10-pinned-answer-action");
    Learning.Question q = app.current();
    click(q.answer);
    click("检查答案");
    ui(
        () -> {
          Button next = findButton(app.position >= app.queue.length() ? "完成本次学习  →" : "继续  →");
          assertNotNull(next);
          assertEquals(app.actionFooter, next.getParent());
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
