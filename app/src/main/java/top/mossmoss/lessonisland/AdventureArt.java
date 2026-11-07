package top.mossmoss.lessonisland;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;

/** Small, scalable illustrations and tactile controls. No network or image assets required. */
@SuppressLint("ViewConstructor") // These views are constructed with data, never inflated from XML.
final class AdventureArt {
  static final int INK = Color.rgb(36, 59, 74),
      TEAL = Color.rgb(12, 119, 99),
      LEAF = Color.rgb(151, 224, 84),
      LEAF_EDGE = Color.rgb(91, 166, 44),
      LINE = Color.rgb(218, 231, 237),
      SKY = Color.rgb(229, 246, 255),
      SUN = Color.rgb(255, 206, 80),
      LILAC = Color.rgb(237, 232, 255),
      PEACH = Color.rgb(255, 239, 219);

  static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  /** An opaque face over a solid lower edge, with a visibly depressed pressed state. */
  static final class Face extends Drawable {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rect = new RectF();
    final float radius, depth, stroke;
    final int face, edge;
    boolean pressed, enabled = true;

    Face(Context c, int face, int edge, int radius, int depth) {
      this.face = face;
      this.edge = edge;
      this.radius = dp(c, radius);
      this.depth = dp(c, depth);
      stroke = dp(c, 1.5f);
    }

    @Override
    public boolean isStateful() {
      return true;
    }

    @Override
    protected boolean onStateChange(int[] states) {
      boolean p = false, e = false;
      for (int state : states) {
        if (state == android.R.attr.state_pressed) p = true;
        if (state == android.R.attr.state_enabled) e = true;
      }
      if (p == pressed && e == enabled) return false;
      pressed = p;
      enabled = e;
      invalidateSelf();
      return true;
    }

