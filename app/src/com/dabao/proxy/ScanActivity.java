package com.dabao.proxy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码添加：全屏相机预览 + ZXing core 纯 Java 解码，不依赖 Google Play 服务。
 * 扫到后震动一下，把内容放进 extra "qr" 返回；按返回/取消则 RESULT_CANCELED。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera camera;
    private SurfaceView surfaceView;
    private boolean torchOn = false;
    private volatile long lastTry = 0;
    private final AtomicBoolean done = new AtomicBoolean(false);
    private final Handler handler = new Handler();
    private final Camera.AutoFocusCallback afCb = new Camera.AutoFocusCallback() {
        @Override public void onAutoFocus(boolean success, Camera c) {}
    };
    private final Runnable afTask = new Runnable() {
        @Override public void run() {
            try { if (camera != null) camera.autoFocus(afCb); } catch (Exception ignored) {}
            handler.postDelayed(this, 2000);
        }
    };

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** 取景框：只画四个角的白边。 */
    static class FrameView extends View {
        private final Paint paint = new Paint();
        FrameView(Activity a) {
            super(a);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6);
            paint.setAntiAlias(true);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight(), l = Math.min(w, h) / 4;
            // 左上
            c.drawLine(0, 0, l, 0, paint); c.drawLine(0, 0, 0, l, paint);
            // 右上
            c.drawLine(w - l, 0, w, 0, paint); c.drawLine(w, 0, w, l, paint);
            // 左下
            c.drawLine(0, h - l, 0, h, paint); c.drawLine(0, h, l, h, paint);
            // 右下
            c.drawLine(w - l, h, w, h, paint); c.drawLine(w, h - l, w, h, paint);
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(this);

        TextView hint = new TextView(this);
        hint.setText("将二维码放入框内");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(18);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hintLp.topMargin = dp(72);
        root.addView(hint, hintLp);

        int frame = dp(240);
        FrameView frameView = new FrameView(this);
        FrameLayout.LayoutParams frameLp = new FrameLayout.LayoutParams(frame, frame);
        frameLp.gravity = Gravity.CENTER;
        root.addView(frameView, frameLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setBackgroundColor(Color.parseColor("#3a3a3c"));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setResult(RESULT_CANCELED); finish(); }
        });
        final Button torch = new Button(this);
        torch.setText("手电筒");
        torch.setTextColor(Color.WHITE);
        torch.setBackgroundColor(Color.parseColor("#3a3a3c"));
        torch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTorch(torch); }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(12); btnLp.rightMargin = dp(12);
        bottom.addView(cancel, btnLp);
        bottom.addView(torch, btnLp);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bottomLp.bottomMargin = dp(72);
        root.addView(bottom, bottomLp);

        setContentView(root);
    }

    private void toggleTorch(Button btn) {
        try {
            if (camera == null) return;
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null) return;
            if (torchOn) {
                if (modes.contains(Camera.Parameters.FLASH_MODE_OFF)) p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                torchOn = false; btn.setText("手电筒");
            } else {
                if (modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                torchOn = true; btn.setText("手电筒：开");
            }
            camera.setParameters(p);
        } catch (Exception ignored) {}
    }

    private void startCamera() {
        try {
            if (camera != null) return;
            camera = Camera.open();
            camera.setDisplayOrientation(90);
            Camera.Parameters p = camera.getParameters();
            List<String> focusModes = p.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size best = sizes.get(0);
                for (Camera.Size s : sizes) {
                    if (s.width >= 640 && s.width * s.height < best.width * best.height) best = s;
                    if (s.width == 1280 && s.height == 720) { best = s; break; }
                }
                p.setPreviewSize(best.width, best.height);
            }
            camera.setParameters(p);
            camera.setPreviewDisplay(surfaceView.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();
            handler.postDelayed(afTask, 1500);
        } catch (Exception e) {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void stopCamera() {
        handler.removeCallbacks(afTask);
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            }
        } catch (Exception ignored) {}
        camera = null;
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { startCamera(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { stopCamera(); }
    @Override protected void onPause() { super.onPause(); stopCamera(); }
    @Override protected void onResume() {
        super.onResume();
        if (surfaceView.getHolder().getSurface() != null && surfaceView.getHolder().getSurface().isValid()) {
            startCamera();
        }
    }

    @Override public void onPreviewFrame(byte[] data, Camera cam) {
        if (done.get() || data == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTry < 300) return;
        lastTry = now;
        final byte[] copy = data.clone();
        final Camera.Parameters p;
        try { p = cam.getParameters(); } catch (Exception e) { return; }
        final int w = p.getPreviewSize().width;
        final int h = p.getPreviewSize().height;
        new Thread(new Runnable() {
            @Override public void run() {
                if (done.get()) return;
                try {
                    LuminanceSource src = new PlanarYUVLuminanceSource(copy, w, h, 0, 0, w, h, false);
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
                    Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
                    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
                    Result result = new QRCodeReader().decode(bitmap, hints);
                    if (result != null && result.getText() != null && !result.getText().trim().isEmpty()) {
                        if (!done.compareAndSet(false, true)) return;
                        final String text = result.getText().trim();
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                try {
                                    Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                                    if (vib != null) vib.vibrate(80);
                                } catch (Exception ignored) {}
                                Intent i = new Intent();
                                i.putExtra("qr", text);
                                setResult(RESULT_OK, i);
                                finish();
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // 扫不到不报错，停留在预览等下一帧
                }
            }
        }).start();
    }
}
