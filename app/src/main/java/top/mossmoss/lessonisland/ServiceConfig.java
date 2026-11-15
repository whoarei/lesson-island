package top.mossmoss.lessonisland;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.*;

/** A settings snapshot lets connection and voice tests use the form without saving credentials. */
final class ServiceConfig {
  static final String[] VOICES = {
    "Mia", "Chloe", "Milo", "Dean", "mimo_default", "冰糖", "茉莉", "苏打", "白桦"
  };
  static final String[] PACES = {"slow", "normal", "fast"};
  static final String[] PACE_LABELS = {"慢速 · 适合跟读", "标准", "较快"};
  final String base, model, tts, key, voice, pace;

  ServiceConfig(String base, String model, String tts, String key, String voice, String pace)
      throws Exception {
    this.base = base.trim().replaceAll("/+$", "");
    URI uri;
    try {
      uri = URI.create(this.base);
    } catch (Exception e) {
      throw new Exception("请输入有效 HTTPS API 地址");
    }
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) throw new Exception("请输入有效 HTTPS API 地址");
    this.model = model.trim();
    this.tts = tts.trim();
    this.key = key.trim();
    this.voice = voice.trim();
    this.pace = pace;
    if (this.model.isEmpty() || this.tts.isEmpty()) throw new Exception("请填写模型名称");
    if (this.voice.isEmpty()) throw new Exception("请选择或填写音色");
    if (!Arrays.asList(PACES).contains(pace)) throw new Exception("请选择朗读语速");
  }

  static ServiceConfig load(Context context) throws Exception {
    SharedPreferences config = context.getSharedPreferences("config", 0);
    return new ServiceConfig(
        config.getString("base", "https://api.xiaomimimo.com/v1"),
        config.getString("model", "mimo-v2.6-flash"),
        config.getString("tts", "mimo-v2.5-tts"),
        new Secrets(context).get(),
        config.getString("ttsVoice", "Mia"),
        config.getString("ttsPace", "slow"));
  }

  void save(Context context, Secrets secrets, String enteredKey) throws Exception {
    if (!enteredKey.trim().isEmpty()) secrets.set(enteredKey.trim());
    if (!context
        .getSharedPreferences("config", 0)
        .edit()
        .putString("base", base)
        .putString("model", model)
        .putString("tts", tts)
        .putString("ttsVoice", voice)
        .putString("ttsPace", pace)
        .commit()) throw new Exception("设置保存失败，请重试");
  }

  String speechInstruction() {
    String rate =
        pace.equals("slow")
            ? "Use a slow, clear teaching pace with natural pauses."
            : pace.equals("fast")
                ? "Use a brisk but clear conversational pace."
                : "Use a normal, natural speaking pace.";
    return "Read the exact English text for a child learning English. "
        + rate
        + " Do not add, remove, translate, or paraphrase any words.";
  }
}
