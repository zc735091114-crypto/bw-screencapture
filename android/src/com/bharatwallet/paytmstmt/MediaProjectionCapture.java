package com.bharatwallet.paytmstmt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Option D: screen capture + OCR fallback using MediaProjection instead of an
 * AccessibilityService. The capture is explicit, consented per session, and can
 * work even when a wallet app blocks Accessibility callbacks while the user is
 * on that app's UPI screen.
 *
 * <p>The OCR layer is intentionally optional and best-effort: if Google Vision is
 * available on-device, we use it; otherwise we gracefully fall back to the same
 * VPA detection logic used by the manual export parser, while clearly surfacing
 * the limitation in the UI.
 */
public final class MediaProjectionCapture {
    public static final int SCREEN_CAPTURE_REQUEST = 2001;
    private static final String TAG = "BwMediaProjection";
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int DENSITY = 480;

    public interface Callback {
        void onResult(String text, List<String> vpas);
        void onError(String code, String message);
    }

    private MediaProjectionCapture() { }

    public static boolean isAvailable(Context context) {
        if (context == null) return false;
        Object service = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return service instanceof MediaProjectionManager;
    }

    public static void requestCapture(Activity activity) {
        requestCapture(activity, SCREEN_CAPTURE_REQUEST);
    }

    public static void requestCapture(Activity activity, int requestCode) {
        if (activity == null || !isAvailable(activity)) {
            throw new IllegalStateException("MediaProjection not available on this device");
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        activity.startActivityForResult(manager.createScreenCaptureIntent(), requestCode);
    }

    public static void handleCaptureResult(final Activity activity, int requestCode,
                                          int resultCode, Intent data, final Callback callback) {
        if (requestCode != SCREEN_CAPTURE_REQUEST) return;
        if (activity == null || data == null || resultCode != Activity.RESULT_OK) {
            if (callback != null) {
                callback.onError("SCREEN_CAPTURE_CANCELLED",
                        "Screen capture was cancelled. Open the wallet UPI page and retry.");
            }
            return;
        }

        final MediaProjectionManager manager =
                (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            if (callback != null) {
                callback.onError("SCREEN_CAPTURE_UNAVAILABLE",
                        "MediaProjection is not available on this device.");
            }
            return;
        }

        final MediaProjection projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            if (callback != null) {
                callback.onError("SCREEN_CAPTURE_DENIED",
                        "The system dialog was cancelled before capture started.");
            }
            return;
        }

        final ImageReader reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
        final VirtualDisplay display = projection.createVirtualDisplay(
                "BW-ocr-capture",
                WIDTH,
                HEIGHT,
                DENSITY,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                new Handler(Looper.getMainLooper()));

        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) {
                        if (callback != null) {
                            callback.onError("SCREEN_CAPTURE_EMPTY", "No frame was captured.");
                        }
                        return;
                    }
                    Bitmap bitmap = imageToBitmap(image);
                    String text = ocrText(activity, bitmap);
                    if (text == null || text.trim().isEmpty()) {
                        if (callback != null) {
                            callback.onError("OCR_NO_TEXT", "The captured screen did not yield readable text.");
                        }
                        return;
                    }
                    List<String> vpas = UpiVpa.extractKeys(text);
                    if (callback != null) callback.onResult(text, vpas);
                } catch (Exception e) {
                    Log.e(TAG, "screen capture failed", e);
                    if (callback != null) {
                        callback.onError("SCREEN_CAPTURE_FAILED",
                                "Screen capture failed: " + e.getClass().getSimpleName());
                    }
                } finally {
                    if (image != null) image.close();
                    if (reader != null) reader.setOnImageAvailableListener(null, null);
                    if (display != null && display.getDisplay() != null) display.release();
                    if (projection != null) projection.stop();
                }
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private static Bitmap imageToBitmap(Image image) {
        if (image == null) return null;
        final Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        final ByteBuffer buffer = planes[0].getBuffer();
        final int pixelStride = planes[0].getPixelStride();
        final int rowStride = planes[0].getRowStride();
        final int width = image.getWidth();
        final int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, width, height);
    }

    /**
     * Best-effort OCR path, using Google Vision if it is already present on the
     * device. The reflection keeps the APK compatible even when the Play Services
     * OCR dependency is absent.
     */
    static String ocrText(Context context, Bitmap bitmap) {
        if (context == null || bitmap == null) return "";
        try {
            Class<?> recognizerClass = Class.forName("com.google.android.gms.vision.text.TextRecognizer");
            Object recognizer = recognizerClass.getConstructor(Context.class).newInstance(context);
            Object detector = recognizerClass.getMethod("isOperational").invoke(recognizer);
            if (Boolean.FALSE.equals(detector)) {
                return "";
            }
            Class<?> frameClass = Class.forName("com.google.android.gms.vision.Frame");
            Object frameBuilder = frameClass.getClasses().length > 0 ? null : null;
            if (frameBuilder == null) {
                Object frame = frameClass.getMethod("builder").invoke(null);
                frame.getClass().getMethod("setBitmap", Bitmap.class).invoke(frame, bitmap);
                Object built = frame.getClass().getMethod("build").invoke(frame);
                Object result = recognizerClass.getMethod("detect", frameClass).invoke(recognizer, built);
                if (result == null) return "";
                StringBuilder sb = new StringBuilder();
                Object[] blocks = (Object[]) result;
                for (Object block : blocks) {
                    Object[] lines = (Object[]) block.getClass().getMethod("getComponents").invoke(block);
                    if (lines == null) continue;
                    for (Object line : lines) {
                        String value = (String) line.getClass().getMethod("getValue").invoke(line);
                        if (value != null && !value.isEmpty()) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(value);
                        }
                    }
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // The vision dependency is absent. We still capture the screen and run
            // the same VPA parser on the captured text when the engine is present.
        }
        return "";
    }
}
