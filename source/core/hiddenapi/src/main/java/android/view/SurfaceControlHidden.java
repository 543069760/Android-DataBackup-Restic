package android.view;

import android.os.Build;
import android.os.IBinder;

import androidx.annotation.RequiresApi;

import dev.rikka.tools.refine.RefineAs;

/**
 * @see <a href="https://cs.android.com/android/platform/superproject/+/android-8.0.0_r51:frameworks/base/core/java/android/view/SurfaceControl.java">SurfaceControl.java</a>
 */
@RefineAs(SurfaceControl.class)
public class SurfaceControlHidden {
    public static final int POWER_MODE_OFF = 0;
    public static final int POWER_MODE_NORMAL = 2;

    public static IBinder getBuiltInDisplay(int builtInDisplayId) {
        throw new RuntimeException("Stub!");
    }

    /**
     * @see <a href="https://cs.android.com/android/platform/superproject/+/android-10.0.0_r47:frameworks/base/core/java/android/view/SurfaceControl.java;l=1821">SurfaceControl.java</a>
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static long[] getPhysicalDisplayIds() {
        throw new RuntimeException("Stub!");
    }

    /**
     * @see <a href="https://cs.android.com/android/platform/superproject/+/android-10.0.0_r47:frameworks/base/core/java/android/view/SurfaceControl.java;l=1828">SurfaceControl.java</a>
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        throw new RuntimeException("Stub!");
    }

    /**
     * 真实签名为 {@code public static boolean setDisplayPowerMode(IBinder, int)}，
     * 返回值表示是否设置成功，调用方据此决定是否降级到下一级路径。
     */
    public static boolean setDisplayPowerMode(IBinder displayToken, int mode) {
        throw new RuntimeException("Stub!");
    }
}
