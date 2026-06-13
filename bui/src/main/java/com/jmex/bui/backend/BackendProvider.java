//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui.backend;

/**
 * Provides the render backend in use by BUI. Defaults to the vendored jME fork backend
 * ({@link JmeRenderBackend}); an alternate backend must be installed via {@link #set}
 * before any BUI rendering takes place.
 */
public class BackendProvider
{
    /**
     * Returns the active render backend, creating the default (jME fork) backend if none
     * has been installed.
     */
    public static BRenderBackend get ()
    {
        BRenderBackend backend = _backend;
        if (backend == null) {
            _backend = backend = new JmeRenderBackend();
        }
        return backend;
    }

    /**
     * Installs the render backend. Must be called before any BUI component is rendered
     * (and before the default backend has been lazily created).
     */
    public static void set (BRenderBackend backend)
    {
        _backend = backend;
    }

    protected static BRenderBackend _backend;
}
