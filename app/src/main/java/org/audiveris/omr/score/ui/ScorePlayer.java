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
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.grid.LineInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.Voice;
import org.audiveris.omr.sheet.ui.StubsController;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sheet.ui.SheetAssembly;
import org.audiveris.omr.ui.view.Rubber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import javax.swing.SwingUtilities;

/**
 * Class <code>ScorePlayer</code> plays the scores of a {@link Book} through the
 * MIDI synthesizer, one movement after the other, showing a playhead line that
 * follows the sounding chord without disturbing the user selection.
 * <p>
 * The played MIDI is generated on the fly by {@link MidiExporter} into temporary
 * files. Chord markers (meta 0x06, "chord=&lt;id&gt;") emitted by the exporter
 * drive the playhead.
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

    /** Queued movements (exported MIDI + chord index each). */
    private final List<Movement> queue = new ArrayList<>();

    /** Index of the movement being played. */
    private int queueIndex;

    /** Explicit tempo override in qpm, null means exporter default. */
    private Integer tempoOverride;

    /** Whether the playhead line is shown. */
    private boolean showPlayhead = true;

    /** Rubber currently showing the playhead, if any. */
    private Rubber playheadRubber;

    /** Listeners notified of each sounding chord (preview playhead). */
    private final List<Consumer<AbstractChordInter>> chordListeners = new CopyOnWriteArrayList<>();

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

    //-----------//
    // isPaused //
    //-----------//
    /**
     * Report whether playback is paused (resources held, position kept).
     *
     * @return true if paused
     */
    public synchronized boolean isPaused ()
    {
        return (sequencer != null) && sequencer.isOpen() && !sequencer.isRunning();
    }

    //-----------//
    // setTempo //
    //-----------//
    /**
     * Set the playback/export tempo. It wins over metronome marks and applies
     * live to a running playback as well as to the next exports.
     *
     * @param qpm quarters per minute (30..300)
     */
    public synchronized void setTempo (int qpm)
    {
        tempoOverride = Math.max(30, Math.min(300, qpm));

        if (isPlaying() && sequencer != null) {
            try {
                sequencer.setTempoInBPM(tempoOverride.floatValue());
            } catch (Exception ex) {
                logger.debug("Could not set live tempo", ex);
            }
        }

        logger.info("Playback tempo set to {} qpm", tempoOverride);
    }

    //-----------//
    // getTempo //
    //-----------//
    /**
     * Report the current playback tempo.
     *
     * @return quarters per minute
     */
    public synchronized int getTempo ()
    {
        return (tempoOverride != null) ? tempoOverride : MidiExporter.DEFAULT_QPM;
    }

    //---------------------//
    // isPlayheadShown //
    //---------------------//
    /**
     * Report whether the playhead line is shown.
     *
     * @return true if shown
     */
    public synchronized boolean isPlayheadShown ()
    {
        return showPlayhead;
    }

    //----------------------//
    // setPlayheadShown //
    //----------------------//
    /**
     * Show or hide the playhead line.
     *
     * @param shown true to show
     */
    public synchronized void setPlayheadShown (boolean shown)
    {
        showPlayhead = shown;

        if (!shown) {
            hidePlayhead();
        }
    }

    //--------------------//
    // addChordListener //
    //--------------------//
    /**
     * Register a listener notified of each sounding chord.
     *
     * @param listener listener to add
     */
    public void addChordListener (Consumer<AbstractChordInter> listener)
    {
        chordListeners.add(listener);
    }

    //-----------------------//
    // removeChordListener //
    //-----------------------//
    /**
     * Unregister a chord listener.
     *
     * @param listener listener to remove
     */
    public void removeChordListener (Consumer<AbstractChordInter> listener)
    {
        chordListeners.remove(listener);
    }

    //------//
    // play //
    //------//
    /**
     * Play the scores of the provided book in sequence.
     * If paused, playback resumes from the paused position.
     * Otherwise playback (re)starts from the selected chord if any,
     * else from the beginning.
     *
     * @param book the book to play
     */
    public synchronized void play (Book book)
    {
        if (isPaused()) {
            try {
                sequencer.start();
                logger.info("Playback resumed");
            } catch (Exception ex) {
                logger.warn("Could not resume playback", ex);
                stop();
            }

            return;
        }

        stop();

        if (book == null || book.getScores().isEmpty()) {
            logger.warn("Nothing to play");
            return;
        }

        try {
            queue.clear();

            for (Score score : book.getScores()) {
                final MidiExporter exporter = new MidiExporter(score);
                exporter.setTempoOverride(tempoOverride);

                final Path midiFile = Files.createTempFile("audiveris-play-", ".mid");
                midiFile.toFile().deleteOnExit();
                exporter.export(midiFile);

                final Movement movement = new Movement();
                movement.score = score;
                movement.midiFile = midiFile;
                movement.chordTicks = exporter.getChordTicks();
                movement.tempoQpm = exporter.getTempoQpm();
                indexChords(score, movement);
                queue.add(movement);
            }

            queueIndex = 0;
            final long startTick = seekTick(book);
            startMovement(queueIndex, startTick);
        } catch (Exception ex) {
            logger.warn("Could not play book", ex);
            stop();
        }
    }

    //-------//
    // pause //
    //-------//
    /**
     * Pause playback, keeping position and resources for a resume.
     */
    public synchronized void pause ()
    {
        if (isPlaying() && sequencer != null) {
            sequencer.stop();
            logger.info("Playback paused at tick {}", sequencer.getTickPosition());
        }
    }

    //------//
    // stop //
    //------//
    /**
     * Stop any running playback, hide the playhead and release MIDI resources.
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

        hidePlayhead();
        queue.clear();
        queueIndex = 0;
    }

    //----------------//
    // startMovement //
    //----------------//
    /**
     * Start playing the queued movement at the provided tick.
     */
    private void startMovement (int index,
                                long startTick)
        throws Exception
    {
        final Movement movement = queue.get(index);
        final Sequence sequence = MidiSystem.getSequence(movement.midiFile.toFile());

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
                        movePlayhead(movement, Integer.parseInt(text.substring(6)));
                    } catch (NumberFormatException ignored) {
                        // Ignore
                    }
                }
            } else if (meta.getType() == 0x2F) {
                onTrackEnd();
            }
        });

        sequencer.setSequence(sequence);

        if (startTick > 0 && startTick < sequence.getTickLength()) {
            sequencer.setTickPosition(startTick);
        }

        sequencer.start();
        logger.info(
                "Playing movement {}/{} at {} qpm",
                index + 1,
                queue.size(),
                movement.tempoQpm);
    }

    //-------------//
    // onTrackEnd //
    //-------------//
    /**
     * Called on every end-of-track meta event. Only the very end of the
     * sequence (all tracks done) moves to the next movement or stops.
     */
    private void onTrackEnd ()
    {
        final Sequencer seq = sequencer;

        if (seq == null) {
            return;
        }

        // Ignore intermediate end-of-track events (e.g. short tracks)
        if (seq.getTickPosition() < seq.getTickLength() - 10) {
            return;
        }

        synchronized (this) {
            if (sequencer != seq) {
                return; // Stopped meanwhile
            }

            if (queueIndex + 1 < queue.size()) {
                queueIndex++;

                try {
                    closeMidi();
                    startMovement(queueIndex, 0);
                } catch (Exception ex) {
                    logger.warn("Could not play next movement", ex);
                    stop();
                }
            } else {
                stop();
            }
        }
    }

    //------------//
    // closeMidi //
    //------------//
    /**
     * Close current MIDI devices, keeping the queue for the next movement.
     */
    private void closeMidi ()
    {
        if (sequencer != null) {
            try {
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
    }

    //-----------//
    // seekTick //
    //-----------//
    /**
     * Determine the starting tick: tick of the currently selected chord if any,
     * else tick matching the rubber selection, else 0.
     */
    private long seekTick (Book book)
    {
        // 1) Selected chord (or note/stem of a chord) in current sheet
        try {
            final Sheet sheet = StubsController.getCurrentStub().getSheet();

            if (sheet != null) {
                final List<Inter> selected = sheet.getInterIndex().getEntityService()
                        .getSelectedEntityList();

                if (selected != null) {
                    for (Inter inter : selected) {
                        final AbstractChordInter chord = toChord(inter);

                        if (chord != null) {
                            final Long tick = tickOf(chord.getId());

                            if (tick != null) {
                                logger.info("Playing from selected chord {}", chord.getId());

                                return tick;
                            }
                        }
                    }
                }

                // 2) Rubber rectangle: nearest chord at or left of its abscissa
                final SheetAssembly assembly = sheet.getStub().getAssembly();

                if (assembly != null && assembly.getRubber() != null
                        && assembly.getRubber().getRectangle() != null) {
                    final int x = assembly.getRubber().getRectangle().x;
                    Long bestTick = null;
                    int bestX = Integer.MIN_VALUE;

                    for (Movement movement : queue) {
                        for (Map.Entry<Integer, AbstractChordInter> entry : movement.chords
                                .entrySet()) {
                            final AbstractChordInter chord = entry.getValue();

                            try {
                                if (chord.getSig().getSystem().getSheet() == sheet) {
                                    final int cx = chord.getCenter().x;

                                    if (cx <= x && cx > bestX) {
                                        bestX = cx;
                                        bestTick = movement.chordTicks.get(entry.getKey());
                                    }
                                }
                            } catch (Exception ignored) {
                                // Ignore
                            }
                        }
                    }

                    if (bestTick != null) {
                        logger.info("Playing from rubber position x={}", x);

                        return bestTick;
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not determine seek position", ex);
        }

        return 0;
    }

    //----------//
    // toChord //
    //----------//
    /**
     * Report the chord related to the provided inter, if any.
     */
    private static AbstractChordInter toChord (Inter inter)
    {
        if (inter instanceof AbstractChordInter chord) {
            return chord;
        }

        try {
            final var chord = inter.getEnsemble();

            if (chord instanceof AbstractChordInter chordInter) {
                return chordInter;
            }
        } catch (Exception ignored) {
            // Ignore
        }

        return null;
    }

    //---------//
    // tickOf //
    //---------//
    /**
     * Report the note-on tick of a chord id across queued movements.
     */
    private Long tickOf (int chordId)
    {
        for (Movement movement : queue) {
            final Long tick = movement.chordTicks.get(chordId);

            if (tick != null) {
                return tick;
            }
        }

        return null;
    }

    //--------------//
    // movePlayhead //
    //--------------//
    /**
     * Move the playback playhead line to the chord with the provided id.
     * Unlike entity selection, the playhead does not disturb the user selection.
     * The line spans from the top line of the upper staff to the bottom line
     * of the lower staff.
     *
     * @param movement the movement being played
     * @param id       chord id
     */
    private void movePlayhead (Movement movement,
                               int id)
    {
        if (!showPlayhead) {
            return;
        }

        final AbstractChordInter chord = movement.chords.get(id);

        if (chord == null) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            for (Consumer<AbstractChordInter> listener : chordListeners) {
                try {
                    listener.accept(chord);
                } catch (Exception ex) {
                    logger.debug("Chord listener failed", ex);
                }
            }

            try {
                final SystemInfo system = chord.getSig().getSystem();
                final List<Part> parts = system.getParts();

                if (parts.isEmpty()) {
                    return;
                }

                final Point center = chord.getCenter();
                final List<Staff> topStaves = parts.get(0).getStaves();
                final List<Staff> bottomStaves = parts.get(parts.size() - 1).getStaves();

                if (topStaves.isEmpty() || bottomStaves.isEmpty()) {
                    return;
                }

                final double topY = topStaves.get(0).getLines().get(0).yAt(center.x);
                final List<LineInfo> bottomLines = bottomStaves.get(bottomStaves.size() - 1)
                        .getLines();
                final double bottomY = bottomLines.get(bottomLines.size() - 1).yAt(center.x);
                final int margin = system.getSheet().getScale().getInterline() / 2;
                final Sheet sheet = system.getSheet();
                final SheetAssembly assembly = sheet.getStub().getAssembly();

                if (assembly == null || assembly.getRubber() == null) {
                    return;
                }

                assembly.getRubber().showPlayhead(
                        new Rectangle(
                                center.x - 1,
                                (int) Math.round(topY) - margin,
                                3,
                                (int) Math.round(bottomY - topY) + 2 * margin));
                playheadRubber = assembly.getRubber();
            } catch (Exception ex) {
                logger.debug("Could not move playhead to chord {}", id, ex);
            }
        });
    }

    //--------------//
    // hidePlayhead //
    //--------------//
    /**
     * Hide any playback playhead line.
     */
    private void hidePlayhead ()
    {
        final Rubber rubber = playheadRubber;
        playheadRubber = null;

        if (rubber == null) {
            return;
        }

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                rubber.hidePlayhead();
            } else {
                SwingUtilities.invokeLater(rubber::hidePlayhead);
            }
        } catch (Exception ex) {
            logger.debug("Could not hide playhead", ex);
        }
    }

    //--------------//
    // indexChords //
    //--------------//
    /**
     * Index all sounding chords of a score by id.
     *
     * @param score    the score to index
     * @param movement the movement to fill
     */
    private static void indexChords (Score score,
                                     Movement movement)
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
                                    movement.chords.put(chord.getId(), chord);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //----------//
    // Movement //
    //----------//
    /** One queued movement: MIDI file, chord index and chord ticks. */
    private static class Movement
    {
        Score score;

        Path midiFile;

        final Map<Integer, AbstractChordInter> chords = new TreeMap<>();

        Map<Integer, Long> chordTicks = new TreeMap<>();

        int tempoQpm;
    }
}
