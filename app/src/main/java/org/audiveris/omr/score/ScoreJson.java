//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                         S c o r e J s o n                                        //
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
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.KeyInter;
import org.audiveris.omr.sig.inter.RestInter;

/**
 * Class <code>ScoreJson</code> serializes a {@link Score} to a compact JSON
 * structure (no third-party dependency): parts -&gt; staves -&gt; measures
 * -&gt; voices -&gt; chords with onset/duration and note step/octave/alter.
 * Durations are expressed in quarter-note multiples (1.0 = one quarter).
 *
 * @author Audiveris contributors
 */
public class ScoreJson
{
    //~ Constructors -------------------------------------------------------------------------------

    private ScoreJson ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //--------//
    // of //
    //--------//
    /**
     * Serialize a score to JSON text.
     *
     * @param score the score to serialize
     * @return JSON text
     */
    public static String of (Score score)
    {
        final StringBuilder sb = new StringBuilder(65536);
        sb.append("{\"id\":").append(score.getId()).append(",\"parts\":[");

        boolean firstPart = true;

        for (Page page : score.getPages()) {
            for (SystemInfo system : page.getSystems()) {
                for (Part part : system.getParts()) {
                    if (!firstPart) {
                        sb.append(',');
                    }

                    firstPart = false;
                    appendPart(sb, part);
                }
            }
        }

        sb.append("]}");

        return sb.toString();
    }

    //------------//
    // appendPart //
    //------------//
    private static void appendPart (StringBuilder sb,
                                    Part part)
    {
        sb.append("{\"staves\":").append(part.getStaves().size()).append(",\"measures\":[");

        boolean firstMeasure = true;
        Integer fifths = null;

        for (Measure measure : part.getMeasures()) {
            if (measure.hasKeys()) {
                try {
                    final KeyInter key = measure.getKey(0);

                    if (key != null && key.getFifths() != null) {
                        fifths = key.getFifths();
                    }
                } catch (Exception ignored) {
                    // Ignore
                }
            }

            if (!firstMeasure) {
                sb.append(',');
            }

            firstMeasure = false;
            appendMeasure(sb, measure, fifths);
        }

        sb.append("]}");
    }

    //---------------//
    // appendMeasure //
    //---------------//
    private static void appendMeasure (StringBuilder sb,
                                       Measure measure,
                                       Integer fifths)
    {
        final var stack = measure.getStack();
        final boolean repeatLeft = stack != null && stack.isRepeat(
                org.audiveris.omr.util.HorizontalSide.LEFT);
        final boolean repeatRight = stack != null && stack.isRepeat(
                org.audiveris.omr.util.HorizontalSide.RIGHT);
        final java.util.Set<Integer> endings = (stack != null)
                ? RepeatExpander.endingNumbers(stack) : java.util.Collections.emptySet();

        sb.append("{\"repeatLeft\":").append(repeatLeft);
        sb.append(",\"repeatRight\":").append(repeatRight);
        sb.append(",\"ending\":[");

        boolean firstEnding = true;

        for (int number : endings) {
            if (!firstEnding) {
                sb.append(',');
            }

            firstEnding = false;
            sb.append(number);
        }

        sb.append("],\"voices\":[");

        boolean firstVoice = true;

        for (Voice voice : measure.getVoices()) {
            if (!firstVoice) {
                sb.append(',');
            }

            firstVoice = false;
            sb.append("{\"id\":").append(voice.getId()).append(",\"chords\":[");

            boolean firstChord = true;

            if (!voice.isMeasureRest()) {
                for (AbstractChordInter chord : voice.getChords()) {
                    if (!firstChord) {
                        sb.append(',');
                    }

                    firstChord = false;
                    appendChord(sb, chord, fifths);
                }
            }

            sb.append("]}");
        }

        sb.append("]}");
    }

    //--------------//
    // appendChord //
    //--------------//
    private static void appendChord (StringBuilder sb,
                                     AbstractChordInter chord,
                                     Integer fifths)
    {
        final Rational onset = chord.getTimeOffset();
        final Rational duration = chord.getDuration();

        sb.append("{\"onset\":").append(quartets(onset));
        sb.append(",\"duration\":").append(quartets(duration));
        sb.append(",\"rest\":").append(chord.isRest());
        sb.append(",\"notes\":[");

        boolean firstNote = true;

        for (Inter inter : chord.getNotes()) {
            if (inter instanceof RestInter) {
                continue;
            }

            if (inter instanceof HeadInter head) {
                if (!firstNote) {
                    sb.append(',');
                }

                firstNote = false;
                sb.append("{\"step\":\"").append(head.getStep().name());
                sb.append("\",\"octave\":").append(head.getOctave());
                sb.append(",\"alter\":").append(head.getAlteration(fifths));
                sb.append('}');
            }
        }

        sb.append("]}");
    }

    //----------//
    // quartets //
    //----------//
    /**
     * Convert a whole-note-based rational to quarter-note multiples.
     */
    private static double quartets (Rational rational)
    {
        if (rational == null) {
            return 0;
        }

        return rational.doubleValue() * 4;
    }
}
