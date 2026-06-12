//
// $Id$

package com.threerings.bang.client;

import org.lwjgl.openal.AL;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.threerings.bang.client.BangPrefs;

public class BangDesktop
{
    public static void main (String[] args) {
        LwjglApplicationConfiguration cfg = new LwjglApplicationConfiguration();
        cfg.title = "Bang! Howdy";
        cfg.width = BangPrefs.getDisplayWidth();
        cfg.height = BangPrefs.getDisplayHeight();
        cfg.depth = BangPrefs.getDisplayBPP();
        cfg.fullscreen = BangPrefs.isFullscreen();
        // cfg.resizble = false;
        final LwjglApplication app = new LwjglApplication(new BangApp(), cfg);

        // if the JVM is terminated externally (SIGTERM/SIGINT) the default handler starts the
        // shutdown sequence with the render loop still running: the OpenAL device is left open
        // (openal-soft's atexit cleanup can then segfault against its own mixer thread) and
        // LWJGL's Display.destroy() blows up trying to deregister its shutdown hook; instead,
        // route those signals to a clean gdx exit, which tears down the display and the shared
        // AL device in order on the render thread before the JVM begins shutting down
        sun.misc.SignalHandler exiter = sig -> {
            app.exit();
            // if the render loop fails to wind down, force the issue rather than hang
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

        // last-resort safety net for shutdowns that bypass the signal path (e.g. System.exit
        // from app code): if the AL device is somehow still open once the render loop has had
        // a chance to exit, close it so openal-soft's atexit cleanup cannot crash
        Runtime.getRuntime().addShutdownHook(new Thread("BangDesktop AL cleanup") {
            @Override public void run () {
                long deadline = System.currentTimeMillis() + 3000L;
                while (AL.isCreated() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                if (AL.isCreated()) {
                    AL.destroy();
                }
            }
        });
    }
}
