//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                  R e p e a t E x p a n d e r                                     //
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

import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.sig.inter.EndingInter;
import org.audiveris.omr.sheet.PartBarline;
import static org.audiveris.omr.util.HorizontalSide.LEFT;
import static org.audiveris.omr.util.HorizontalSide.RIGHT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Class <code>RepeatExpander</code> computes the play order of measure stacks,
 * expanding backward repeats (with volta first/second endings support).
 * <p>
 * Supported cases:
 * <ul>
 * <li>Plain backward repeat (no endings): the section plays twice.
 * <li>Volta endings (e.g. "1." then "2."): first pass plays ending 1 and jumps
 * back, second pass skips ending 1 and plays ending 2.
 * <li>Nested repeats via a context stack.
 * </ul>
 * A backward repeat with no matching forward repeat restarts from the
 * beginning. Segno/coda jumps are not expanded (played straight through).
 *
 * @author Audiveris contributors
 */
public class RepeatExpander
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(RepeatExpander.class);

    /** Maximum total plays of a repeated section (initial + repeats). */
    private static final int MAX_PLAYS = 2;

    //~ Constructors -------------------------------------------------------------------------------

    private RepeatExpander ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //--------//
    // expand //
    //--------//
    /**
     * Compute the play order of the provided stacks.
     *
     * @param stacks stacks in score order
     * @return stacks in play order (repeated sections duplicated, skipped
     *         voltas removed)
     */
    public static List<MeasureStack> expand (List<MeasureStack> stacks)
    {
        final List<MeasureStack> order = new ArrayList<>();
        final Deque<RepeatCtx> pile = new ArrayDeque<>();
        int i = 0;

        while (i < stacks.size()) {
            final MeasureStack stack = stacks.get(i);

            if (stack.isRepeat(LEFT) && (pile.isEmpty() || pile.peek().start != i)) {
                pile.push(new RepeatCtx(i));
                logger.debug("Repeat start at stack {}", stack.getIdValue());
            }

            final Set<Integer> endings = endingNumbers(stack);

            if (!pile.isEmpty() && !endings.isEmpty() && isSubset(endings, pile.peek().played)) {
                // Volta already played in a previous pass: skip to past its closing repeat
                final int closing = findClosingRepeat(stacks, i);

                if (closing < 0) {
                    // No closing repeat: play straight through (safer)
                    logger.debug("Volta {} with no closing repeat, playing through", endings);
                } else {
                    logger.debug("Skipping volta {} forward", endings);
                    i = closing + 1;

                    continue;
                }
            }

            order.add(stack);

            if (!pile.isEmpty()) {
                pile.peek().seen.addAll(endings);
            }

            if (stack.isRepeat(RIGHT)) {
                if (pile.isEmpty()) {
                    // Backward repeat with no forward sign: from the beginning, once.
                    // Pushed so the second arrival terminates instead of looping.
                    pile.push(new RepeatCtx(0));
                    logger.debug("Backward repeat with no forward sign");
                }

                final RepeatCtx ctx = pile.peek();
                ctx.plays++;
                ctx.played.addAll(ctx.seen);
                ctx.seen.clear();

                if (ctx.plays < MAX_PLAYS) {
                    logger.debug("Repeating section");
                    i = ctx.start;

                    continue;
                } else {
                    pile.pop();
                }
            }

            i++;
        }

        return order;
    }

    //----------------//
    // endingNumbers //
    //----------------//
    /**
     * Report the volta ending numbers attached to the provided stack,
     * unioned over all parts.
     *
     * @param stack the stack to inspect
     * @return sorted set of ending numbers (empty if none)
     */
    static Set<Integer> endingNumbers (MeasureStack stack)
    {
        final Set<Integer> numbers = new TreeSet<>();

        try {
            final SystemInfo system = stack.getSystem();

            if (system == null) {
                return numbers;
            }

            for (Part part : system.getParts()) {
                for (Measure measure : part.getMeasures()) {
                    if (measure.getStack() != stack) {
                        continue;
                    }

                    collectEnding(measure.getLeftPartBarline(), LEFT, numbers);
                    collectEnding(measure.getRightPartBarline(), RIGHT, numbers);
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not read endings", ex);
        }

        return numbers;
    }

    //----------------//
    // collectEnding //
    //----------------//
    private static void collectEnding (PartBarline barline,
                                       org.audiveris.omr.util.HorizontalSide side,
                                       Set<Integer> numbers)
    {
        if (barline == null) {
            return;
        }

        try {
            final EndingInter ending = barline.getEnding(side);

            if (ending == null) {
                return;
            }

            String value = ending.getExportedNumber();

            if (value == null) {
                value = ending.getNumber();
            }

            if (value != null) {
                for (String token : value.split("[^0-9]+")) {
                    if (!token.isEmpty()) {
                        numbers.add(Integer.parseInt(token));
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not read ending number", ex);
        }
    }

    //--------------------//
    // findClosingRepeat //
    //--------------------//
    /**
     * Find the first stack at or after the provided index with a right repeat.
     *
     * @param stacks stacks in score order
     * @param from   start index (inclusive)
     * @return index of closing stack, or -1 if none
     */
    private static int findClosingRepeat (List<MeasureStack> stacks,
                                          int from)
    {
        for (int j = from; j < stacks.size(); j++) {
            if (stacks.get(j).isRepeat(RIGHT)) {
                return j;
            }
        }

        return -1;
    }

    //-----------//
    // isSubset //
    //-----------//
    private static boolean isSubset (Set<Integer> endings,
                                     Set<Integer> played)
    {
        return played.containsAll(endings);
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // RepeatCtx //
    //-----------//
    /** Bookkeeping for one open repeat section. */
    private static class RepeatCtx
    {
        /** Index of the repeat start stack. */
        final int start;

        /** Completed plays (initial play counts once it passes the end). */
        int plays;

        /** Ending numbers already played. */
        final Set<Integer> played = new HashSet<>();

        /** Ending numbers seen in the current pass. */
        final Set<Integer> seen = new HashSet<>();

        RepeatCtx (int start)
        {
            this.start = start;
        }
    }
}
