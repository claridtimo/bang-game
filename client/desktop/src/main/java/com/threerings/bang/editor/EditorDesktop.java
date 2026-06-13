//
// $Id$

package com.threerings.bang.editor;

import com.google.inject.Injector;
import com.google.inject.Guice;

import com.jme3.system.AppSettings;

import com.threerings.bang.client.BangApp;

/**
 * The board editor entry point. jME3 cutover (Phase 3): {@link EditorApp} is now a
 * {@code com.jme3.app.SimpleApplication} on the LWJGL3 context. The Swing/AWT-canvas embedding of
 * the editor (the old gdx {@code LwjglCanvas} in a {@code JFrame}) is deferred to Phase 5; for now
 * the editor boots on its own LWJGL3 window like the client.
 */
public class EditorDesktop
{
    public static void main (String[] args)
    {
        // configure our debug log
        BangApp.configureLog("editor.log");

        // save these for later
        EditorApp.appArgs = args;

        // create our editor server which we're going to run in the same JVM with the client
        Injector injector = Guice.createInjector(new EditorServer.Module());
        EditorServer server = injector.getInstance(EditorServer.class);
        try {
            server.init(injector);
        } catch (Exception e) {
            System.err.println("Unable to initialize server.");
            e.printStackTrace(System.err);
        }

        // let the BangClientController know we're in editor mode
        System.setProperty("editor", "true");

        EditorApp app = injector.getInstance(EditorApp.class);

        AppSettings settings = new AppSettings(true);
        settings.setTitle("Bang Editor");
        settings.setResolution(1224, 768);
        settings.setVSync(true);
        settings.setDepthBits(24);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }
}
