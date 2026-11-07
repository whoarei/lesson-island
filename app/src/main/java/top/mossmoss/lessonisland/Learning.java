package top.mossmoss.lessonisland;

import java.text.Normalizer;
import java.util.*;

/** Deterministic learning rules, independent from the Android UI. */
public final class Learning {
  public static final String[] STAGES = {"初识课文", "词汇探险", "短语搭桥", "听力寻宝", "句子工坊", "理解挑战"};
  public static final String[] HINTS = {
    "读懂每一句，认识故事", "让重点单词成为你的伙伴", "把词语连成有用的表达", "仔细听，再找到正确的意思", "听音拼写 · 排列完整句子", "用课文里的线索解开谜题"
  };
  public static final long DAY = 86400000L;

  public static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replace('’', '\'')
        .replaceAll("[.!?,;:\"“”]", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  public static boolean matches(String entered, String expected) {
    return !normalize(entered).isEmpty() && normalize(entered).equals(normalize(expected));
  }

  public static long nextReview(long now, int successfulReviews) {
    int[] days = {1, 3, 7, 14, 30};
    return now + DAY * days[Math.max(0, Math.min(successfulReviews, days.length - 1))];
  }

  public static int accuracy(int correct, int attempted) {
    return attempted == 0 ? 0 : (int) Math.round(100.0 * correct / attempted);
  }

  public static List<String> tokens(String sentence) {
    return Arrays.asList(sentence.trim().split("\\s+"));
  }

  public static final class Question {
    public String id, type, prompt, answer, explanation, audio;
    public List<String> options = new ArrayList<>();

    public Question(
        String id, String type, String prompt, String answer, String explanation, String audio) {
      this.id = id;
      this.type = type;
      this.prompt = prompt;
      this.answer = answer;
      this.explanation = explanation;
      this.audio = audio;
    }
  }
}
