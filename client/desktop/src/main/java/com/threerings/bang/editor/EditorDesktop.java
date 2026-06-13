//
// $Id$

package com.threerings.bang.editor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import javax.swing.JFrame;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.Guice;

import org.lwjgl.openal.AL;

import com.badlogic.gdx.backends.lwjgl.LwjglCanvas;

import com.threerings.bang.client.BangApp;

public class EditorDesktop
{
    public static void main (String[] args) {
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

        // the editor exits via System.exit (EXIT_ON_CLOSE and the editor controller's quit
        // paths) with the render loop still live, so close the AL device during shutdown to
        // keep openal-soft's atexit cleanup from segfaulting against its own mixer thread
        // (same hazard BangDesktop guards against with its ordered-exit signal handlers)
        Runtime.getRuntime().addShutdownHook(new Thread("EditorDesktop AL cleanup") {
            @Override public void run () {
                if (AL.isCreated()) {
                    AL.destroy();
                }
            }
        });

        // build the UI on the AWT event dispatch thread: realizing the canvas (in frame.pack)
        // triggers LwjglCanvas.create -> Display.create, which makes the GL context current on
        // the calling thread. All subsequent GL work (the libGDX render loop and the editor's
        // game logic, which runs on RunQueue.AWT) happens on the EDT, so the context must be
        // created there or every GL call dies with "No OpenGL context found in current thread".
        EventQueue.invokeLater(new Runnable() {
            public void run () {
                // create a frame
                JFrame frame = new JFrame("Bang Editor");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

                // this is the entry point for all the "client-side" stuff
                EditorApp app = injector.getInstance(EditorApp.class);
                app.frame = frame;

                LwjglCanvas canvas = new LwjglCanvas(app);
                app.canvas = canvas.getCanvas();

                // display the GL canvas to start so that it initializes everything; size the
                // frame before showing it so it appears once at its final size
                frame.getContentPane().add(canvas.getCanvas(), BorderLayout.CENTER);
                frame.pack();
                frame.setSize(new Dimension(1224, 768));
                frame.setVisible(true);
            }
        });
    }
}
