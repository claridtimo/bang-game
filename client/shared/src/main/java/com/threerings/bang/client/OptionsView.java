//
// $Id$

package com.threerings.bang.client;

import java.util.Arrays;

import com.jmex.bui.BButton;
import com.jmex.bui.BCheckBox;
import com.jmex.bui.BComboBox;
import com.jmex.bui.BContainer;
import com.jmex.bui.BDecoratedWindow;
import com.jmex.bui.BLabel;
import com.jmex.bui.BList;
import com.jmex.bui.BScrollPane;
import com.jmex.bui.BSlider;
import com.jmex.bui.BWindow;
import com.jmex.bui.BoundedRangeModel;
import com.jmex.bui.event.ActionEvent;
import com.jmex.bui.event.ActionListener;
import com.jmex.bui.event.ChangeEvent;
import com.jmex.bui.event.ChangeListener;
import com.jmex.bui.layout.GroupLayout;
import com.jmex.bui.layout.TableLayout;
import com.jmex.bui.util.Dimension;

import com.samskivert.util.StringUtil;

import com.threerings.util.MessageBundle;
import com.threerings.util.Name;

import com.threerings.crowd.chat.client.CurseFilter;

import com.threerings.bang.client.bui.OptionDialog;
import com.threerings.bang.client.bui.TabbedPane;
import com.threerings.bang.data.BangCodes;
import com.threerings.bang.util.BangContext;

import static com.threerings.bang.Log.log;

/**
 * Allows options to be viewed and adjusted. Presently that's just video
 * mode and whether or not we're in full screen mode.
 */
