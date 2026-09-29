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

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.math.Rational;
import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.Voice;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.AbstractTimeInter;
import org.audiveris.omr.sig.inter.ClefInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.Inters;
import org.audiveris.omr.sig.inter.KeyInter;
import org.audiveris.omr.sig.inter.MetronomeInter;
import org.audiveris.omr.sig.inter.RestInter;
import org.audiveris.omr.sig.relation.ChordArpeggiatoRelation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
 * One MIDI track is created per (logical part, staff), plus a conductor track
 * holding the tempo — staves (e.g. treble G vs bass F of a piano part) get
 * separate channels so they sound independently. Note pitches reuse the same
 * logic as the MusicXML exporter ({@link HeadInter#getAlteration(Integer)} with
 * the running key signature). Durations come from
 * {@link AbstractChordInter#getDuration()}, so tuplets and augmentation dots are
 * honored. Chords carrying an arpeggiato sign are strummed bottom-up instead of
 * sounding as a block. Tempo comes from the first metronome mark found, else
 * from the {@code defaultTempoQpm} constant. Incomplete voices (rhythm warnings)
 * are exported as-is: chords are placed at their time offsets while measures
 * advance by expected duration, so later measures stay aligned.
 *
 * @author Audiveris contributors
 */
public class MidiExporter
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(MidiExporter.class);

    /** Ticks per quarter note. */
    public static final int PPQ = 480;

    /** Strum gap between two notes of an arpeggiated chord, in milliseconds. */
    private static final int STRUM_GAP_MS = 35;

    /** Default velocity. */
    private static final int VELOCITY = 80;

    //~ Instance fields ----------------------------------------------------------------------------

    private final Score score;

    /** Tempo actually used for the export (quarters per minute). */
    private int tempoQpm;

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
        tempoQpm = findTempo();
        logger.info("Exporting MIDI at {} qpm", tempoQpm);

        final Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        final Track conductor = sequence.createTrack();
        addTempo(conductor, 0, tempoQpm);

        // One track per (logical part, staff index in part)
        final Map<String, Track> tracks = new TreeMap<>();
        final Map<String, Integer> channels = new TreeMap<>();
        final Map<String, Long> cursors = new TreeMap<>();
        final Map<String, int[]> timeSigs = new TreeMap<>(); // trackKey -> {num, den}
        final Map<String, String> clefs = new TreeMap<>(); // trackKey -> G/F/C/?
        int channelAlloc = 0;

        for (Page page : score.getPages()) {
            for (SystemInfo system : page.getSystems()) {
                for (Part part : system.getParts()) {
                    final LogicalPart logical = part.getLogicalPart();
                    final int logicalId = (logical != null) ? logical.getId() : part.getId();
                    final String partName = (logical != null && logical.getName() != null)
                            ? logical.getName() : ("Part " + logicalId);
                    final int program = (logical != null && logical.getMidiProgram() != null)
                            ? logical.getMidiProgram() : 0;

                    for (Measure measure : part.getMeasures()) {
                        // Per-staff dispatch within the measure
                        final Map<Integer, List<VoiceEntry>> byStaff = dispatchByStaff(
                                measure);

                        for (Map.Entry<Integer, List<VoiceEntry>> entry : byStaff.entrySet()) {
                            final int staffIndex = entry.getKey();
                            final String trackKey = logicalId + ":" + staffIndex;
                            Track track = tracks.get(trackKey);

                            if (track == null) {
                                track = sequence.createTrack();
                                tracks.put(trackKey, track);

                                int channel = channelAlloc;

                                if (channel == 9) {
                                    channel = 10; // Skip percussion channel
                                }

                                channelAlloc = channel + 1;
                                channels.put(trackKey, channel % 16);
                                cursors.put(trackKey, 0L);

                                final String clef = clefOf(
                                        system,
                                        measure,
                                        staffIndex,
                                        clefs,
                                        trackKey);
                                addTrackName(
                                        track,
                                        0,
                                        partName + "-" + clef);
                                addProgramChange(track, 0, channels.get(trackKey), program);
                            }

                            exportEntries(
                                    measure,
                                    entry.getValue(),
                                    track,
                                    channels.get(trackKey),
                                    cursors,
                                    timeSigs,
                                    trackKey);
                        }
                    }
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

    //--------------//
    // findTempo //
    //--------------//
    /**
     * Report the tempo to use: first metronome mark found in the score,
     * else the default tempo constant.
     *
     * @return quarters per minute
     */
    private int findTempo ()
    {
        for (Page page : score.getPages()) {
            for (SystemInfo system : page.getSystems()) {
                final SIGraph sig = system.getSig();
                final List<Inter> metros = sig.inters(MetronomeInter.class);

                if (!metros.isEmpty()) {
                    for (Inter inter : metros) {
                        try {
                            final int qpm = ((MetronomeInter) inter).getQuartersPerMinute();

                            if (qpm > 0) {
                                logger.info("Using metronome tempo {} qpm", qpm);

                                return qpm;
                            }
                        } catch (Exception ex) {
                            logger.debug("Unusable metronome {}", inter, ex);
                        }
                    }
                }
            }
        }

        return constants.defaultTempoQpm.getValue();
    }

    //-----------------//
    // dispatchByStaff //
    //-----------------//
    /**
     * Group the sounding chords of a measure by staff index in part.
     *
     * @param measure the measure to dispatch
     * @return map of staff index to voice entries
     */
    private static Map<Integer, List<VoiceEntry>> dispatchByStaff (Measure measure)
    {
        final Map<Integer, List<VoiceEntry>> map = new TreeMap<>();
        Integer fifths = null;

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

        for (Voice voice : measure.getVoices()) {
            if (voice.isMeasureRest()) {
                continue;
            }

            for (AbstractChordInter chord : voice.getChords()) {
                if (chord.isRest()) {
                    continue;
                }

                final Staff staff = chord.getStaff();
                final int index = (staff != null) ? staff.getIndexInPart() : 0;
                map.computeIfAbsent(index, k -> new ArrayList<>()).add(
                        new VoiceEntry(chord, fifths));
            }
        }

        return map;
    }

    //---------//
    // clefOf //
    //---------//
    /**
     * Report the clef letter (G/F/C) for a staff track, caching the first
     * clef found for the staff.
     */
    private static String clefOf (SystemInfo system,
                                  Measure measure,
                                  int staffIndex,
                                  Map<String, String> cache,
                                  String trackKey)
    {
        if (cache.containsKey(trackKey)) {
            return cache.get(trackKey);
        }

        String letter = "S" + (staffIndex + 1);

        try {
            final List<Inter> clefs = system.getSig().inters(ClefInter.class);
            Collections.sort(clefs, Inters.byAbscissa);

            for (Inter inter : clefs) {
                final ClefInter clef = (ClefInter) inter;

                if (clef.getStaff() != null && clef.getStaff().getIndexInPart() == staffIndex) {
                    final String name = clef.getShape().name();

                    if (name.startsWith("G_")) {
                        letter = "G";
                    } else if (name.startsWith("F_")) {
                        letter = "F";
                    } else if (name.startsWith("C_")) {
                        letter = "C";
                    }

                    break;
                }
            }
        } catch (Exception ex) {
            logger.debug("No clef found for {}", trackKey, ex);
        }

        cache.put(trackKey, letter);

        return letter;
    }

    //---------------//
    // exportEntries //
    //---------------//
    /**
     * Export the entries of one staff within one measure.
     */
    private void exportEntries (Measure measure,
                                List<VoiceEntry> entries,
                                Track track,
                                int channel,
                                Map<String, Long> cursors,
                                Map<String, int[]> timeSigs,
                                String trackKey)
        throws Exception
    {
        final long cursor = cursors.get(trackKey);

        // Time signature tracking
        final AbstractTimeInter timeSig = measure.getTimeSignature();

        if (timeSig != null) {
            final int num = timeSig.getNumerator();
            final int den = timeSig.getDenominator();
            final int[] current = timeSigs.get(trackKey);

            if (current == null || current[0] != num || current[1] != den) {
                timeSigs.put(trackKey, new int[] { num, den });
                addTimeSignature(track, cursor, num, den);
            }
        }

        final List<NoteEvent> events = new ArrayList<>();
        final List<Marker> markers = new ArrayList<>();
        final long strumTicks = Math.max(1, Math.round(STRUM_GAP_MS * tempoQpm * PPQ / 60000.0));

        for (VoiceEntry entry : entries) {
            final AbstractChordInter chord = entry.chord;
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

            // Arpeggiated chord? Strum bottom-up instead of block.
            final boolean arpeggiated = isArpeggiated(chord);
            final List<Integer> pitches = new ArrayList<>();

            for (Inter inter : chord.getNotes()) {
                if (inter instanceof RestInter) {
                    continue;
                }

                if (inter instanceof HeadInter head) {
                    pitches.add(toMidi(head, entry.fifths));
                }
            }

            Collections.sort(pitches);

            for (int i = 0; i < pitches.size(); i++) {
                final int pitch = pitches.get(i);
                final long noteOn = arpeggiated ? (onTick + i * strumTicks) : onTick;
                events.add(new NoteEvent(pitch, noteOn, true));
                events.add(new NoteEvent(pitch, onTick + durTicks, false));
            }

            // Chord marker for playback highlight sync ("chord=<id>")
            markers.add(new Marker(chord.getId(), onTick));
        }

        // Advance by expected measure duration (keeps later measures aligned
        // even when a voice is incomplete)
        Rational expected = null;

        try {
            expected = measure.getStack().getExpectedDuration();
        } catch (Exception ex) {
            logger.debug("No expected duration for {}", measure, ex);
        }

        final long advance;

        if (expected != null && expected.doubleValue() > 0) {
            advance = toTicks(expected);
        } else {
            advance = toTicks(Rational.ONE); // Fallback: whole note
        }

        cursors.put(trackKey, cursor + advance);

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

        for (Marker marker : markers) {
            addMarker(track, marker.tick, "chord=" + marker.chordId);
        }
    }

    //---------------//
    // isArpeggiated //
    //---------------//
    /**
     * Report whether the chord carries an arpeggiato sign.
     */
    private static boolean isArpeggiated (AbstractChordInter chord)
    {
        try {
            return !chord.getSig().getRelations(chord, ChordArpeggiatoRelation.class).isEmpty();
        } catch (Exception ex) {
            return false;
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
    // addMarker //
    //-----------//
    /**
     * Add a marker meta event (for playback highlight sync).
     */
    private static void addMarker (Track track,
                                   long tick,
                                   String text)
        throws Exception
    {
        final byte[] data = text.getBytes("UTF-8");
        track.add(new MidiEvent(new MetaMessage(0x06, data, data.length), tick));
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
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.Integer defaultTempoQpm = new Constant.Integer(
                "qpm",
                120,
                "Default playback/export tempo when no metronome mark is found");
    }

    //--------//
    // Marker //
    //--------//
    /** Chord marker for playback highlight sync. */
    private static class Marker
    {
        final int chordId;

        final long tick;

        Marker (int chordId,
                long tick)
        {
            this.chordId = chordId;
            this.tick = tick;
        }
    }

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

    //------------//
    // VoiceEntry //
    //------------//
    /** A sounding chord with its measure key signature. */
    private static class VoiceEntry
    {
        final AbstractChordInter chord;

        final Integer fifths;

        VoiceEntry (AbstractChordInter chord,
                    Integer fifths)
        {
            this.chord = chord;
            this.fifths = fifths;
        }
    }
}
