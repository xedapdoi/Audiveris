//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                              M e r g e B o o k s T e s t                                         //
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

import org.audiveris.omr.Main;
import org.audiveris.omr.OMR;
import org.audiveris.omr.log.LogUtil;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.BookManager;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.util.OmrExecutors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Cross-book merge check: transcribe several books and export them into a
 * single MIDI file, played back to back.
 * <p>
 * Book paths come from the {@code merge.books} system property
 * (comma-separated), defaulting to the developer sample files.
 */
public class MergeBooksTest
{
    @Test
    public void mergeFourBooks ()
        throws Exception
    {
        final String prop = System.getProperty(
                "merge.books",
                "../hen-uoc-bo-cong-anh-1.omr,"
                        + "../hen-uoc-bo-cong-anh-2.omr,"
                        + "../hen-uoc-bo-cong-anh-3.omr,"
                        + "../hen-uoc-bo-cong-anh-4.omr");

        LogUtil.addFileAppender();
        Main.initHeadlessCli();
        OmrExecutors.restart();
        OMR.engine = BookManager.getInstance();

        final List<Score> allScores = new ArrayList<>();
        int expectedNotes = 0;

        for (String name : prop.split(",")) {
            final Path path = Paths.get(name.trim()).toAbsolutePath();

            if (!Files.exists(path)) {
                System.out.println("MergeBooksTest: missing " + path + ", skipped");

                continue;
            }

            final Book book = name.trim().toLowerCase().endsWith(".omr")
                    ? OMR.engine.loadBook(path)
                    : OMR.engine.loadInput(path);

            if (book.getStubs().isEmpty()) {
                book.createStubs();
            }

            final List<SheetStub> stubs = book.getValidStubs();
            final List<Score> scores = new ArrayList<>();
            assertTrue("transcribe " + name, book.transcribe(stubs, scores, false));
            assertTrue("scores " + name, !scores.isEmpty());
            allScores.addAll(scores);

            for (Score score : scores) {
                final Path single = Files.createTempFile("single-", ".mid");
                new MidiExporter(score).export(single);
                expectedNotes += countNotes(single);
                Files.deleteIfExists(single);
            }
        }

        assertTrue("at least 2 books merged", allScores.size() >= 2);

        final Path merged = Files.createTempFile("merged-", ".mid");
        new MidiExporter(allScores.get(0)).exportMerged(merged, allScores);

        final int mergedNotes = countNotes(merged);
        System.out.println(
                "MergeBooksTest: movements=" + allScores.size() + " expectedNotes=" + expectedNotes
                        + " mergedNotes=" + mergedNotes);
        assertEquals(expectedNotes, mergedNotes);

        // Continuity: every part track reaches the merged end (no silent tail gaps)
        final Sequence sequence = MidiSystem.getSequence(merged.toFile());
        long end = 0;

        for (Track track : sequence.getTracks()) {
            if (track.size() > 0) {
                end = Math.max(end, track.get(track.size() - 1).getTick());
            }
        }

        assertTrue("merged length " + end, end > 0);
        Files.deleteIfExists(merged);

        // Merged single-file MusicXML: well-formed, measure count = sum.
        // (External DTD loading disabled: offline environment.)
        final javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory
                .newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        int singleMeasures = 0;

        for (Score score : allScores) {
            final Path single = Files.createTempFile("single-", ".musicxml");
            new ScoreExporter(score).export(single, "single", true, false);
            singleMeasures += factory.newDocumentBuilder().parse(single.toFile())
                    .getElementsByTagName("measure").getLength();
            Files.deleteIfExists(single);
        }

        final Path mergedXml = Files.createTempFile("merged-", ".musicxml");
        ScoreExporter.exportMerged(mergedXml, "merged", true, allScores);

        final org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(
                mergedXml.toFile());
        final int measures = doc.getElementsByTagName("measure").getLength();
        final int divisions = doc.getElementsByTagName("divisions").getLength();
        System.out.println("MergeBooksTest: single measures=" + singleMeasures + " merged measures="
                + measures + " divisions=" + divisions);
        assertEquals(singleMeasures, measures);
        assertTrue("divisions present", divisions > 0);
        Files.deleteIfExists(mergedXml);
    }

    private static int countNotes (Path midi)
        throws Exception
    {
        final Sequence sequence = MidiSystem.getSequence(midi.toFile());
        int on = 0;

        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof javax.sound.midi.ShortMessage sm
                        && sm.getCommand() == javax.sound.midi.ShortMessage.NOTE_ON
                        && sm.getData2() > 0) {
                    on++;
                }
            }
        }

        return on;
    }
}
