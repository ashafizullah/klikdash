package com.ashafizullah.klikdash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

/** Focus ring dan fokus remote yang konsisten untuk kontrol kustom yang digambar sendiri. */
final class TvFocus {
    private static final Paint RING = new Paint();

    private TvFocus() { }

    static void enable(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setOnFocusChangeListener((v, focused) -> v.invalidate());
    }

    static void draw(Canvas canvas, View view, float dp) {
        if (view.isFocused()) Theme.ring(canvas, RING, view.getWidth(), view.getHeight(),
                HomeActivity.AMBER, Math.max(2, Math.round(3 * dp)));
    }

    static StateListDrawable selector() {
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_focused},
                new ColorDrawable(HomeActivity.AMBER & 0x55FFFFFF));
        return selector;
    }
}
