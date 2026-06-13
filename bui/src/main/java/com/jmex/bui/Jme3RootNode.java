//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui;

import java.util.ArrayList;

import com.jme3.input.InputManager;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;

import com.badlogic.gdx.Input.Keys;

import com.jmex.bui.event.InputEvent;
import com.jmex.bui.event.KeyEvent;
import com.jmex.bui.event.MouseEvent;

/**
 * The concrete jME3 {@link BRootNode}. Replaces the deleted fork {@code PolledRootNode}
 * (LWJGL2/gdx-polled glue): the host attaches this node's {@link RawInputListener} to jME3's
 * {@link InputManager} (via {@link #registerWith}), and BUI input flows from jME3 input events
 * through {@link BRootNode#dispatchEvent}.
 *
 * <p>BUI's component model compares key codes against libGDX {@code Input.Keys} constants
 * (woven through {@code BComponent}/{@code KeyEvent}/{@code DefaultKeyMap}); this node maps the
 * jME3 raw key codes onto those gdx constants in {@link #toBuiKeyCode} so the rest of BUI is
 * untouched. The mouse y-axis is flipped from jME3's bottom-left origin... actually jME3 mouse
 * events already use a bottom-left origin (y up), which matches BUI, so no flip is needed.
 */
public class Jme3RootNode extends BRootNode
    implements RawInputListener
{
    public Jme3RootNode ()
    {
        // Seed the tick stamp immediately. getTickStamp() must return a valid wall-clock value
        // before the first updateRootState frame: BangClient.IdleTracker.start() samples it as its
        // baseline _lastEventStamp, and if it read the field's default 0 the idle delta would be
        // ~System.currentTimeMillis() (decades) on the very first expire() tick, logging a passive
        // (no-input) client out instantly.
        _tickStamp = System.currentTimeMillis();
    }

    /**
     * Attaches this node's raw input listener to the supplied jME3 input manager and records the
     * display height (jME3 mouse events report y with a bottom-left origin, matching BUI).
     */
    public void registerWith (InputManager inputManager)
    {
        _inputManager = inputManager;
        inputManager.addRawInputListener(this);
    }

    // documentation inherited
    public long getTickStamp ()
    {
        return _tickStamp;
    }

    // documentation inherited
    public void rootInvalidated (BComponent root)
    {
        if (!_invalidRoots.contains(root)) {
            _invalidRoots.add(root);
        }
    }

    @Override // documentation inherited
    public void updateRootState (float time)
    {
        // determine our tick stamp in milliseconds
        _tickStamp = System.currentTimeMillis();

        // effect key repeat
        if (_pressed >= 0 && _nextRepeat < _tickStamp) {
            _nextRepeat += SUBSEQ_REPEAT_DELAY;
            KeyEvent event = new KeyEvent(
                this, _tickStamp, _modifiers, KeyEvent.KEY_TYPED, _presschar, _pressed);
            dispatchEvent(_focus, event);
        }

        // validate all invalid roots
        while (_invalidRoots.size() > 0) {
            BComponent root = _invalidRoots.remove(0);
            if (root.isAdded()) {
                root.validate();
            }
        }

        // run the standard tooltip/geom-view logic
        super.updateRootState(time);
    }

    @Override // documentation inherited
    public float getTooltipTimeout ()
    {
        return ((_modifiers & InputEvent.CTRL_DOWN_MASK) != 0) ? 0 : _tipTime;
    }

    // from interface RawInputListener
    public void beginInput () {}
    public void endInput () {}
    public void onJoyAxisEvent (JoyAxisEvent evt) {}
    public void onJoyButtonEvent (JoyButtonEvent evt) {}
    public void onTouchEvent (TouchEvent evt) {}

    // from interface RawInputListener
    public void onKeyEvent (KeyInputEvent kie)
    {
        int code = toBuiKeyCode(kie.getKeyCode());
        char ch = kie.getKeyChar();
        if (kie.isRepeating()) {
            return; // we synthesize our own repeats
        }
        if (kie.isPressed()) {
            int modMask = getModMask(code);
            if (modMask != -1) {
                _modifiers |= modMask;
            }
            dispatchEvent(_focus, new KeyEvent(
                this, _tickStamp, _modifiers, KeyEvent.KEY_PRESSED, (char)0, code));
            // a key that yields a character generates a TYPED event too
            if (ch != 0 && ch != '￿' && !Character.isISOControl(ch)) {
                dispatchEvent(_focus, new KeyEvent(
                    this, _tickStamp, _modifiers, KeyEvent.KEY_TYPED, ch, 0));
                _presschar = ch;
            } else {
                _presschar = (char)0;
            }
            _pressed = code;
            _nextRepeat = _tickStamp + INITIAL_REPEAT_DELAY;
        } else {
            int modMask = getModMask(code);
            if (modMask != -1) {
                _modifiers &= ~modMask;
            }
            dispatchEvent(_focus, new KeyEvent(
                this, _tickStamp, _modifiers, KeyEvent.KEY_RELEASED, (char)0, code));
            _pressed = -1;
        }
        kie.setConsumed();
    }

    // from interface RawInputListener
    public void onMouseMotionEvent (MouseMotionEvent mme)
    {
        int x = mme.getX(), y = mme.getY();
        _mouseX = x;
        _mouseY = y;

        // mouse wheel
        int wheelDelta = mme.getDeltaWheel();
        if (wheelDelta != 0) {
            // jME3 reports positive deltaWheel for scroll up; BUI expects positive for up too
            dispatchMouse(new MouseEvent(
                this, _tickStamp, _modifiers, MouseEvent.MOUSE_WHEELED, -1, x, y,
                wheelDelta > 0 ? 1 : -1));
        }

        mouseDidMove(x, y);
        dispatchMouse(new MouseEvent(
            this, _tickStamp, _modifiers,
            _ccomponent != null ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED, x, y));
        mme.setConsumed();
    }

    // from interface RawInputListener
    public void onMouseButtonEvent (MouseButtonEvent mbe)
    {
        int button = mbe.getButtonIndex();
        boolean pressed = mbe.isPressed();
        int x = mbe.getX(), y = mbe.getY();

        // jME3 buttons: 0=left,1=right,2=middle; BUI: 0=left,1=right,2=middle (same indices)
        updateHoverComponent(x, y);
        if (pressed && (_modifiers & ANY_BUTTON_PRESSED) == 0) {
            setFocus(_ccomponent = _hcomponent);
        }
        if (button >= 0 && button < MOUSE_MODIFIER_MAP.length) {
            if (pressed) {
                _modifiers |= MOUSE_MODIFIER_MAP[button];
            } else {
                _modifiers &= ~MOUSE_MODIFIER_MAP[button];
            }
        }
        dispatchMouse(new MouseEvent(
            this, _tickStamp, _modifiers,
            pressed ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_RELEASED, button, x, y));
        if ((_modifiers & ANY_BUTTON_PRESSED) == 0) {
            _ccomponent = null;
        }
        mbe.setConsumed();
    }

    protected void dispatchMouse (MouseEvent event)
    {
        dispatchEvent(_ccomponent != null ? _ccomponent : _hcomponent, event);
    }

    protected int getModMask (int keyCode)
    {
        for (int ii = 0; ii < KEY_MODIFIER_MAP.length; ii += 2) {
            if (KEY_MODIFIER_MAP[ii] == keyCode) {
                return KEY_MODIFIER_MAP[ii+1];
            }
        }
        return -1;
    }

    /**
     * Maps a jME3 {@code com.jme3.input.KeyInput.KEY_*} code to the libGDX {@code Input.Keys}
     * constant BUI compares against.
     */
    protected static int toBuiKeyCode (int jmeCode)
    {
        if (jmeCode >= 0 && jmeCode < KEYMAP.length && KEYMAP[jmeCode] != 0) {
            return KEYMAP[jmeCode];
        }
        return Keys.UNKNOWN;
    }

    protected InputManager _inputManager;
    protected long _tickStamp;
    protected ArrayList<BComponent> _invalidRoots = new ArrayList<BComponent>();

    /** Used for key repeat. */
    protected int _pressed = -1;
    protected char _presschar;
    protected long _nextRepeat;

    /** Maps (BUI) key codes to modifier flags. */
    protected static final int[] KEY_MODIFIER_MAP = {
        Keys.SHIFT_LEFT, InputEvent.SHIFT_DOWN_MASK,
        Keys.SHIFT_RIGHT, InputEvent.SHIFT_DOWN_MASK,
        Keys.CONTROL_LEFT, InputEvent.CTRL_DOWN_MASK,
        Keys.CONTROL_RIGHT, InputEvent.CTRL_DOWN_MASK,
        Keys.ALT_LEFT, InputEvent.ALT_DOWN_MASK,
        Keys.ALT_RIGHT, InputEvent.ALT_DOWN_MASK,
    };

    protected static final int[] MOUSE_MODIFIER_MAP = {
        InputEvent.BUTTON1_DOWN_MASK,
        InputEvent.BUTTON2_DOWN_MASK,
        InputEvent.BUTTON3_DOWN_MASK,
    };

    protected static final int ANY_BUTTON_PRESSED =
        InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON2_DOWN_MASK | InputEvent.BUTTON3_DOWN_MASK;

    protected static final long INITIAL_REPEAT_DELAY = 300L;
    protected static final long SUBSEQ_REPEAT_DELAY = 50L;

    /** jME3 KEY_* code -> gdx Input.Keys code. Indexed by the jME3 code (0..255). Built lazily
     * below from name-paired constants. */
    protected static final int[] KEYMAP = new int[256];
    static {
        // letters
        m(com.jme3.input.KeyInput.KEY_A, Keys.A);
        m(com.jme3.input.KeyInput.KEY_B, Keys.B);
        m(com.jme3.input.KeyInput.KEY_C, Keys.C);
        m(com.jme3.input.KeyInput.KEY_D, Keys.D);
        m(com.jme3.input.KeyInput.KEY_E, Keys.E);
        m(com.jme3.input.KeyInput.KEY_F, Keys.F);
        m(com.jme3.input.KeyInput.KEY_G, Keys.G);
        m(com.jme3.input.KeyInput.KEY_H, Keys.H);
        m(com.jme3.input.KeyInput.KEY_I, Keys.I);
        m(com.jme3.input.KeyInput.KEY_J, Keys.J);
        m(com.jme3.input.KeyInput.KEY_K, Keys.K);
        m(com.jme3.input.KeyInput.KEY_L, Keys.L);
        m(com.jme3.input.KeyInput.KEY_M, Keys.M);
        m(com.jme3.input.KeyInput.KEY_N, Keys.N);
        m(com.jme3.input.KeyInput.KEY_O, Keys.O);
        m(com.jme3.input.KeyInput.KEY_P, Keys.P);
        m(com.jme3.input.KeyInput.KEY_Q, Keys.Q);
        m(com.jme3.input.KeyInput.KEY_R, Keys.R);
        m(com.jme3.input.KeyInput.KEY_S, Keys.S);
        m(com.jme3.input.KeyInput.KEY_T, Keys.T);
        m(com.jme3.input.KeyInput.KEY_U, Keys.U);
        m(com.jme3.input.KeyInput.KEY_V, Keys.V);
        m(com.jme3.input.KeyInput.KEY_W, Keys.W);
        m(com.jme3.input.KeyInput.KEY_X, Keys.X);
        m(com.jme3.input.KeyInput.KEY_Y, Keys.Y);
        m(com.jme3.input.KeyInput.KEY_Z, Keys.Z);
        // digits (top row)
        m(com.jme3.input.KeyInput.KEY_0, Keys.NUM_0);
        m(com.jme3.input.KeyInput.KEY_1, Keys.NUM_1);
        m(com.jme3.input.KeyInput.KEY_2, Keys.NUM_2);
        m(com.jme3.input.KeyInput.KEY_3, Keys.NUM_3);
        m(com.jme3.input.KeyInput.KEY_4, Keys.NUM_4);
        m(com.jme3.input.KeyInput.KEY_5, Keys.NUM_5);
        m(com.jme3.input.KeyInput.KEY_6, Keys.NUM_6);
        m(com.jme3.input.KeyInput.KEY_7, Keys.NUM_7);
        m(com.jme3.input.KeyInput.KEY_8, Keys.NUM_8);
        m(com.jme3.input.KeyInput.KEY_9, Keys.NUM_9);
        // numpad
        m(com.jme3.input.KeyInput.KEY_NUMPAD0, Keys.NUMPAD_0);
        m(com.jme3.input.KeyInput.KEY_NUMPAD1, Keys.NUMPAD_1);
        m(com.jme3.input.KeyInput.KEY_NUMPAD2, Keys.NUMPAD_2);
        m(com.jme3.input.KeyInput.KEY_NUMPAD3, Keys.NUMPAD_3);
        m(com.jme3.input.KeyInput.KEY_NUMPAD4, Keys.NUMPAD_4);
        m(com.jme3.input.KeyInput.KEY_NUMPAD5, Keys.NUMPAD_5);
        m(com.jme3.input.KeyInput.KEY_NUMPAD6, Keys.NUMPAD_6);
        m(com.jme3.input.KeyInput.KEY_NUMPAD7, Keys.NUMPAD_7);
        m(com.jme3.input.KeyInput.KEY_NUMPAD8, Keys.NUMPAD_8);
        m(com.jme3.input.KeyInput.KEY_NUMPAD9, Keys.NUMPAD_9);
        // function keys
        m(com.jme3.input.KeyInput.KEY_F1, Keys.F1);
        m(com.jme3.input.KeyInput.KEY_F2, Keys.F2);
        m(com.jme3.input.KeyInput.KEY_F3, Keys.F3);
        m(com.jme3.input.KeyInput.KEY_F4, Keys.F4);
        m(com.jme3.input.KeyInput.KEY_F5, Keys.F5);
        m(com.jme3.input.KeyInput.KEY_F6, Keys.F6);
        m(com.jme3.input.KeyInput.KEY_F7, Keys.F7);
        m(com.jme3.input.KeyInput.KEY_F8, Keys.F8);
        m(com.jme3.input.KeyInput.KEY_F9, Keys.F9);
        m(com.jme3.input.KeyInput.KEY_F10, Keys.F10);
        m(com.jme3.input.KeyInput.KEY_F11, Keys.F11);
        m(com.jme3.input.KeyInput.KEY_F12, Keys.F12);
        // editing / navigation
        m(com.jme3.input.KeyInput.KEY_ESCAPE, Keys.ESCAPE);
        m(com.jme3.input.KeyInput.KEY_RETURN, Keys.ENTER);
        m(com.jme3.input.KeyInput.KEY_NUMPADENTER, Keys.ENTER);
        m(com.jme3.input.KeyInput.KEY_TAB, Keys.TAB);
        m(com.jme3.input.KeyInput.KEY_BACK, Keys.BACKSPACE);
        m(com.jme3.input.KeyInput.KEY_DELETE, Keys.FORWARD_DEL);
        m(com.jme3.input.KeyInput.KEY_INSERT, Keys.INSERT);
        m(com.jme3.input.KeyInput.KEY_SPACE, Keys.SPACE);
        m(com.jme3.input.KeyInput.KEY_HOME, Keys.HOME);
        m(com.jme3.input.KeyInput.KEY_END, Keys.END);
        m(com.jme3.input.KeyInput.KEY_PRIOR, Keys.PAGE_UP);
        m(com.jme3.input.KeyInput.KEY_NEXT, Keys.PAGE_DOWN);
        m(com.jme3.input.KeyInput.KEY_LEFT, Keys.LEFT);
        m(com.jme3.input.KeyInput.KEY_RIGHT, Keys.RIGHT);
        m(com.jme3.input.KeyInput.KEY_UP, Keys.UP);
        m(com.jme3.input.KeyInput.KEY_DOWN, Keys.DOWN);
        // modifiers
        m(com.jme3.input.KeyInput.KEY_LSHIFT, Keys.SHIFT_LEFT);
        m(com.jme3.input.KeyInput.KEY_RSHIFT, Keys.SHIFT_RIGHT);
        m(com.jme3.input.KeyInput.KEY_LCONTROL, Keys.CONTROL_LEFT);
        m(com.jme3.input.KeyInput.KEY_RCONTROL, Keys.CONTROL_RIGHT);
        m(com.jme3.input.KeyInput.KEY_LMENU, Keys.ALT_LEFT);
        m(com.jme3.input.KeyInput.KEY_RMENU, Keys.ALT_RIGHT);
        // punctuation
        m(com.jme3.input.KeyInput.KEY_MINUS, Keys.MINUS);
        m(com.jme3.input.KeyInput.KEY_EQUALS, Keys.EQUALS);
        m(com.jme3.input.KeyInput.KEY_LBRACKET, Keys.LEFT_BRACKET);
        m(com.jme3.input.KeyInput.KEY_RBRACKET, Keys.RIGHT_BRACKET);
        m(com.jme3.input.KeyInput.KEY_SEMICOLON, Keys.SEMICOLON);
        m(com.jme3.input.KeyInput.KEY_APOSTROPHE, Keys.APOSTROPHE);
        m(com.jme3.input.KeyInput.KEY_GRAVE, Keys.GRAVE);
        m(com.jme3.input.KeyInput.KEY_BACKSLASH, Keys.BACKSLASH);
        m(com.jme3.input.KeyInput.KEY_COMMA, Keys.COMMA);
        m(com.jme3.input.KeyInput.KEY_PERIOD, Keys.PERIOD);
        m(com.jme3.input.KeyInput.KEY_SLASH, Keys.SLASH);
    }

    private static void m (int jmeCode, int gdxCode) {
        if (jmeCode >= 0 && jmeCode < KEYMAP.length) {
            KEYMAP[jmeCode] = gdxCode;
        }
    }
}
