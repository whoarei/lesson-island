package top.mossmoss.lessonisland;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.text.*;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Audio replacements are staged in cache and only attached to the item when its form is saved. */
final class ItemAudioEditor implements AutoCloseable {
  final MainActivity host;
  final EditText english;
  final JSONObject original;
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final TextView status;
  final Button preview, generate, record, undo;
  AlertDialog dialog;
  Future<?> generation;
  MediaRecorder recorder;
  File pending, recording;
  String pendingText = "",
      pendingSource = "",
      pendingVoice = "",
      pendingPace = "",
      recordingText = "",
      installed = "";
  boolean generating, previewing, closed, saved;

  ItemAudioEditor(MainActivity host, LinearLayout form, EditText english, JSONObject original)
      throws Exception {
    this.host = host;
    this.english = english;
    this.original = original;
    host.space(form, 18);
    host.add(form, host.text("朗读音频", 18, MainActivity.INK, true));
    status = host.text("", 13, MainActivity.MUTED, false);
    host.space(form, 7);
    host.add(form, status);
    LinearLayout row = host.row();
    preview = host.button("试听当前音频", false, () -> togglePreview());
    generate = host.button("重新生成音频", false, this::generate);
    host.weight(row, preview);
    View gap = new View(host);
    row.addView(gap, new LinearLayout.LayoutParams(host.dp(8), 1));
    host.weight(row, generate);
    host.space(form, 10);
    host.add(form, row);
    record =
        host.button(
            "开始录音",
            false,
            () -> {
              if (recorder != null) stopRecording(true);
              else requestRecording();
            });
    host.space(form, 8);
    host.add(form, record);
    undo =
        host.button(
            "撤销本次音频替换",
            false,
            () -> {
              host.audio.stop();
              clearPending();
              describe();
            });
    host.space(form, 8);
    host.add(form, undo);
    english.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (closed) return;
            host.audio.stop();
            if (pending != null && !pendingText.equals(text())) clearPending();
            describe();
          }

