//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                          T e m p o B o x                                         //
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
package org.audiveris.omr.score.ui;

import java.awt.FlowLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Class <code>TempoBox</code> is the toolbar widget to display and set the
 * playback/export tempo in BPM. It wins over metronome marks and applies live
 * to a running playback.
 *
 * @author Audiveris contributors
 */
public class TempoBox
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static JPanel panel;

    private static JSpinner spinner;

    //~ Constructors -------------------------------------------------------------------------------

    private TempoBox ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //----------------//
    // getComponent //
    //----------------//
    /**
     * Report the toolbar component, creating it on first call.
     * Must be called on the event dispatch thread.
     *
     * @return the tempo box component
     */
    public static JPanel getComponent ()
    {
        if (panel == null) {
            panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
            panel.setOpaque(false);
            panel.setToolTipText("Playback tempo in BPM (wins over metronome marks)");

            final JLabel label = new JLabel("BPM");
            panel.add(label);

            spinner = new JSpinner(new SpinnerNumberModel(120, 30, 300, 1));
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
            spinner.addChangeListener(e -> ScorePlayer.getInstance().setTempo(
                    (Integer) spinner.getValue()));
            panel.add(spinner);

            refresh();
        }

        return panel;
    }

    //---------//
    // refresh //
    //---------//
    /**
     * Sync the spinner with the player tempo (e.g. after the tempo dialog).
     */
    public static void refresh ()
    {
        if (spinner != null) {
            spinner.setValue(ScorePlayer.getInstance().getTempo());
        }
    }
}
