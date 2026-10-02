package org.audiveris.omr.score;

import org.audiveris.omr.Main;
import org.audiveris.omr.OMR;
import org.audiveris.omr.log.LogUtil;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.BookManager;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.util.OmrExecutors;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class RepeatDiagTest
{
    @Test
    public void diagnose ()
        throws Exception
    {
        LogUtil.addFileAppender();
        Main.initHeadlessCli();
        OmrExecutors.restart();
        OMR.engine = BookManager.getInstance();

        final Book book = OMR.engine.loadBook(
                Paths.get("../hen-uoc-bo-cong-anh-Full.omr").toAbsolutePath());
        System.out.println("RepeatDiagTest: sheets=" + book.getStubs().size());

        // Combined stacks across all scores (single continuous piece)
        final List<MeasureStack> stacks = new ArrayList<>();

        for (Score score : book.getScores()) {
            for (Page page : score.getPages()) {
                for (SystemInfo system : page.getSystems()) {
                    stacks.addAll(system.getStacks());
                }
            }
        }

        System.out.println("RepeatDiagTest: combined stacks=" + stacks.size());

        final List<MeasureStack> order = RepeatExpander.expand(stacks);
        System.out.println("RepeatDiagTest: play order size=" + order.size());

        // Position of each played stack in the original list
        final Map<MeasureStack, Integer> positions = new HashMap<>();

        for (int i = 0; i < stacks.size(); i++) {
            positions.putIfAbsent(stacks.get(i), i);
        }

        final StringBuilder sb = new StringBuilder("RepeatDiagTest: positions:");

        for (MeasureStack s : order) {
            sb.append(' ').append(positions.get(s));
        }

        System.out.println(sb);

        // A backward jump must exist (repeat back to page 2 region)
        boolean jumpedBack = false;
        int prev = -1;

        for (MeasureStack s : order) {
            final int pos = positions.get(s);

            if (pos < prev) {
                jumpedBack = true;
            }

            prev = pos;
        }

        assertTrue("expected a backward repeat jump", jumpedBack);

        // Volta stacks (with endings) play exactly once (skipped on 2nd pass)
        final Map<MeasureStack, Integer> counts = new HashMap<>();

        for (MeasureStack s : order) {
            counts.merge(s, 1, Integer::sum);
        }

        int voltaOnce = 0;

        for (MeasureStack s : stacks) {
            if (!RepeatExpander.endingNumbers(s).isEmpty()) {
                final int count = counts.getOrDefault(s, 0);
                System.out.println("RepeatDiagTest: volta stack plays=" + count);
                assertTrue("volta stack must play exactly once", count == 1);
                voltaOnce++;
            }
        }

        assertTrue("expected volta stacks, found " + voltaOnce, voltaOnce > 0);
        assertTrue("expansion expected", order.size() > stacks.size());
    }
}