public class OptionsView extends BDecoratedWindow
    implements ActionListener
{
    /** The types of sound that can be configured. */
    public static enum SoundType { MUSIC, EFFECTS };

    /**
     * Creates a slider for adjusting the specified sound type.
     */
    public static BContainer createSoundSlider (
        final BangContext ctx, final SoundType type)
    {
        int value = 0;
        switch (type) {
        case MUSIC: value = BangPrefs.getMusicVolume(); break;
        case EFFECTS: value = BangPrefs.getEffectsVolume(); break;
        }

        // create our slider and label display
        BSlider slider = new BSlider(BSlider.HORIZONTAL, 0, 100, value);
        final BLabel vallbl = new BLabel(slider.getModel().getValue() + "%");
        vallbl.setPreferredSize(new Dimension(50, 10));
        slider.getModel().addChangeListener(new ChangeListener() {
            public void stateChanged (ChangeEvent event) {
                BoundedRangeModel model = (BoundedRangeModel)event.getSource();
                switch (type) {
                case MUSIC:
                    BangPrefs.updateMusicVolume(model.getValue());
                    ctx.getBangClient().setMusicVolume(model.getValue());
                    break;
                case EFFECTS:
                    BangPrefs.updateEffectsVolume(model.getValue());
                    ctx.getSoundManager().setBaseGain(model.getValue()/100f);
                    break;
                }
                vallbl.setText(model.getValue() + "%");
            }
        });

        // create a wrapper to hold them both
        BContainer wrapper = new BContainer(GroupLayout.makeHStretch());
        wrapper.add(slider);
        wrapper.add(vallbl, GroupLayout.FIXED);
        return wrapper;
    }

    public OptionsView (BangContext ctx, BWindow parent)
    {
        super(ctx.getStyleSheet(), ctx.xlate(BangCodes.OPTS_MSGS, "m.title"));

        ((GroupLayout)getLayoutManager()).setGap(15);
        setModal(true);

        _ctx = ctx;
        _parent = parent;
        _msgs = ctx.getMessageManager().getBundle(BangCodes.OPTS_MSGS);

        TabbedPane tabs = new TabbedPane(false);
        tabs.setPreferredSize(new Dimension(375, 275));

        // create the General tab
        TableLayout layout = new TableLayout(2, 10, 10);
        layout.setHorizontalAlignment(TableLayout.CENTER);
        layout.setVerticalAlignment(TableLayout.CENTER);
        BContainer cont = new BContainer(layout);
        cont.setStyleClass("options_tab");
        tabs.addTab(_msgs.get("t.general"), cont);

        cont.add(new BLabel(_msgs.get("m.video_mode"), "right_label"));
        cont.add(_modes = new BComboBox());

        cont.add(new BLabel(_msgs.get("m.fullscreen"), "right_label"));
        cont.add(_fullscreen = new BCheckBox(""));
        _fullscreen.setSelected(BangPrefs.isFullscreen());
        _fullscreen.addListener(_modelist);

        cont.add(new BLabel(_msgs.get("m.detail_lev"), "right_label"));
        cont.add(createDetailSlider());

        cont.add(new BLabel(_msgs.get("m.music_vol"), "right_label"));
        cont.add(createSoundSlider(_ctx, SoundType.MUSIC));
        cont.add(new BLabel(_msgs.get("m.effects_vol"), "right_label"));
        cont.add(createSoundSlider(_ctx, SoundType.EFFECTS));

        // create the Chat tab if we're logged on
        if (ctx.getMuteDirector() != null) {
            cont = new BContainer(
                GroupLayout.makeVert(GroupLayout.STRETCH, GroupLayout.CENTER,
                                     GroupLayout.CONSTRAIN));
            ((GroupLayout)cont.getLayoutManager()).setGap(10);
            cont.setStyleClass("options_tab");
            tabs.addTab(_msgs.get("t.chat"), cont);

            BContainer top = new BContainer(layout);
            cont.add(top, GroupLayout.FIXED);

            top.add(new BLabel(_msgs.get("m.chat_mode"), "right_label"));
            final BComboBox chatmode = new BComboBox();
            top.add(chatmode);
            BComboBox.Item selitem = null;
            for (CurseFilter.Mode mode : CurseFilter.Mode.values()) {
                String label = _msgs.get("m.cfm_" + StringUtil.toUSLowerCase(mode.toString()));
                BComboBox.Item item = new BComboBox.Item(mode, label);
                chatmode.addItem(item);
                if (mode.equals(BangPrefs.getChatFilterMode())) {
                    selitem = item;
                }
            }
            chatmode.selectItem(selitem);
            chatmode.addListener(new ActionListener() {
                public void actionPerformed (ActionEvent event) {
                    BangPrefs.setChatFilterMode((CurseFilter.Mode)chatmode.getSelectedValue());
                }
            });

            top.add(new BLabel(_msgs.get("m.chat_mogrify"), "right_label"));
            final BCheckBox mogrify = new BCheckBox("");
            mogrify.setSelected(BangPrefs.getChatMogrifierEnabled());
            mogrify.addListener(new ActionListener() {
                public void actionPerformed (ActionEvent event) {
                    BangPrefs.setChatMogrifierEnabled(mogrify.isSelected());
                    _ctx.getChatDirector().setMogrifyChat(mogrify.isSelected());
                }
            });
            top.add(mogrify);

            cont.add(new BLabel(_msgs.get("m.ignore_list")), GroupLayout.FIXED);

            BScrollPane sp = new BScrollPane(
                _muted = new BList(ctx.getMuteDirector().getMuted()));
            sp.setStyleClass("mute_list_pane");
            sp.setPreferredSize(new Dimension(250, 50));
            cont.add(sp);
            _muted.addListener(this);

            _remove = new BButton(_msgs.get("b.remove"), this, "remove");
            cont.add(_remove, GroupLayout.FIXED);
            _remove.setEnabled(false);
        }

        add(tabs);

        BContainer bcont = GroupLayout.makeHBox(GroupLayout.CENTER);
        ((GroupLayout)bcont.getLayoutManager()).setGap(25);
        bcont.add(new BButton(_msgs.get("m.quit"), this, "quit"));
        bcont.add(new BButton(_msgs.get("m.resume"), this, "dismiss"));
        add(bcont, GroupLayout.FIXED);

        // TODO(phase3-host): seed _mode from the live LWJGL3/GLFW display mode (was
        // Display.getDisplayMode()).
        _mode = new ModeItem(BangPrefs.getDisplayWidth(), BangPrefs.getDisplayHeight(),
            BangPrefs.getDisplayBPP(), BangPrefs.getDisplayFreq());
        refreshDisplayModes();
        _modes.addListener(_modelist);
    }

    // documentation inherited from interface ActionListener
    public void actionPerformed (ActionEvent event)
    {
        if ("dismiss".equals(event.getAction())) {
            _ctx.getBangClient().clearPopup(this, true);
        } else if ("quit".equals(event.getAction())) {
            _ctx.getApp().stop();
        } else if (BList.SELECT.equals(event.getAction())) {
            _remove.setEnabled(_muted.getSelectedValue() != null);
        } else if ("remove".equals(event.getAction())) {
            Name name = (Name)_muted.getSelectedValue();
            _ctx.getMuteDirector().setMuted(name, false);
            _muted.removeValue(name);
            _remove.setEnabled(false);
        }
    }

    @Override // documentation inherited
    protected Dimension computePreferredSize (int whint, int hhint)
    {
        Dimension d = super.computePreferredSize(whint, hhint);
        d.width = Math.max(d.width, 350);
        return d;
    }

    protected BContainer createDetailSlider ()
    {
        // create our slider and label display
        final BangPrefs.DetailLevel[] levels =
            BangPrefs.DetailLevel.class.getEnumConstants();
        BangPrefs.DetailLevel level = BangPrefs.getDetailLevel();
        BSlider slider = new BSlider(BSlider.HORIZONTAL, 0, levels.length - 1,
            level.ordinal());
        final BLabel vallbl = new BLabel(getDetailText(level));
        vallbl.setPreferredSize(new Dimension(65, 10));
        slider.getModel().addChangeListener(new ChangeListener() {
            public void stateChanged (ChangeEvent event) {
                BangPrefs.DetailLevel level =
                    levels[((BoundedRangeModel)event.getSource()).getValue()];
                BangPrefs.updateDetailLevel(level);
                vallbl.setText(getDetailText(level));

                // as soon as the user makes a detail choice on their own, we
                // can stop making suggestions
                BangPrefs.setSuggestDetail(false);
            }
        });

        // create a wrapper to hold them both
        BContainer wrapper = new BContainer(GroupLayout.makeHStretch());
        wrapper.add(slider);
        wrapper.add(vallbl, GroupLayout.FIXED);
        return wrapper;
    }

    protected String getDetailText (BangPrefs.DetailLevel level)
    {
        return _msgs.get("m.detail_" + StringUtil.toUSLowerCase(level.name()));
    }

    protected void refreshDisplayModes ()
    {
        // TODO(phase3-host): enumerate available modes from the LWJGL3/GLFW context (was
        // Display.getAvailableDisplayModes()), filter to >= MIN_WIDTH/MIN_HEIGHT, add the Linux
        // Xinerama 1024x768 / 1280x1024 fallbacks, sort, and select the current one. Until the
        // host flip we present only the stored/current mode so the chooser is non-empty.
        ModeItem current = _mode;
        ModeItem[] items = new ModeItem[] { _mode };
        Arrays.sort(items);
        _modes.setItems(items);
        _modes.selectItem(current);
    }

    protected void updateDisplayMode (ModeItem mode, boolean confirm)
    {
        boolean wantFullscreen = _fullscreen.isSelected();
        if (mode == null || (_mode != null && _mode.equals(mode) &&
                             wantFullscreen == BangPrefs.isFullscreen())) {
            return;
        }

        final ModeItem omode = _mode;
        _mode = mode;
        log.info("Switching to " + _mode + " (from " + omode + ")");

        // we fake up non-full screen display modes above, but there's no way
        // to set the bit depth to anything but zero, so we have to adjust that
        // here so that JME doesn't freak out
        int bpp = Math.max(16, _mode.bpp);
        int width = _mode.width, height = _mode.height;

        // TODO(phase3-host): recreate the LWJGL3 window at the new mode (was
        // _ctx.getDisplay().recreateWindow(...)) and re-fit the parent view to the new size
        // (was _ctx.getDisplay().getWidth()/getHeight()). The jME3 context owns the window; there
        // is no fork DisplaySystem to drive here.

        // reconfigure the camera frustum in case the aspect ratio changed
        _ctx.getCameraHandler().getCamera().setFrustumPerspective(
            45.0f, width/(float)height, 1, 10000);

        // recenter and invalidate the main view and options window
        if (_parent != null) {
            if (_parent instanceof ShopView || _parent instanceof LogonView) {
                _parent.center();
            } else {
                _parent.setBounds(0, 0, _ctx.getCamera().getWidth(),
                                  _ctx.getCamera().getHeight());
            }
        }
        center();

        // if we don't need to confirm, stop here
        if (!confirm) {
            return;
        }

        OptionDialog.ResponseReceiver rr = new OptionDialog.ResponseReceiver() {
            public void resultPosted (int button, Object result) {
                switch (button) {
                case OptionDialog.OK_BUTTON:
                    // store these settings for later
                    BangPrefs.updateDisplayMode(_mode.width, _mode.height, _mode.bpp,
                        _mode.freq);
                    BangPrefs.updateFullscreen(_fullscreen.isSelected());
                    break;

                default:
                    // revert to the old mode
                    updateDisplayMode(omode, false);
                    break;
                }
            }
        };
        OptionDialog.showConfirmDialog(
            _ctx, BangCodes.OPTS_MSGS, "m.keep_mode", rr);

    }

    protected void fullscreenRestart ()
    {
        // TODO(phase3-host): the fork compared the live display's fullscreen state
        // (_ctx.getDisplay().isFullScreen()) against the preference; the jME3 context owns this
        // now. Until the host flip, assume a restart is required when the preference changed.
        _fullscreen.setText(_msgs.get("m.restart_required"));
        OptionDialog.ResponseReceiver rr = new OptionDialog.ResponseReceiver() {
            public void resultPosted (int button, Object result) {
                if (button == OptionDialog.OK_BUTTON) {
                    if (!BangClient.relaunchGetdown(_ctx, 500L)) {
                        log.info("Failed to restart Bang, exiting");
                        _ctx.getApp().stop();
                    }
                }
            }
        };
        OptionDialog.showConfirmDialog(_ctx, BangCodes.OPTS_MSGS,
                "m.fullscreen_changed", "m.restart", "m.resume", rr);
    }

    /**
     * A host-neutral display-mode descriptor. jME3 cutover: replaces the LWJGL2
     * {@code org.lwjgl.opengl.DisplayMode} this view used to wrap; the Phase-3 LWJGL3 host
     * supplies width/height/bpp/freq.
     */
    protected static class ModeItem implements Comparable<ModeItem>
    {
        public int width, height, bpp, freq;

        public ModeItem (int width, int height, int bpp, int freq) {
            this.width = width;
            this.height = height;
            this.bpp = bpp;
            this.freq = freq;
        }

        public String toString () {
            String text = width + "x" + height;
            if (bpp > 0) {
                text += ("x" + bpp + " " + freq + "Hz");
            }
            return text;
        }

        @Override public boolean equals (Object o) {
            if (!(o instanceof ModeItem)) {
                return false;
            }
            ModeItem m = (ModeItem)o;
            return width == m.width && height == m.height && bpp == m.bpp && freq == m.freq;
        }

        @Override public int hashCode () {
            return (width * 31 + height) * 31 + bpp * 31 + freq;
        }

        public int compareTo (ModeItem other) {
            if (width != other.width) {
                return width - other.width;
            } else if (height != other.height) {
                return height - other.height;
            } else if (bpp != other.bpp) {
                return bpp - other.bpp;
            } else {
                return freq - other.freq;
            }
        }
    }

    protected ActionListener _modelist = new ActionListener() {
        public void actionPerformed (ActionEvent event) {
            updateDisplayMode((ModeItem)_modes.getSelectedItem(), true);
        }
    };

    protected BangContext _ctx;
    protected BWindow _parent;
    protected MessageBundle _msgs;
    protected ModeItem _mode;

    protected BComboBox _modes;
    protected BCheckBox _fullscreen;

    protected BList _muted;
    protected BButton _remove;
}
