package top.mossmoss.lessonisland;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

public final class MainActivity extends Activity {
  static final int INK = AdventureArt.INK,
      MUTED = Color.rgb(94, 115, 131),
      GREEN = AdventureArt.TEAL,
      BG = Color.rgb(247, 252, 255),
      LINE = AdventureArt.LINE,
      LIME = Color.rgb(235, 249, 215);
  Store store;
  Audio audio;
  Secrets secrets;
  AiClient ai;
  final ExecutorService jobs = Executors.newSingleThreadExecutor();
  boolean parent = false, busy = false, externalPending = false, relock = false;
  LinearLayout shell, body, header, bottom, actionFooter;
  ScrollView scroll;
  String page = "home", selectedId = "", status = "";
  int materialTab = 0;
  TextView statusView;
  final List<LinearLayout> materialCards = new ArrayList<>();
  final List<TextView> materialStates = new ArrayList<>();
  LinearLayout materialPlaybackBar;
  TextView materialPlaybackStatus;
  int materialPlaying = -1;
  boolean followMaterialPlayback = true;
  Toast lastToast;
  EditText apiKeyInput;
  ImageButton apiKeyToggle;
  boolean apiKeyVisible;
  List<Learning.Question> activeQuestions;
  JSONArray queue;
  int position, sessionStage, sessionAttempts, sessionCorrect;
  String sessionCourse = "";
  long started;
  long activeMillis, activeTick;
  boolean reviewing = false, answered = false;
  String selectedAnswer = "";
  EditText answerInput;
  List<Integer> ordered = new ArrayList<>();
  JSONObject quizSession;

  interface Task {
    void run() throws Exception;
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    try {
      store = new Store(this);
      secrets = new Secrets(this);
      ai = new AiClient(this);
      audio = new Audio(this, store);
      home();
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setTitle("本机资料暂时无法读取")
          .setMessage("为保留原有资料，应用没有覆盖数据。请保留应用数据并联系维护者。\n" + e.getClass().getSimpleName())
          .setPositiveButton("关闭", (d, w) -> finish())
          .show();
    }
  }

