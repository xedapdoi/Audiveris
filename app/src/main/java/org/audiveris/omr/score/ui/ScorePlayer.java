//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      S c o r e P l a y e r                                       //
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

import org.audiveris.omr.score.MidiExporter;
import org.audiveris.omr.score.Page;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.Voice;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.ui.selection.EntityListEvent;
import org.audiveris.omr.ui.selection.MouseMovement;
import org.audiveris.omr.ui.selection.SelectionHint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import javax.swing.SwingUtilities;

/**
 * Class <code>ScorePlayer</code> plays a {@link Score} through the MIDI
 * synthesizer and highlights each sounding chord in its sheet view.
 * <p>
 * The played MIDI is generated on the fly by {@link MidiExporter} into a
 * temporary file. Chord markers (meta 0x06, "chord=&lt;id&gt;") emitted by the
 * exporter drive the highlight via the sheet selection service.
 *
 * @author Audiveris contributors
 */
public class ScorePlayer
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ScorePlayer.class);

    /** Singleton instance. */
    private static volatile ScorePlayer INSTANCE;

    //~ Instance fields ----------------------------------------------------------------------------

    private Sequencer sequencer;

    private Synthesizer synthesizer;

    /** Chord id -> chord, for highlight sync. */
    private final Map<Integer, AbstractChordInter> chords = new TreeMap<>();

    private Book book;

    //~ Constructors -------------------------------------------------------------------------------

    private ScorePlayer ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    /**
     * Report the singleton instance.
     *
     * @return the player instance
     */
    public static ScorePlayer getInstance ()
    {
        if (INSTANCE == null) {
            synchronized (ScorePlayer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ScorePlayer();
                }
            }
        }

        return INSTANCE;
    }

    //~ Methods ------------------------------------------------------------------------------------

    //------------//
    // isPlaying //
    //------------//
    /**
     * Report whether playback is currently running.
     *
     * @return true if playing
     */
    public synchronized boolean isPlaying ()
    {
        return (sequencer != null) && sequencer.isRunning();
    }

    //------//
    // play //
    //------//
    /**
     * Play the first score of the provided book.
     * A previous playback, if any, is stopped beforehand.
     *
     * @param book the book to play
     */
    public synchronized void play (Book book)
    {
        stop();

        if (book == null || book.getScores().isEmpty()) {
            logger.warn("Nothing to play");
            return;
        }

        final Score score = book.getScores().get(0);
        this.book = book;

        try {
            // Build chord index for highlight
            chords.clear();
            indexChords(score);

            // Export MIDI on the fly
            final Path midiFile = Files.createTempFile("audiveris-play-", ".mid");
            midiFile.toFile().deleteOnExit();
            new MidiExporter(score).export(midiFile);

            final Sequence sequence = MidiSystem.getSequence(midiFile.toFile());

            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();

            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();

            final Transmitter transmitter = sequencer.getTransmitter();
            final Receiver receiver = synthesizer.getReceiver();
            transmitter.setReceiver(receiver);

            sequencer.addMetaEventListener(meta -> {
                if (meta.getType() == 0x06) {
                    final String text = new String(meta.getData());

                    if (text.startsWith("chord=")) {
                        try {
                            final int id = Integer.parseInt(text.substring(6));
                            highlight(id);
                        } catch (NumberFormatException ignored) {
                            // Ignore
                        }
                    }
                } else if (meta.getType() == 0x2F) {
                    // End of track, stop on all tracks done is handled by sequencer
                    SwingUtilities.invokeLater(this::stop);
                }
            });

            sequencer.setSequence(sequence);
            sequencer.start();
            logger.info("Playing score {}", score.getId());
        } catch (Exception ex) {
            logger.warn("Could not play score", ex);
            stop();
        }
    }

    //------//
    // stop //
    //------//
    /**
     * Stop any running playback and release MIDI resources.
     */
    public synchronized void stop ()
    {
        if (sequencer != null) {
            try {
                if (sequencer.isRunning()) {
                    sequencer.stop();
                }

                sequencer.close();
            } catch (Exception ex) {
                logger.debug("Error closing sequencer", ex);
            } finally {
                sequencer = null;
            }
        }

        if (synthesizer != null) {
            try {
                synthesizer.close();
            } catch (Exception ex) {
                logger.debug("Error closing synthesizer", ex);
            } finally {
                synthesizer = null;
            }
        }

        chords.clear();
        book = null;
    }

    //-----------//
    // highlight //
    //-----------//
    /**
     * Highlight the chord with the provided id in its sheet view.
     *
     * @param id chord id
     */
    private void highlight (int id)
    {
        final AbstractChordInter chord = chords.get(id);

        if (chord == null) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                final Sheet sheet = chord.getSig().getSystem().getSheet();
                sheet.getInterIndex().getEntityService().publish(
                        new EntityListEvent<>(
                                this,
                                SelectionHint.ENTITY_INIT,
                                MouseMovement.PRESSING,
                                List.of(chord)));
            } catch (Exception ex) {
                logger.debug("Could not highlight chord {}", id, ex);
            }
        });
    }

    //--------------//
    // indexChords //
    //--------------//
    /**
     * Index all sounding chords of the score by id.
     *
     * @param score the score to index
     */
    private void indexChords (Score score)
    {
        for (Page page : score.getPages()) {
            for (SystemInfo system : page.getSystems()) {
                for (Part part : system.getParts()) {
                    for (Measure measure : part.getMeasures()) {
                        for (Voice voice : measure.getVoices()) {
                            if (voice.isMeasureRest()) {
                                continue;
                            }

                            for (AbstractChordInter chord : voice.getChords()) {
                                if (!chord.isRest()
                                        && chord.getTimeOffset() != null
                                        && chord.getDuration() != null) {
                                    chords.put(chord.getId(), chord);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
