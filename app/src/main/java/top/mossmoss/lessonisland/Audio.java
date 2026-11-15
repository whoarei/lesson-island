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
  private boolean playerPrepared;
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
    List<String> texts = new ArrayList<>();
    for (Clip clip : clips(course)) texts.add(clip.text);
    return texts;
  }

  static String itemFile(JSONObject item) {
    return item.optString("audioText").equals(item.optString("en"))
        ? item.optString("audioFile")
        : "";
  }

  static boolean validRef(String ref) {
    return ref.matches("audio/[a-f0-9]{64}\\.(mp3|wav|m4a)");
  }

  static final class Clip {
    final String text, ref;
    final File preview;

    Clip(String text, String ref) {
      this.text = text;
      this.ref = ref;
      preview = null;
    }

    Clip(File preview) {
      this.preview = preview;
      text = "";
      ref = "";
    }
  }

  List<Clip> clips(JSONObject course) throws Exception {
    Map<String, Clip> set = new LinkedHashMap<>();
    for (String name : new String[] {"sentences", "vocabulary"}) {
      JSONArray a = course.getJSONArray(name);
      for (int i = 0; i < a.length(); i++) {
        JSONObject item = a.getJSONObject(i);
        String text = item.getString("en"), ref = itemFile(item);
        set.put(ref.isEmpty() ? "text:" + text : ref, new Clip(text, ref));
      }
    }
    return new ArrayList<>(set.values());
  }

  boolean exists(String text) {
    String name = Store.hash(text) + ".mp3";
    File file = new File(store.root, "audio/" + name);
    return file.length() > 200;
  }

  int cached(JSONObject c) throws Exception {
    int n = 0;
    for (Clip clip : clips(c)) if (local(clip.text, clip.ref) != null) n++;
    return n;
  }

  synchronized File local(String text) throws Exception {
    String name = Store.hash(text) + ".mp3";
    File file = new File(store.root, "audio/" + name);
    return file.length() > 200 ? file : null;
  }

  synchronized File local(String text, String ref) throws Exception {
    if (ref == null || ref.isEmpty()) return local(text);
    if (!validRef(ref)) throw new Exception("素材音频路径无效");
    File file = new File(store.root, ref);
    return file.length() > 200 ? file : null;
  }

  static void validateClip(File file) throws Exception {
    if (file == null || file.length() < 200) throw new Exception("录音过短或音频为空，请重试");
    MediaMetadataRetriever reader = new MediaMetadataRetriever();
    try {
      reader.setDataSource(file.getAbsolutePath());
      String duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      if (duration == null || Long.parseLong(duration) < 150) throw new Exception("录音过短，请重新录制");
    } catch (Exception e) {
      throw new Exception("音频不可用或录音过短，请重新生成或录制");
    } finally {
      reader.release();
    }
  }

  File generate(String text, ServiceConfig config) throws Exception {
    byte[] bytes = new AiClient(context, config).speech(text);
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    File file = File.createTempFile("speech_", ".mp3", context.getCacheDir());
    try {
      java.nio.file.Files.write(file.toPath(), bytes);
      validateClip(file);
      return file;
    } catch (Exception e) {
      file.delete();
      throw e;
    }
  }

  String installClip(File source) throws Exception {
    validateClip(source);
    String ext =
        source.getName().endsWith(".m4a")
            ? ".m4a"
            : source.getName().endsWith(".wav") ? ".wav" : ".mp3";
    String ref = "audio/" + Store.hash(UUID.randomUUID().toString()) + ext;
    android.util.AtomicFile atomic = new android.util.AtomicFile(new File(store.root, ref));
    FileOutputStream out = atomic.startWrite();
    try {
      java.nio.file.Files.copy(source.toPath(), out);
      atomic.finishWrite(out);
    } catch (Exception e) {
      atomic.failWrite(out);
      throw e;
    }
    return ref;
  }

  void download(JSONObject course, AiClient.Progress progress) throws Exception {
    List<Clip> clips = clips(course);
    int n = 0;
    for (Clip clip : clips) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (local(clip.text, clip.ref) == null) {
        if (!clip.ref.isEmpty()) throw new Exception("有一条自定义音频缺失，请在校对素材中重新生成或录制");
        byte[] data = ai.speech(clip.text);
        android.util.AtomicFile f =
            new android.util.AtomicFile(
                new File(store.root, "audio/" + Store.hash(clip.text) + ".mp3"));
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
      progress.update("朗读音频已就绪 " + n + " / " + clips.size() + "，可离线播放");
    }
  }

  void setSpeed(float value) {
    speed = value;
    if (tts != null) tts.setSpeechRate(speed);
    if (player != null && playerPrepared) {
      try {
        player.setPlaybackParams(new PlaybackParams().setSpeed(speed));
      } catch (IllegalStateException ignored) {
        // A finishing clip may already be releasing; the next clip uses the new speed.
      }
    }
  }

  void play(String text, java.util.function.Consumer<String> error) {
    playList(Collections.singletonList(text), error);
  }

  void play(String text, String ref, java.util.function.Consumer<String> error) {
    playList(Collections.singletonList(text), Collections.singletonList(ref), 0, null, error);
  }

  void playFile(File file, java.util.function.Consumer<String> error) {
    playFile(file, null, error);
  }

  void playFile(
      File file,
      java.util.function.IntConsumer listener,
      java.util.function.Consumer<String> error) {
    stop();
    playbackListener = listener;
    playAt(Collections.singletonList(new Clip(file)), 0, playbackGeneration, error);
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
    playList(items, null, start, listener, error);
  }

  void playList(
      List<String> items,
      List<String> refs,
      int start,
      java.util.function.IntConsumer listener,
      java.util.function.Consumer<String> error) {
    stop();
    int token = playbackGeneration;
    playbackListener = listener;
    List<Clip> clips = new ArrayList<>();
    for (int i = 0; i < items.size(); i++)
      clips.add(new Clip(items.get(i), refs == null ? "" : refs.get(i)));
    playAt(clips, start, token, error);
  }

  void playAt(List<Clip> items, int index, int token, java.util.function.Consumer<String> error) {
    if (token != playbackGeneration) return;
    if (index < 0 || index >= items.size()) {
      stop();
      return;
    }
    activeIndex = index;
    if (playbackListener != null) playbackListener.accept(index);
    try {
      Clip clip = items.get(index);
      File file = clip.preview != null ? clip.preview : local(clip.text, clip.ref);
      if (file == null && !clip.ref.isEmpty()) throw new Exception("素材录音缺失");
      if (file != null) {
        playerPrepared = false;
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
                playerPrepared = true;
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
              playerPrepared = false;
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
        if (tts.speak(clip.text, TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.ERROR)
          fail(token, error, "系统朗读失败，请重试");
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
    playerPrepared = false;
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
