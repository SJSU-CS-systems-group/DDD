package org.thoughtcrime.securesms.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.ColorInt;

import org.thoughtcrime.securesms.util.ViewUtil;

public class Outliner {

  private final float[] radii        = new float[8];
  private final Path    corners      = new Path();
  private final RectF   bounds       = new RectF();
  private final Paint   outlinePaint = new Paint();
  {
    outlinePaint.setStyle(Paint.Style.STROKE);
    outlinePaint.setStrokeWidth(ViewUtil.dpToPx(1));
    outlinePaint.setAntiAlias(true);
  }

  public void setColor(@ColorInt int color) {
    outlinePaint.setColor(color);
  }

  public void setStrokeWidth(float pixels) {
    outlinePaint.setStrokeWidth(pixels);
  }

  public void setAlpha(int alpha) {
    outlinePaint.setAlpha(alpha);
  }

  public void draw(Canvas canvas) {
    draw(canvas, 0, canvas.getWidth(), canvas.getHeight(), 0);
  }

  public void draw(Canvas canvas, int top, int right, int bottom, int left) {
    final float halfStrokeWidth = outlinePaint.getStrokeWidth() / 2;

    bounds.left   = left + halfStrokeWidth;
    bounds.top    = top + halfStrokeWidth;
    bounds.right  = right - halfStrokeWidth;
    bounds.bottom = bottom - halfStrokeWidth;

    corners.reset();
    corners.addRoundRect(bounds, radii, Path.Direction.CW);

    canvas.drawPath(corners, outlinePaint);
  }

  public void setRadius(int radius) {
    setRadii(radius, radius, radius, radius);
  }

  public void setRadii(int topLeft, int topRight, int bottomRight, int bottomLeft) {
    radii[0] = radii[1] = topLeft;
    radii[2] = radii[3] = topRight;
    radii[4] = radii[5] = bottomRight;
    radii[6] = radii[7] = bottomLeft;
  }
}
