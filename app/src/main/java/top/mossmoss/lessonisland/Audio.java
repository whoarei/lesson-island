package top.mossmoss.lessonisland;

import android.content.*;
import android.media.*;
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
    if (file.length() > 200) return true;
    try (InputStream ignored = context.getAssets().open("audio/" + name)) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  int cached(JSONObject c) throws Exception {
    int n = 0;
    for (String text : texts(c)) if (exists(text)) n++;
    return n;
  }

  synchronized File local(String text) throws Exception {
    String name = Store.hash(text) + ".mp3";
    File file = new File(store.root, "audio/" + name);
    if (file.length() > 200) return file;
    try (InputStream asset = context.getAssets().open("audio/" + name)) {
      java.nio.file.Files.copy(
          asset, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return file;
    } catch (FileNotFoundException e) {
      return null;
    }
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
    stop();
    int token = playbackGeneration;
    playAt(items, 0, token, error);
  }

  void playAt(List<String> items, int index, int token, java.util.function.Consumer<String> error) {
    if (index >= items.size() || token != playbackGeneration) return;
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
              if (token != playbackGeneration) {
                p.release();
                return;
              }
              p.setPlaybackParams(new PlaybackParams().setSpeed(speed));
              p.start();
            });
        player.setOnCompletionListener(
            p -> {
              p.release();
              if (player == p) player = null;
              playAt(items, index + 1, token, error);
            });
        player.setOnErrorListener(
            (p, a, b) -> {
              error.accept("音频播放失败，请重试");
              stop();
              return true;
            });
        player.prepareAsync();
      } else if (ready) {
        tts.setSpeechRate(speed);
        tts.speak(items.get(index), TextToSpeech.QUEUE_FLUSH, null, "island");
        if (items.size() > 1) error.accept("此句使用系统朗读；请先下载完整音频以使用全文连播");
      } else error.accept("此音频尚未下载，且设备没有可用的英语语音。请让家长下载音频");
    } catch (Exception e) {
      error.accept("无法播放此音频，请让家长重新下载");
    }
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
  }

  boolean isPlaying() {
    try {
      return player != null && player.isPlaying();
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