          public void afterTextChanged(Editable text) {}
        });
    describe();
  }

  String text() {
    return english.getText().toString().trim();
  }

  void bind(AlertDialog dialog) {
    this.dialog = dialog;
    dialog.setOnDismissListener(
        d -> {
          close();
          if (host.itemAudioEditor == this) host.itemAudioEditor = null;
        });
    refresh();
  }

  void describe() {
    if (closed) return;
    if (pending != null)
      status.setText(pendingSource.equals("recorded") ? "新录音已准备好，试听满意后保存。" : "新语音已生成，试听满意后保存。");
    else if (!text().equals(original.optString("en"))) status.setText("英文已修改，可重新生成或录制对应音频。");
    else status.setText("可试听现有音频，也可重新生成或直接录音。新音频在保存后生效。");
    refresh();
  }

  void refresh() {
    if (closed) return;
    boolean recordingNow = recorder != null;
    preview.setEnabled(!generating && !recordingNow);
    generate.setEnabled(!generating && !recordingNow);
    record.setEnabled(!generating);
    undo.setVisibility(pending != null && !recordingNow && !generating ? View.VISIBLE : View.GONE);
    preview.setText(previewing ? "停止试听" : "试听当前音频");
    generate.setText(generating ? "正在生成…" : "重新生成音频");
    record.setText(recordingNow ? "停止录音" : "开始录音");
    english.setEnabled(!generating && !recordingNow);
    if (dialog != null && dialog.getButton(-1) != null) {
      dialog.getButton(-1).setEnabled(!generating && !recordingNow);
      if (dialog.getButton(-3) != null)
        dialog.getButton(-3).setEnabled(!generating && !recordingNow);
    }
  }

  void togglePreview() throws Exception {
    if (previewing) {
      host.audio.stop();
      return;
    }
    File file =
        pending != null
            ? pending
            : host.audio.local(
                text(), text().equals(original.optString("en")) ? Audio.itemFile(original) : "");
    if (file == null) throw new Exception("暂无对应音频，请先重新生成或录音");
    host.audio.setSpeed(1f);
    host.audio.playFile(
        file,
        index -> {
          previewing = index >= 0;
          refresh();
        },
        message -> {
          if (!closed) status.setText(message);
        });
  }

  void generate() throws Exception {
    if (generating || recorder != null) return;
    String value = text();
    if (value.isEmpty()) throw new Exception("请先填写英文");
    ServiceConfig config = ServiceConfig.load(host);
    host.audio.stop();
    generating = true;
    status.setText("正在用当前语速、音色生成新音频，原音频仍保留…");
    refresh();
    generation =
        worker.submit(
            () -> {
              File file = null;
              String failure = null;
              try {
                file = host.audio.generate(value, config);
              } catch (Exception e) {
                failure =
                    e instanceof InterruptedException
                        ? "生成已取消"
                        : e.getMessage() == null ? "生成失败，请重试" : e.getMessage();
              }
              File result = file;
              String error = failure;
              host.runOnUiThread(
                  () -> {
                    if (closed) {
                      if (result != null) result.delete();
                      return;
                    }
                    generating = false;
                    if (error != null) {
                      status.setText(host.getString(R.string.audio_edit_failed, error));
                      refresh();
                    } else {
                      replacePending(result, value, "tts", config.voice, config.pace);
                      describe();
                    }
                  });
            });
  }

  void requestRecording() throws Exception {
    if (text().isEmpty()) throw new Exception("请先填写这段录音对应的英文");
    if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      host.requestMicrophone(this);
      return;
    }
    startRecording();
  }

  void startRecording() {
    if (closed || !host.parent || generating || recorder != null) return;
    if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      status.setText("请先允许麦克风权限，再开始录音。原音频保留。");
      return;
    }
    host.audio.stop();
    ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(english.getWindowToken(), 0);
    try {
      recordingText = text();
      if (recordingText.isEmpty()) throw new Exception("请先填写英文");
      recording = File.createTempFile("recording_", ".m4a", host.getCacheDir());
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setAudioSamplingRate(44100);
      recorder.setAudioEncodingBitRate(128000);
      recorder.setOutputFile(recording.getAbsolutePath());
      recorder.setMaxDuration(120000);
      recorder.setOnInfoListener(
          (r, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
              host.runOnUiThread(() -> stopRecording(true));
          });
      recorder.setOnErrorListener(
          (r, what, extra) ->
              host.runOnUiThread(
                  () -> {
                    stopRecording(false);
                    if (!closed) status.setText("录音未完成，原音频保留。请检查麦克风后重试。");
                  }));
      recorder.prepare();
      recorder.start();
      status.setText("录音中，最长 2 分钟。完成后点「停止录音」，可先试听再保存。");
      refresh();
    } catch (Exception e) {
      stopRecording(false);
      status.setText("无法开始录音，请检查麦克风权限或占用情况。原音频保留。");
    }
  }

  void stopRecording(boolean keep) {
    if (recorder == null) return;
    MediaRecorder current = recorder;
    recorder = null;
    File file = recording;
    recording = null;
    boolean valid = keep;
    try {
      current.stop();
    } catch (RuntimeException ignored) {
    } finally {
      current.release();
    }
    if (valid) {
      try {
        Audio.validateClip(file);
        replacePending(file, recordingText, "recorded", "", "");
        describe();
        return;
      } catch (Exception ignored) {
      }
    }
    if (file != null) file.delete();
    if (!closed) {
      status.setText(keep ? "录音过短或未完成，请重新录制。原音频保留。" : "录音已停止，未覆盖原音频。");
      refresh();
    }
  }

  void replacePending(File file, String text, String source, String voice, String pace) {
    host.audio.stop();
    clearPending();
    pending = file;
    pendingText = text;
    pendingSource = source;
    pendingVoice = voice;
    pendingPace = pace;
  }

  void clearPending() {
    if (pending != null) pending.delete();
    pending = null;
    if (!installed.isEmpty() && !saved) new File(host.store.root, installed).delete();
    installed = "";
  }

  void attachTo(JSONObject value) throws Exception {
    if (pending != null) {
      if (!pendingText.equals(value.getString("en"))) throw new Exception("英文已变化，请重新生成或录音");
      if (installed.isEmpty()) installed = host.audio.installClip(pending);
      value
          .put("audioFile", installed)
          .put("audioText", pendingText)
          .put("audioSource", pendingSource)
          .put("audioVoice", pendingVoice)
          .put("audioPace", pendingPace);
    } else if (value.getString("en").equals(original.optString("en"))
        && !Audio.itemFile(original).isEmpty()) {
      for (String key :
          new String[] {"audioFile", "audioText", "audioSource", "audioVoice", "audioPace"})
        if (original.has(key)) value.put(key, original.get(key));
    }
  }

  void markSaved() {
    saved = true;
  }

  void pause() {
    stopRecording(false);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (generation != null) generation.cancel(true);
    worker.shutdownNow();
    stopRecording(false);
    host.audio.stop();
    clearPending();
  }
}
