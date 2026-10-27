package io.github.jackwener.dsh.core.platform;

/** Runs inside Shizuku's shell-uid user service process. */
interface IShellService {
    void destroy() = 16777114;
    /** Runs `sh -c cmd`; returns "<exitCode>\n<combined output>". */
    String exec(String cmd, long timeoutMs) = 1;
    /** Creates a private virtual display backed by an ImageReader; returns displayId or -1. */
    int createDisplay(int width, int height, int dpi) = 2;
    void releaseDisplay(int displayId) = 3;
    /** Latest frame of the virtual display as JPEG bytes (null if none). */
    byte[] captureDisplay(int displayId) = 4;
}
