//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      S c o r e M e r g e r                                       //
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

import org.audiveris.proxymusic.Attributes;
import org.audiveris.proxymusic.ScorePartwise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

/**
 * Class <code>ScoreMerger</code> concatenates several movement partwise scores
 * into a single partwise score (measures appended part by part, renumbered
 * sequentially). Divisions changes across movements are preserved by ensuring
 * the first appended measure of each movement carries its divisions value, so
 * durations stay valid for plain MusicXML readers without opus support.
 *
 * @author Audiveris contributors
 */
public class ScoreMerger
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ScoreMerger.class);

    //~ Constructors -------------------------------------------------------------------------------

    private ScoreMerger ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //-------//
    // merge //
    //-------//
    /**
     * Merge movement partwise scores into the first one (modified in place).
     *
     * @param movements partwise scores in play order (at least one)
     * @return the first score, holding all measures
     */
    public static ScorePartwise merge (List<ScorePartwise> movements)
    {
        final ScorePartwise base = movements.get(0);

        for (int k = 1; k < movements.size(); k++) {
            final ScorePartwise next = movements.get(k);
            final List<ScorePartwise.Part> baseParts = base.getPart();
            final List<ScorePartwise.Part> nextParts = next.getPart();

            if (nextParts.size() != baseParts.size()) {
                logger.warn(
                        "Part count mismatch ({} vs {}), appending anyway",
                        baseParts.size(),
                        nextParts.size());
            }

            for (int i = 0; i < nextParts.size(); i++) {
                final List<ScorePartwise.Part.Measure> nextMeasures = nextParts.get(i)
                        .getMeasure();

                if (nextMeasures.isEmpty()) {
                    continue;
                }

                ensureDivisions(nextMeasures.get(0), divisionsOf(nextParts.get(i)));

                if (i < baseParts.size()) {
                    baseParts.get(i).getMeasure().addAll(nextMeasures);
                } else {
                    // Movement with extra parts: append the whole part
                    baseParts.add(nextParts.get(i));
                }
            }
        }

        // Renumber measures sequentially per part
        for (ScorePartwise.Part part : base.getPart()) {
            int number = 0;

            for (ScorePartwise.Part.Measure measure : part.getMeasure()) {
                measure.setNumber(String.valueOf(++number));
            }
        }

        // Movement title reflects the merge
        try {
            base.setMovementTitle("Merged movements");
        } catch (Exception ex) {
            logger.debug("Could not set movement title", ex);
        }

        return base;
    }

    //--------------//
    // divisionsOf //
    //--------------//
    /**
     * Report the divisions value used by a part (first attributes found).
     *
     * @return divisions or null
     */
    private static BigDecimal divisionsOf (ScorePartwise.Part part)
    {
        for (ScorePartwise.Part.Measure measure : part.getMeasure()) {
            for (Object item : measure.getNoteOrBackupOrForward()) {
                if (item instanceof Attributes attributes && attributes.getDivisions() != null) {
                    return attributes.getDivisions();
                }
            }
        }

        return null;
    }

    //-----------------//
    // ensureDivisions //
    //-----------------//
    /**
     * Make sure the provided (first appended) measure carries the divisions
     * value, creating attributes if needed.
     */
    private static void ensureDivisions (ScorePartwise.Part.Measure measure,
                                         BigDecimal divisions)
    {
        if (divisions == null) {
            return;
        }

        for (Object item : measure.getNoteOrBackupOrForward()) {
            if (item instanceof Attributes attributes) {
                if (attributes.getDivisions() == null) {
                    attributes.setDivisions(divisions);
                }

                return;
            }
        }

        // No attributes at all: insert divisions-only attributes first
        final Attributes attributes = new Attributes();
        attributes.setDivisions(divisions);
        measure.getNoteOrBackupOrForward().add(0, attributes);
    }
}