    @Override
    public void draw(Canvas c) {
      Rect bounds = getBounds();
      float inset = stroke / 2;
      rect.set(
          bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(edge);
      c.drawRoundRect(rect, radius, radius, paint);
      rect.top += pressed ? depth : 0;
      rect.bottom -= pressed ? 0 : depth;
      paint.setColor(enabled ? face : Color.rgb(238, 243, 246));
      c.drawRoundRect(rect, radius, radius, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(stroke);
      paint.setColor(edge);
      c.drawRoundRect(rect, radius, radius, paint);
      paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
      invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  static void star(Canvas c, Paint p, float x, float y, float radius, int color) {
    Path path = new Path();
    for (int i = 0; i < 10; i++) {
      double angle = -Math.PI / 2 + i * Math.PI / 5;
      float r = i % 2 == 0 ? radius : radius * .47f;
      float px = x + (float) Math.cos(angle) * r, py = y + (float) Math.sin(angle) * r;
      if (i == 0) path.moveTo(px, py);
      else path.lineTo(px, py);
    }
    path.close();
    p.setStyle(Paint.Style.FILL);
    p.setColor(color);
    c.drawPath(path, p);
  }

  static void line(Canvas c, Paint p, int color, float width, float... points) {
    p.setColor(color);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    p.setStrokeWidth(width);
    Path path = new Path();
    path.moveTo(points[0], points[1]);
    for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
    c.drawPath(path, p);
    p.setStyle(Paint.Style.FILL);
  }

  /** Icon vocabulary used in navigation, stages, and achievements. Coordinates: 0..48. */
  static void icon(Canvas c, Paint p, String kind, int color) {
    p.setColor(color);
    p.setStyle(Paint.Style.FILL);
    switch (kind) {
      case "book", "home", "courses" -> {
        c.drawRoundRect(5, 11, 43, 38, 5, 5, p);
        p.setColor(Color.WHITE);
        c.drawRoundRect(9, 8, 23, 33, 3, 3, p);
        c.drawRoundRect(25, 8, 39, 33, 3, 3, p);
        line(c, p, color, 2, 24, 11, 24, 35);
        line(c, p, color, 2, 13, 17, 19, 17);
        line(c, p, color, 2, 29, 17, 35, 17);
        line(c, p, color, 2, 13, 23, 19, 23);
      }
      case "review", "leaf" -> {
        Path leaf = new Path();
        leaf.moveTo(10, 35);
        leaf.cubicTo(1, 16, 22, 5, 40, 9);
        leaf.cubicTo(44, 29, 27, 43, 10, 35);
        leaf.close();
        c.drawPath(leaf, p);
        line(c, p, Color.WHITE, 3, 12, 34, 31, 17);
        line(c, p, Color.WHITE, 2, 22, 25, 22, 17);
        line(c, p, Color.WHITE, 2, 22, 25, 31, 25);
      }
      case "trophy", "profile", "dashboard" -> {
        c.drawRoundRect(13, 7, 35, 28, 7, 7, p);
        line(c, p, color, 4, 13, 10, 6, 10, 6, 19, 15, 24);
        line(c, p, color, 4, 35, 10, 42, 10, 42, 19, 33, 24);
        c.drawRoundRect(21, 26, 27, 37, 2, 2, p);
        c.drawRoundRect(13, 36, 35, 41, 2, 2, p);
        star(c, p, 24, 17, 6, Color.WHITE);
      }
      case "listen" -> {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawArc(9, 7, 39, 37, 180, 180, false, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(7, 22, 15, 38, 4, 4, p);
        c.drawRoundRect(33, 22, 41, 38, 4, 4, p);
        line(c, p, color, 3, 24, 19, 24, 31);
        line(c, p, color, 3, 19, 23, 19, 27);
        line(c, p, color, 3, 29, 23, 29, 27);
      }
      case "puzzle" -> {
        c.drawRoundRect(8, 13, 40, 40, 5, 5, p);
        c.drawCircle(24, 11, 7, p);
        p.setColor(Color.WHITE);
        c.drawCircle(39, 26, 6, p);
      }
      case "write" -> {
        c.save();
        c.rotate(38, 24, 24);
        c.drawRoundRect(19, 5, 29, 34, 3, 3, p);
        Path nib = new Path();
        nib.moveTo(19, 36);
        nib.lineTo(29, 36);
        nib.lineTo(24, 44);
        nib.close();
        c.drawPath(nib, p);
        line(c, p, Color.WHITE, 2, 21, 12, 27, 12);
        c.restore();
      }
      case "lock" -> {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRoundRect(15, 7, 33, 30, 9, 9, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(9, 21, 39, 41, 6, 6, p);
        p.setColor(Color.WHITE);
        c.drawCircle(24, 29, 3, p);
        c.drawRoundRect(22, 29, 26, 36, 2, 2, p);
      }
      case "check" -> line(c, p, color, 6, 11, 24, 20, 33, 37, 15);
      case "settings" -> {
        for (int i = 0; i < 8; i++) {
          c.save();
          c.rotate(i * 45, 24, 24);
          c.drawRoundRect(20, 3, 28, 15, 2, 2, p);
          c.restore();
        }
        c.drawCircle(24, 24, 15, p);
        p.setColor(Color.WHITE);
        c.drawCircle(24, 24, 6, p);
      }
      default -> star(c, p, 24, 24, 19, color);
    }
  }

  static final class Icon extends View {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final String kind;
    final int color;

    Icon(Context c, String kind, int color) {
      super(c);
      this.kind = kind;
      this.color = color;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas c) {
      float s = Math.min(getWidth(), getHeight()) / 48f;
      c.save();
      c.translate((getWidth() - s * 48) / 2, (getHeight() - s * 48) / 2);
      c.scale(s, s);
      icon(c, paint, kind, color);
      c.restore();
    }
  }

  static final class Meter extends View {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final float value;

    Meter(Context c, int current, int total) {
      super(c);
      value = Math.max(0, Math.min(1, current / (float) Math.max(1, total)));
      setContentDescription("进度 " + current + " / " + total);
    }

    @Override
    protected void onDraw(Canvas c) {
      float h = getHeight(), w = getWidth(), fill = w * value;
      paint.setColor(LINE);
      c.drawRoundRect(0, 0, w, h, h / 2, h / 2, paint);
      if (fill <= 0) return;
      paint.setColor(LEAF_EDGE);
      c.drawRoundRect(0, 0, Math.max(h, fill), h, h / 2, h / 2, paint);
      paint.setColor(LEAF);
      c.drawRoundRect(0, 0, Math.max(h, fill), h * .8f, h / 2, h / 2, paint);
      if (fill > h * 2) {
        paint.setColor(Color.rgb(201, 245, 143));
        c.drawRoundRect(h / 2, h * .16f, fill - h / 2, h * .33f, h / 5, h / 5, paint);
      }
    }
  }

  /** 芽芽: an original little island turtle carrying a book, in three encouraging moods. */
  static final class Companion extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final String mood;
    final boolean scene;
    ValueAnimator animator;
    float phase;

    Companion(Context c, String mood, boolean scene) {
      super(c);
      this.mood = mood;
      this.scene = scene;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void animateIfVisible() {
      if (!isAttachedToWindow()
          || getWindowVisibility() != VISIBLE
          || !isShown()
          || !ValueAnimator.areAnimatorsEnabled()) return;
      if (animator != null) return;
      animator = ValueAnimator.ofFloat(0, 1);
      // A brief greeting, then rest. Continuous animations prevent low-end devices from idling.
      animator.setDuration(1000);
      animator.addUpdateListener(
          a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
          });
      animator.start();
    }

    void stop() {
      if (animator != null) {
        animator.cancel();
        animator = null;
      }
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      animateIfVisible();
    }

    @Override
    protected void onDetachedFromWindow() {
      stop();
      super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int v) {
      super.onWindowVisibilityChanged(v);
      if (v == VISIBLE) animateIfVisible();
      else stop();
    }

    void oval(Canvas c, int color, float l, float t, float r, float b) {
      p.setColor(color);
      p.setStyle(Paint.Style.FILL);
      c.drawOval(l, t, r, b, p);
    }

    void round(Canvas c, int color, float l, float t, float r, float b, float radius) {
      p.setColor(color);
      p.setStyle(Paint.Style.FILL);
      c.drawRoundRect(l, t, r, b, radius, radius, p);
    }

    @Override
    protected void onDraw(Canvas c) {
      float s = Math.min(getWidth() / 200f, getHeight() / 190f);
      c.save();
      c.translate((getWidth() - 200 * s) / 2, (getHeight() - 190 * s) / 2);
      c.scale(s, s);
      boolean happy = mood.equals("celebrate");
      float bob = (float) Math.sin(phase * Math.PI * 2) * 2.5f;
      if (scene) {
        oval(c, Color.rgb(192, 232, 244), 9, 148, 191, 183);
        oval(c, Color.rgb(255, 224, 140), 22, 143, 178, 174);
        oval(c, Color.rgb(208, 239, 173), 28, 137, 172, 164);
        p.setColor(SUN);
        c.drawCircle(169, 28, 15, p);
        round(c, Color.WHITE, 9, 40, 49, 51, 6);
        round(c, Color.WHITE, 20, 32, 39, 51, 8);
      }
      if (happy) {
        for (int i = 0; i < 13; i++) {
          float x = 9 + (i * 41 % 180), y = 10 + (i * 23 % 130);
          c.save();
          c.rotate((i * 29) + bob * 2, x, y);
          round(
              c,
              i % 3 == 0 ? SUN : i % 3 == 1 ? Color.rgb(110, 197, 237) : Color.rgb(181, 158, 240),
              x,
              y,
              x + 5,
              y + 10,
              2);
          c.restore();
        }
      }
      c.translate(0, bob);
      oval(c, Color.rgb(72, 157, 105), 48, 65, 154, 153);
      oval(c, Color.rgb(117, 203, 124), 52, 63, 151, 146);
      oval(c, TEAL, 56, 138, 87, 160);
      oval(c, TEAL, 117, 138, 148, 160);
      c.save();
      c.rotate(happy ? 42 : -22, 57, 115);
      oval(c, Color.rgb(81, 184, 150), 22, 101, 77, 126);
      c.restore();
      c.save();
      c.rotate(happy ? -42 : 22, 145, 115);
      oval(c, Color.rgb(81, 184, 150), 126, 101, 179, 126);
      c.restore();
      oval(c, Color.rgb(52, 158, 128), 48, 40, 156, 120);
      oval(c, Color.rgb(132, 219, 174), 47, 34, 155, 113);
      oval(c, Color.rgb(164, 235, 188), 54, 36, 146, 83);
      // A tiny sprout makes the silhouette unmistakably 芽芽.
      path.reset();
      path.moveTo(100, 29);
      path.quadTo(77, 31, 77, 9);
      path.quadTo(102, 6, 100, 29);
      p.setColor(LEAF_EDGE);
      c.drawPath(path, p);
      path.reset();
      path.moveTo(103, 28);
      path.quadTo(101, 5, 128, 6);
      path.quadTo(126, 29, 103, 28);
      p.setColor(LEAF);
      c.drawPath(path, p);
      line(c, p, TEAL, 3, 101, 34, 102, 23);
      if (happy || (phase > .82f && phase < .93f)) {
        p.setColor(INK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(70, 62, 84, 75, 190, 160, false, p);
        c.drawArc(119, 62, 133, 75, 190, 160, false, p);
        p.setStyle(Paint.Style.FILL);
      } else {
        oval(c, INK, 72, 60, 84, 78);
        oval(c, INK, 119, 60, 131, 78);
        oval(c, Color.WHITE, 75, 62, 80, 67);
        oval(c, Color.WHITE, 122, 62, 127, 67);
      }
      oval(c, Color.rgb(251, 163, 139), 58, 81, 78, 91);
      oval(c, Color.rgb(251, 163, 139), 125, 81, 145, 91);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(3);
      p.setColor(INK);
      p.setStrokeCap(Paint.Cap.ROUND);
      c.drawArc(91, 78, 111, 92, 10, 160, false, p);
      p.setStyle(Paint.Style.FILL);
      // Open book, held in the flippers.
      round(c, Color.rgb(231, 171, 61), 63, 119, 142, 155, 6);
      path.reset();
      path.moveTo(66, 115);
      path.quadTo(83, 108, 102, 120);
      path.quadTo(123, 108, 139, 115);
      path.lineTo(139, 146);
      path.quadTo(121, 141, 102, 151);
      path.quadTo(84, 141, 66, 146);
      path.close();
      p.setColor(Color.rgb(255, 255, 243));
      c.drawPath(path, p);
      line(c, p, Color.rgb(211, 219, 184), 2, 102, 121, 102, 146);
      line(c, p, Color.rgb(153, 186, 147), 2, 75, 125, 92, 129);
      line(c, p, Color.rgb(153, 186, 147), 2, 75, 133, 92, 137);
      star(c, p, 120, 131, 7, SUN);
      oval(c, Color.rgb(108, 207, 165), 53, 123, 74, 136);
      oval(c, Color.rgb(108, 207, 165), 133, 123, 153, 136);
      if (mood.equals("think")) {
        star(c, p, 168, 73, 9, SUN);
        p.setColor(SUN);
        c.drawCircle(164, 91, 3, p);
      }
      c.restore();
    }
  }

  static final class StageNode extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final String kind;
    final int state; // 0 locked, 1 next, 2 completed

    StageNode(Context c, String kind, int state) {
      super(c);
      this.kind = kind;
      this.state = state;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void drawableStateChanged() {
      super.drawableStateChanged();
      invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
      float s = Math.min(getWidth(), getHeight()) / 96f;
      c.save();
      c.translate((getWidth() - 96 * s) / 2, 0);
      c.scale(s, s);
      int edge =
          state == 0 ? Color.rgb(188, 207, 217) : state == 2 ? Color.rgb(224, 160, 40) : LEAF_EDGE;
      int face = state == 0 ? Color.rgb(230, 239, 244) : state == 2 ? SUN : LEAF;
      p.setColor(edge);
      c.drawRoundRect(9, 15, 87, 90, 33, 33, p);
      c.save();
      c.translate(0, isPressed() ? 5 : 0);
      p.setColor(face);
      c.drawRoundRect(9, 7, 87, 80, 33, 33, p);
      p.setColor(
          state == 0
              ? Color.rgb(242, 247, 250)
              : state == 2 ? Color.rgb(255, 226, 142) : Color.rgb(195, 245, 143));
      c.drawRoundRect(24, 13, 66, 20, 4, 4, p);
      c.save();
      c.translate(24, 21);
      icon(
          c,
          p,
          state == 0 ? "lock" : state == 2 ? "check" : kind,
          state == 0 ? Color.rgb(150, 171, 184) : TEAL);
      c.restore();
      if (state == 2) star(c, p, 78, 12, 10, Color.WHITE);
      c.restore();
      c.restore();
    }
  }

  /** Adaptive native children on a dotted, winding trail. Labels remain accessible. */
  static final class Trail extends ViewGroup {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final float[] offsets = {-.21f, .20f, .24f, -.08f, -.24f, .08f};
    final int done;
    final DashPathEffect dots;

    Trail(Context c, int done) {
      super(c);
      this.done = done;
      setWillNotDraw(false);
      dots = new DashPathEffect(new float[] {dp(c, 2), dp(c, 15)}, 0);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec), y = dp(getContext(), 12);
      int tileWidth = Math.min(dp(getContext(), 180), Math.round(width * .57f));
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        child.measure(
            MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        y += child.getMeasuredHeight() + dp(getContext(), 32);
      }
      setMeasuredDimension(width, resolveSize(y, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int width = r - l, y = dp(getContext(), 12);
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
        int x = Math.round(width * (.5f + offsets[i]) - w / 2f);
        x = Math.max(0, Math.min(width - w, x));
        child.layout(x, y, x + w, y + h);
        y += h + dp(getContext(), 32);
      }
    }

    @Override
    protected void onDraw(Canvas c) {
      for (int i = 1; i < getChildCount(); i++) {
        View a = getChildAt(i - 1), b = getChildAt(i);
        float x1 = a.getLeft() + a.getWidth() / 2f, x2 = b.getLeft() + b.getWidth() / 2f;
        float y1 = a.getTop() + dp(getContext(), 43), y2 = b.getTop() + dp(getContext(), 43);
        // Route around the labels below the previous stepping stone.
        float bend = x2 > x1 ? getWidth() * .88f : getWidth() * .12f;
        path.reset();
        path.moveTo(x1, y1);
        path.cubicTo(bend, y1 + dp(getContext(), 45), bend, y2 - dp(getContext(), 40), x2, y2);
        p.setColor(i <= done ? Color.rgb(181, 222, 131) : LINE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(getContext(), 7));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setPathEffect(dots);
        c.drawPath(path, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        float decorX = x2 > x1 ? getWidth() * .16f : getWidth() * .81f;
        float decorY = (a.getBottom() + b.getTop()) / 2f;
        c.save();
        c.translate(decorX - dp(getContext(), 20), decorY - dp(getContext(), 20));
        float scale = dp(getContext(), 40) / 48f;
        c.scale(scale, scale);
        icon(
            c,
            p,
            i % 2 == 0 ? "leaf" : "star",
            i % 2 == 0 ? Color.rgb(190, 225, 185) : Color.rgb(255, 222, 141));
        c.restore();
      }
    }
  }
}
