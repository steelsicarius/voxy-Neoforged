package me.cortex.voxy.client.taskbar;

import me.cortex.voxy.common.Logger;

//MC 26.3 switched from GLFW to SDL3, the glfw win32 window handle trick used by the
//taskbar integration is no longer available. A SDL-based version can be implemented later
//via SDL_GetWindowProperties (SDL_PROP_WINDOW_WIN32_HWND_POINTER).
@Deprecated
public class WindowsTaskbar implements Taskbar.ITaskbar {
    WindowsTaskbar(long windowId) {
        Logger.warn("Windows taskbar integration is not available on MC 26.3 (SDL window system)");
    }

    @Override public void setProgress(long count, long outOf) {}
    @Override public void setIsNone() {}
    @Override public void setIsProgression() {}
    @Override public void setIsPaused() {}
    @Override public void setIsError() {}
}
