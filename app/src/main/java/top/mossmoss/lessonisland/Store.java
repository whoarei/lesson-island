package top.mossmoss.lessonisland;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public final class Store {
  final Context context;
  final File root;
  JSONObject state;

  public Store(Context context) throws Exception {
    this.context = context;
    root = context.getFilesDir();
    for (String dir : new String[] {"courses", "audio", "images"}) new File(root, dir).mkdirs();
    recoverRestore();
    File file = new File(root, "state.json");
    if (file.exists()) {
      state = new JSONObject(read(new AtomicFile(file).openRead()));
    } else {
      state =
          new JSONObject()
              .put("version", 1)
              .put("name", "小小探索家")
              .put("xp", 0)
              .put("goal", 10)
              .put("progress", new JSONObject())
              .put("reviews", new JSONObject())
              .put("history", new JSONArray());
      save();
    }
    if (StudyTimeLedger.initialize(state, System.currentTimeMillis(), java.time.ZoneId.systemDefault())) save();
    archiveLegacySamples();
    migrateSessions();
  }

  private void migrateSessions() throws Exception {
    boolean changed = false;
    if (state.optJSONObject("courseSessions") == null) {
      state.put("courseSessions", new JSONObject());
      changed = true;
    }
    if (state.optJSONObject("reviewSessions") == null) {
      state.put("reviewSessions", new JSONObject());
      changed = true;
    }
    JSONObject legacy = state.optJSONObject("session");
    if (legacy != null) {
      if (legacy.optBoolean("review")) {
        String key = legacy.optBoolean("reviewOnlyMistakes") ? "mistakes" : "all";
        if (!state.getJSONObject("reviewSessions").has(key))
          state.getJSONObject("reviewSessions").put(key, legacy);
      } else {
        String id = legacy.optString("course");
        if (id.matches("[A-Za-z0-9_-]{1,100}")) {
          if (!state.getJSONObject("courseSessions").has(id))
            state.getJSONObject("courseSessions").put(id, legacy);
          state.put("lastLearningCourse", id);
        }
      }
      state.remove("session");
      changed = true;
    }
    if (changed) save();
  }

  synchronized JSONObject courseSession(String id) throws Exception {
    return state.getJSONObject("courseSessions").optJSONObject(id);
  }

  synchronized JSONObject latestCourseSession() throws Exception {
    JSONObject sessions = state.getJSONObject("courseSessions");
    JSONObject latest = sessions.optJSONObject(state.optString("lastLearningCourse"));
    if (isFrontierSession(latest)) return latest;
    latest = null;
    long latestTime = Long.MIN_VALUE;
    for (Iterator<String> keys = sessions.keys(); keys.hasNext(); ) {
      JSONObject candidate = sessions.optJSONObject(keys.next());
      if (isFrontierSession(candidate) && candidate.optLong("updatedAt", candidate.optLong("started")) >= latestTime) {
        latest = candidate;
        latestTime = candidate.optLong("updatedAt", candidate.optLong("started"));
      }
    }
    return latest;
  }

  private boolean isFrontierSession(JSONObject session) throws Exception {
    if (session == null) return false;
    String id = session.optString("course");
    int stage = session.optInt("stage", -1);
    JSONObject progress = state.getJSONObject("progress").optJSONObject(id);
    int completed = progress == null ? 0 : progress.optInt("completed");
    if (stage < 0 || stage >= Learning.STAGES.length || stage != completed) return false;
    try {
      return course(id).optBoolean("published");
    } catch (FileNotFoundException e) {
      return false;
    }
  }

  synchronized JSONObject reviewSession(boolean mistakes) throws Exception {
    return state.getJSONObject("reviewSessions").optJSONObject(mistakes ? "mistakes" : "all");
  }

  synchronized void saveCourseSession(JSONObject session) throws Exception {
    String id = safe(session.getString("course"));
    session.put("updatedAt", System.currentTimeMillis());
    state.getJSONObject("courseSessions").put(id, session);
    state.put("lastLearningCourse", id);
    save();
  }

  synchronized void saveReviewSession(JSONObject session, boolean mistakes) throws Exception {
    session.put("reviewOnlyMistakes", mistakes);
    state.getJSONObject("reviewSessions").put(mistakes ? "mistakes" : "all", session);
    save();
  }

  synchronized void removeCourseSession(String id) throws Exception {
    state.getJSONObject("courseSessions").remove(id);
    if (id.equals(state.optString("lastLearningCourse"))) state.remove("lastLearningCourse");
  }

  synchronized void removeReviewSession(boolean mistakes) throws Exception {
    state.getJSONObject("reviewSessions").remove(mistakes ? "mistakes" : "all");
  }

  private void archiveLegacySamples() throws Exception {
    if (state.optBoolean("legacySamplesArchived")) return;
    String[] fingerprints = {
      "a770d5de5b882d81950e3cb328ed6ca8d2298f1f293ca8fb45620d986ab6307c",
      "34e494a3e1f76022996b503df466bfa60bde5293f2f67d62a7fd309ee80d7aa9",
      "352381b0f5e0c8ea4ff7760cc679b784ea7dc85454834d25e02d1b8a5f730422",
      "0e643774e3e21ab0f65c13c47a2dcfb1d65f24934da69607afd7e2294b3ccd96"
    };
    Set<String> archived = new HashSet<>();
    File archive = new File(root, "legacy-sample-archive");
    for (int i = 0; i < fingerprints.length; i++) {
      String id = "lesson" + (i + 1);
      File source = new File(root, "courses/" + id + ".json");
      File destination = new File(archive, id + ".json");
      if (source.exists()) {
        JSONObject course = new JSONObject(read(new FileInputStream(source)));
        // Only move the unchanged, previously auto-installed samples. Edited and new courses stay.
        if (id.equals(course.optString("id"))
            && !hasCustomAudio(course)
            && fingerprints[i].equals(sampleFingerprint(course))) {
          archive.mkdirs();
          java.nio.file.Files.move(
              source.toPath(),
              destination.toPath(),
              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          archived.add(id);
        }
      } else if (destination.exists()) archived.add(id);
    }
    JSONObject session = state.optJSONObject("session");
    if (session != null && archived.contains(session.optString("course"))) {
      state.put("archivedSampleSession", copy(session));
      state.remove("session");
    }
    state.put("legacySamplesArchived", true);
    save();
  }

  private static boolean hasCustomAudio(JSONObject course) throws Exception {
    for (String section : new String[] {"sentences", "vocabulary"}) {
      JSONArray items = course.getJSONArray(section);
      for (int i = 0; i < items.length(); i++)
        if (!items.getJSONObject(i).optString("audioFile").isEmpty()) return true;
    }
    return false;
  }

  private static String sampleFingerprint(JSONObject course) throws Exception {
    StringBuilder result =
        new StringBuilder(course.optString("title"))
            .append('\0')
            .append(course.optString("subtitle"))
            .append('\n');
    String[] arrays = {"sentences", "vocabulary", "questions"};
    String[][] fields = {
      {"en", "zh", "speaker"},
      {"en", "zh", "kind", "note", "phonetic"},
      {"prompt", "options", "answer", "explanation"}
    };
    for (int section = 0; section < arrays.length; section++) {
      result.append(arrays[section]).append('\n');
      JSONArray items = course.getJSONArray(arrays[section]);
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.getJSONObject(i);
        for (String field : fields[section]) {
          if (field.equals("options")) {
            JSONArray options = item.getJSONArray(field);
            for (int j = 0; j < options.length(); j++) {
              if (j > 0) result.append('\0');
              result.append(options.getString(j));
            }
          } else result.append(item.optString(field));
          result.append('\0');
        }
        result.append('\n');
      }
    }
    return hash(result.toString());
  }

  static byte[] bytes(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if (output.size() + n > limit) throw new IOException("文件大小超出限制");
      output.write(buffer, 0, n);
    }
    return output.toByteArray();
  }

  static String read(InputStream input) throws IOException {
    try (input) {
      return new String(bytes(input, 24 * 1024 * 1024), StandardCharsets.UTF_8);
    }
  }

  static void write(File file, String value) throws IOException {
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream output = null;
    try {
      output = atomic.startWrite();
      output.write(value.getBytes(StandardCharsets.UTF_8));
      atomic.finishWrite(output);
    } catch (IOException e) {
      if (output != null) atomic.failWrite(output);
      throw e;
    }
  }

  synchronized void save() throws Exception {
    write(new File(root, "state.json"), state.toString());
  }

  synchronized List<JSONObject> courses(boolean publishedOnly) throws Exception {
    List<JSONObject> result = new ArrayList<>();
    File[] files = new File(root, "courses").listFiles();
    if (files != null)
      for (File f : files)
        if (f.getName().endsWith(".json")) {
          JSONObject c = publication(new JSONObject(read(new FileInputStream(f))));
          if (!publishedOnly || c.optBoolean("published")) result.add(c);
        }
    result.sort(Comparator.comparingLong(c -> c.optLong("createdAt")));
    return result;
  }

  synchronized JSONObject course(String id) throws Exception {
    return publication(
        new JSONObject(read(new FileInputStream(new File(root, "courses/" + safe(id) + ".json")))));
  }

  synchronized void saveCourse(JSONObject course) throws Exception {
    validate(course, false);
    write(new File(root, "courses/" + safe(course.getString("id")) + ".json"), course.toString());
  }

  synchronized void saveDraft(JSONObject draft) throws Exception {
    if (!isDraft(course(draft.getString("id")))) throw new Exception("草稿状态已变化，请重新打开编辑");
    saveCourse(draft);
  }

  static String lessonId(JSONObject course) {
    return safe(course.optString("lessonId", course.optString("id")));
  }

  private JSONObject publication(JSONObject course) throws Exception {
    JSONObject published = state.optJSONObject("publishedLessons");
    if (published != null && published.has(lessonId(course))) {
      boolean active = course.getString("id").equals(published.optString(lessonId(course)));
      course.put("published", active);
      if (active) course.put("reviewed", true);
    }
    JSONObject history = state.optJSONObject("publishedCourses");
    if (history != null && history.has(course.getString("id")))
      course.put("publishedAt", history.getLong(course.getString("id")));
    return course;
  }

  synchronized List<JSONObject> versions(JSONObject course) throws Exception {
    String lesson = lessonId(course);
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject candidate : courses(false))
      if (lesson.equals(lessonId(candidate))) result.add(candidate);
    result.sort(
        Comparator.comparingLong((JSONObject c) -> c.optLong("createdAt"))
            .reversed()
            .thenComparing(c -> c.optString("id")));
    return result;
  }

  synchronized boolean hasLearningRecord(String id) throws Exception {
    JSONObject progress = state.getJSONObject("progress").optJSONObject(id);
    if (progress != null && (progress.optInt("attempted") > 0 || progress.optInt("completed") > 0))
      return true;
    JSONArray history = state.getJSONArray("history");
    for (int i = 0; i < history.length(); i++)
      if (id.equals(history.getJSONObject(i).optString("course"))) return true;
    JSONObject reviews = state.getJSONObject("reviews");
    for (Iterator<String> keys = reviews.keys(); keys.hasNext(); )
      if (id.equals(reviews.getJSONObject(keys.next()).optString("course"))) return true;
    JSONObject sessions = state.optJSONObject("courseSessions");
    if (sessions != null && sessions.optJSONObject(id) != null) return true;
    JSONObject session = state.optJSONObject("session");
    return session != null && id.equals(session.optString("course"));
  }

  boolean wasPublished(JSONObject course) {
    JSONObject history = state.optJSONObject("publishedCourses");
    return course.optBoolean("published")
        || course.optLong("publishedAt") > 0
        || (history != null && history.has(course.optString("id")));
  }

  synchronized boolean isDraft(JSONObject course) throws Exception {
    return !wasPublished(course) && !hasLearningRecord(course.getString("id"));
  }

  static String draftName(JSONObject course) {
    if (course.optLong("createdAt") <= 0) return "草稿（创建时间未知）";
    return "草稿 "
        + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
            .format(new Date(course.optLong("createdAt")));
  }

  synchronized JSONObject newDraft(String sourceId) throws Exception {
    JSONObject source = course(sourceId), draft = copy(source);
    long created = System.currentTimeMillis();
    Set<Long> usedTimes = new HashSet<>();
    for (JSONObject version : versions(source)) usedTimes.add(version.optLong("createdAt"));
    while (usedTimes.contains(created)) created++;
    draft
        .put("id", "course_" + UUID.randomUUID().toString().replace("-", ""))
        .put("lessonId", lessonId(source))
        .put("createdAt", created)
        .put("published", false)
        .put("reviewed", false);
    draft.remove("publishedAt");
    saveCourse(draft);
    return draft;
  }

  synchronized JSONObject editableDraft(String id) throws Exception {
    JSONObject current = course(id);
    return isDraft(current) ? current : newDraft(id);
  }

  /** The one current publication and its history switch in a single AtomicFile state commit. */
  synchronized void setPublished(String id, boolean active) throws Exception {
    JSONObject current = course(id);
    if (active) validate(current, true);
    else if (!current.optBoolean("published")) throw new Exception("当前版本已经下架，请重新打开课文");
    JSONObject next = copy(state);
    JSONObject published = next.optJSONObject("publishedLessons"),
        history = next.optJSONObject("publishedCourses");
    if (published == null) {
      published = new JSONObject();
      next.put("publishedLessons", published);
    }
    if (history == null) {
      history = new JSONObject();
      next.put("publishedCourses", history);
    }
    long now = System.currentTimeMillis();
    for (JSONObject version : versions(current))
      if (wasPublished(version) && !history.has(version.getString("id")))
        history.put(
            version.getString("id"),
            Math.max(1, version.optLong("publishedAt", version.optLong("createdAt", now))));
    if (active && !history.has(id)) history.put(id, now);
    published.put(lessonId(current), active ? id : "");
    write(new File(root, "state.json"), next.toString());
    state = next;
  }

  synchronized void deleteDraft(String id) throws Exception {
    JSONObject current = course(id);
    if (!isDraft(current)) throw new Exception("只能删除未发布且没有学习记录的草稿");
    // Copies share original images and audio. Removing one draft must never remove those files.
    AtomicFile file = new AtomicFile(new File(root, "courses/" + safe(id) + ".json"));
    file.delete();
    if (file.getBaseFile().exists()) throw new IOException("草稿未删除，请重试");
  }

  static String safe(String value) {
    if (!value.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("无效的课程编号");
    return value;
  }

  static String hash(String value) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder();
      for (byte v : d) b.append(String.format("%02x", v & 255));
      return b.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static JSONObject copy(JSONObject value) throws JSONException {
    return new JSONObject(value.toString());
  }

  static void validate(JSONObject c, boolean publish) throws Exception {
    safe(c.getString("id"));
    if (c.has("lessonId")) safe(c.getString("lessonId"));
    if (c.optString("title").trim().isEmpty()) throw new Exception("请填写课文标题");
    JSONArray sentences = c.getJSONArray("sentences"), vocab = c.getJSONArray("vocabulary");
    if (sentences.length() == 0) throw new Exception("请至少保留一个课文句子");
    if (sentences.length() > 250 || vocab.length() > 250)
      throw new Exception("单课最多 250 句和 250 个重点素材");
    for (int i = 0; i < sentences.length(); i++) {
      JSONObject s = sentences.getJSONObject(i);
      validateAudio(s);
      if (s.optString("en").trim().isEmpty()) throw new Exception("课文英文不能为空");
      if (publish && s.optString("zh").trim().isEmpty()) throw new Exception("请补全每句话的中文翻译");
    }
    Set<String> kinds = new HashSet<>();
    for (int i = 0; i < vocab.length(); i++) {
      JSONObject v = vocab.getJSONObject(i);
      validateAudio(v);
      if (v.optString("en").trim().isEmpty()) throw new Exception("重点素材英文不能为空");
      if (!Arrays.asList("word", "phrase", "sentence").contains(v.optString("kind")))
        throw new Exception("素材类型应为 word、phrase 或 sentence");
      kinds.add(v.getString("kind"));
      if (publish && v.optString("zh").trim().isEmpty()) throw new Exception("请补全重点素材翻译");
    }
    if (publish && !kinds.containsAll(Arrays.asList("word", "phrase", "sentence")))
      throw new Exception("发布需要单词、短语和重点句子三类素材");
    JSONArray qs = c.optJSONArray("questions");
    if (publish && (qs == null || qs.length() < 2)) throw new Exception("发布需要至少 2 道阅读理解题");
    if (qs != null)
      for (int i = 0; i < qs.length(); i++) {
        JSONObject q = qs.getJSONObject(i);
        JSONArray opts = q.getJSONArray("options");
        if (q.optString("prompt").trim().isEmpty()
            || opts.length() < 2
            || opts.length() > 6
            || q.getInt("answer") < 0
            || q.getInt("answer") >= opts.length()) throw new Exception("阅读理解题或正确选项不完整");
        Set<String> distinct = new HashSet<>();
        for (int j = 0; j < opts.length(); j++) {
          String option = opts.getString(j).trim();
          if (option.isEmpty() || !distinct.add(option)) throw new Exception("选项不能为空或重复");
        }
      }
  }

  JSONObject progress(String id) throws JSONException {
    JSONObject all = state.getJSONObject("progress");
    JSONObject p = all.optJSONObject(id);
    if (p == null) {
      p = new JSONObject().put("completed", 0).put("attempted", 0).put("correct", 0);
      all.put(id, p);
    }
    return p;
  }

  static void validateAudio(JSONObject item) throws Exception {
    String ref = item.optString("audioFile");
    if (!ref.isEmpty()
        && (!Audio.validRef(ref) || !item.optString("audioText").equals(item.optString("en"))))
      throw new Exception("素材音频与英文不匹配或路径无效，请重新生成或录制");
  }

  List<Learning.Question> questions(JSONObject c, int stage) throws Exception {
    List<Learning.Question> result = new ArrayList<>();
    JSONArray sentences = c.getJSONArray("sentences"), vocab = c.getJSONArray("vocabulary");
    if (stage == 0) {
      for (int i = 0; i < sentences.length(); i++) {
        JSONObject s = sentences.getJSONObject(i);
        result.add(
            new Learning.Question(
                    "s" + i,
                    "read",
                    s.getString("en"),
                    s.getString("zh"),
                    s.getString("zh"),
                    s.getString("en"))
                .withAudioFile(Audio.itemFile(s)));
      }
      return result;
    }
    if (stage == 1 || stage == 2) {
      String kind = stage == 1 ? "word" : "phrase";
      List<JSONObject> pool = new ArrayList<>();
      for (int i = 0; i < vocab.length(); i++)
        if (vocab.getJSONObject(i).optString("kind").equals(kind)) pool.add(vocab.getJSONObject(i));
      for (int i = 0; i < pool.size(); i++) {
        JSONObject v = pool.get(i);
        Learning.Question q =
            new Learning.Question(
                    "v" + i,
                    "choice",
                    v.getString("en"),
                    v.getString("zh"),
                    v.optString("note"),
                    v.getString("en"))
                .withAudioFile(Audio.itemFile(v));
        q.options = choices(v.getString("zh"), pool);
        if (q.options.size() < 2) q.type = "flash";
        result.add(q);
      }
      return result;
    }
    if (stage == 3) {
      List<JSONObject> pool = new ArrayList<>();
      for (int i = 0; i < sentences.length(); i++) pool.add(sentences.getJSONObject(i));
      for (int i = 0; i < pool.size(); i++) {
        JSONObject s = pool.get(i);
        Learning.Question q =
            new Learning.Question(
                    "l" + i,
                    "listen",
                    "听一听，选择这句话的意思",
                    s.getString("zh"),
                    s.getString("en"),
                    s.getString("en"))
                .withAudioFile(Audio.itemFile(s));
        q.options = choices(s.getString("zh"), pool);
        if (q.options.size() < 2) q.type = "dictation";
        if (q.type.equals("dictation")) q.answer = s.getString("en");
        result.add(q);
      }
      return result;
    }
    if (stage == 4) {
      for (int i = 0; i < vocab.length(); i++) {
        JSONObject v = vocab.getJSONObject(i);
        if (v.optString("kind").equals("word"))
          result.add(
              new Learning.Question(
                      "d" + i,
                      "dictation",
                      "听音拼写这个单词",
                      v.getString("en"),
                      v.getString("zh"),
                      v.getString("en"))
                  .withAudioFile(Audio.itemFile(v)));
      }
      for (int i = 0; i < sentences.length(); i++) {
        JSONObject s = sentences.getJSONObject(i);
        if (Learning.tokens(s.getString("en")).size() > 1)
          result.add(
              new Learning.Question(
                      "o" + i,
                      "order",
                      s.getString("zh"),
                      s.getString("en"),
                      s.getString("zh"),
                      s.getString("en"))
                  .withAudioFile(Audio.itemFile(s)));
      }
      return result;
    }
    JSONArray qs = c.getJSONArray("questions");
    for (int i = 0; i < qs.length(); i++) {
      JSONObject x = qs.getJSONObject(i);
      JSONArray options = x.getJSONArray("options");
      Learning.Question q =
          new Learning.Question(
              "c" + i,
              "choice",
              x.getString("prompt"),
              options.getString(x.getInt("answer")),
              x.optString("explanation"),
              "");
      for (int j = 0; j < options.length(); j++) q.options.add(options.getString(j));
      Collections.shuffle(q.options, new Random((c.getString("id") + i).hashCode()));
      result.add(q);
    }
    return result;
  }

  static List<String> choices(String answer, List<JSONObject> pool) {
    List<String> wrong = new ArrayList<>();
    for (JSONObject x : pool) {
      String s = x.optString("zh");
      if (!s.equals(answer) && !wrong.contains(s)) wrong.add(s);
    }
    Collections.shuffle(wrong, new Random(answer.hashCode()));
    List<String> options = new ArrayList<>();
    options.add(answer);
    options.addAll(wrong.subList(0, Math.min(3, wrong.size())));
    Collections.shuffle(options, new Random(answer.hashCode() + 7));
    return options;
  }

  synchronized void recordReading(String courseId, int sentences) throws Exception {
    JSONObject p = progress(courseId);
    p.put("attempted", p.optInt("attempted") + sentences);
    p.put("correct", p.optInt("correct") + sentences);
    save();
  }

  synchronized void record(String courseId, Learning.Question q, boolean correct, boolean first)
      throws Exception {
    JSONObject p = progress(courseId);
    p.put("attempted", p.optInt("attempted") + 1);
    if (correct) p.put("correct", p.optInt("correct") + 1);
    if (!q.type.equals("read") && !q.type.equals("flash")) {
      String key = courseId + "_" + q.id;
      JSONObject reviews = state.getJSONObject("reviews"), r = reviews.optJSONObject(key);
      long now = System.currentTimeMillis();
      if (r == null)
        r =
            new JSONObject()
                .put("course", courseId)
                .put("id", q.id)
                .put("answer", q.answer)
                .put("prompt", q.prompt)
                .put("audio", q.audio)
                .put("audioFile", q.audioFile)
                .put("type", q.type)
                .put("explanation", q.explanation)
                .put("options", new JSONArray(q.options))
                .put("level", 0)
                .put("mistakes", 0);
      if (correct) {
        if (r.optLong("due") <= now) {
          int level = r.optInt("level");
          r.put("due", Learning.nextReview(now, level)).put("level", level + 1);
        }
      } else r.put("due", now).put("level", 0).put("mistakes", r.optInt("mistakes") + 1);
      reviews.put(key, r);
    }
    save();
  }

  synchronized boolean complete(String id, int stage, int attempts, int correct, long seconds)
      throws Exception {
    JSONObject p = progress(id);
    boolean fresh = stage == p.optInt("completed");
    if (fresh) {
      p.put("completed", stage + 1);
      state.put("xp", state.optInt("xp") + 20);
    }
    state
        .getJSONArray("history")
        .put(
            new JSONObject()
                .put("course", id)
                .put("stage", stage)
                .put("attempted", attempts)
                .put("correct", correct)
                .put("seconds", seconds)
                .put("time", System.currentTimeMillis())
                .put("xp", fresh ? 20 : 0));
    JSONObject pending = courseSession(id);
    if (pending != null && pending.optInt("stage", -1) == stage) removeCourseSession(id);
    save();
    return fresh;
  }

  List<JSONObject> due(boolean mistakesOnly) throws Exception {
    List<JSONObject> list = new ArrayList<>();
    JSONObject rs = state.getJSONObject("reviews");
    Set<String> visible = new HashSet<>();
    for (JSONObject c : courses(true)) visible.add(c.getString("id"));
    Iterator<String> keys = rs.keys();
    while (keys.hasNext()) {
      JSONObject r = rs.getJSONObject(keys.next());
      if (visible.contains(r.optString("course"))
          && r.optLong("due") <= System.currentTimeMillis()
          && (!mistakesOnly || r.optInt("mistakes") > 0)) list.add(r);
    }
    list.sort(Comparator.comparingLong(a -> a.optLong("due")));
    return list;
  }

  synchronized void exportZip(OutputStream stream) throws Exception {
    try (ZipOutputStream zip = new ZipOutputStream(stream)) {
      Set<String> exportedImages = new HashSet<>();
      add(zip, "state.json", state.toString().getBytes(StandardCharsets.UTF_8));
      for (JSONObject c : courses(false)) {
        add(
            zip,
            "courses/" + c.getString("id") + ".json",
            c.toString().getBytes(StandardCharsets.UTF_8));
        JSONArray sources = c.optJSONArray("sources");
        if (sources != null)
          for (int i = 0; i < sources.length(); i++) {
            String src = sources.getString(i);
            if (src.startsWith("images/") && exportedImages.add(src))
              add(zip, src, java.nio.file.Files.readAllBytes(new File(root, src).toPath()));
          }
      }
      JSONArray pending = state.optJSONArray("pendingImages");
      if (pending != null)
        for (int i = 0; i < pending.length(); i++) {
          String src = pending.getString(i);
          if (src.matches("images/[A-Za-z0-9_.-]+\\.(jpg|png)") && exportedImages.add(src))
            add(zip, src, java.nio.file.Files.readAllBytes(new File(root, src).toPath()));
        }
      File[] audio = new File(root, "audio").listFiles();
      if (audio != null)
        for (File f : audio)
          if (f.getName().matches("[a-f0-9]{64}\\.(mp3|wav|m4a)"))
            add(zip, "audio/" + f.getName(), java.nio.file.Files.readAllBytes(f.toPath()));
    }
  }

  static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  private void mergePublications(JSONObject incoming, List<JSONObject> imported) throws Exception {
    for (String field : new String[] {"publishedLessons", "publishedCourses"})
      if (incoming.has(field) && !(incoming.get(field) instanceof JSONObject))
        throw new Exception("备份中的课文版本信息无效");
    JSONObject active = incoming.optJSONObject("publishedLessons"),
        history = incoming.optJSONObject("publishedCourses");
    if (active == null) active = new JSONObject();
    if (history == null) history = new JSONObject();
    Map<String, JSONObject> available = new HashMap<>();
    for (JSONObject c : courses(false)) available.put(c.getString("id"), c);
    Set<String> families = new HashSet<>();
    Map<String, String> legacyActive = new HashMap<>();
    for (JSONObject c : imported) {
      available.put(c.getString("id"), c);
      String family = lessonId(c);
      families.add(family);
      if (c.optBoolean("published")) {
        if (legacyActive.put(family, c.getString("id")) != null && !active.has(family))
          throw new Exception("同一课文包含多个已发布版本");
      }
    }
    JSONObject existingActive = state.optJSONObject("publishedLessons");
    if (existingActive != null)
      for (Iterator<String> keys = existingActive.keys(); keys.hasNext(); ) {
        String family = keys.next();
        if (!families.contains(family) && !active.has(family))
          active.put(family, existingActive.getString(family));
      }
    for (String family : families)
      if (!active.has(family)) active.put(family, legacyActive.getOrDefault(family, ""));
    for (Iterator<String> keys = active.keys(); keys.hasNext(); ) {
      String family = safe(keys.next());
      if (!(active.get(family) instanceof String)) throw new Exception("备份中的发布版本无效");
      String id = active.getString(family);
      if (!id.isEmpty()) {
        safe(id);
        JSONObject selected = available.get(id);
        if (selected == null || !family.equals(lessonId(selected)))
          throw new Exception("发布版本不属于这篇课文");
      }
    }
    JSONObject existingHistory = state.optJSONObject("publishedCourses");
    if (existingHistory != null)
      for (Iterator<String> keys = existingHistory.keys(); keys.hasNext(); ) {
        String id = keys.next();
        if (!history.has(id)) history.put(id, existingHistory.getLong(id));
      }
    for (Iterator<String> keys = history.keys(); keys.hasNext(); ) {
      String id = safe(keys.next());
      Object time = history.get(id);
      if (!(time instanceof Number) || ((Number) time).longValue() <= 0)
        throw new Exception("备份中的发布时间无效");
    }
    incoming.put("publishedLessons", active).put("publishedCourses", history);
  }

  synchronized void importZip(InputStream stream) throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    long total = 0;
    JSONObject incoming = null;
    List<JSONObject> cs = new ArrayList<>();
    try (ZipInputStream zip = new ZipInputStream(stream)) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        String n = e.getName();
        if (e.isDirectory()) continue;
        if (!n.matches(
            "state\\.json|courses/[A-Za-z0-9_-]+\\.json|audio/[a-f0-9]{64}\\.(mp3|wav|m4a)|images/[A-Za-z0-9_.-]+\\.(jpg|png)"))
          throw new Exception("备份含无效文件路径");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int size;
        while ((size = zip.read(block)) != -1) {
          total += size;
          if (total > 64L * 1024 * 1024) throw new Exception("备份大小超过 64MB");
          b.write(block, 0, size);
        }
        if (files.size() > 4000) throw new Exception("备份文件过多");
        if (files.containsKey(n)) throw new Exception("备份含重复文件");
        files.put(n, b.toByteArray());
      }
    }
    if (!files.containsKey("state.json")) throw new Exception("不是课文岛备份");
    incoming = new JSONObject(new String(files.get("state.json"), StandardCharsets.UTF_8));
    if (incoming.optInt("version") != 1) throw new Exception("不支持的备份版本");
    incoming.getJSONObject("progress");
    incoming.getJSONObject("reviews");
    incoming.getJSONArray("history");
    if (incoming.optInt("xp") < 0 || incoming.optInt("goal") < 1 || incoming.optInt("goal") > 60)
      throw new Exception("备份中的学习设置无效");
    JSONObject progress = incoming.getJSONObject("progress");
    Iterator<String> ids = progress.keys();
    while (ids.hasNext()) {
      JSONObject p = progress.getJSONObject(safe(ids.next()));
      if (p.optInt("completed") < 0
          || p.optInt("completed") > 6
          || p.optInt("correct") < 0
          || p.optInt("attempted") < p.optInt("correct")) throw new Exception("备份中的学习记录无效");
    }
    StudyTimeLedger.initialize(incoming, System.currentTimeMillis(), java.time.ZoneId.systemDefault());
    incoming.remove("session");
    incoming.put("courseSessions", new JSONObject());
    incoming.put("reviewSessions", new JSONObject());
    incoming.remove("lastLearningCourse");
    for (Map.Entry<String, byte[]> e : files.entrySet())
      if (e.getKey().startsWith("courses/")) {
        JSONObject c = new JSONObject(new String(e.getValue(), StandardCharsets.UTF_8));
        validate(c, c.optBoolean("published"));
        if (!e.getKey().equals("courses/" + c.getString("id") + ".json"))
          throw new Exception("课程编号不匹配");
        cs.add(c);
      }
    mergePublications(incoming, cs);
    for (JSONObject c : cs)
      for (String array : new String[] {"sentences", "vocabulary"}) {
        JSONArray items = c.getJSONArray(array);
        for (int i = 0; i < items.length(); i++) {
          String ref = Audio.itemFile(items.getJSONObject(i));
          if (!ref.isEmpty() && !files.containsKey(ref) && !new File(root, ref).isFile())
            throw new Exception("备份缺少素材录音，未导入任何数据");
        }
      }
    JSONObject incomingReviews = incoming.getJSONObject("reviews");
    Iterator<String> reviewKeys = incomingReviews.keys();
    while (reviewKeys.hasNext()) {
      String ref = incomingReviews.getJSONObject(reviewKeys.next()).optString("audioFile");
      if (!ref.isEmpty()
          && (!Audio.validRef(ref) || (!files.containsKey(ref) && !new File(root, ref).isFile())))
        throw new Exception("备份中的复习录音无效或缺失");
    }
    files.put("state.json", incoming.toString().getBytes(StandardCharsets.UTF_8));
    File backup = new File(root, "restore-backup");
    JSONArray journal = new JSONArray();
    // Snapshot every destination before writing the journal. An interrupted restore rolls back at
    // startup.
    for (String name : files.keySet()) {
      File target = new File(root, name);
      journal.put(new JSONObject().put("path", name).put("existed", target.exists()));
      if (target.exists()) {
        File old = new File(backup, name);
        old.getParentFile().mkdirs();
        java.nio.file.Files.copy(
            target.toPath(), old.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
    write(new File(root, "restore-journal.json"), journal.toString());
    try {
      for (Map.Entry<String, byte[]> entry : files.entrySet()) {
        AtomicFile atomic = new AtomicFile(new File(root, entry.getKey()));
        FileOutputStream output = atomic.startWrite();
        try {
          output.write(entry.getValue());
          atomic.finishWrite(output);
        } catch (Exception ex) {
          atomic.failWrite(output);
          throw ex;
        }
      }
      if (!new File(root, "restore-journal.json").delete()) throw new IOException("恢复提交未完成");
      state = incoming;
    } catch (Exception failure) {
      recoverRestore();
      throw failure;
    }
    deleteOwnedTree(backup);
    archiveLegacySamples();
  }

  void recoverRestore() throws Exception {
    File journalFile = new File(root, "restore-journal.json");
    if (!journalFile.exists()) return;
    JSONArray journal = new JSONArray(read(new AtomicFile(journalFile).openRead()));
    for (int i = 0; i < journal.length(); i++) {
      JSONObject entry = journal.getJSONObject(i);
      String name = entry.getString("path");
      if (!name.matches(
          "state\\.json|courses/[A-Za-z0-9_-]+\\.json|audio/[a-f0-9]{64}\\.(mp3|wav|m4a)|images/[A-Za-z0-9_.-]+\\.(jpg|png)"))
        throw new IOException("恢复日志路径无效");
      File target = new File(root, name);
      new AtomicFile(target).delete();
      if (entry.getBoolean("existed")) {
        File old = new File(root, "restore-backup/" + name);
        java.nio.file.Files.copy(
            old.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
    if (!journalFile.delete()) throw new IOException("恢复日志无法清理");
    deleteOwnedTree(new File(root, "restore-backup"));
  }

  private void deleteOwnedTree(File file) throws IOException {
    if (!file.getCanonicalPath().startsWith(new File(root, "restore-backup").getCanonicalPath()))
      throw new IOException("无效恢复目录");
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteOwnedTree(child);
    if (file.exists() && !file.delete()) throw new IOException("恢复临时文件无法清理");
  }
}
