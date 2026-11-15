package top.mossmoss.lessonisland;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.RelativeSizeSpan;
import android.view.*;
import android.widget.TextView;
import java.util.*;
import java.util.function.IntConsumer;
import org.json.*;

/** A compact bilingual passage; each English sentence and its translation start on a new line. */
@SuppressLint(
    "ViewConstructor") // Passage data and actions are supplied in code, never through XML.
final class PassageView extends TextView {
  final List<SentenceLink> sentences = new ArrayList<>();
  final List<SentenceLink> translations = new ArrayList<>();
  final List<SentenceLink> links = new ArrayList<>();
  final SentenceMovement movement = new SentenceMovement();
  final boolean editable;

  PassageView(Context context, JSONArray source, IntConsumer play, IntConsumer edit)
      throws JSONException {
    super(context);
    editable = edit != null;
    setTextSize(21);
    setTextColor(MainActivity.INK);
    setTypeface(Typeface.create("sans-serif-rounded", Typeface.NORMAL));
    setLineSpacing(AdventureArt.dp(context, 4), 1.05f);
    setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    setTextDirection(View.TEXT_DIRECTION_LTR);
    setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
    setGravity(Gravity.TOP | Gravity.START);
    setHighlightColor(android.graphics.Color.TRANSPARENT);
    setFontFeatureSettings("kern");
    SpannableStringBuilder passage = new SpannableStringBuilder();
    for (int i = 0; i < source.length(); i++) {
      JSONObject item = source.getJSONObject(i);
      if (i > 0) passage.append("\n");
      int start = passage.length();
      passage.append(item.getString("en").trim());
      SentenceLink sentence = new SentenceLink(i, MainActivity.INK, play, edit);
      sentences.add(sentence);
      links.add(sentence);
      passage.setSpan(sentence, start, passage.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      passage.append("\n");
      int translationStart = passage.length();
      passage.append(item.getString("zh").trim());
      SentenceLink translation = new SentenceLink(i, MainActivity.MUTED, play, edit);
      translations.add(translation);
      links.add(translation);
      passage.setSpan(
          translation, translationStart, passage.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      passage.setSpan(
          new RelativeSizeSpan(16f / 21f),
          translationStart,
          passage.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    setText(passage, BufferType.SPANNABLE);
    setMovementMethod(movement);
    // The movement method owns long presses so holding a link never also fires a tap or text menu.
    setLongClickable(false);
  }

  void highlight(int index) {
    for (SentenceLink sentence : links) sentence.playing = sentence.index == index;
    invalidate();
  }

  /** Both the English sentence and its translation, in local view coordinates. */
  boolean sentenceBounds(int index, Rect rect) {
    if (getLayout() == null || index < 0 || index >= sentences.size()) return false;
    Spannable passage = (Spannable) getText();
    int start = passage.getSpanStart(sentences.get(index));
    int end = passage.getSpanEnd(translations.get(index));
    int first = getLayout().getLineForOffset(start),
        last = getLayout().getLineForOffset(Math.max(start, end - 1));
    rect.set(
        getTotalPaddingLeft(),
        getTotalPaddingTop() + getLayout().getLineTop(first),
        getWidth() - getTotalPaddingRight(),
        getTotalPaddingTop() + getLayout().getLineBottom(last));
    return true;
  }

  @Override
  protected void onDetachedFromWindow() {
    movement.cancel(this);
    super.onDetachedFromWindow();
  }

  static final class SentenceLink extends ClickableSpan {
    final int index, color;
    final IntConsumer play, edit;
    boolean playing;

    SentenceLink(int index, int color, IntConsumer play, IntConsumer edit) {
      this.index = index;
      this.color = color;
      this.play = play;
      this.edit = edit;
    }

    @Override
    public void onClick(View view) {
      play.accept(index);
    }

    @Override
    public void updateDrawState(TextPaint paint) {
      paint.setUnderlineText(false);
      paint.setColor(playing ? MainActivity.GREEN : color);
      paint.bgColor = playing ? AdventureArt.SKY : android.graphics.Color.TRANSPARENT;
    }
  }

  /**
   * Link taps remain accessible through ClickableSpan; long press uses the same exact text range.
   */
  final class SentenceMovement extends LinkMovementMethod {
    SentenceLink pressed;
    Runnable longPress;
    float downX, downY;
    boolean consumed;

    void cancel(TextView view) {
      if (longPress != null) view.removeCallbacks(longPress);
      longPress = null;
      pressed = null;
      view.setPressed(false);
    }

    SentenceLink hit(TextView view, Spannable passage, MotionEvent event) {
      Layout layout = view.getLayout();
      if (layout == null) return null;
      float x = event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
      float y = event.getY() - view.getTotalPaddingTop() + view.getScrollY();
      if (y < 0 || y >= layout.getHeight()) return null;
      int line = layout.getLineForVertical((int) y);
      if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
      int offset = layout.getOffsetForHorizontal(line, x);
      if (offset > layout.getLineStart(line) && layout.getPrimaryHorizontal(offset) > x) offset--;
      for (SentenceLink sentence : links)
        if (offset >= passage.getSpanStart(sentence) && offset < passage.getSpanEnd(sentence))
          return sentence;
      return null;
    }

    @Override
    public boolean onTouchEvent(TextView view, Spannable passage, MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN -> {
          cancel(view);
          consumed = false;
          pressed = hit(view, passage, event);
          if (pressed == null) return false;
          downX = event.getX();
          downY = event.getY();
          view.setPressed(true);
          SentenceLink target = pressed;
          longPress =
              () -> {
                consumed = true;
                if (target.edit != null) {
                  view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                  target.edit.accept(target.index);
                }
              };
          view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
          return true;
        }
        case MotionEvent.ACTION_MOVE -> {
          int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
          if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
            cancel(view);
            consumed = true;
          }
          return pressed != null;
        }
        case MotionEvent.ACTION_UP -> {
          SentenceLink target = pressed;
          boolean click = !consumed && target != null && target == hit(view, passage, event);
          cancel(view);
          if (click) {
            view.performClick();
            target.onClick(view);
          }
          return target != null || consumed;
        }
        case MotionEvent.ACTION_CANCEL -> {
          cancel(view);
          return true;
        }
        default -> {
          return false;
        }
      }
    }
  }
}
