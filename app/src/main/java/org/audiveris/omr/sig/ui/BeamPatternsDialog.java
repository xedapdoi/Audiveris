//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                B e a m P a t t e r n s D i a l o g                               //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.sig.ui;

import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.AbstractNoteInter;
import org.audiveris.omr.sig.inter.HeadChordInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.SmallChordInter;
import org.audiveris.omr.sig.inter.StemInter;
import org.audiveris.omr.sig.ui.InterController.BeamPattern;
import org.audiveris.omr.ui.selection.EntityListEvent;

import org.bushe.swing.event.EventSubscriber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Class <code>BeamPatternsDialog</code> is a Photoshop-like tool palette for
 * beaming: pick selected notes, click a pattern to redraw them.
 * <p>
 * Only patterns matching the selected notes count are enabled, the others are
 * dimmed. The palette follows the sheet selection live.
 *
 * @author Audiveris contributors
 */
public class BeamPatternsDialog
        extends JDialog
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(BeamPatternsDialog.class);

    /** Open palettes, one per sheet. */
    private static final Map<Sheet, BeamPatternsDialog> OPEN = new LinkedHashMap<>();

    //~ Instance fields ----------------------------------------------------------------------------

    private final Sheet sheet;

    private final Map<BeamPattern, JButton> buttons = new LinkedHashMap<>();

    private final EventSubscriber<EntityListEvent> subscriber = event -> SwingUtilities
            .invokeLater(this::refresh);

    //~ Constructors -------------------------------------------------------------------------------

    private BeamPatternsDialog (JFrame owner,
                                Sheet sheet)
    {
        super(owner, "Beam patterns", false);
        this.sheet = sheet;

        setLayout(new BorderLayout(6, 6));

        final JPanel grid = new JPanel();
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));

        for (int size : new int[] { 2, 3, 4 }) {
            final List<BeamPattern> patterns = BeamPattern.forSize(size);

            if (patterns.isEmpty()) {
                continue;
            }

            grid.add(new JLabel(size + " notes:"));

            final JPanel row = new JPanel(new GridLayout(1, 0, 4, 4));

            for (BeamPattern pattern : patterns) {
                final JButton button = new JButton(pattern.toString());
                button.setIcon(new PatternIcon(pattern));
                button.setVerticalTextPosition(JButton.BOTTOM);
                button.setHorizontalTextPosition(JButton.CENTER);
                button.setToolTipText("Redraw selection as " + pattern);
                button.addActionListener(e -> apply(pattern));
                buttons.put(pattern, button);
                row.add(button);
            }

            grid.add(row);
        }

        add(grid, BorderLayout.CENTER);
        pack();
        setAlwaysOnTop(false);

        sheet.getInterIndex().getEntityService().subscribeStrongly(
                EntityListEvent.class,
                subscriber);

        addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosed (WindowEvent e)
            {
                try {
                    sheet.getInterIndex().getEntityService().unsubscribe(
                            EntityListEvent.class,
                            subscriber);
                } catch (Exception ex) {
                    logger.debug("Unsubscribe failed", ex);
                }

                OPEN.remove(sheet);
            }
        });

        refresh();
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //------//
    // open //
    //------//
    /**
     * Open (or focus) the palette for the provided sheet.
     *
     * @param owner owning frame
     * @param sheet related sheet
     */
    public static void open (JFrame owner,
                             Sheet sheet)
    {
        if (sheet == null) {
            return;
        }

        BeamPatternsDialog dialog = OPEN.get(sheet);

        if (dialog == null) {
            dialog = new BeamPatternsDialog(owner, sheet);
            OPEN.put(sheet, dialog);
            dialog.setVisible(true);
        } else {
            dialog.toFront();
            dialog.requestFocus();
            dialog.refresh();
        }
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-------//
    // apply //
    //-------//
    /**
     * Apply a pattern to the current selection.
     */
    private void apply (BeamPattern pattern)
    {
        final List<HeadChordInter> chords = selectedHeadChords(sheet);

        if (chords.size() != pattern.size()) {
            return;
        }

        sheet.getInterController().applyBeamPattern(chords, pattern);
        refresh();
    }

    //---------//
    // refresh //
    //---------//
    /**
     * Enable only patterns matching the current selection count (and validity).
     */
    private void refresh ()
    {
        final List<HeadChordInter> chords = selectedHeadChords(sheet);
        final boolean valid = isValid(chords);

        for (Map.Entry<BeamPattern, JButton> entry : buttons.entrySet()) {
            entry.getValue().setEnabled(valid && entry.getKey().size() == chords.size());
        }
    }

    //-----------------//
    // selectedHeadChords //
    //-----------------//
    /**
     * Resolve the selected inters of a sheet to head chords.
     *
     * @param sheet the sheet at hand
     * @return the selected head chords (deduplicated)
     */
    static List<HeadChordInter> selectedHeadChords (Sheet sheet)
    {
        final List<HeadChordInter> chords = new ArrayList<>();

        if (sheet == null) {
            return chords;
        }

        final List<Inter> selected = sheet.getInterIndex().getEntityService()
                .getSelectedEntityList();

        if (selected == null) {
            return chords;
        }

        for (Inter inter : selected) {
            if (inter instanceof HeadChordInter headChord && !(inter instanceof SmallChordInter)) {
                if (!chords.contains(headChord)) {
                    chords.add(headChord);
                }
            } else if (inter instanceof AbstractNoteInter note) {
                final AbstractChordInter chord = note.getChord();

                if (chord instanceof HeadChordInter headChord
                        && !(chord instanceof SmallChordInter) && !chords.contains(headChord)) {
                    chords.add(headChord);
                }
            } else if (inter instanceof StemInter stem) {
                for (HeadChordInter stemChord : stem.getChords()) {
                    if (stemChord != null && !(stemChord instanceof SmallChordInter)
                            && !chords.contains(stemChord)) {
                        chords.add(stemChord);
                    }
                }
            }
        }

        return chords;
    }

    //---------//
    // isValid //
    //---------//
    /**
     * Check pattern preconditions: all stemmed and in a single measure.
     */
    private static boolean isValid (List<HeadChordInter> chords)
    {
        if (chords.size() < 2) {
            return false;
        }

        org.audiveris.omr.sheet.rhythm.Measure measure = null;

        for (HeadChordInter chord : chords) {
            if (chord.getStem() == null) {
                return false;
            }

            if (measure == null) {
                measure = chord.getMeasure();
            } else if (measure != chord.getMeasure()) {
                return false;
            }
        }

        return true;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-------------//
    // PatternIcon //
    //-------------//
    /**
     * Paints a miniature of a beam pattern: note heads, stems and beams.
     */
    private static class PatternIcon
            implements Icon
    {
        private static final int WIDTH = 64;

        private static final int HEIGHT = 40;

        private final BeamPattern pattern;

        PatternIcon (BeamPattern pattern)
        {
            this.pattern = pattern;
        }

        @Override
        public int getIconWidth ()
        {
            return WIDTH;
        }

        @Override
        public int getIconHeight ()
        {
            return HEIGHT;
        }

        @Override
        public void paintIcon (Component c,
                               Graphics g,
                               int x,
                               int y)
        {
            final int n = pattern.beams.length;
            final int[] xs = new int[n];

            for (int i = 0; i < n; i++) {
                xs[i] = x + 10 + i * ((WIDTH - 20) / Math.max(1, n - 1));
            }

            final int headY = y + HEIGHT - 8;
            final int beamY = y + 4;

            // Heads + stems
            g.setColor(c.getForeground());

            for (int i = 0; i < n; i++) {
                g.fillOval(xs[i] - 4, headY - 3, 8, 6);
                g.drawLine(xs[i] + 3, headY, xs[i] + 3, beamY + 8);
            }

            // Beams per level (runs) and hooks for isolated notes
            int maxLevel = 0;

            for (int b : pattern.beams) {
                maxLevel = Math.max(maxLevel, b);
            }

            for (int level = 1; level <= maxLevel; level++) {
                final int yy = beamY + (level - 1) * 6;
                int runStart = -1;

                for (int i = 0; i <= n; i++) {
                    final boolean inRun = (i < n) && (pattern.beams[i] >= level);

                    if (inRun && runStart < 0) {
                        runStart = i;
                    }

                    if (!inRun && runStart >= 0) {
                        if (i - 1 > runStart) {
                            g.fillRect(xs[runStart] + 3, yy, xs[i - 1] - xs[runStart], 3);
                        } else {
                            final boolean forward = runStart < n - 1;
                            final int hx = xs[runStart] + 3;
                            g.fillRect(
                                    forward ? hx : hx - 12,
                                    yy,
                                    12,
                                    3);
                        }

                        runStart = -1;
                    }
                }
            }

            if (pattern.triplet) {
                g.drawString("3", x + WIDTH / 2 - 3, y + HEIGHT - 12);
            }
        }
    }
}
