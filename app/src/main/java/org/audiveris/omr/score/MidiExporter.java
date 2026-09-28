//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      M i d i E x p o r t e r                                     //
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
package org.audiveris.omr.score;

import org.audiveris.omr.math.Rational;
import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.Voice;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.AbstractTimeInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.KeyInter;
import org.audiveris.omr.sig.inter.RestInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Class <code>MidiExporter</code> exports a {@link Score} to a standard MIDI file (.mid).
 * <p>
 * One MIDI track is created per logical part, plus a conductor track holding
 * the tempo. Note pitches reuse the same logic as the MusicXML exporter
 * ({@link HeadInter#getAlteration(Integer)} with the running key signature).
 * Durations come from {@link AbstractChordInter#getDuration()}, so tuplets and
 * augmentation dots are honored. Incomplete voices (rhythm warnings) are exported
 * as-is: chords are placed at their time offsets while measures advance by
 * expected duration, so later measures stay aligned.
 *
 * @author Audiveris contributors
 */
public class MidiExporter
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(MidiExporter.class);

    /** Ticks per quarter note. */
    public static final int PPQ = 480;

    /** Default tempo (quarter notes per minute) when no metronome is found. */
    public static final int DEFAULT_TEMPO_QPM = 120;

    /** Default velocity. */
    private static final int VELOCITY = 80;

    //~ Instance fields ----------------------------------------------------------------------------

    private final Score score;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new <code>MidiExporter</code> object.
     *
     * @param score the score to export
     */
    public MidiExporter (Score score)
    {
        this.score = score;
    }

    //~ Methods ------------------------------------------------------------------------------------

    //--------//
    // export //
    //--------//
    /**
     * Export the score to the provided MIDI file path.
     *
     * @param midiPath target .mid path (parent folders are created)
     * @throws Exception if anything goes wrong
     */
    public void export (Path midiPath)
        throws Exception
    {
        final Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        final Track conductor = sequence.createTrack();
        addTempo(conductor, 0, DEFAULT_TEMPO_QPM);

        // One track per logical part (stable across systems)
        final Map<Integer, Track> tracks = new TreeMap<>();
        final Map<Integer, Integer> channels = new TreeMap<>();
        final Map<Integer, Long> cursors = new TreeMap<>();
        final Map<Integer, int[]> timeSigs = new TreeMap<>(); // logicalId -> {num, den}
        int channelAlloc = 0;

        for (Page page : score.getPages()) {
            for (SystemInfo system : page.getSystems()) {
                for (Part part : system.getParts()) {
                    final LogicalPart logical = part.getLogicalPart();
                    final int logicalId = (logical != null) ? logical.getId() : part.getId();

                    Track track = tracks.get(logicalId);

                    if (track == null) {
                        track = sequence.createTrack();
                        tracks.put(logicalId, track);

                        int channel = channelAlloc;

                        if (channel == 9) {
                            channel = 10; // Skip percussion channel
                        }

                        channelAlloc = channel + 1;
                        channels.put(logicalId, channel % 16);
                        cursors.put(logicalId, 0L);

                        final String partName = (logical != null && logical.getName() != null)
                                ? logical.getName() : ("Part " + logicalId);
                        addTrackName(track, 0, partName);

                        int program = 0;

                        if (logical != null && logical.getMidiProgram() != null) {
                            program = logical.getMidiProgram();
                        }

                        addProgramChange(track, 0, channels.get(logicalId), program);
                    }

                    exportPart(
                            part,
                            logicalId,
                            track,
                            channels.get(logicalId),
                            cursors,
                            timeSigs);
                }
            }
        }

        conductor.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), 0));

        for (Track track : tracks.values()) {
            track.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), lastTick(track)));
        }

        midiPath.toFile().getParentFile().mkdirs();
        MidiSystem.write(sequence, 1, midiPath.toFile());
        logger.info("Exported MIDI {}", midiPath);
    }

    //------------//
    // exportPart //
    //------------//
    /**
     * Export all measures of a physical part into its logical track.
     */
    private void exportPart (Part part,
                             int logicalId,
                             Track track,
                             int channel,
                             Map<Integer, Long> cursors,
                             Map<Integer, int[]> timeSigs)
        throws Exception
    {
        long cursor = cursors.get(logicalId);
        Integer fifths = null; // Running key signature
        final List<NoteEvent> events = new ArrayList<>();

        for (Measure measure : part.getMeasures()) {
            // Key signature tracking (global case is enough for MIDI pitch)
            if (measure.hasKeys()) {
                try {
                    final KeyInter key = measure.getKey(0);

                    if (key != null && key.getFifths() != null) {
                        fifths = key.getFifths();
                    }
                } catch (Exception ex) {
                    logger.debug("No usable key in {}", measure, ex);
                }
            }

            // Time signature tracking
            final AbstractTimeInter timeSig = measure.getTimeSignature();

            if (timeSig != null) {
                final int num = timeSig.getNumerator();
                final int den = timeSig.getDenominator();
                final int[] current = timeSigs.get(logicalId);

                if (current == null || current[0] != num || current[1] != den) {
                    timeSigs.put(logicalId, new int[] { num, den });
                    addTimeSignature(track, cursor, num, den);
                }
            }

            for (Voice voice : measure.getVoices()) {
                if (voice.isMeasureRest()) {
                    continue; // Silence, cursor still advances by measure length
                }

                for (AbstractChordInter chord : voice.getChords()) {
                    if (chord.isRest()) {
                        continue;
                    }

                    final Rational onset = chord.getTimeOffset();
                    final Rational duration = chord.getDuration();

                    if (onset == null || duration == null) {
                        continue;
                    }

                    final long onTick = cursor + toTicks(onset);
                    final long durTicks = toTicks(duration);

                    if (durTicks <= 0) {
                        continue; // Grace note or invalid, skip sounding
                    }

                    for (Inter inter : chord.getNotes()) {
                        if (inter instanceof RestInter) {
                            continue;
                        }

                        if (inter instanceof HeadInter head) {
                            final int pitch = toMidi(head, fifths);
                            events.add(new NoteEvent(pitch, onTick, true));
                            events.add(new NoteEvent(pitch, onTick + durTicks, false));
                        }
                    }
                }
            }

            // Advance by expected measure duration (keeps later measures aligned
            // even when a voice is incomplete)
            Rational expected = null;

            try {
                expected = measure.getStack().getExpectedDuration();
            } catch (Exception ex) {
                logger.debug("No expected duration for {}", measure, ex);
            }

            if (expected != null && expected.doubleValue() > 0) {
                cursor += toTicks(expected);
            } else {
                cursor += toTicks(Rational.ONE); // Fallback: whole note
            }
        }

        cursors.put(logicalId, cursor);

        // Emit note events ordered by tick (offs before ons at equal ticks)
        events.sort((a,
                     b) -> (a.tick != b.tick) ? Long.compare(a.tick, b.tick)
                       : Boolean.compare(a.on, b.on));

        for (NoteEvent event : events) {
            if (event.on) {
                addNoteOn(track, event.tick, channel, event.pitch, VELOCITY);
            } else {
                addNoteOff(track, event.tick, channel, event.pitch);
            }
        }
    }

    //---------//
    // toTicks //
    //---------//
    private static long toTicks (Rational duration)
    {
        return Math.round(duration.doubleValue() * 4 * PPQ);
    }

    //--------//
    // toMidi //
    //--------//
    /**
     * Convert a note head to a MIDI key number (C4 = 60).
     *
     * @param head   the note head
     * @param fifths current key signature, may be null
     * @return MIDI key 0..127
     */
    private static int toMidi (HeadInter head,
                               Integer fifths)
    {
        final int semi = switch (head.getStep()) {
            case C -> 0;
            case D -> 2;
            case E -> 4;
            case F -> 5;
            case G -> 7;
            case A -> 9;
            case B -> 11;
        };
        final int alter = head.getAlteration(fifths);
        final int midi = (head.getOctave() + 1) * 12 + semi + alter;

        return Math.max(0, Math.min(127, midi));
    }

    //-----------//
    // lastTick //
    //-----------//
    private static long lastTick (Track track)
    {
        long last = 0;

        for (int i = 0; i < track.size(); i++) {
            last = Math.max(last, track.get(i).getTick());
        }

        return last;
    }

    //-----------//
    // addTempo //
    //-----------//
    private static void addTempo (Track track,
                                  long tick,
                                  int qpm)
        throws Exception
    {
        final int mpq = 60_000_000 / qpm;
        final byte[] data = { (byte) ((mpq >> 16) & 0xFF), (byte) ((mpq >> 8) & 0xFF),
                (byte) (mpq & 0xFF) };
        track.add(new MidiEvent(new MetaMessage(0x51, data, 3), tick));
    }

    //------------------//
    // addTimeSignature //
    //------------------//
    private static void addTimeSignature (Track track,
                                          long tick,
                                          int num,
                                          int den)
        throws Exception
    {
        int power = 0;
        int d = den;

        while (d > 1) {
            d /= 2;
            power++;
        }

        final byte[] data = { (byte) num, (byte) power, (byte) 24, (byte) 8 };
        track.add(new MidiEvent(new MetaMessage(0x58, data, 4), tick));
    }

    //--------------//
    // addTrackName //
    //--------------//
    private static void addTrackName (Track track,
                                      long tick,
                                      String name)
        throws Exception
    {
        final byte[] data = name.getBytes("UTF-8");
        track.add(new MidiEvent(new MetaMessage(0x03, data, data.length), tick));
    }

    //-------------------//
    // addProgramChange //
    //-------------------//
    private static void addProgramChange (Track track,
                                          long tick,
                                          int channel,
                                          int program)
        throws Exception
    {
        track.add(
                new MidiEvent(
                        new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0),
                        tick));
    }

    //-----------//
    // addNoteOn //
    //-----------//
    private static void addNoteOn (Track track,
                                   long tick,
                                   int channel,
                                   int pitch,
                                   int velocity)
        throws Exception
    {
        track.add(
                new MidiEvent(
                        new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, velocity),
                        tick));
    }

    //------------//
    // addNoteOff //
    //------------//
    private static void addNoteOff (Track track,
                                    long tick,
                                    int channel,
                                    int pitch)
        throws Exception
    {
        track.add(
                new MidiEvent(
                        new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0),
                        tick));
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // NoteEvent //
    //-----------//
    /** Note-on/off event, sorted by tick (offs before ons at equal ticks). */
    private static class NoteEvent
    {
        final int pitch;

        final long tick;

        final boolean on;

        NoteEvent (int pitch,
                   long tick,
                   boolean on)
        {
            this.pitch = pitch;
            this.tick = tick;
            this.on = on;
        }
    }
}
