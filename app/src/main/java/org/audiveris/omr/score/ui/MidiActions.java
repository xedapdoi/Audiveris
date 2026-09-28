//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       M i d i A c t i o n s                                      //
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

import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.ui.StubsController;

import org.jdesktop.application.Action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionEvent;

/**
 * Class <code>MidiActions</code> gathers playback actions for the MIDI domain menu.
 *
 * @author Audiveris contributors
 */
public class MidiActions
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(MidiActions.class);

    //~ Methods ------------------------------------------------------------------------------------

    //------------//
    // playScore //
    //------------//
    /**
     * Play the current book score through the MIDI synthesizer,
     * highlighting each sounding chord.
     * A running playback, if any, is stopped and restarted.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void playScore (ActionEvent e)
    {
        final Book book = StubsController.getCurrentBook();

        if (book == null) {
            logger.warn("No current book to play");
            return;
        }

        ScorePlayer.getInstance().play(book);
    }

    //------------//
    // stopScore //
    //------------//
    /**
     * Stop any running score playback.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void stopScore (ActionEvent e)
    {
        ScorePlayer.getInstance().stop();
    }
}
