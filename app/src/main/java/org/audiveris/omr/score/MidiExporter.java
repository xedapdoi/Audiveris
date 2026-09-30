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
import org.audiveris.omr.sheet.rhythm.MeasureStack;
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
import org.audiveris.omr.sig.inter.SlurInter;
import org.audiveris.omr.sig.inter.StemInter;
import org.audiveris.omr.sig.relation.ChordArpeggiatoRelation;
import org.audiveris.omr.sig.relation.SlurHeadRelation;
import org.audiveris.omr.sig.relation.TremoloStemRelation;
import org.audiveris.omr.sig.relation.TremoloWholeRelation;
import org.audiveris.omr.util.HorizontalSide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /** Fallback tempo (quarters per minute) when nothing else is set. */
    public static final int DEFAULT_QPM = 120;

    /** Strum gap between two notes of an arpeggiated chord, in milliseconds. */
    private static final int STRUM_GAP_MS = 35;

    /** Default velocity. */
    private static final int VELOCITY = 80;

    //~ Instance fields ----------------------------------------------------------------------------

    private final Score score;

    /** Tempo actually used for the export (quarters per minute). */
    private int tempoQpm;

    /** Explicit tempo override (toolbar), wins over metronome and constant. */
    private Integer tempoOverride;

    /** Chord id -> note-on tick, for playback seek/sync. */
    private final Map<Integer, Long> chordTicks = new TreeMap<>();

    /** Head id -> head, for tie analysis. */
    private final Map<Integer, HeadInter> headIndex = new TreeMap<>();

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

    //------------------//
    // setTempoOverride //
    //------------------//
    /**
     * Set an explicit tempo (toolbar BPM box), winning over metronome marks
     * and the default constant.
     *
     * @param qpm quarters per minute, null to clear the override
     */
    public void setTempoOverride (Integer qpm)
    {
        tempoOverride = qpm;
    }

    //----------------//
    // getChordTicks //
    //----------------//
    /**
     * Report the note-on tick of each exported chord, for playback seek/sync.
     *
     * @return map of chord id to tick (valid after {@link #export(Path)})
     */
    public Map<Integer, Long> getChordTicks ()
    {
        return chordTicks;
    }

    //--------------//
    // getTempoQpm //
    //--------------//
    /**
     * Report the tempo used by the latest export.
     *
     * @return quarters per minute
     */
    public int getTempoQpm ()
    {
        return tempoQpm;
    }

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
        final Map<String, List<NoteEvent>> trackEvents = new TreeMap<>();
        int channelAlloc = 0;

        // Play order with repeats expanded (volta-aware)
        final List<MeasureStack> playOrder = RepeatExpander.expand(collectStacks());
        logger.info("Play order: {} stacks ({} unique)", playOrder.size(), collectStacks().size());

        // Stack -> (part -> measure) index for the whole score
        final Map<MeasureStack, Map<Part, Measure>> scoreMap = new LinkedHashMap<>();

        for (Page page : score.getPages()) {
            for (SystemInfo system : page.getSystems()) {
                for (Part part : system.getParts()) {
                    for (Measure measure : part.getMeasures()) {
                        scoreMap
                                .computeIfAbsent(measure.getStack(), s -> new LinkedHashMap<>())
                                .put(part, measure);
                    }
                }
            }
        }

        // Walk the play order ONCE; every staff track advances in lockstep,
        // sounding or silent, so staves never drift apart
        for (MeasureStack stack : playOrder) {
            final SystemInfo system = stack.getSystem();
            final Map<Part, Measure> partMap = scoreMap.get(stack);

            if (system == null || partMap == null) {
                continue;
            }

            final long advance = expectedTicks(stack);

            for (Part part : system.getParts()) {
                final LogicalPart logical = part.getLogicalPart();
                final int logicalId = (logical != null) ? logical.getId() : part.getId();
                final String partName = (logical != null && logical.getName() != null)
                        ? logical.getName() : ("Part " + logicalId);
                final int program = (logical != null && logical.getMidiProgram() != null)
                        ? logical.getMidiProgram() : 0;
                final Measure measure = partMap.get(part);

                // Per-staff dispatch within the measure (empty when missing)
                final Map<Integer, List<VoiceEntry>> byStaff = (measure != null)
                        ? dispatchByStaff(measure) : Collections.emptyMap();
                final int staffCount = Math.max(1, part.getStaves().size());

                for (int staffIndex = 0; staffIndex < staffCount; staffIndex++) {
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
                        cursors.putIfAbsent(trackKey, 0L);

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

                    final long startTick = cursors.get(trackKey);
                    final List<VoiceEntry> entries = byStaff.get(staffIndex);

                    if (entries != null && measure != null) {
                        exportEntries(
                                measure,
                                entries,
                                track,
                                channels.get(trackKey),
                                startTick,
                                timeSigs,
                                trackKey,
                                trackEvents);
                    }

                    cursors.put(trackKey, startTick + advance);
                }
            }
        }

        // Merge ties, then emit note events per track (offs before ons at
        // equal ticks). Tied-to note-ons disappear into the sustained note.
        for (Map.Entry<String, Track> trackEntry : tracks.entrySet()) {
            final String trackKey = trackEntry.getKey();
            final Track track = trackEntry.getValue();
            final List<NoteEvent> events = trackEvents.getOrDefault(
                    trackKey,
                    new ArrayList<>());
            mergeTies(events);

            events.sort((a,
                         b) -> (a.tick != b.tick) ? Long.compare(a.tick, b.tick)
                           : Boolean.compare(a.on, b.on));

            final int channel = channels.get(trackKey);

            for (NoteEvent event : events) {
                if (event.on) {
                    addNoteOn(track, event.tick, channel, event.pitch, VELOCITY);
                } else {
                    addNoteOff(track, event.tick, channel, event.pitch);
                }
            }

            track.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), lastTick(track)));
        }

        // Conductor end-of-track at the very end (never at tick 0, so players
        // and our own end detector never stop playback right at start)
        long end = 0;

        for (Track track : tracks.values()) {
            end = Math.max(end, lastTick(track));
        }

        conductor.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), end));

        midiPath.toFile().getParentFile().mkdirs();
        MidiSystem.write(sequence, 1, midiPath.toFile());
        logger.info("Exported MIDI {}", midiPath);
    }

    //----------------//
    // collectStacks //
    //----------------//
    /**
     * Collect the measure stacks of the score in score order.
     *
     * @return ordered stacks
     */
    private List<MeasureStack> collectStacks ()
    {
        final List<MeasureStack> stacks = new ArrayList<>();

        for (Page page : score.getPages()) {
            for (SystemInfo system : page.getSystems()) {
                stacks.addAll(system.getStacks());
            }
        }

        return stacks;
    }

    //----------------//
    // expectedTicks //
    //----------------//
    /**
     * Report the expected duration of a stack in ticks.
     */
    private static long expectedTicks (MeasureStack stack)
    {
        try {
            final Rational expected = stack.getExpectedDuration();

            if (expected != null && expected.doubleValue() > 0) {
                return toTicks(expected);
            }
        } catch (Exception ex) {
            logger.debug("No expected duration, using whole note", ex);
        }

        return toTicks(Rational.ONE);
    }

    //--------------//
    // findTempo //
    //--------------//
    /**
     * Report the tempo to use: explicit override, else first metronome mark
     * found in the score, else the default tempo constant.
     *
     * @return quarters per minute
     */
    private int findTempo ()
    {
        if (tempoOverride != null && tempoOverride > 0) {
            return tempoOverride;
        }
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
     * Export the entries of one staff within one measure, starting at the
     * provided tick. Cursor advancement is handled by the caller so that all
     * staff tracks stay in lockstep.
     */
    private void exportEntries (Measure measure,
                                List<VoiceEntry> entries,
                                Track track,
                                int channel,
                                long startTick,
                                Map<String, int[]> timeSigs,
                                String trackKey,
                                Map<String, List<NoteEvent>> trackEvents)
        throws Exception
    {
        final long cursor = startTick;

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
            final List<PitchedHead> pitched = new ArrayList<>();

            for (Inter inter : chord.getNotes()) {
                if (inter instanceof RestInter) {
                    continue;
                }

                if (inter instanceof HeadInter head) {
                    pitched.add(new PitchedHead(toMidi(head, entry.fifths), head.getId()));
                    headIndex.put(head.getId(), head);
                }
            }

            pitched.sort((a,
                          b) -> Integer.compare(a.pitch, b.pitch));

            final boolean tremolo = isTremolo(chord) && !pitched.isEmpty();

            if (tremolo) {
                // Repeated strikes (32nd-note grid) for tremolo signs
                final long step = Math.max(1, toTicks(new Rational(1, 32)));
                long strike = onTick;

                while (strike < onTick + durTicks) {
                    final long off = Math.min(strike + step, onTick + durTicks);

                    for (PitchedHead ph : pitched) {
                        events.add(new NoteEvent(ph.pitch, strike, true));
                        events.add(new NoteEvent(ph.pitch, off, false));
                    }

                    strike += step;
                }
            } else {
                for (int i = 0; i < pitched.size(); i++) {
                    final PitchedHead ph = pitched.get(i);
                    final long noteOn = arpeggiated ? (onTick + i * strumTicks) : onTick;
                    events.add(new NoteEvent(ph.pitch, noteOn, true, ph.headId));
                    events.add(new NoteEvent(ph.pitch, onTick + durTicks, false));
                }
            }

            // Chord marker for playback highlight sync ("chord=<id>")
            markers.add(new Marker(chord.getId(), onTick));
            chordTicks.putIfAbsent(chord.getId(), onTick);
        }

        // Stash events for end-of-export tie merging and emission
        trackEvents.computeIfAbsent(trackKey, k -> new ArrayList<>()).addAll(events);

        for (Marker marker : markers) {
            addMarker(track, marker.tick, "chord=" + marker.chordId);
        }
    }

    //-------------//
    // isTremolo //
    //-------------//
    /**
     * Report whether the chord carries a tremolo sign (on stem or whole head).
     */
    private static boolean isTremolo (AbstractChordInter chord)
    {
        try {
            final StemInter stem = chord.getStem();

            if (stem != null && !stem.getSig().getRelations(
                    stem,
                    TremoloStemRelation.class).isEmpty()) {
                return true;
            }

            for (Inter inter : chord.getNotes()) {
                if (!inter.getSig().getRelations(inter, TremoloWholeRelation.class).isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ex) {
            // Ignore
        }

        return false;
    }

    //-----------//
    // mergeTies //
    //-----------//
    /**
     * Merge tied notes within one track: a note-on whose head is tied from a
     * previous head, coinciding with that pitch note-off, disappears into the
     * sustained note (no re-articulation, extended duration).
     *
     * @param events track events (modified in place)
     */
    private void mergeTies (List<NoteEvent> events)
    {
        final java.util.Set<Long> offs = new java.util.HashSet<>();

        for (NoteEvent event : events) {
            if (!event.on) {
                offs.add((((long) event.pitch) << 32) | (event.tick & 0xFFFFFFFFL));
            }
        }

        final java.util.Set<NoteEvent> removed = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());

        for (NoteEvent event : events) {
            if (event.on && event.headId != 0 && isTieTarget(event.headId)
                    && offs.contains((((long) event.pitch) << 32) | (event.tick & 0xFFFFFFFFL))) {
                // Remove this re-articulation...
                removed.add(event);

                // ...and the matching note-off it swallows
                for (NoteEvent off : events) {
                    if (!off.on && !removed.contains(off) && off.pitch == event.pitch
                            && off.tick == event.tick) {
                        removed.add(off);

                        break;
                    }
                }
            }
        }

        if (!removed.isEmpty()) {
            events.removeIf(removed::contains);
            logger.debug("Merged {} tied note events", removed.size());
        }
    }

    //--------------//
    // isTieTarget //
    //--------------//
    /**
     * Report whether the head is tied from a previous head (right side of a
     * tie slur).
     */
    private boolean isTieTarget (int headId)
    {
        final HeadInter head = headIndex.get(headId);

        if (head == null) {
            return false;
        }

        try {
            for (org.audiveris.omr.sig.relation.Relation rel : head.getSig()
                    .getRelations(head, SlurHeadRelation.class)) {
                final SlurInter slur = (SlurInter) head.getSig().getOppositeInter(head, rel);

                if (slur.isTie() && slur.getHead(HorizontalSide.RIGHT) == head) {
                    return true;
                }
            }
        } catch (Exception ex) {
            logger.debug("Tie check failed for head {}", headId, ex);
        }

        return false;
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
                DEFAULT_QPM,
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

        /** Head id for on-events (0 when unknown), used for tie merging. */
        final int headId;

        NoteEvent (int pitch,
                   long tick,
                   boolean on)
        {
            this(pitch, tick, on, 0);
        }

        NoteEvent (int pitch,
                   long tick,
                   boolean on,
                   int headId)
        {
            this.pitch = pitch;
            this.tick = tick;
            this.on = on;
            this.headId = headId;
        }
    }

    //--------------//
    // PitchedHead //
    //--------------//
    /** A MIDI pitch with its head id, sorted bottom-up. */
    private static class PitchedHead
    {
        final int pitch;

        final int headId;

        PitchedHead (int pitch,
                     int headId)
        {
            this.pitch = pitch;
            this.headId = headId;
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