  int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + 0.5f);
  }

  void safe(Task task) {
    try {
      task.run();
    } catch (Exception e) {
      error(e);
    }
  }

  void error(Exception e) {
    toast(e.getMessage() == null ? "操作未完成，请重试" : e.getMessage());
  }

  void toast(String text) {
    runOnUiThread(
        () -> {
          if (lastToast != null) lastToast.cancel();
          lastToast = Toast.makeText(this, text, Toast.LENGTH_LONG);
          lastToast.show();
        });
  }

  TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setLineSpacing(dp(3), 1f);
    t.setTypeface(Typeface.create("sans-serif-rounded", bold ? Typeface.BOLD : Typeface.NORMAL));
    return t;
  }

  LinearLayout col() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    l.setOrientation(LinearLayout.HORIZONTAL);
    return l;
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  GradientDrawable outline(int color, int stroke) {
    GradientDrawable d = shape(color, 22);
    d.setStroke(dp(1.5f), stroke);
    return d;
  }

  void add(LinearLayout p, View v) {
    p.addView(v, new LinearLayout.LayoutParams(-1, -2));
  }

  void space(LinearLayout p, int height) {
    View v = new View(this);
    p.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }

  TextView label(String value) {
    TextView t = text(value, 12, MUTED, true);
    t.setLetterSpacing(.04f);
    return t;
  }

  void heading(String title, String detail) {
    add(body, text(title, 28, INK, true));
    space(body, 6);
    if (!detail.isEmpty()) add(body, text(detail, 14, MUTED, false));
    space(body, 22);
  }

  TextView pill(String value, int color, int background) {
    TextView t = text(value, 12, color, true);
    t.setPadding(dp(11), dp(6), dp(11), dp(6));
    t.setGravity(Gravity.CENTER);
    t.setBackground(shape(background, 30));
    return t;
  }

  Button button(String label, boolean primary, Task action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(16);
    b.setTypeface(Typeface.create("sans-serif-rounded", Typeface.BOLD));
    b.setTextColor(primary ? INK : GREEN);
    b.setMinHeight(dp(56));
    b.setMinimumHeight(dp(56));
    b.setPadding(dp(14), dp(10), dp(14), dp(15));
    b.setBackground(
        new AdventureArt.Face(
            this,
            primary ? AdventureArt.LEAF : Color.WHITE,
            primary ? AdventureArt.LEAF_EDGE : LINE,
            18,
            5));
    b.setStateListAnimator(null);
    b.setOnClickListener(v -> safe(action));
    return b;
  }

  void action(LinearLayout p, String label, boolean primary, Task task) {
    if (page.equals("quiz") && primary) {
      pinAction(label, task);
      return;
    }
    space(p, 10);
    add(p, button(label, primary, task));
  }

  void pinAction(String label, Task task) {
    if (actionFooter == null) {
      actionFooter = col();
      actionFooter.setPadding(dp(20), dp(12), dp(20), dp(12));
      actionFooter.setBackgroundColor(Color.WHITE);
      shell.addView(
          actionFooter, shell.indexOfChild(scroll) + 1, new LinearLayout.LayoutParams(-1, -2));
    }
    actionFooter.removeAllViews();
    add(actionFooter, button(label, true, task));
  }

  LinearLayout card(LinearLayout target) {
    LinearLayout c = col();
    c.setPadding(dp(20), dp(20), dp(20), dp(20));
    c.setBackground(new AdventureArt.Face(this, Color.WHITE, LINE, 24, 3));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(14);
    target.addView(c, lp);
    return c;
  }

  void weight(LinearLayout row, View view) {
    row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
  }

  void companion(LinearLayout target, String message, String mood) {
    LinearLayout r = row();
    r.addView(
        new AdventureArt.Companion(this, mood, false),
        new LinearLayout.LayoutParams(dp(88), dp(90)));
    TextView bubble = text(message, 15, INK, true);
    bubble.setPadding(dp(15), dp(13), dp(15), dp(13));
    bubble.setBackground(outline(Color.WHITE, LINE));
    weight(r, bubble);
    add(target, r);
    space(target, 12);
  }

  void iconTitle(LinearLayout target, String title, String icon, int background) {
    LinearLayout r = row();
    AdventureArt.Icon art = new AdventureArt.Icon(this, icon, GREEN);
    art.setPadding(dp(7), dp(7), dp(7), dp(7));
    art.setBackground(shape(background, 14));
    r.addView(art, new LinearLayout.LayoutParams(dp(42), dp(42)));
    TextView t = text(title, 21, INK, true);
    t.setPadding(dp(12), 0, 0, 0);
    weight(r, t);
    add(target, r);
  }

  void screen(String name, String title, boolean nav) {
    if (page.equals("materials") && audio != null) audio.stop();
    materialCards.clear();
    materialStates.clear();
    materialPlaying = -1;
    materialPlaybackBar = null;
    materialPlaybackStatus = null;
    followMaterialPlayback = true;
    if (lastToast != null) lastToast.cancel();
    setApiKeyVisible(false);
    if (apiKeyInput != null) apiKeyInput.setText("");
    apiKeyInput = null;
    apiKeyToggle = null;
    if (page.equals("quiz") && !name.equals("quiz")) pauseClock();
    page = name;
    if (name.equals("quiz") && activeTick == 0) activeTick = SystemClock.elapsedRealtime();
    statusView = null;
    actionFooter = null;
    answered = false;
    shell = col();
    shell.setBackgroundColor(BG);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    shell.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    header = row();
    header.setPadding(dp(20), dp(10), dp(20), dp(10));
    AdventureArt.Icon brand = new AdventureArt.Icon(this, "book", GREEN);
    brand.setBackground(shape(LIME, 14));
    brand.setPadding(dp(5), dp(5), dp(5), dp(5));
    header.addView(brand, new LinearLayout.LayoutParams(dp(42), dp(42)));
    LinearLayout logo = col();
    logo.setPadding(dp(10), 0, dp(6), 0);
    add(logo, text("课文岛", 21, INK, true));
    TextView wordmark = text(parent ? "家长工作室" : "LESSON ISLAND", 9, MUTED, true);
    wordmark.setLetterSpacing(.08f);
    add(logo, wordmark);
    weight(header, logo);
    TextView toggle = pill(parent ? "返回学生" : "家长入口", GREEN, parent ? LIME : Color.WHITE);
    toggle.setMinHeight(dp(44));
    toggle.setBackground(outline(parent ? LIME : Color.WHITE, LINE));
    toggle.setContentDescription("切换模式");
    toggle.setOnClickListener(
        v -> {
          if (busy) {
            toast("正在制作素材，完成后可切换模式");
            return;
          }
          audio.stop();
          if (parent) {
            parent = false;
            home();
          } else gate();
        });
    header.addView(toggle);
    add(shell, header);
    scroll = new ContentScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    LinearLayout outer = col();
    outer.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    body = new ContentLayout(this);
    body.setPadding(dp(20), dp(12), dp(20), dp(28));
    outer.addView(body, new LinearLayout.LayoutParams(-1, -2));
    scroll.addView(outer);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (!title.isEmpty()) {
      TextView back = text("‹  " + title, 14, GREEN, true);
      back.setMinHeight(dp(42));
      back.setGravity(Gravity.CENTER_VERTICAL);
      back.setOnClickListener(v -> goBack());
      add(body, back);
      space(body, 10);
    }
    if (nav) {
      bottom = row();
      bottom.setBackgroundColor(Color.WHITE);
      bottom.setPadding(dp(10), dp(7), dp(10), dp(7));
      String[] names =
          parent ? new String[] {"学习概览", "课程工作台", "家长设置"} : new String[] {"学习岛", "复习站", "我的成长"};
      for (int i = 0; i < 3; i++) {
        int index = i;
        boolean current =
            parent
                ? (i == 0 && name.equals("dashboard")
                    || i == 1 && name.equals("courses")
                    || i == 2 && name.equals("settings"))
                : (i == 0 && name.equals("home")
                    || i == 1 && name.equals("reviews")
                    || i == 2 && name.equals("profile"));
        LinearLayout tab = col();
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(3), dp(3), dp(3), dp(3));
        tab.setMinimumHeight(dp(62));
        String[] icons =
            parent
                ? new String[] {"dashboard", "courses", "settings"}
                : new String[] {"home", "review", "profile"};
        tab.addView(
            new AdventureArt.Icon(this, icons[i], current ? GREEN : MUTED),
            new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView tabLabel = text(names[i], 12, current ? GREEN : MUTED, true);
        tabLabel.setGravity(Gravity.CENTER);
        add(tab, tabLabel);
        tab.setContentDescription(names[i]);
        tab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tab.setFocusable(true);
        tab.setSelected(current);
        if (current) tab.setBackground(shape(LIME, 18));
        tab.setOnClickListener(
            v ->
                safe(
                    () -> {
                      audio.stop();
                      if (parent) {
                        if (index == 0) dashboard();
                        else if (index == 1) courses();
                        else settings();
                      } else {
                        if (index == 0) home();
                        else if (index == 1) reviews();
                        else profile();
                      }
                    }));
        weight(bottom, tab);
      }
      add(shell, bottom);
    }
    setContentView(shell);
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController controller = getWindow().getInsetsController();
      if (controller != null)
        controller.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    } else if (Build.VERSION.SDK_INT >= 27) {
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
  }

  void home() {
    safe(
        () -> {
          screen("home", "", true);
          List<JSONObject> list = store.courses(true);
          if (list.isEmpty()) {
            emptyHome();
            return;
          }
          add(body, label("今日冒险  /  LET’S EXPLORE"));
          space(body, 9);
          heading("你好，" + store.state.optString("name", "探索家") + "！", "芽芽已经准备好，和你一起出发啦。");
          LinearLayout hero = card(body);
          hero.setBackground(
              new AdventureArt.Face(this, AdventureArt.SKY, Color.rgb(192, 225, 240), 26, 4));
          LinearLayout r = row(), left = col();
          weight(r, left);
          add(left, text("芽芽的探险邀请", 12, GREEN, true));
          space(left, 8);
          add(left, text("一起去\n课文里探险！", 23, INK, true));
          space(left, 8);
          add(left, text("一小关，一点新发现。", 12, MUTED, false));
          r.addView(
              new AdventureArt.Companion(this, "wave", true),
              new LinearLayout.LayoutParams(dp(112), dp(132)));
          add(hero, r);
          space(hero, 14);
          LinearLayout meta = row();
          weight(meta, pill("✦  " + store.state.optInt("xp") + " XP", INK, Color.WHITE));
          View gap = new View(this);
          meta.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
          weight(meta, pill("↻  " + store.due(false).size() + " 项待复习", GREEN, Color.WHITE));
          add(hero, meta);
          JSONObject pending = store.state.optJSONObject("session");
          if (pending != null) {
            action(body, "继续上次学习  →", true, () -> resumeSession());
            space(body, 18);
          }
          LinearLayout title = row();
          weight(title, text("选择你的学习岛", 21, INK, true));
          title.addView(pill(store.courses(true).size() + " 个学习岛", MUTED, Color.WHITE));
          add(body, title);
          space(body, 14);
          int n = 0;
          for (JSONObject c : list) {
            final String id = c.getString("id");
            int done = store.progress(id).optInt("completed");
            LinearLayout box = card(body);
            box.setOnClickListener(v -> safe(() -> path(id)));
            box.setFocusable(true);
            box.setContentDescription(c.getString("title") + "，已点亮 " + done + " / 6 关，打开学习路线");
            box.setForeground(
                new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(Color.argb(30, 12, 119, 99)),
                    null,
                    shape(Color.WHITE, 24)));
            LinearLayout top = row();
            int islandNumber = ++n;
            AdventureArt.Icon island =
                new AdventureArt.Icon(this, done == 6 ? "trophy" : "book", GREEN);
            island.setBackground(shape(islandNumber % 2 == 0 ? AdventureArt.LILAC : LIME, 16));
            island.setPadding(dp(7), dp(7), dp(7), dp(7));
            top.addView(island, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView islandLabel = label("小岛 " + String.format(Locale.ROOT, "%02d", islandNumber));
            islandLabel.setPadding(dp(10), 0, 0, 0);
            weight(top, islandLabel);
            top.addView(
                pill(
                    done == 6 ? "已通关" : done > 0 ? "探索中" : "准备出发",
                    GREEN,
                    done == 6 ? LIME : Color.rgb(235, 244, 238)));
            add(box, top);
            space(box, 14);
            add(box, text(c.getString("title"), 23, INK, true));
            space(box, 5);
            add(box, text(c.optString("subtitle"), 14, MUTED, false));
            space(box, 14);
            add(
                box,
                text(
                    c.getJSONArray("sentences").length()
                        + " 个句子  ·  "
                        + c.getJSONArray("vocabulary").length()
                        + " 个重点  ·  6 关",
                    12,
                    MUTED,
                    false));
            space(box, 13);
            add(box, text("已点亮 " + done + " / 6 关", 12, GREEN, true));
            space(box, 7);
            progressBar(box, done, 6);
            action(
                box,
                done == 0
                    ? "开启这座学习岛  →"
                    : done == 6 ? "再探索一次  →" : "继续探索  ·  第 " + (done + 1) + " 关  →",
                true,
                () -> path(id));
          }
        });
  }

  void emptyHome() throws Exception {
    boolean hasDrafts = !store.courses(false).isEmpty();
    add(body, label("欢迎登岛  /  WELCOME ABOARD"));
    space(body, 9);
    heading(
        hasDrafts ? "课程正在准备中" : "从第一篇课文开始", hasDrafts ? "家长发布课程后，孩子就可以开始闯关。" : "请家长先创建课程，再交给孩子学习。");
    LinearLayout welcome = card(body);
    welcome.setBackground(
        new AdventureArt.Face(this, AdventureArt.SKY, Color.rgb(192, 225, 240), 26, 4));
    welcome.addView(
        new AdventureArt.Companion(this, "wave", true), new LinearLayout.LayoutParams(-1, dp(135)));
    TextView greeting = text("芽芽在岛上等你！", 16, GREEN, true);
    greeting.setGravity(Gravity.CENTER);
    add(welcome, greeting);
    space(welcome, 18);
    add(welcome, text(hasDrafts ? "发布课程，就能出发" : "课文图片 → 你的学习岛", 19, INK, true));
    pinAction(
        hasDrafts ? "家长管理课程" : "家长创建课程",
        () -> gate(hasDrafts ? this::courses : this::importScreen));
    space(welcome, 18);
    String[] steps = {"选择课文图片", "生成并校对课文、翻译和音频", "发布课程，开启六关冒险"};
    for (int i = 0; i < steps.length; i++) {
      LinearLayout step = row();
      TextView number = pill(String.valueOf(i + 1), GREEN, Color.WHITE);
      step.addView(number, new LinearLayout.LayoutParams(dp(34), dp(34)));
      TextView copy = text(steps[i], 14, INK, false);
      copy.setPadding(dp(10), 0, 0, 0);
      weight(step, copy);
      add(welcome, step);
      space(welcome, 9);
    }
    space(welcome, 10);
    add(welcome, text("进入家长模式需要验证 PIN，首次使用时由家长设置。", 12, MUTED, false));
    if (!secrets.hasPin()) {
      space(body, 10);
      add(body, text("右上角「家长入口」也可以随时进入课程工作台。", 13, MUTED, false));
    }
  }

  void empty(String title, String subtitle) {
    LinearLayout c = card(body);
    c.addView(
        new AdventureArt.Companion(this, "think", false),
        new LinearLayout.LayoutParams(-1, dp(110)));
    space(c, 10);
    add(c, text(title, 20, INK, true));
    space(c, 8);
    add(c, text(subtitle, 14, MUTED, false));
  }

  void progressBar(LinearLayout p, int value, int max) {
    p.addView(new AdventureArt.Meter(this, value, max), new LinearLayout.LayoutParams(-1, dp(14)));
  }

  void path(String id) throws Exception {
    selectedId = id;
    JSONObject c = store.course(id);
    if (!c.optBoolean("published")) throw new Exception("这门课还没有发布");
    screen("path", "我的课文", false);
    int done = store.progress(id).optInt("completed");
    LinearLayout journeyHead = row(), courseCopy = col();
    add(courseCopy, label(done == 6 ? "小岛全部点亮！" : "芽芽陪你闯六关"));
    space(courseCopy, 6);
    add(courseCopy, text(c.getString("title"), 24, INK, true));
    add(courseCopy, text(c.optString("subtitle"), 13, MUTED, false));
    weight(journeyHead, courseCopy);
    journeyHead.addView(
        new AdventureArt.Companion(this, done == 6 ? "celebrate" : "wave", false),
        new LinearLayout.LayoutParams(dp(82), dp(92)));
    add(body, journeyHead);
    space(body, 14);
    LinearLayout intro = card(body);
    intro.setPadding(dp(14), dp(12), dp(14), dp(15));
    intro.setBackground(new AdventureArt.Face(this, LIME, Color.rgb(205, 227, 178), 24, 3));
    LinearLayout status = row();
    weight(status, text("已点亮 " + done + " / 6 关", 14, GREEN, true));
    Button materialsButton = button("课文与重点素材  ›", false, () -> materials(id, false));
    materialsButton.setTextSize(12);
    materialsButton.setMinHeight(dp(48));
    materialsButton.setMinimumHeight(dp(48));
    materialsButton.setPadding(dp(10), dp(7), dp(10), dp(11));
    status.addView(materialsButton);
    add(intro, status);
    space(intro, 10);
    progressBar(intro, done, 6);
    AdventureArt.Trail trail = new AdventureArt.Trail(this, done);
    String[] icons = {"book", "leaf", "puzzle", "listen", "write", "trophy"};
    String[] hints = {"听一句，读一句", "和新单词交朋友", "把表达装进口袋", "听声音，找答案", "动手拼出句子", "读懂故事的秘密"};
    for (int i = 0; i < 6; i++) {
      int stage = i;
      boolean locked = i > done;
      LinearLayout box = col();
      box.setGravity(Gravity.CENTER_HORIZONTAL);
      AdventureArt.StageNode node =
          new AdventureArt.StageNode(this, icons[i], locked ? 0 : i < done ? 2 : 1);
      if (!locked) {
        node.setOnClickListener(v -> safe(() -> begin(id, stage)));
        node.setFocusable(true);
        node.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        node.setContentDescription(
            "第 " + (i + 1) + " 关，" + Learning.STAGES[i] + (i < done ? "，再练一次" : "，开始学习"));
      }
      box.addView(node, new LinearLayout.LayoutParams(dp(88), dp(88)));
      TextView stageTitle =
          text((i + 1) + ". " + Learning.STAGES[i], 17, locked ? MUTED : INK, true);
      stageTitle.setGravity(Gravity.CENTER);
      stageTitle.setBackground(shape(BG, 10));
      add(box, stageTitle);
      TextView hint = text(locked ? "完成上一关解锁" : hints[i], 12, MUTED, false);
      hint.setGravity(Gravity.CENTER);
      hint.setBackground(shape(BG, 8));
      add(box, hint);
      if (locked) {
        box.setContentDescription("第 " + (i + 1) + " 关，" + Learning.STAGES[i] + "，尚未解锁");
      } else
        action(box, i < done ? "再练一次" : "开始第 " + (i + 1) + " 关", i == done, () -> begin(id, stage));
      trail.addView(box);
    }
    add(body, trail);
    companion(body, "一步一步来，终点的宝藏是读懂整篇课文！", "think");
  }

  void begin(String id, int stage) throws Exception {
    JSONObject c = store.course(id);
    if (!c.optBoolean("published") || stage > store.progress(id).optInt("completed"))
      throw new Exception("请先完成上一关");
    if (store.state.has("session")) {
      new AlertDialog.Builder(this)
          .setTitle("继续还是重新开始？")
          .setMessage("已有保存的学习进度。新开一关会替换未完成的本次练习；已完成关卡和错题会保留。")
          .setPositiveButton("开始本关", (d, w) -> safe(() -> newSession(id, stage)))
          .setNegativeButton("继续上次", (d, w) -> safe(this::resumeSession))
          .show();
    } else newSession(id, stage);
  }

  void newSession(String id, int stage) throws Exception {
    audio.stop();
    reviewing = false;
    sessionCourse = id;
    sessionStage = stage;
    activeQuestions = store.questions(store.course(id), stage);
    if (activeQuestions.isEmpty()) throw new Exception("本关素材不足，请让家长补全素材");
    queue = new JSONArray();
    for (int i = 0; i < activeQuestions.size(); i++) queue.put(i);
    position = 0;
    sessionAttempts = 0;
    sessionCorrect = 0;
    started = System.currentTimeMillis();
    activeMillis = 0;
    activeTick = SystemClock.elapsedRealtime();
    saveSession();
    showQuestion();
  }

  void saveSession() throws Exception {
    accrueClock();
    quizSession =
        new JSONObject()
            .put("course", sessionCourse)
            .put("stage", sessionStage)
            .put("queue", queue)
            .put("position", position)
            .put("attempts", sessionAttempts)
            .put("correct", sessionCorrect)
            .put("started", started)
            .put("activeMillis", activeMillis)
            .put("review", reviewing);
    if (reviewing) {
      JSONArray qs = new JSONArray();
      for (Learning.Question q : activeQuestions) qs.put(questionJson(q));
      quizSession.put("questions", qs);
    }
    store.state.put("session", quizSession);
    store.save();
  }

  static JSONObject questionJson(Learning.Question q) throws Exception {
    return new JSONObject()
        .put("id", q.id)
        .put("type", q.type)
        .put("prompt", q.prompt)
        .put("answer", q.answer)
        .put("explanation", q.explanation)
        .put("audio", q.audio)
        .put("options", new JSONArray(q.options));
  }

  static Learning.Question readQuestion(JSONObject j) throws Exception {
    Learning.Question q =
        new Learning.Question(
            j.getString("id"),
            j.getString("type"),
            j.getString("prompt"),
            j.getString("answer"),
            j.optString("explanation"),
            j.optString("audio"));
    JSONArray a = j.optJSONArray("options");
    if (a != null) for (int i = 0; i < a.length(); i++) q.options.add(a.getString(i));
    return q;
  }

  void resumeSession() throws Exception {
    JSONObject s = store.state.getJSONObject("session");
    reviewing = s.optBoolean("review");
    sessionCourse = s.getString("course");
    sessionStage = s.getInt("stage");
    queue = s.getJSONArray("queue");
    position = s.getInt("position");
    sessionAttempts = s.getInt("attempts");
    sessionCorrect = s.getInt("correct");
    started = s.getLong("started");
    activeMillis = s.optLong("activeMillis");
    activeTick = SystemClock.elapsedRealtime();
    if (reviewing) {
      activeQuestions = new ArrayList<>();
      JSONArray qs = s.getJSONArray("questions");
      for (int i = 0; i < qs.length(); i++) activeQuestions.add(readQuestion(qs.getJSONObject(i)));
      for (Learning.Question q : activeQuestions) {
        String cid = q.id.substring(0, q.id.indexOf('/'));
        if (!store.course(cid).optBoolean("published")) throw new Exception("部分复习课程已下架，请重新开始复习");
      }
    } else {
      JSONObject c = store.course(sessionCourse);
      if (!c.optBoolean("published")) throw new Exception("这门课已下架，请让家长重新发布");
      activeQuestions = store.questions(c, sessionStage);
    }
    if (position >= queue.length()) {
      finishSession();
      return;
    }
    showQuestion();
  }

  Learning.Question current() throws Exception {
    return activeQuestions.get(queue.getInt(position));
  }

  void showQuestion() throws Exception {
    audio.stop();
    Learning.Question q = current();
    screen("quiz", "保存并退出", false);
    selectedAnswer = "";
    ordered.clear();
    answerInput = null;
    add(
        body,
        label(
            reviewing
                ? "REVIEW & REMEMBER"
                : "LEVEL 0" + (sessionStage + 1) + "  /  " + Learning.STAGES[sessionStage]));
    space(body, 14);
    progressBar(body, position, queue.length());
    space(body, 9);
    add(
        body,
        text(
            "第 "
                + (position + 1)
                + " / "
                + queue.length()
                + " 题"
                + (queue.length() > activeQuestions.size() ? "  ·  含错题再练" : ""),
            12,
            MUTED,
            false));
    space(body, 18);
    String hint =
        switch (q.type) {
          case "read" -> "听一听，再读出来";
          case "listen" -> "竖起耳朵，听力寻宝";
          case "dictation" -> "听到的单词，写下来";
          case "order" -> "搭词块，造句子";
          case "flash" -> "收集一个新表达";
          default -> "找一找，哪个意思对？";
        };
    LinearLayout prompt = card(body);
    prompt.setBackground(
        new AdventureArt.Face(
            this,
            q.type.equals("listen") || q.type.equals("dictation") ? AdventureArt.SKY : Color.WHITE,
            LINE,
            24,
            3));
    LinearLayout promptTop = row();
    promptTop.addView(
        new AdventureArt.Companion(this, "think", false),
        new LinearLayout.LayoutParams(dp(62), dp(64)));
    TextView challenge = text(hint, 16, GREEN, true);
    challenge.setPadding(dp(8), 0, 0, 0);
    weight(promptTop, challenge);
    add(prompt, promptTop);
    space(prompt, 8);
    add(prompt, text(q.prompt, q.type.equals("read") ? 26 : 23, INK, true));
    space(body, 4);
    if (!q.audio.isEmpty()) {
      LinearLayout controls = row();
      weight(
          controls,
          button(
              "▶  听一遍",
              false,
              () -> {
                audio.setSpeed(1f);
                audio.play(q.audio, this::toast);
              }));
      View gap = new View(this);
      controls.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
      weight(
          controls,
          button(
              "◷  慢速听",
              false,
              () -> {
                audio.setSpeed(.78f);
                audio.play(q.audio, this::toast);
              }));
      add(body, controls);
      space(body, 20);
    }
    if (q.type.equals("read") || q.type.equals("flash")) {
      LinearLayout panel = card(body);
      TextView translation = text("点这里，看看中文意思", 17, GREEN, true);
      translation.setMinHeight(dp(55));
      translation.setGravity(Gravity.CENTER_VERTICAL);
      translation.setOnClickListener(
          v -> {
            translation.setText(q.answer);
            translation.setTextColor(INK);
          });
      add(panel, translation);
      space(panel, 8);
      add(panel, text("先听一遍，再试着自己读出来。", 13, MUTED, false));
      action(body, "我读懂了，继续  →", true, () -> submit(q.answer));
    } else if (q.type.equals("order")) {
      LinearLayout chosen = card(body);
      TextView built = text("点击下方词块，拼出完整句子", 21, MUTED, false);
      built.setMinHeight(dp(78));
      add(chosen, built);
      List<String> tokens = Learning.tokens(q.answer);
      List<Integer> order = new ArrayList<>();
      for (int i = 0; i < tokens.size(); i++) order.add(i);
      Collections.shuffle(order, new Random(q.id.hashCode() + 13));
      if (order.size() > 1 && order.get(0) == 0) Collections.rotate(order, 1);
      TokenFlow tokensBox = new TokenFlow(this);
      add(body, tokensBox);
      List<Button> tokenButtons = new ArrayList<>();
      for (int index : order) {
        String token = tokens.get(index);
        Button b = button(token, false, () -> {});
        b.setTextSize(15);
        b.setOnClickListener(
            v -> {
              if (answered || ordered.contains(index)) return;
              ordered.add(index);
              b.setAlpha(.3f);
              b.setEnabled(false);
              List<String> words = new ArrayList<>();
              for (int picked : ordered) words.add(tokens.get(picked));
              selectedAnswer = String.join(" ", words);
              built.setText(selectedAnswer);
              built.setTextColor(INK);
            });
        tokensBox.addView(b, new ViewGroup.LayoutParams(-2, -2));
        tokenButtons.add(b);
      }
      action(
          body,
          "重新排列",
          false,
          () -> {
            if (answered) return;
            ordered.clear();
            selectedAnswer = "";
            built.setText("点击下方词块，拼出完整句子");
            for (Button b : tokenButtons) {
              b.setEnabled(true);
              b.setAlpha(1f);
            }
          });
      action(
          body,
          "检查答案",
          true,
          () -> {
            if (ordered.size() != tokens.size()) {
              toast("请用完所有词块");
              return;
            }
            submit(selectedAnswer);
          });
    } else if (q.type.equals("dictation")) {
      answerInput = field(body, "输入你听到的英文", "", false);
      answerInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
      answerInput.setSingleLine(true);
      action(body, "检查拼写", true, () -> submit(answerInput.getText().toString()));
    } else {
      for (String option : q.options) {
        Button b = button(option, false, () -> {});
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        b.setOnClickListener(
            v ->
                safe(
                    () -> {
                      if (answered) return;
                      selectedAnswer = option;
                      for (int i = 0; i < body.getChildCount(); i++) {
                        View child = body.getChildAt(i);
                        if (child instanceof Button && "option".equals(child.getTag()))
                          child.setBackground(
                              new AdventureArt.Face(this, Color.WHITE, LINE, 18, 4));
                      }
                      b.setBackground(
                          new AdventureArt.Face(
                              this, AdventureArt.SKY, Color.rgb(100, 178, 212), 18, 4));
                    }));
        b.setTag("option");
        space(body, 10);
        add(body, b);
      }
      action(
          body,
          "检查答案",
          true,
          () -> {
            if (selectedAnswer.isEmpty()) {
              toast("先选一个答案吧");
              return;
            }
            submit(selectedAnswer);
          });
    }
    space(body, 16);
    add(body, text("每一次练习，都在让记忆更牢固。", 12, MUTED, false));
  }

  void submit(String answer) throws Exception {
    if (answered) return;
    if (answer.trim().isEmpty()) {
      toast("先填写答案吧");
      return;
    }
    Learning.Question q = current();
    boolean ok = Learning.matches(answer, q.answer);
    answered = true;
    audio.stop();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(body.getWindowToken(), 0);
    sessionAttempts++;
    if (ok) sessionCorrect++;
    else queue.put(queue.getInt(position));
    String cid = sessionCourse;
    Learning.Question record = q;
    if (reviewing) {
      int slash = q.id.indexOf('/');
      cid = q.id.substring(0, slash);
      record = readQuestion(questionJson(q).put("id", q.id.substring(slash + 1)));
    }
    store.record(cid, record, ok, true);
    position++;
    saveSession();
    LinearLayout feedback = card(body);
    feedback.setBackground(
        new AdventureArt.Face(
            this,
            ok ? LIME : AdventureArt.PEACH,
            ok ? Color.rgb(193, 222, 158) : Color.rgb(237, 207, 165),
            24,
            4));
    LinearLayout feedbackTop = row();
    feedbackTop.addView(
        new AdventureArt.Companion(this, ok ? "celebrate" : "think", false),
        new LinearLayout.LayoutParams(dp(76), dp(78)));
    weight(
        feedbackTop,
        text(ok ? "太棒啦，找到答案！" : "再试试，我陪着你", 21, ok ? GREEN : Color.rgb(144, 88, 34), true));
    add(feedback, feedbackTop);
    space(feedback, 9);
    add(feedback, text((ok ? "" : "正确答案：\n") + q.answer, 19, INK, true));
    if (!q.explanation.isEmpty()) {
      space(feedback, 8);
      add(feedback, text(q.explanation, 14, MUTED, false));
    }
    if (!ok) {
      space(feedback, 8);
      add(feedback, text("这道题会在本轮稍后再出现，也已收进复习站。", 13, MUTED, false));
    }
    action(
        feedback,
        position >= queue.length() ? "完成本次学习  →" : "继续  →",
        true,
        () -> {
          if (position >= queue.length()) finishSession();
          else showQuestion();
        });
    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
      feedback.setAlpha(0);
      feedback.setTranslationY(dp(16));
      feedback.animate().alpha(1).translationY(0).setDuration(240).start();
    }
    scroll.post(() -> scroll.smoothScrollTo(0, body.getBottom()));
  }

  void finishSession() throws Exception {
    boolean fresh = false;
    accrueClock();
    long seconds = Math.max(1, activeMillis / 1000);
    activeTick = 0;
    if (reviewing) {
      store.state.remove("session");
      store
          .state
          .getJSONArray("history")
          .put(
              new JSONObject()
                  .put("course", "review")
                  .put("stage", -1)
                  .put("time", System.currentTimeMillis())
                  .put("attempted", sessionAttempts)
                  .put("correct", sessionCorrect)
                  .put("seconds", seconds)
                  .put("xp", 0));
      store.save();
    } else
      fresh = store.complete(sessionCourse, sessionStage, sessionAttempts, sessionCorrect, seconds);
    screen("result", "返回学习岛", false);
    space(body, 4);
    LinearLayout celebration = card(body);
    celebration.setBackground(
        new AdventureArt.Face(this, AdventureArt.SKY, Color.rgb(192, 225, 240), 28, 4));
    TextView ribbon = pill(reviewing ? "今日温习完成" : "冒险任务完成", GREEN, Color.WHITE);
    add(celebration, ribbon);
    celebration.addView(
        new AdventureArt.Companion(this, "celebrate", true),
        new LinearLayout.LayoutParams(-1, dp(145)));
    TextView resultTitle = text(reviewing ? "记忆又牢固了一点！" : "又点亮了一段旅程！", 24, INK, true);
    resultTitle.setGravity(Gravity.CENTER);
    add(celebration, resultTitle);
    space(celebration, 8);
    TextView detail =
        text(
            reviewing ? "今天的复习完成了，下一次再见。" : "你完成了「" + Learning.STAGES[sessionStage] + "」",
            14,
            MUTED,
            false);
    detail.setGravity(Gravity.CENTER);
    add(celebration, detail);
    space(celebration, 20);
    LinearLayout rewards = row();
    weight(rewards, pill(fresh ? "✦  +20 XP" : "✓  练习完成", GREEN, Color.WHITE));
    View gap = new View(this);
    rewards.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
    weight(
        rewards,
        pill(
            "正确率 " + Learning.accuracy(sessionCorrect, sessionAttempts) + "%", GREEN, Color.WHITE));
    add(celebration, rewards);
    space(celebration, 12);
    TextView count = text("认真作答 " + sessionAttempts + " 次，每一步都算数。", 13, MUTED, false);
    count.setGravity(Gravity.CENTER);
    add(celebration, count);
    space(body, 4);
    companion(body, "歇一歇也没关系。接下来，我会在复习站等你！", "wave");
    pinAction(
        reviewing ? "返回复习站" : "查看学习路线  →",
        () -> {
          if (reviewing) reviews();
          else path(sessionCourse);
        });
  }

  void reviews() throws Exception {
    screen("reviews", "", true);
    add(body, label("记忆花园  /  GROW YOUR MEMORY"));
    space(body, 9);
    heading("来给记忆浇浇水", "每次温习，都让学过的知识长得更牢。");
    List<JSONObject> due = store.due(false);
    LinearLayout hero = card(body);
    hero.setBackground(new AdventureArt.Face(this, LIME, Color.rgb(205, 227, 178), 26, 4));
    LinearLayout reviewTop = row(), reviewCopy = col();
    add(reviewCopy, pill("今日温习", GREEN, Color.WHITE));
    space(reviewCopy, 8);
    add(reviewCopy, text(String.valueOf(due.size()), 48, GREEN, true));
    add(reviewCopy, text("项记忆等你唤醒", 16, INK, true));
    weight(reviewTop, reviewCopy);
    reviewTop.addView(
        new AdventureArt.Companion(this, due.isEmpty() ? "celebrate" : "think", true),
        new LinearLayout.LayoutParams(dp(130), dp(150)));
    add(hero, reviewTop);
    space(hero, 8);
    add(hero, text("错题及时再练；答对的内容按记忆间隔回来。", 13, MUTED, false));
    if (!due.isEmpty()) action(hero, "开始今日复习  →", true, () -> startReview(false));
    if (!store.due(true).isEmpty()) action(hero, "只练错题", false, () -> startReview(true));
    if (due.isEmpty()) {
      companion(body, "今天没有到期的复习，记忆花园状态很好！", "celebrate");
      action(body, "去学习岛探险  →", true, this::home);
    } else {
      add(body, text("本次将复习", 19, INK, true));
      space(body, 12);
      for (JSONObject q : due.subList(0, Math.min(12, due.size()))) {
        LinearLayout c = card(body);
        add(
            c,
            text(
                q.optString("type").equals("dictation") ? "听音拼写" : q.optString("prompt"),
                17,
                INK,
                true));
        space(c, 5);
        add(
            c,
            text(
                store.course(q.getString("course")).optString("title")
                    + (q.optInt("mistakes") > 0 ? "  ·  曾错 " + q.optInt("mistakes") + " 次" : ""),
                12,
                MUTED,
                false));
      }
      if (due.size() > 12)
        add(body, text("还有 " + (due.size() - 12) + " 项，本轮最多练习 12 项。", 13, MUTED, false));
    }
  }

  void startReview(boolean mistakes) throws Exception {
    if (store.state.has("session")) {
      new AlertDialog.Builder(this)
          .setTitle("保存的练习还没完成")
          .setMessage("继续上次练习，或退出后重新开始复习。当前学习进度已自动保存。")
          .setPositiveButton("继续上次", (d, w) -> safe(this::resumeSession))
          .setNegativeButton(
              "放弃本轮并复习",
              (d, w) ->
                  safe(
                      () -> {
                        store.state.remove("session");
                        store.save();
                        startReview(mistakes);
                      }))
          .show();
      return;
    }
    List<JSONObject> list = store.due(mistakes);
    if (list.isEmpty()) {
      toast("暂时没有到期的复习内容");
      return;
    }
    activeQuestions = new ArrayList<>();
    for (JSONObject j : list.subList(0, Math.min(12, list.size()))) {
      Learning.Question q = readQuestion(j);
      q.id = j.getString("course") + "/" + q.id;
      activeQuestions.add(q);
    }
    reviewing = true;
    sessionCourse = "review";
    sessionStage = -1;
    position = 0;
    sessionAttempts = 0;
    sessionCorrect = 0;
    started = System.currentTimeMillis();
    activeMillis = 0;
    activeTick = SystemClock.elapsedRealtime();
    queue = new JSONArray();
    for (int i = 0; i < activeQuestions.size(); i++) queue.put(i);
    saveSession();
    showQuestion();
  }

  void profile() throws Exception {
    screen("profile", "", true);
    add(body, label("我的成长  /  LITTLE WINS"));
    space(body, 8);
    heading("每一点进步，都算数", "这是 " + store.state.optString("name") + " 的冒险手账。");
    LinearLayout c = card(body);
    c.setBackground(
        new AdventureArt.Face(this, AdventureArt.LILAC, Color.rgb(214, 205, 241), 26, 4));
    LinearLayout growthTop = row(), growthCopy = col();
    add(growthCopy, pill("我的探险能量", GREEN, Color.WHITE));
    space(growthCopy, 10);
    add(growthCopy, text(store.state.optInt("xp") + " XP", 32, GREEN, true));
    space(growthCopy, 8);
    add(growthCopy, text("点亮 " + completedCount() + " 关\n学习 " + studyDays() + " 天", 15, INK, true));
    weight(growthTop, growthCopy);
    growthTop.addView(
        new AdventureArt.Companion(this, "celebrate", false),
        new LinearLayout.LayoutParams(dp(118), dp(148)));
    add(c, growthTop);
    action(
        c,
        "修改我的昵称",
        false,
        () -> {
          EditText name = new EditText(this);
          name.setText(store.state.optString("name"));
          new AlertDialog.Builder(this)
              .setTitle("给自己起个昵称")
              .setView(name)
              .setPositiveButton(
                  "保存",
                  (d, w) ->
                      safe(
                          () -> {
                            String value = name.getText().toString().trim();
                            if (value.isEmpty() || value.length() > 20)
                              throw new Exception("昵称请填写 1–20 个字");
                            store.state.put("name", value);
                            store.save();
                            profile();
                          }))
              .setNegativeButton("取消", null)
              .show();
        });
    iconTitle(body, "我的冒险徽章", "trophy", AdventureArt.PEACH);
    space(body, 12);
    LinearLayout badges = row();
    String[] badgeNames = {"初次出发", "点亮一座岛", "三日足迹"};
    String[] badgeHints = {"完成 1 关", "完成一篇课文", "学习 3 天"};
    boolean clearedIsland = false;
    for (JSONObject course : store.courses(false))
      clearedIsland |= store.progress(course.getString("id")).optInt("completed") >= 6;
    boolean[] earned = {completedCount() >= 1, clearedIsland, studyDays() >= 3};
    for (int i = 0; i < 3; i++) {
      LinearLayout badge = col();
      badge.setGravity(Gravity.CENTER);
      badge.setPadding(dp(3), dp(12), dp(3), dp(12));
      AdventureArt.Icon badgeArt =
          new AdventureArt.Icon(
              this, i == 2 ? "leaf" : "trophy", earned[i] ? GREEN : Color.rgb(139, 161, 175));
      badgeArt.setPadding(dp(8), dp(8), dp(8), dp(8));
      badgeArt.setBackground(shape(earned[i] ? AdventureArt.PEACH : Color.rgb(235, 242, 246), 20));
      badge.addView(badgeArt, new LinearLayout.LayoutParams(dp(60), dp(60)));
      TextView name = text(badgeNames[i], 13, INK, true);
      name.setGravity(Gravity.CENTER);
      add(badge, name);
      TextView hint = text(earned[i] ? "已获得" : badgeHints[i], 10, MUTED, false);
      hint.setGravity(Gravity.CENTER);
      add(badge, hint);
      weight(badges, badge);
    }
    add(body, badges);
    space(body, 18);
    int minutes = todaySeconds() / 60;
    LinearLayout goal = card(body);
    iconTitle(goal, "今日小目标", "leaf", LIME);
    space(goal, 8);
    add(goal, text(minutes + " / " + store.state.optInt("goal", 10) + " 分钟", 16, GREEN, true));
    space(goal, 10);
    progressBar(
        goal, Math.min(minutes, store.state.optInt("goal", 10)), store.state.optInt("goal", 10));
    space(goal, 8);
    add(goal, text("专注练习，按自己的节奏前进。", 13, MUTED, false));
    add(body, text("最近的脚印", 20, INK, true));
    space(body, 12);
    history(false);
  }

  int completedCount() throws Exception {
    int n = 0;
    for (JSONObject c : store.courses(false))
      n += store.progress(c.getString("id")).optInt("completed");
    return n;
  }

  int studyDays() throws Exception {
    Set<String> days = new HashSet<>();
    JSONArray h = store.state.getJSONArray("history");
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    for (int i = 0; i < h.length(); i++)
      days.add(f.format(new Date(h.getJSONObject(i).getLong("time"))));
    return days.size();
  }

  int todaySeconds() throws Exception {
    int n = 0;
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    String today = f.format(new Date());
    JSONArray h = store.state.getJSONArray("history");
    for (int i = 0; i < h.length(); i++) {
      JSONObject x = h.getJSONObject(i);
      if (f.format(new Date(x.getLong("time"))).equals(today))
        n += Math.min(3600, x.optInt("seconds"));
    }
    return n;
  }

  void history(boolean detailed) throws Exception {
    JSONArray h = store.state.getJSONArray("history");
    if (h.length() == 0) {
      add(body, text("完成第一关后，这里会留下你的足迹。", 14, MUTED, false));
      return;
    }
    for (int i = h.length() - 1; i >= Math.max(0, h.length() - 15); i--) {
      JSONObject x = h.getJSONObject(i);
      LinearLayout c = card(body);
      String title =
          x.optString("course").equals("review")
              ? "记忆复习"
              : courseTitle(x.optString("course"))
                  + " · "
                  + Learning.STAGES[Math.max(0, x.optInt("stage"))];
      add(c, text(title, 16, INK, true));
      space(c, 5);
      add(
          c,
          text(
              new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(x.getLong("time")))
                  + "  ·  正确率 "
                  + Learning.accuracy(x.optInt("correct"), x.optInt("attempted"))
                  + "%  ·  "
                  + (x.optInt("seconds") / 60)
                  + "分"
                  + (x.optInt("seconds") % 60)
                  + "秒",
              12,
              MUTED,
              false));
    }
  }

  String courseTitle(String id) {
    try {
      return store.course(id).optString("title");
    } catch (Exception e) {
      return "课文练习";
    }
  }

  void gate() {
    gate(
        () -> {
          if (store.courses(false).isEmpty()) courses();
          else dashboard();
        });
  }

  void gate(Task destination) {
    safe(
        () -> {
          LinearLayout form = col();
          form.setPadding(dp(24), dp(8), dp(24), dp(8));
          boolean create = !secrets.hasPin();
          add(
              form,
              text(
                  create ? "设置一个 4 位家长 PIN，以后进入家长模式时使用。请妥善记住。" : "输入家长 PIN，进入课程工作台。",
                  14,
                  MUTED,
                  false));
          EditText input = field(form, "4 位数字 PIN", "", true);
          input.setInputType(
              InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
          EditText confirm = create ? field(form, "再输入一次 PIN", "", true) : null;
          if (confirm != null)
            confirm.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
          AlertDialog dialog =
              new AlertDialog.Builder(this)
                  .setTitle(create ? "第一次进入家长模式" : "家长入口")
                  .setView(form)
                  .setPositiveButton(create ? "设置并进入" : "进入", null)
                  .setNegativeButton("取消", null)
                  .create();
          dialog.setOnShowListener(
              d ->
                  dialog
                      .getButton(-1)
                      .setOnClickListener(
                          v ->
                              safe(
                                  () -> {
                                    long locked = getPreferences(0).getLong("pinLocked", 0);
                                    if (System.currentTimeMillis() < locked)
                                      throw new Exception(
                                          "请 "
                                              + ((locked - System.currentTimeMillis()) / 1000 + 1)
                                              + " 秒后再试");
                                    String pin = input.getText().toString();
                                    if (!pin.matches("[0-9]{4}")) throw new Exception("请输入 4 位数字");
                                    if (create) {
                                      if (!pin.equals(confirm.getText().toString()))
                                        throw new Exception("两次 PIN 不一致");
                                      secrets.setPin(pin);
                                    } else if (!secrets.checkPin(pin)) {
                                      int failures = getPreferences(0).getInt("pinFailures", 0) + 1;
                                      getPreferences(0)
                                          .edit()
                                          .putInt("pinFailures", failures)
                                          .putLong(
                                              "pinLocked",
                                              failures >= 5
                                                  ? System.currentTimeMillis() + 30000
                                                  : 0)
                                          .apply();
                                      throw new Exception(
                                          failures >= 5 ? "输入错误，30 秒后可重试" : "PIN 不正确");
                                    }
                                    getPreferences(0)
                                        .edit()
                                        .putInt("pinFailures", 0)
                                        .putLong("pinLocked", 0)
                                        .apply();
                                    parent = true;
                                    dialog.dismiss();
                                    destination.run();
                                  })));
          dialog.show();
        });
  }

  void dashboard() throws Exception {
    if (!parent) {
      home();
      return;
    }
    screen("dashboard", "", true);
    add(body, label("PARENT STUDIO"));
    space(body, 9);
    heading("看见每一步成长", "课程在本机制作和发布，学习记录只保存在这台设备。 ");
    action(body, "＋  创建课程", true, this::importScreen);
    space(body, 18);
    LinearLayout summary = card(body);
    add(summary, text(store.state.optString("name"), 24, INK, true));
    space(summary, 15);
    LinearLayout r = row();
    weight(r, metric("已完成关卡", String.valueOf(completedCount())));
    weight(r, metric("积累经验", store.state.optInt("xp") + " XP"));
    weight(r, metric("学习天数", studyDays() + " 天"));
    add(summary, r);
    space(summary, 18);
    add(
        summary,
        text(
            "今日学习 " + (todaySeconds() / 60) + " 分钟  ·  待复习 " + store.due(false).size() + " 项",
            13,
            MUTED,
            false));
    add(body, text("课文学习情况", 20, INK, true));
    space(body, 13);
    for (JSONObject c : store.courses(false)) {
      JSONObject p = store.progress(c.getString("id"));
      LinearLayout box = card(body);
      add(box, text(c.getString("title"), 19, INK, true));
      space(box, 8);
      progressBar(box, p.optInt("completed"), 6);
      space(box, 8);
      add(
          box,
          text(
              p.optInt("completed")
                  + " / 6 关  ·  "
                  + p.optInt("attempted")
                  + " 次作答  ·  正确率 "
                  + Learning.accuracy(p.optInt("correct"), p.optInt("attempted"))
                  + "%",
              13,
              MUTED,
              false));
    }
    add(body, text("待复习与薄弱内容", 20, INK, true));
    space(body, 10);
    int n = 0;
    JSONObject rs = store.state.getJSONObject("reviews");
    Iterator<String> keys = rs.keys();
    while (keys.hasNext() && n < 10) {
      JSONObject q = rs.getJSONObject(keys.next());
      if (q.optInt("mistakes") > 0) {
        LinearLayout b = card(body);
        add(
            b,
            text(
                q.optString("audio").isEmpty() ? q.optString("prompt") : q.optString("audio"),
                16,
                INK,
                true));
        space(b, 5);
        add(
            b,
            text(
                q.optString("answer") + "  ·  累计答错 " + q.optInt("mistakes") + " 次",
                13,
                MUTED,
                false));
        n++;
      }
    }
    if (n == 0) add(body, text("还没有错题记录。", 14, MUTED, false));
    space(body, 20);
    add(body, text("最近学习记录", 20, INK, true));
    space(body, 12);
    history(true);
  }

  LinearLayout metric(String title, String value) {
    LinearLayout c = col();
    add(c, text(value, 22, GREEN, true));
    space(c, 3);
    add(c, text(title, 11, MUTED, false));
    return c;
  }

  void courses() throws Exception {
    if (!parent) return;
    screen("courses", "", true);
    add(body, label("MAKE A LITTLE LEARNING ISLAND"));
    space(body, 9);
    heading("课程工作台", "从几张课文图片，开始一段新的学习旅程。 ");
    action(body, "＋  创建课程", true, this::importScreen);
    space(body, 20);
    if (store.courses(false).isEmpty()) {
      empty("还没有创建课程", "点击上方「创建课程」，选择课文图片开始制作。");
    }
    for (JSONObject c : store.courses(false)) {
      String id = c.getString("id");
      LinearLayout box = card(body);
      LinearLayout top = row();
      weight(top, text(c.getString("title"), 21, INK, true));
      top.addView(
          pill(
              c.optBoolean("published") ? "已发布" : "草稿",
              GREEN,
              c.optBoolean("published") ? LIME : BG));
      add(box, top);
      space(box, 8);
      add(box, text(c.optString("subtitle"), 14, MUTED, false));
      space(box, 8);
      int total = audio.texts(c).size();
      add(
          box,
          text(
              c.getJSONArray("sentences").length()
                  + " 句课文  ·  音频 "
                  + audio.cached(c)
                  + " / "
                  + total,
              13,
              MUTED,
              false));
      action(box, "查看、校对与发布  →", false, () -> materials(id, true));
    }
  }

  EditText field(LinearLayout p, String hint, String value, boolean password) {
    EditText e = new EditText(this);
    e.setTextSize(16);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setHint(hint);
    e.setText(value);
    e.setPadding(dp(14), dp(13), dp(14), dp(13));
    e.setBackground(outline(Color.WHITE, LINE));
    e.setMinHeight(dp(51));
    if (password)
      e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    space(p, 10);
    add(p, e);
    return e;
  }

  void importScreen() throws Exception {
    if (!parent) return;
    screen("import", "课程工作台", false);
    heading("把课文变成学习岛", "按阅读顺序选择图片。生成后先校对，再发布给孩子。 ");
    if (secrets.get().isEmpty()) {
      LinearLayout setup = card(body);
      add(setup, text("先连接制课服务", 20, INK, true));
      space(setup, 8);
      add(setup, text("导入 API 配置后，就能从图片生成课文、翻译和音频。", 14, MUTED, false));
      action(setup, "配置制课服务", false, this::settings);
    }
    JSONArray photos = store.state.optJSONArray("pendingImages");
    if (photos == null) photos = new JSONArray();
    final JSONArray list = photos;
    EditText title = field(body, "课文标题（可留空，自动识别）", store.state.optString("pendingTitle"), false);
    title.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int before, int count) {
            safe(
                () -> {
                  store.state.put("pendingTitle", s.toString());
                  store.save();
                });
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    space(body, 18);
    for (int i = 0; i < list.length(); i++) {
      int index = i;
      LinearLayout c = card(body);
      String name = list.getString(i);
      ImageView image = new ImageView(this);
      image.setAdjustViewBounds(true);
      image.setMaxHeight(dp(180));
      image.setImageBitmap(decodeImage(name, 600));
      image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      c.addView(image, new LinearLayout.LayoutParams(-1, dp(150)));
      space(c, 8);
      add(c, text("第 " + (i + 1) + " 张", 14, INK, true));
      LinearLayout controls = row();
      if (i > 0)
        weight(
            controls,
            button(
                "上移",
                false,
                () -> {
                  if (busy) return;
                  String prev = list.getString(index - 1);
                  list.put(index - 1, name);
                  list.put(index, prev);
                  store.state.put("pendingImages", list);
                  store.save();
                  importScreen();
                }));
      weight(
          controls,
          button(
              "移除",
              false,
              () -> {
                if (busy) return;
                list.remove(index);
                store.state.put("pendingImages", list);
                store.save();
                importScreen();
              }));
      space(c, 8);
      add(c, controls);
    }
    if (!busy) {
      action(
          body,
          "＋  选择课文图片（最多 8 张）",
          false,
          () -> {
            externalPending = true;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(pick, 101);
          });
      action(
          body,
          "识别并生成课程  →",
          true,
          () -> {
            if (list.length() == 0) throw new Exception("请先选择课文图片");
            if (secrets.get().isEmpty()) throw new Exception("请先前往家长设置，导入 llm info.txt 配置");
            List<File> files = new ArrayList<>();
            for (int i = 0; i < list.length(); i++)
              files.add(new File(store.root, list.getString(i)));
            String t = title.getText().toString();
            runJob(
                "正在制作课程…",
                () -> {
                  JSONObject c = ai.create(files, t, this::updateStatus);
                  store.saveCourse(c);
                  store.state.remove("pendingImages");
                  store.state.remove("pendingTitle");
                  store.save();
                  selectedId = c.getString("id");
                  try {
                    audio.download(c, this::updateStatus);
                  } catch (Exception e) {
                    updateStatus("课文素材已保存。部分音频未完成，可在素材页继续下载。 " + e.getMessage());
                    throw e;
                  }
                },
                () -> materials(selectedId, true));
          });
    }
    space(body, 20);
    statusView =
        text(
            status.isEmpty() ? "图片将发送到你配置的模型 API 进行识别。课程、音频与学习进度保存在本机。" : status, 14, MUTED, false);
    add(body, statusView);
    if (busy) {
      ProgressBar loading = new ProgressBar(this);
      add(body, loading);
    } else if (!selectedId.isEmpty() && status.contains("音频"))
      action(body, "打开已保存的课程草稿", false, () -> materials(selectedId, true));
  }

  Bitmap decodeImage(String path, int max) throws Exception {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream in = openImage(path)) {
      BitmapFactory.decodeStream(in, null, options);
    }
    int sample = 1;
    while (Math.max(options.outWidth, options.outHeight) / sample > max * 2) sample *= 2;
    options.inJustDecodeBounds = false;
    options.inSampleSize = sample;
    try (InputStream in = openImage(path)) {
      return BitmapFactory.decodeStream(in, null, options);
    }
  }

  InputStream openImage(String path) throws Exception {
    if (path.startsWith("courses/")) return getAssets().open(path);
    if (!path.matches("images/[A-Za-z0-9_.-]+\\.(jpg|png)")) throw new Exception("无效图片路径");
    return new FileInputStream(new File(store.root, path));
  }

  void runJob(String message, Task task, Task success) {
    if (busy) {
      toast("正在完成上一项制作任务");
      return;
    }
    busy = true;
    status = message;
    updateStatus(message);
    jobs.submit(
        () -> {
          Exception failure = null;
          try {
            task.run();
          } catch (Exception e) {
            failure = e;
          }
          final Exception error = failure;
          runOnUiThread(
              () -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                  status = "未完成：" + error.getMessage();
                  toast(status);
                  if (parent)
                    safe(
                        () -> {
                          if (page.equals("import")) importScreen();
                          else if (page.equals("materials")) materials(selectedId, true);
                          else settings();
                        });
                } else {
                  status = "操作完成";
                  if (parent) safe(success);
                  else toast("课程素材已保存，请进入家长模式查看");
                }
              });
        });
    if (page.equals("import")) safe(this::importScreen);
  }

  void updateStatus(String value) {
    status = value;
    runOnUiThread(
        () -> {
          if (statusView != null) statusView.setText(value);
        });
  }

  void materials(String id, boolean edit) throws Exception {
    boolean admin = edit && parent;
    JSONObject c = store.course(id);
    selectedId = id;
    screen("materials", admin ? "课程工作台" : "学习路线", false);
    heading(c.getString("title"), c.optString("subtitle"));
    boolean editable =
        admin && !c.optBoolean("published") && store.progress(id).optInt("attempted") == 0;
    if (admin) {
      LinearLayout info = card(body);
      add(info, text(c.optBoolean("published") ? "课程已发布，学生可以开始学习" : "课程草稿 · 校对后发布", 19, INK, true));
      space(info, 7);
      add(
          info,
          text(
              "朗读音频 " + audio.cached(c) + " / " + audio.texts(c).size() + " 已就绪", 14, GREEN, true));
      space(info, 8);
      if (!c.optString("note").isEmpty())
        add(info, text("识别备注：" + c.optString("note"), 13, MUTED, false));
      if (editable) action(info, "修改标题与主题", false, () -> editMeta(c));
      else
        action(
            info,
            "复制为新草稿并编辑",
            false,
            () -> {
              JSONObject copy = Store.copy(c);
              copy.put("id", "course_" + UUID.randomUUID().toString().replace("-", ""))
                  .put("published", false)
                  .put("reviewed", false)
                  .put("createdAt", System.currentTimeMillis());
              store.saveCourse(copy);
              materials(copy.getString("id"), true);
            });
      action(
          info,
          busy ? "音频制作中…" : "下载 / 补全离线音频",
          false,
          () ->
              runJob(
                  "正在准备离线音频…",
                  () -> audio.download(c, this::updateStatus),
                  () -> materials(id, true)));
      if (c.optBoolean("published"))
        action(
            info,
            "下架课程",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("下架这门课程？")
                    .setMessage("学生将暂时看不到这门课，已有进度会保留。")
                    .setPositiveButton(
                        "下架",
                        (d, w) ->
                            safe(
                                () -> {
                                  c.put("published", false);
                                  store.saveCourse(c);
                                  materials(id, true);
                                }))
                    .setNegativeButton("取消", null)
                    .show());
      else
        action(
            info,
            "校对完成，发布课程  →",
            true,
            () -> {
              Store.validate(c, true);
              if (audio.cached(c) < audio.texts(c).size())
                throw new Exception("请先下载完整音频，保证学生可以离线学习");
              CheckBox check = new CheckBox(this);
              check.setText("我已逐句核对原图、翻译和理解题答案");
              check.setPadding(dp(20), dp(14), dp(20), dp(14));
              AlertDialog d =
                  new AlertDialog.Builder(this)
                      .setTitle("发布到学生学习岛")
                      .setMessage("发布后建议通过复制草稿修改素材，以保留已有学习记录。")
                      .setView(check)
                      .setPositiveButton("发布", null)
                      .setNegativeButton("再检查一下", null)
                      .create();
              d.setOnShowListener(
                  x ->
                      d.getButton(-1)
                          .setOnClickListener(
                              v ->
                                  safe(
                                      () -> {
                                        if (!check.isChecked()) throw new Exception("请先确认已经校对");
                                        c.put("published", true).put("reviewed", true);
                                        store.saveCourse(c);
                                        d.dismiss();
                                        toast("课程已发布");
                                        materials(id, true);
                                      })));
              d.show();
            });
      space(info, 12);
      statusView = text(busy ? status : "图片与 AI 生成结果请人工核对。", 12, MUTED, false);
      add(info, statusView);
    }
    LinearLayout tabs = row();
    String[] labels = {"课文", "重点素材", admin ? "原图与理解题" : "原图"};
    for (int i = 0; i < labels.length; i++) {
      int index = i;
      TextView t =
          pill(
              labels[i],
              materialTab == i ? Color.WHITE : GREEN,
              materialTab == i ? GREEN : Color.WHITE);
      t.setMinHeight(dp(46));
      t.setOnClickListener(
          v -> {
            materialTab = index;
            safe(() -> materials(id, admin));
          });
      weight(tabs, t);
    }
    add(body, tabs);
    space(body, 18);
    if (materialTab == 0) {
      materialHint(editable);
      List<String> items = new ArrayList<>();
      JSONArray a = c.getJSONArray("sentences");
      for (int i = 0; i < a.length(); i++) {
        JSONObject s = a.getJSONObject(i);
        int index = i;
        items.add(s.getString("en"));
        LinearLayout box = card(body);
        bindMaterialCard(
            box,
            c,
            "sentences",
            index,
            items,
            editable,
            String.format(Locale.CHINA, "%02d", i + 1)
                + (s.optString("speaker").isEmpty() ? "" : "  /  " + s.optString("speaker")));
        space(box, 10);
        add(box, text(s.getString("en"), 21, INK, true));
        space(box, 9);
        add(box, text(s.getString("zh"), 15, MUTED, false));
      }
      if (editable) action(body, "＋  添加句子", false, () -> editItem(c, "sentences", -1));
    } else if (materialTab == 1) {
      materialHint(editable);
      List<String> items = new ArrayList<>();
      JSONArray a = c.getJSONArray("vocabulary");
      for (String kind : new String[] {"word", "phrase", "sentence"}) {
        add(
            body,
            text(
                kind.equals("word") ? "重点词汇" : kind.equals("phrase") ? "常用短语" : "重点句子",
                21,
                INK,
                true));
        space(body, 12);
        for (int i = 0; i < a.length(); i++) {
          JSONObject v = a.getJSONObject(i);
          if (!kind.equals(v.optString("kind"))) continue;
          int index = i;
          items.add(v.getString("en"));
          LinearLayout box = card(body);
          bindMaterialCard(
              box,
              c,
              "vocabulary",
              index,
              items,
              editable,
              String.format(Locale.CHINA, "%02d", items.size()));
          space(box, 8);
          add(box, text(v.getString("en"), 21, INK, true));
          if (!v.optString("phonetic").isEmpty()) {
            space(box, 4);
            add(box, text(v.optString("phonetic"), 14, GREEN, false));
          }
          space(box, 7);
          add(box, text(v.getString("zh"), 16, INK, false));
          space(box, 8);
          add(box, text(v.optString("note"), 13, MUTED, false));
        }
      }
      if (editable) action(body, "＋  添加重点素材", false, () -> editItem(c, "vocabulary", -1));
    } else {
      JSONArray sources = c.optJSONArray("sources");
      if (sources != null)
        for (int i = 0; i < sources.length(); i++) {
          String src = sources.getString(i);
          LinearLayout box = card(body);
          add(box, label("SOURCE  /  " + (i + 1)));
          space(box, 8);
          ImageView image = new ImageView(this);
          image.setImageBitmap(decodeImage(src, 1200));
          image.setAdjustViewBounds(true);
          image.setScaleType(ImageView.ScaleType.FIT_CENTER);
          add(box, image);
          image.setContentDescription("课文原图第 " + (i + 1) + " 页");
        }
      if (admin) {
        add(body, text("阅读理解题及答案", 21, INK, true));
        space(body, 12);
        JSONArray qs = c.optJSONArray("questions");
        if (qs != null)
          for (int i = 0; i < qs.length(); i++) {
            int index = i;
            JSONObject q = qs.getJSONObject(i);
            LinearLayout box = card(body);
            add(box, text(q.getString("prompt"), 18, INK, true));
            JSONArray opts = q.getJSONArray("options");
            space(box, 9);
            for (int k = 0; k < opts.length(); k++)
              add(
                  box,
                  text(
                      (k == q.getInt("answer") ? "✓  " : "·  ") + opts.getString(k),
                      14,
                      k == q.getInt("answer") ? GREEN : MUTED,
                      false));
            space(box, 8);
            add(box, text(q.optString("explanation"), 13, MUTED, false));
            if (editable) action(box, "编辑理解题", false, () -> editQuestion(c, index));
          }
        if (editable) action(body, "＋  添加理解题", false, () -> editQuestion(c, -1));
      }
    }
  }

  void materialHint(boolean editable) {
    add(body, text("轻点连播，再点当前内容停止。" + (editable ? "\n长按未播放的内容可编辑。" : ""), 13, MUTED, false));
    space(body, 14);
    ((ContentScrollView) scroll).onManualScroll =
        () -> {
          if (followMaterialPlayback) {
            followMaterialPlayback = false;
            updateMaterialPlaybackStatus();
          }
        };
  }

  void bindMaterialCard(
      LinearLayout box,
      JSONObject course,
      String array,
      int sourceIndex,
      List<String> items,
      boolean editable,
      String number)
      throws Exception {
    int playbackIndex = materialCards.size();
    JSONObject item = course.getJSONArray(array).getJSONObject(sourceIndex);
    LinearLayout head = row();
    weight(head, label(number));
    TextView state = text("正在朗读", 12, GREEN, true);
    state.setVisibility(View.GONE);
    head.addView(state);
    add(box, head);
    materialCards.add(box);
    materialStates.add(state);
    box.setTag("material:" + array + ":" + sourceIndex);
    box.setFocusable(true);
    box.setContentDescription(
        item.getString("en")
            + "。"
            + item.getString("zh")
            + "。轻点开始或停止连续朗读"
            + (editable ? "，长按未播放的内容可编辑" : ""));
    box.setForeground(
        new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(30, 12, 119, 99)), null, shape(Color.WHITE, 24)));
    box.setOnClickListener(
        v -> {
          if (materialPlaying == playbackIndex) audio.stop();
          else {
            followMaterialPlayback = true;
            audio.setSpeed(1f);
            audio.playList(items, playbackIndex, this::showMaterialPlaying, this::toast);
          }
        });
    if (editable)
      box.setOnLongClickListener(
          v -> {
            if (materialPlaying == playbackIndex) {
              toast("先轻点停止朗读，再长按编辑");
            } else {
              audio.stop();
              safe(() -> editItem(course, array, sourceIndex));
            }
            return true;
          });
  }

  void showMaterialPlaying(int index) {
    if (!page.equals("materials")) return;
    materialPlaying = index;
    for (int i = 0; i < materialCards.size(); i++) {
      boolean active = i == index;
      materialCards.get(i).setSelected(active);
      materialCards
          .get(i)
          .setBackground(
              new AdventureArt.Face(
                  this, active ? AdventureArt.SKY : Color.WHITE, active ? GREEN : LINE, 24, 3));
      materialStates.get(i).setVisibility(active ? View.VISIBLE : View.GONE);
    }
    if (index < 0 || index >= materialCards.size()) {
      if (materialPlaybackBar != null) materialPlaybackBar.setVisibility(View.GONE);
      return;
    }
    if (materialPlaybackBar == null) {
      materialPlaybackBar = col();
      materialPlaybackBar.setPadding(dp(20), dp(8), dp(20), dp(8));
      materialPlaybackBar.setBackgroundColor(Color.WHITE);
      LinearLayout strip = row();
      strip.setMinimumHeight(dp(48));
      strip.setPadding(dp(14), dp(10), dp(14), dp(10));
      strip.setBackground(outline(LIME, Color.rgb(205, 227, 178)));
      TextView stop = text("■", 18, GREEN, true);
      stop.setPadding(0, 0, dp(12), 0);
      strip.addView(stop);
      materialPlaybackStatus = text("", 13, GREEN, true);
      weight(strip, materialPlaybackStatus);
      strip.setOnClickListener(v -> audio.stop());
      strip.setFocusable(true);
      strip.setContentDescription("停止连续朗读");
      add(materialPlaybackBar, strip);
      shell.addView(
          materialPlaybackBar,
          shell.indexOfChild(scroll) + 1,
          new LinearLayout.LayoutParams(-1, -2));
    }
    materialPlaybackBar.setVisibility(View.VISIBLE);
    updateMaterialPlaybackStatus();
    LinearLayout current = materialCards.get(index);
    scroll.post(
        () -> {
          if (followMaterialPlayback
              && materialPlaying == index
              && materialCards.size() > index
              && materialCards.get(index) == current) {
            Rect rect = new Rect();
            current.getDrawingRect(rect);
            scroll.offsetDescendantRectToMyCoords(current, rect);
            scroll.smoothScrollTo(0, Math.max(0, rect.top - dp(12)));
          }
        });
  }

  void updateMaterialPlaybackStatus() {
    if (materialPlaybackStatus != null && materialPlaying >= 0) {
      materialPlaybackStatus.setText(
          getString(
              R.string.material_playback_status,
              materialPlaying + 1,
              materialCards.size(),
              getString(
                  followMaterialPlayback
                      ? R.string.material_stop_hint
                      : R.string.material_browse_stop_hint)));
      materialPlaybackBar.getChildAt(0).setContentDescription(materialPlaybackStatus.getText());
    }
  }

  void editMeta(JSONObject c) throws Exception {
    LinearLayout form = col();
    form.setPadding(dp(20), 0, dp(20), dp(20));
    EditText t = field(form, "课文标题", c.getString("title"), false),
        sub = field(form, "中文主题", c.optString("subtitle"), false);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("修改课程信息")
            .setView(form)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(
        x ->
            d.getButton(-1)
                .setOnClickListener(
                    v ->
                        safe(
                            () -> {
                              JSONObject copy = Store.copy(c);
                              copy.put("title", t.getText().toString().trim())
                                  .put("subtitle", sub.getText().toString().trim());
                              store.saveCourse(copy);
                              d.dismiss();
                              materials(copy.getString("id"), true);
                            })));
    d.show();
  }

  void editItem(JSONObject c, String array, int index) throws Exception {
    JSONArray a = c.getJSONArray(array);
    JSONObject item = index < 0 ? new JSONObject() : a.getJSONObject(index);
    boolean vocab = array.equals("vocabulary");
    LinearLayout form = col();
    form.setPadding(dp(20), 0, dp(20), dp(20));
    EditText en = field(form, "英文", item.optString("en"), false),
        zh = field(form, "中文翻译", item.optString("zh"), false);
    Spinner kind = new Spinner(this);
    kind.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"word · 单词", "phrase · 短语", "sentence · 重点句"}));
    kind.setSelection(
        Math.max(0, Arrays.asList("word", "phrase", "sentence").indexOf(item.optString("kind"))));
    if (vocab) {
      space(form, 10);
      add(form, kind);
    }
    EditText extra =
        field(form, vocab ? "用法提示" : "人物（可留空）", item.optString(vocab ? "note" : "speaker"), false);
    EditText phonetic = vocab ? field(form, "音标（可留空）", item.optString("phonetic"), false) : null;
    ScrollView sc = new ScrollView(this);
    sc.addView(form);
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle(index < 0 ? "添加素材" : "校对素材")
            .setView(sc)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null);
    if (index >= 0) builder.setNeutralButton("删除", null);
    AlertDialog d = builder.create();
    d.setOnShowListener(
        x -> {
          d.getButton(-1)
              .setOnClickListener(
                  v ->
                      safe(
                          () -> {
                            JSONObject copy = Store.copy(c);
                            JSONObject value =
                                new JSONObject()
                                    .put("en", en.getText().toString().trim())
                                    .put("zh", zh.getText().toString().trim())
                                    .put(
                                        vocab ? "note" : "speaker",
                                        extra.getText().toString().trim());
                            if (vocab)
                              value
                                  .put(
                                      "kind",
                                      new String[] {"word", "phrase", "sentence"}
                                          [kind.getSelectedItemPosition()])
                                  .put("phonetic", phonetic.getText().toString().trim());
                            JSONArray items = copy.getJSONArray(array);
                            if (index < 0) items.put(value);
                            else items.put(index, value);
                            copy.put("reviewed", false);
                            store.saveCourse(copy);
                            d.dismiss();
                            materials(copy.getString("id"), true);
                          }));
          if (index >= 0)
            d.getButton(-3)
                .setOnClickListener(
                    v ->
                        safe(
                            () -> {
                              JSONObject copy = Store.copy(c);
                              copy.getJSONArray(array).remove(index);
                              store.saveCourse(copy);
                              d.dismiss();
                              materials(copy.getString("id"), true);
                            }));
        });
    d.show();
  }

  void editQuestion(JSONObject c, int index) throws Exception {
    JSONArray a = c.getJSONArray("questions");
    JSONObject q = index < 0 ? new JSONObject() : a.getJSONObject(index);
    LinearLayout form = col();
    form.setPadding(dp(20), 0, dp(20), dp(20));
    EditText prompt = field(form, "中文问题", q.optString("prompt"), false);
    JSONArray opts = q.optJSONArray("options");
    List<EditText> fields = new ArrayList<>();
    for (int i = 0; i < 4; i++)
      fields.add(
          field(
              form,
              "选项 " + (i + 1),
              opts != null && i < opts.length() ? opts.getString(i) : "",
              false));
    EditText answer =
        field(form, "正确选项序号（1–4）", index < 0 ? "1" : String.valueOf(q.optInt("answer") + 1), false);
    answer.setInputType(InputType.TYPE_CLASS_NUMBER);
    EditText exp = field(form, "答案解析", q.optString("explanation"), false);
    ScrollView sc = new ScrollView(this);
    sc.addView(form);
    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle("校对理解题")
            .setView(sc)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null);
    if (index >= 0) b.setNeutralButton("删除", null);
    AlertDialog d = b.create();
    d.setOnShowListener(
        x -> {
          d.getButton(-1)
              .setOnClickListener(
                  v ->
                      safe(
                          () -> {
                            int correct;
                            try {
                              correct = Integer.parseInt(answer.getText().toString()) - 1;
                            } catch (Exception e) {
                              throw new Exception("正确选项请填 1–4");
                            }
                            JSONObject copy = Store.copy(c);
                            JSONArray options = new JSONArray();
                            for (EditText field : fields)
                              options.put(field.getText().toString().trim());
                            JSONObject value =
                                new JSONObject()
                                    .put("prompt", prompt.getText().toString().trim())
                                    .put("options", options)
                                    .put("answer", correct)
                                    .put("explanation", exp.getText().toString().trim());
                            if (index < 0) copy.getJSONArray("questions").put(value);
                            else copy.getJSONArray("questions").put(index, value);
                            store.saveCourse(copy);
                            d.dismiss();
                            materials(copy.getString("id"), true);
                          }));
          if (index >= 0)
            d.getButton(-3)
                .setOnClickListener(
                    v ->
                        safe(
                            () -> {
                              JSONObject copy = Store.copy(c);
                              copy.getJSONArray("questions").remove(index);
                              store.saveCourse(copy);
                              d.dismiss();
                              materials(copy.getString("id"), true);
                            }));
        });
    d.show();
  }

  void settings() throws Exception {
    if (!parent) return;
    screen("settings", "", true);
    heading("家长设置", "制作需要联网，学习可以离线。 ");
    LinearLayout config = card(body);
    add(config, text("模型与语音服务", 21, INK, true));
    space(config, 8);
    boolean hasSavedKey = !secrets.get().isEmpty();
    add(
        config,
        text(hasSavedKey ? "API 密钥已加密保存在本机。" : "尚未配置 API。可直接导入 llm info.txt。", 14, MUTED, false));
    action(config, "导入 llm info.txt 配置", true, () -> pickFile(102, "text/*"));
    EditText base = field(config, "API 地址（HTTPS）", ai.base(), false),
        model = field(config, "图片识别与课程模型", ai.model(), false),
        tts =
            field(
                config,
                "语音模型",
                getSharedPreferences("config", 0).getString("tts", "mimo-v2.5-tts"),
                false),
        key = apiKeyField(config, hasSavedKey);
    action(
        config,
        "保存 API 设置",
        false,
        () -> {
          String url = base.getText().toString().trim().replaceAll("/+$", "");
          java.net.URI uri;
          try {
            uri = java.net.URI.create(url);
          } catch (Exception e) {
            throw new Exception("请输入有效 HTTPS 地址");
          }
          if (!"https".equals(uri.getScheme())
              || uri.getHost() == null
              || uri.getUserInfo() != null
              || uri.getQuery() != null) throw new Exception("请输入有效 HTTPS API 地址");
          String m = model.getText().toString().trim(), t = tts.getText().toString().trim();
          if (m.isEmpty() || t.isEmpty()) throw new Exception("请填写模型名称");
          if (!key.getText().toString().trim().isEmpty())
            secrets.set(key.getText().toString().trim());
          getSharedPreferences("config", 0)
              .edit()
              .putString("base", url)
              .putString("model", m)
              .putString("tts", t)
              .commit();
          toast("API 设置已保存");
          settings();
        });
    action(
        config,
        "测试模型连接",
        false,
        () ->
            runJob(
                "正在测试连接…",
                () -> ai.test(),
                () -> {
                  settings();
                  toast("模型连接成功");
                }));
    space(config, 10);
    statusView = text(status, 13, MUTED, false);
    add(config, statusView);
    LinearLayout security = card(body);
    add(security, text("家长 PIN 与学习目标", 21, INK, true));
    action(
        security,
        "修改家长 PIN",
        false,
        () -> {
          LinearLayout f = col();
          f.setPadding(dp(20), 0, dp(20), dp(20));
          EditText old = field(f, "当前 PIN", "", true),
              next = field(f, "新的 4 位数字 PIN", "", true),
              confirm = field(f, "再输入新 PIN", "", true);
          AlertDialog d =
              new AlertDialog.Builder(this)
                  .setTitle("修改家长 PIN")
                  .setView(f)
                  .setPositiveButton("保存", null)
                  .setNegativeButton("取消", null)
                  .create();
          d.setOnShowListener(
              x ->
                  d.getButton(-1)
                      .setOnClickListener(
                          v ->
                              safe(
                                  () -> {
                                    if (!secrets.checkPin(old.getText().toString()))
                                      throw new Exception("当前 PIN 不正确");
                                    String pin = next.getText().toString();
                                    if (!pin.matches("[0-9]{4}")
                                        || !pin.equals(confirm.getText().toString()))
                                      throw new Exception("请输入一致的 4 位数字新 PIN");
                                    secrets.setPin(pin);
                                    d.dismiss();
                                    toast("PIN 已修改");
                                  })));
          d.show();
        });
    action(
        security,
        "每日目标：" + store.state.optInt("goal", 10) + " 分钟",
        false,
        () ->
            new AlertDialog.Builder(this)
                .setTitle("选择每日学习目标")
                .setItems(
                    new String[] {"5 分钟", "10 分钟", "15 分钟", "20 分钟"},
                    (d, w) ->
                        safe(
                            () -> {
                              store.state.put("goal", new int[] {5, 10, 15, 20}[w]);
                              store.save();
                              settings();
                            }))
                .show());
    LinearLayout backup = card(body);
    add(backup, text("本机备份", 21, INK, true));
    space(backup, 8);
    add(
        backup,
        text("导出课程、已缓存音频和学习记录。API 密钥与家长 PIN 不会包含在备份中。导入时合并课程，以备份中的学习记录为准。", 14, MUTED, false));
    action(
        backup,
        "导出备份 ZIP",
        false,
        () -> {
          externalPending = true;
          Intent intent =
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .setType("application/zip")
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .putExtra(
                      Intent.EXTRA_TITLE,
                      "课文岛备份-"
                          + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date())
                          + ".zip");
          startActivityForResult(intent, 103);
        });
    action(
        backup,
        "导入备份 ZIP",
        false,
        () ->
            new AlertDialog.Builder(this)
                .setTitle("恢复备份？")
                .setMessage("同编号课程将由备份覆盖，学习记录恢复到备份时的状态。建议先导出当前备份。")
                .setPositiveButton("选择备份", (d, w) -> pickFile(104, "application/zip"))
                .setNegativeButton("取消", null)
                .show());
    space(body, 12);
    add(
        body,
        text(
            "课文岛 · Lesson Island "
                + getPackageManager().getPackageInfo(getPackageName(), 0).versionName
                + "  ·  Android 8.0+\n单机存储 · 无账号 · 无广告\n首次进入家长模式设置 PIN；退出应用后需要重新验证。",
            12,
            MUTED,
            false));
  }

  EditText apiKeyField(LinearLayout target, boolean hasSavedKey) {
    space(target, 10);
    LinearLayout container = row();
    container.setBackground(outline(Color.WHITE, LINE));
    EditText input = new EditText(this);
    input.setTextSize(16);
    input.setTextColor(INK);
    input.setHintTextColor(MUTED);
    input.setHint(hasSavedKey ? "已保存密钥；留空保持当前密钥" : "API Key");
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setSingleLine(true);
    input.setSaveEnabled(false);
    input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    input.setPadding(dp(14), dp(13), dp(4), dp(13));
    input.setMinHeight(dp(51));
    input.setBackgroundColor(Color.TRANSPARENT);
    weight(container, input);
    ImageButton toggle = new ImageButton(this);
    toggle.setBackground(shape(Color.TRANSPARENT, 12));
    toggle.setPadding(dp(13), dp(13), dp(13), dp(13));
    toggle.setImageTintList(ColorStateList.valueOf(GREEN));
    toggle.setSaveEnabled(false);
    toggle.setOnClickListener(v -> setApiKeyVisible(!apiKeyVisible));
    container.addView(toggle, new LinearLayout.LayoutParams(dp(50), dp(50)));
    add(target, container);
    apiKeyInput = input;
    apiKeyToggle = toggle;
    setApiKeyVisible(false);
    return input;
  }

  void setApiKeyVisible(boolean visible) {
    apiKeyVisible = visible;
    if (apiKeyInput == null) return;
    int start = apiKeyInput.getSelectionStart(), end = apiKeyInput.getSelectionEnd();
    apiKeyInput.setTransformationMethod(
        visible ? null : PasswordTransformationMethod.getInstance());
    if (start >= 0 && end >= 0) apiKeyInput.setSelection(start, end);
    if (apiKeyToggle != null) {
      apiKeyToggle.setImageResource(
          visible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
      apiKeyToggle.setContentDescription(visible ? "隐藏 API 密钥" : "显示 API 密钥");
    }
  }

  void pickFile(int code, String type) {
    externalPending = true;
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE),
        code);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    externalPending = false;
    if (result != RESULT_OK || data == null) {
      if (parent)
        safe(
            () -> {
              if (request == 101) importScreen();
            });
      return;
    }
    if (!parent) {
      toast("请重新进入家长模式");
      return;
    }
    safe(
        () -> {
          if (request == 101) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null)
              for (int i = 0; i < data.getClipData().getItemCount(); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            JSONArray photos = store.state.optJSONArray("pendingImages");
            if (photos == null) photos = new JSONArray();
            if (photos.length() + uris.size() > 8) throw new Exception("一次课程最多 8 张图片");
            for (Uri uri : uris) {
              BitmapFactory.Options opts = new BitmapFactory.Options();
              opts.inJustDecodeBounds = true;
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
              }
              if (opts.outWidth <= 0 || opts.outHeight <= 0)
                throw new Exception("无法读取图片，请选择 JPG 或 PNG");
              int sample = 1;
              while (Math.max(opts.outWidth, opts.outHeight) / sample > 2200) sample *= 2;
              opts.inSampleSize = sample;
              opts.inJustDecodeBounds = false;
              Bitmap bitmap;
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, opts);
              }
              if (bitmap == null) throw new Exception("图片解码失败");
              float scale = Math.min(1f, 1800f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
              Bitmap scaled =
                  Bitmap.createScaledBitmap(
                      bitmap,
                      Math.max(1, (int) (bitmap.getWidth() * scale)),
                      Math.max(1, (int) (bitmap.getHeight() * scale)),
                      true);
              String name = "images/" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
              try (OutputStream out = new FileOutputStream(new File(store.root, name))) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out);
              }
              if (scaled != bitmap) scaled.recycle();
              bitmap.recycle();
              photos.put(name);
            }
            store.state.put("pendingImages", photos);
            store.save();
            status = "";
            importScreen();
          }
          if (request == 102) {
            String txt;
            try (InputStream input = getContentResolver().openInputStream(data.getData())) {
              byte[] bytes = Store.bytes(input, 16384);
              if (bytes.length > 16384) throw new Exception("配置文件过大");
              txt = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            Matcher url = Pattern.compile("https?://\\S+").matcher(txt);
            String endpoint = url.find() ? url.group() : "https://api.xiaomimimo.com/v1";
            Matcher k = Pattern.compile("sk-[A-Za-z0-9_-]+").matcher(txt);
            String secret = k.find() ? k.group() : "";
            if (secret.isEmpty()) {
              Matcher generic =
                  Pattern.compile(
                          "(?m)^\\s*(?:api[_ -]?key\\s*[:=]\\s*)?([A-Za-z0-9_-]{24,})\\s*$",
                          Pattern.CASE_INSENSITIVE)
                      .matcher(txt);
              if (generic.find()) secret = generic.group(1);
            }
            if (secret.isEmpty()) throw new Exception("配置文件中没有识别到 API Key");
            if (!endpoint.startsWith("https://")) throw new Exception("API 地址必须使用 HTTPS");
            secrets.set(secret);
            getSharedPreferences("config", 0)
                .edit()
                .putString("base", endpoint.replaceAll("/+$", ""))
                .commit();
            status = "配置已导入，密钥已加密保存";
            settings();
            toast(status);
          }
          if (request == 103) {
            Uri uri = data.getData();
            runJob(
                "正在导出课程和学习记录…",
                () -> {
                  for (JSONObject c : store.courses(false))
                    for (String t : audio.texts(c)) audio.local(t);
                  try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    store.exportZip(out);
                  }
                },
                () -> {
                  settings();
                  toast("备份已导出");
                });
          }
          if (request == 104) {
            Uri uri = data.getData();
            runJob(
                "正在校验并恢复备份…",
                () -> {
                  try (InputStream input = getContentResolver().openInputStream(uri)) {
                    store.importZip(input);
                  }
                },
                () -> {
                  settings();
                  toast("备份已恢复");
                });
          }
        });
  }

  void goBack() {
    if (busy) {
      toast("制作任务正在进行，图片和已完成素材会保留");
      return;
    }
    audio.stop();
    safe(
        () -> {
          switch (page) {
            case "quiz" -> {
              saveSession();
              if (reviewing) reviews();
              else path(sessionCourse);
            }
            case "path", "result" -> home();
            case "materials" -> {
              if (parent) courses();
              else path(selectedId);
            }
            case "import" -> courses();
            default -> {
              if (parent) dashboard();
              else home();
            }
          }
        });
  }

  @Override
  public void onBackPressed() {
    if (page.equals("home") || page.equals("dashboard")) {
      super.onBackPressed();
    } else goBack();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (page.equals("quiz")) activeTick = SystemClock.elapsedRealtime();
  }

  void accrueClock() {
    if (activeTick != 0) {
      long now = SystemClock.elapsedRealtime();
      activeMillis += Math.max(0, now - activeTick);
      activeTick = now;
    }
  }

  void pauseClock() {
    accrueClock();
    activeTick = 0;
    if (store != null && store.state.has("session"))
      safe(
          () -> {
            store.state.getJSONObject("session").put("activeMillis", activeMillis);
            store.save();
          });
  }

  @Override
  protected void onPause() {
    setApiKeyVisible(false);
    if (page.equals("quiz")) pauseClock();
    super.onPause();
    if (audio != null) audio.stop();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (parent && !externalPending) {
      parent = false;
      relock = true;
    }
  }

  @Override
  protected void onRestart() {
    super.onRestart();
    if (relock) {
      relock = false;
      home();
    }
  }

  @Override
  protected void onDestroy() {
    if (lastToast != null) lastToast.cancel();
    if (audio != null) audio.close();
    jobs.shutdownNow();
    super.onDestroy();
  }

  static final class ContentScrollView extends ScrollView {
    Runnable onManualScroll;
    float downY;
    final int touchSlop;

    ContentScrollView(Context c) {
      super(c);
      touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downY = event.getY();
      else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
          && Math.abs(event.getY() - downY) > touchSlop
          && onManualScroll != null) {
        onManualScroll.run();
      }
      return super.dispatchTouchEvent(event);
    }
  }

  static final class ContentLayout extends LinearLayout {
    ContentLayout(Context context) {
      super(context);
      setOrientation(VERTICAL);
    }

    @Override
    protected void onMeasure(int width, int height) {
      int max = (int) (760 * getResources().getDisplayMetrics().density);
      super.onMeasure(
          MeasureSpec.makeMeasureSpec(
              Math.min(MeasureSpec.getSize(width), max), MeasureSpec.EXACTLY),
          height);
    }
  }

  /** Wraps measured word buttons at the actual available width, including after rotation. */
  static final class TokenFlow extends ViewGroup {
    final int gap;

    TokenFlow(Context context) {
      super(context);
      gap = (int) (8 * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, rowHeight = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        child.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (x > 0 && x + child.getMeasuredWidth() > width) {
          x = 0;
          y += rowHeight + gap;
          rowHeight = 0;
        }
        x += child.getMeasuredWidth() + gap;
        rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
      }
      setMeasuredDimension(width, resolveSize(y + rowHeight, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      int width = right - left, x = 0, y = 0, rowHeight = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
        if (x > 0 && x + w > width) {
          x = 0;
          y += rowHeight + gap;
          rowHeight = 0;
        }
        child.layout(x, y, x + w, y + h);
        x += w + gap;
        rowHeight = Math.max(rowHeight, h);
      }
    }
  }
}
