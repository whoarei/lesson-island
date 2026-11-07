package top.mossmoss.lessonisland;

import android.content.*;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class Audio {
  final Context context;
  final Store store;
  final AiClient ai;
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  private MediaPlayer player;
  private TextToSpeech tts;
  private volatile boolean ready;
  private float speed = 1f;
  private int playbackGeneration;
  private final Handler main = new Handler(Looper.getMainLooper());
  private java.util.function.IntConsumer playbackListener;
  private int activeIndex = -1;

  Audio(Context c, Store s) {
    context = c;
    store = s;
    ai = new AiClient(c);
    tts =
        new TextToSpeech(
            c,
            status -> {
              ready = status == TextToSpeech.SUCCESS;
              if (ready) {
                int result = tts.setLanguage(Locale.US);
                ready = result >= 0;
              }
            });
  }

  List<String> texts(JSONObject course) throws Exception {
    Set<String> set = new LinkedHashSet<>();
    for (String name : new String[] {"sentences", "vocabulary"}) {
      JSONArray a = course.getJSONArray(name);
      for (int i = 0; i < a.length(); i++) set.add(a.getJSONObject(i).getString("en"));
    }
    return new ArrayList<>(set);
  }

  boolean exists(String text) {
    String name = Store.hash(text) + ".mp3";
    File file = new File(store.root, "audio/" + name);
    return file.length() > 200;
  }

  int cached(JSONObject c) throws Exception {
    int n = 0;
    for (String text : texts(c)) if (exists(text)) n++;
    return n;
  }

  synchronized File local(String text) throws Exception {
    String name = Store.hash(text) + ".mp3";
    File file = new File(store.root, "audio/" + name);
    return file.length() > 200 ? file : null;
  }

  void download(JSONObject course, AiClient.Progress progress) throws Exception {
    List<String> texts = texts(course);
    int n = 0;
    for (String text : texts) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (!exists(text)) {
        byte[] data = ai.speech(text);
        android.util.AtomicFile f =
            new android.util.AtomicFile(new File(store.root, "audio/" + Store.hash(text) + ".mp3"));
        FileOutputStream out = f.startWrite();
        try {
          out.write(data);
          f.finishWrite(out);
        } catch (Exception e) {
          f.failWrite(out);
          throw e;
        }
      }
      n++;
      progress.update("朗读音频已就绪 " + n + " / " + texts.size() + "，可离线播放");
    }
  }

  void setSpeed(float value) {
    speed = value;
    if (tts != null) tts.setSpeechRate(speed);
  }

  void play(String text, java.util.function.Consumer<String> error) {
    playList(Collections.singletonList(text), error);
  }

  void playList(List<String> items, java.util.function.Consumer<String> error) {
    playList(items, 0, null, error);
  }

  /** Includes preparing and system speech; every stop/end clears the highlighted item. */
  void playList(
      List<String> items,
      int start,
      java.util.function.IntConsumer listener,
      java.util.function.Consumer<String> error) {
    stop();
    int token = playbackGeneration;
    playbackListener = listener;
    playAt(new ArrayList<>(items), start, token, error);
  }

  void playAt(List<String> items, int index, int token, java.util.function.Consumer<String> error) {
    if (token != playbackGeneration) return;
    if (index < 0 || index >= items.size()) {
      stop();
      return;
    }
    activeIndex = index;
    if (playbackListener != null) playbackListener.accept(index);
    try {
      File file = local(items.get(index));
      if (file != null) {
        player = new MediaPlayer();
        player.setAudioAttributes(
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        player.setDataSource(file.getAbsolutePath());
        player.setOnPreparedListener(
            p -> {
              if (token != playbackGeneration || player != p) return;
              try {
                p.setPlaybackParams(new PlaybackParams().setSpeed(speed));
                p.start();
              } catch (Exception e) {
                fail(token, error, "音频播放失败，请重试");
              }
            });
        player.setOnCompletionListener(
            p -> {
              if (token != playbackGeneration || player != p) return;
              p.release();
              player = null;
              playAt(items, index + 1, token, error);
            });
        player.setOnErrorListener(
            (p, a, b) -> {
              if (player == p) fail(token, error, "音频播放失败，请重试");
              return true;
            });
        player.prepareAsync();
      } else if (ready) {
        tts.setSpeechRate(speed);
        String utterance = "island-" + token + "-" + index;
        tts.setOnUtteranceProgressListener(
            new UtteranceProgressListener() {
              @Override
              public void onStart(String id) {}

              @Override
              public void onDone(String id) {
                if (utterance.equals(id)) main.post(() -> playAt(items, index + 1, token, error));
              }

              @Override
              public void onError(String id) {
                if (utterance.equals(id)) main.post(() -> fail(token, error, "系统朗读失败，请重试"));
              }
            });
        if (tts.speak(items.get(index), TextToSpeech.QUEUE_FLUSH, null, utterance)
            == TextToSpeech.ERROR) fail(token, error, "系统朗读失败，请重试");
      } else fail(token, error, "此音频尚未下载，且设备没有可用的英语语音。请让家长下载音频");
    } catch (Exception e) {
      fail(token, error, "无法播放此音频，请让家长重新下载");
    }
  }

  private void fail(int token, java.util.function.Consumer<String> error, String message) {
    if (token != playbackGeneration) return;
    stop();
    error.accept(message);
  }

  void stop() {
    playbackGeneration++;
    if (player != null) {
      try {
        player.release();
      } catch (Exception ignored) {
      }
      player = null;
    }
    if (tts != null) tts.stop();
    activeIndex = -1;
    java.util.function.IntConsumer previous = playbackListener;
    playbackListener = null;
    if (previous != null) previous.accept(-1);
  }

  int currentIndex() {
    return activeIndex;
  }

  boolean isPlaying() {
    try {
      return (player != null && player.isPlaying()) || (tts != null && tts.isSpeaking());
    } catch (IllegalStateException ignored) {
      return false;
    }
  }

  void close() {
    stop();
    worker.shutdownNow();
    if (tts != null) tts.shutdown();
  }
}
