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

import org.audiveris.omr.OMR;
import org.audiveris.omr.score.AudioExporter;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.BookManager;
import org.audiveris.omr.sheet.ui.StubsController;
import org.audiveris.omr.ui.util.OmrFileFilter;
import org.audiveris.omr.ui.util.UIUtil;

import org.jdesktop.application.Action;
import org.jdesktop.application.ApplicationActionMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.swing.JOptionPane;

/**
 * Class <code>MidiActions</code> gathers playback actions for the MIDI domain menu.
 *
 * @author Audiveris contributors
 */
public class MidiActions
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(MidiActions.class);

    //~ Instance fields ----------------------------------------------------------------------------

    private ApplicationActionMap actionMap;

    //~ Methods ------------------------------------------------------------------------------------

    //-----------//
    // actionMap //
    //-----------//
    private ApplicationActionMap actionMap ()
    {
        if (actionMap == null) {
            actionMap = OMR.gui.getApplication().getContext().getActionMap(this);
        }

        return actionMap;
    }

    //-----------------//
    // playPauseScore //
    //-----------------//
    /**
     * Play, pause or resume the current book score through the MIDI synthesizer,
     * showing a playhead line that follows the sounding chord.
     * A fresh play starts from the selected chord if any, else from the beginning.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void playPauseScore (ActionEvent e)
    {
        final ScorePlayer player = ScorePlayer.getInstance();

        if (player.isPlaying()) {
            player.pause();

            return;
        }

        if (player.isPaused()) {
            player.play(null);

            return;
        }

        final Book book = StubsController.getCurrentBook();

        if (book == null) {
            logger.warn("No current book to play");
            return;
        }

        player.play(book);
    }

    //-----------//
    // exportMp3 //
    //-----------//
    /**
     * Export the current book scores to MP3 files (MIDI rendered in Java,
     * encoded through the external ffmpeg). Runs in the background.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void exportMp3 (ActionEvent e)
    {
        final Book book = StubsController.getCurrentBook();

        if (book == null || book.getScores().isEmpty()) {
            logger.warn("No scored book to export");
            return;
        }

        final Path sansExt = BookManager.getDefaultExportPathSansExt(book);
        final Path startPath = Paths.get(sansExt + ".mp3");
        final Path target = UIUtil.pathChooser(
                true,
                OMR.gui.getFrame(),
                startPath,
                new OmrFileFilter("MP3 audio", "mp3"));

        if (target == null) {
            return;
        }

        final List<Score> scores = List.copyOf(book.getScores());
        final int tempo = ScorePlayer.getInstance().getTempo();

        new Thread(() -> {
            try {
                for (Score score : scores) {
                    String name = target.getFileName().toString();

                    if (scores.size() > 1) {
                        final int dot = name.lastIndexOf('.');
                        name = name.substring(0, dot) + ".mvt" + score.getId()
                                + name.substring(dot);
                    }

                    new AudioExporter(score).exportMp3(
                            target.resolveSibling(name),
                            tempo);
                }

                logger.info("MP3 export done");
            } catch (Exception ex) {
                logger.warn("Could not export MP3", ex);
            }
        }, "mp3-export").start();
    }

    //------------//
    // stopScore //
    //------------//
    /**
     * Stop any running or paused score playback.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void stopScore (ActionEvent e)
    {
        ScorePlayer.getInstance().stop();
    }

    //-----------//
    // setTempo //
    //-----------//
    /**
     * Ask the user for the playback tempo in BPM. It wins over metronome marks
     * and applies live to a running playback as well as to the next exports.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void setTempo (ActionEvent e)
    {
        final ScorePlayer player = ScorePlayer.getInstance();
        final String answer = JOptionPane.showInputDialog(
                OMR.gui.getFrame(),
                "Playback tempo (quarters per minute, 30-300):",
                player.getTempo());

        if (answer == null) {
            return;
        }

        try {
            player.setTempo(Integer.parseInt(answer.trim()));
            TempoBox.refresh();
        } catch (NumberFormatException ex) {
            logger.warn("Invalid tempo {}", answer);
        }
    }

    //-------------------//
    // togglePlayhead //
    //-------------------//
    /**
     * Show or hide the playback playhead line.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void togglePlayhead (ActionEvent e)
    {
        final ScorePlayer player = ScorePlayer.getInstance();
        player.setPlayheadShown(!player.isPlayheadShown());

        final Object action = actionMap().get("togglePlayhead");

        if (action instanceof javax.swing.Action swingAction) {
            swingAction.putValue("selected", player.isPlayheadShown());
        }
    }
}
