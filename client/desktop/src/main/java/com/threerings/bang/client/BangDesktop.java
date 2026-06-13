//
// $Id$

package com.threerings.bang.client;

import com.jme3.system.AppSettings;

import com.threerings.bang.client.BangPrefs;

/**
 * The client entry point. jME3 cutover (Phase 3 — the atomic host flip): {@link BangApp} is now a
 * {@code com.jme3.app.SimpleApplication} on the LWJGL3 context. This launcher configures the
 * display from {@link BangPrefs} and starts the jME3 main loop (which creates the LWJGL3 window,
 * GL context and input).
 */
public class BangDesktop
{
    public static void main (String[] args)
    {
        final BangApp app = new BangApp();

        AppSettings settings = new AppSettings(true);
        settings.setTitle("Bang! Howdy");
        settings.setResolution(BangPrefs.getDisplayWidth(), BangPrefs.getDisplayHeight());
        settings.setFullscreen(BangPrefs.isFullscreen());
        settings.setVSync(true);
        // request a depth buffer for the 3D scene.
        settings.setDepthBits(24);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);

        // route external termination (SIGTERM/SIGINT) through an ordered jME3 stop so the GL
        // context and the OpenAL device are torn down on the render thread before the JVM begins
        // shutting down (openal-soft's atexit cleanup can otherwise segfault against its own mixer
        // thread).
        sun.misc.SignalHandler exiter = sig -> {
            app.stop();
            Thread enforcer = new Thread("BangDesktop exit enforcer") {
                @Override public void run () {
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    Runtime.getRuntime().halt(1);
                }
            };
            enforcer.setDaemon(true);
            enforcer.start();
        };
        sun.misc.Signal.handle(new sun.misc.Signal("TERM"), exiter);
        sun.misc.Signal.handle(new sun.misc.Signal("INT"), exiter);

        app.start();
    }
}
