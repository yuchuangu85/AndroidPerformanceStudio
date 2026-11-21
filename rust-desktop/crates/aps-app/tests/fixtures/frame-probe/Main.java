package aps.test.frameverify;

public final class Main extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        setContentView(new android.view.View(this) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                paint(canvas);
                postInvalidateOnAnimation();
            }
        });
    }
    private void paint(android.graphics.Canvas canvas) {
        android.os.SystemClock.sleep(24);
        canvas.drawColor(0xff1494cc);
    }
}
