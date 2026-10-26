package com.elvan.tvhome;

/**
 * The Settings panel over whatever app is running. It is the launcher's own screen with a transparent window: the Home
 * behind the panel is hidden and nothing but the panel is shown. It starts in a task of its own, on top of the app, so the
 * app stays visible behind the panel and Home never comes to the front (which is what the same panel in MainActivity needs).
 * Closing the panel closes this screen. See MainActivity.overlayMode().
 */
public class SettingsOverlayActivity extends MainActivity {
    @Override
    protected boolean overlayMode() {
        return true;
    }
}
