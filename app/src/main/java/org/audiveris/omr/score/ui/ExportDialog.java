//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     E x p o r t D i a l o g                                      //
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

import org.audiveris.omr.OMR;
import org.audiveris.omr.score.AudioExporter;
import org.audiveris.omr.score.MidiAbstractions;
import org.audiveris.omr.score.MidiExporter;
import org.audiveris.omr.score.OpusExporter;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.score.ScoreExporter;
import org.audiveris.omr.score.ScoreJson;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.BookManager;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sheet.ui.StubsController;
import org.audiveris.omr.ui.util.OmrFileFilter;
import org.audiveris.omr.ui.util.UIUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * Class <code>ExportDialog</code> lets the user choose the scope (current book
 * or all open books) and which formats to export (MusicXML, MXL opus, MIDI,
 * MP3, WAV, JSON), optionally merging movements into single files. Only
 * checked formats are produced. Progress and errors go to the log.
 *
 * @author Audiveris contributors
 */
public class ExportDialog
        extends JDialog
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ExportDialog.class);

    //~ Instance fields ----------------------------------------------------------------------------

    private final List<Book> books;

    private final JTextField baseField;

    private final JRadioButton currentBookButton = new JRadioButton("Current book", true);

    private final JRadioButton allBooksButton = new JRadioButton("All open books");

    private final JCheckBox musicxmlBox = new JCheckBox("MusicXML (.musicxml)", true);

    private final JCheckBox opusBox = new JCheckBox("MXL opus, single file (.mxl)", false);

    private final JCheckBox midiBox = new JCheckBox("MIDI (.mid)", true);

    private final JCheckBox mp3Box = new JCheckBox("MP3 (.mp3, via ffmpeg)", false);

    private final JCheckBox wavBox = new JCheckBox("WAV (.wav)", false);

    private final JCheckBox jsonBox = new JCheckBox("JSON (.json)", false);

    private final JCheckBox mergeBox = new JCheckBox(
            "Merge movements into a single file (midi/mp3/wav/json)",
            true);

    //~ Constructors -------------------------------------------------------------------------------

    private ExportDialog (Frame owner,
                          List<Book> books)
    {
        super(owner, "Export book(s) as...", true);
        this.books = List.copyOf(books);

        final Book first = books.get(0);
        final Path sansExt = BookManager.getDefaultExportPathSansExt(first);

        setLayout(new BorderLayout(8, 8));

        final JPanel scope = new JPanel();
        scope.setLayout(new BoxLayout(scope, BoxLayout.Y_AXIS));
        scope.setBorder(BorderFactory.createTitledBorder("Scope"));

        final ButtonGroup group = new ButtonGroup();
        group.add(currentBookButton);
        group.add(allBooksButton);

        if (books.size() < 2) {
            allBooksButton.setEnabled(false);
        }

        scope.add(currentBookButton);
        scope.add(allBooksButton);
        add(scope, BorderLayout.NORTH);

        final JPanel formats = new JPanel();
        formats.setLayout(new BoxLayout(formats, BoxLayout.Y_AXIS));
        formats.setBorder(BorderFactory.createTitledBorder("Formats"));
        formats.add(musicxmlBox);
        formats.add(opusBox);
        formats.add(midiBox);
        formats.add(mp3Box);
        formats.add(wavBox);
        formats.add(jsonBox);
        formats.add(mergeBox);
        add(formats, BorderLayout.CENTER);

        final JPanel bottom = new JPanel(new BorderLayout(8, 8));
        baseField = new JTextField((sansExt != null) ? sansExt.toString() : "", 40);

        final JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> {
            final Path target = UIUtil.pathChooser(
                    true,
                    owner,
                    Paths.get(baseField.getText()),
                    new OmrFileFilter("Export base", "mxl"));

            if (target != null) {
                baseField.setText(target.toString());
            }
        });

        final JPanel pathPanel = new JPanel(new BorderLayout(4, 4));
        pathPanel.add(baseField, BorderLayout.CENTER);
        pathPanel.add(browseButton, BorderLayout.EAST);
        pathPanel.setBorder(BorderFactory.createTitledBorder("Base path (without extension)"));
        bottom.add(pathPanel, BorderLayout.CENTER);

        final JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        final JButton okButton = new JButton("Export");
        okButton.addActionListener(e -> {
            setVisible(false);
            runExport();
        });
        buttons.add(okButton);

        final JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> setVisible(false));
        buttons.add(cancelButton);
        bottom.add(buttons, BorderLayout.SOUTH);

        add(bottom, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //------//
    // show //
    //------//
    /**
     * Open the export dialog for the current book (all open books selectable).
     */
    public static void open ()
    {
        final Book current = StubsController.getCurrentBook();

        if (current == null) {
            logger.warn("No current book to export");
            return;
        }

        final List<Book> books = new ArrayList<>();
        books.add(current);

        for (Book book : OMR.engine.getAllBooks()) {
            if (book != current) {
                books.add(book);
            }
        }

        new ExportDialog(OMR.gui.getFrame(), books).setVisible(true);
    }

    //-------------//
    // movementTag //
    //-------------//
    /**
     * Build a movement file infix that never collides, even with null ids.
     *
     * @param index 0-based movement index within its book
     * @param score the movement score
     * @param total total movements in the book
     * @return "" for a single movement, else ".mvt&lt;n&gt;"
     */
    static String movementTag (int index,
                               Score score,
                               int total)
    {
        if (total <= 1) {
            return "";
        }

        final Integer id = score.getId();

        return OMR.MOVEMENT_EXTENSION + ((id != null) ? id : (index + 1));
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-----------//
    // runExport //
    //-----------//
    /**
     * Run the selected exports in the background with progress logs.
     */
    private void runExport ()
    {
        final boolean allBooks = allBooksButton.isSelected();
        final List<Book> targets = allBooks ? books : books.subList(0, 1);
        final Path base = Paths.get(baseField.getText()).toAbsolutePath();
        final boolean musicxml = musicxmlBox.isSelected();
        final boolean opus = opusBox.isSelected();
        final boolean midi = midiBox.isSelected();
        final boolean mp3 = mp3Box.isSelected();
        final boolean wav = wavBox.isSelected();
        final boolean json = jsonBox.isSelected();
        final boolean merge = mergeBox.isSelected();
        final int tempo = ScorePlayer.getInstance().getTempo();
        final String bookName = (base.getFileName() != null) ? base.getFileName().toString()
                : "export";
        final boolean signed = BookManager.useSignature();

        new Thread(() -> {
            final List<String> done = new ArrayList<>();
            final List<String> failed = new ArrayList<>();

            try {
                // 1) Transcribe every target book first (all valid stubs,
                // not just the selection which exists only for current book)
                final List<BookScores> prepared = new ArrayList<>();
                int bi = 0;

                for (Book book : targets) {
                    bi++;
                    logger.info("Export: transcribing book {}/{} ({})", bi, targets.size(), book
                            .getRadix());

                    final List<SheetStub> stubs = book.getValidStubs();
                    final List<Score> scores = new ArrayList<>();

                    if (stubs.isEmpty()) {
                        failed.add("transcribe:" + book.getRadix() + " (no valid sheets)");
                        logger.warn("Book {} has no valid sheets, skipped", book.getRadix());

                        continue;
                    }

                    if (!book.transcribe(stubs, scores, false) || scores.isEmpty()) {
                        failed.add("transcribe:" + book.getRadix());
                        logger.warn("Could not transcribe book {}", book.getRadix());

                        continue;
                    }

                    logger.info("Export: book {} has {} movement(s)", book.getRadix(), scores
                            .size());
                    prepared.add(new BookScores(book, scores));
                }

                if (prepared.isEmpty()) {
                    logger.warn("Export: nothing transcribed");

                    return;
                }

                final boolean multi = prepared.size() > 1
                        || prepared.get(0).scores.size() > 1;
                final boolean doMerge = merge && multi;

                // All scores in order (for merged outputs)
                final List<Score> allScores = new ArrayList<>();

                for (BookScores bs : prepared) {
                    allScores.addAll(bs.scores);
                }

                // 2) MusicXML per movement (always per-movement, safe names)
                if (musicxml) {
                    int mi = 0;

                    for (BookScores bs : prepared) {
                        final String prefix = (prepared.size() > 1)
                                ? (bookName + "-" + safeRadix(bs.book) + "-mvt") : bookName;

                        for (int i = 0; i < bs.scores.size(); i++) {
                            mi++;

                            final Score score = bs.scores.get(i);
                            final String name = (bs.scores.size() > 1 || prepared.size() > 1)
                                    ? (prefix + movementNumber(score, i)) : prefix;
                            final Path path = base.resolveSibling(name + OMR.SCORE_EXTENSION);
                            logger.info("Export: MusicXML movement {}/{} -> {}", mi, allScores
                                    .size(), path);

                            try {
                                new ScoreExporter(score).export(path, name, signed, false);
                                done.add(path.getFileName().toString());
                            } catch (Exception ex) {
                                failed.add(path.getFileName().toString());
                                logger.warn("MusicXML export failed for " + path, ex);
                            }
                        }
                    }
                }

                // 3) MXL opus, single file per book
                if (opus) {
                    for (BookScores bs : prepared) {
                        final String name = (prepared.size() > 1)
                                ? (bookName + "-" + safeRadix(bs.book) + ".opus"
                                        + OMR.COMPRESSED_SCORE_EXTENSION)
                                : (bookName + ".opus" + OMR.COMPRESSED_SCORE_EXTENSION);
                        final Path path = base.resolveSibling(name);
                        logger.info("Export: MXL opus -> {}", path);

                        try {
                            new OpusExporter(bs.book).export(path, bookName, signed, bs.scores);
                            done.add(path.getFileName().toString());
                        } catch (Exception ex) {
                            failed.add(path.getFileName().toString());
                            logger.warn("Opus export failed for " + path, ex);
                        }
                    }
                }

                // 4) MIDI
                if (midi) {
                    if (doMerge) {
                        final Path path = base.resolveSibling(
                                bookName + MidiAbstractions.MIDI_EXTENSION);
                        logger.info("Export: merged MIDI ({} movements) -> {}", allScores.size(),
                                path);

                        try {
                            final MidiExporter exporter = new MidiExporter(allScores.get(0));
                            exporter.setTempoOverride(tempo);
                            exporter.exportMerged(path, allScores);
                            done.add(path.getFileName().toString());
                        } catch (Exception ex) {
                            failed.add(path.getFileName().toString());
                            logger.warn("Merged MIDI export failed for " + path, ex);
                        }
                    } else {
                        int mi = 0;

                        for (BookScores bs : prepared) {
                            for (int i = 0; i < bs.scores.size(); i++) {
                                mi++;

                                final Score score = bs.scores.get(i);
                                final String name = bookName + bookSuffix(prepared, bs.book)
                                        + movementTag(i, score, bs.scores.size());
                                final Path path = base.resolveSibling(
                                        name + MidiAbstractions.MIDI_EXTENSION);
                                logger.info("Export: MIDI movement {}/{} -> {}", mi, allScores
                                        .size(), path);

                                try {
                                    final MidiExporter exporter = new MidiExporter(score);
                                    exporter.setTempoOverride(tempo);
                                    exporter.export(path);
                                    done.add(path.getFileName().toString());
                                } catch (Exception ex) {
                                    failed.add(path.getFileName().toString());
                                    logger.warn("MIDI export failed for " + path, ex);
                                }
                            }
                        }
                    }
                }

                // 5) MP3 / WAV (realtime render, logs estimated duration first)
                if (mp3 || wav) {
                    if (doMerge) {
                        if (mp3) {
                            final Path path = base.resolveSibling(bookName + ".mp3");
                            logger.info("Export: merged MP3 ({} movements) -> {}", allScores
                                    .size(), path);

                            try {
                                new AudioExporter(allScores.get(0)).exportMergedMp3(
                                        path,
                                        allScores,
                                        tempo);
                                done.add(path.getFileName().toString());
                            } catch (Exception ex) {
                                failed.add(path.getFileName().toString());
                                logger.warn("Merged MP3 export failed for " + path, ex);
                            }
                        }

                        if (wav) {
                            final Path path = base.resolveSibling(bookName + ".wav");
                            logger.info("Export: merged WAV ({} movements) -> {}", allScores
                                    .size(), path);

                            try {
                                new AudioExporter(allScores.get(0)).exportMergedWav(
                                        path,
                                        allScores,
                                        tempo);
                                done.add(path.getFileName().toString());
                            } catch (Exception ex) {
                                failed.add(path.getFileName().toString());
                                logger.warn("Merged WAV export failed for " + path, ex);
                            }
                        }
                    } else {
                        int mi = 0;

                        for (BookScores bs : prepared) {
                            for (int i = 0; i < bs.scores.size(); i++) {
                                mi++;

                                final Score score = bs.scores.get(i);
                                final String name = bookName + bookSuffix(prepared, bs.book)
                                        + movementTag(i, score, bs.scores.size());

                                if (mp3) {
                                    final Path path = base.resolveSibling(name + ".mp3");
                                    logger.info("Export: MP3 movement {}/{} -> {}", mi, allScores
                                            .size(), path);

                                    try {
                                        new AudioExporter(score).exportMp3(path, tempo);
                                        done.add(path.getFileName().toString());
                                    } catch (Exception ex) {
                                        failed.add(path.getFileName().toString());
                                        logger.warn("MP3 export failed for " + path, ex);
                                    }
                                }

                                if (wav) {
                                    final Path path = base.resolveSibling(name + ".wav");
                                    logger.info("Export: WAV movement {}/{} -> {}", mi, allScores
                                            .size(), path);

                                    try {
                                        new AudioExporter(score).exportMergedWav(
                                                path,
                                                java.util.Collections.singletonList(score),
                                                tempo);
                                        done.add(path.getFileName().toString());
                                    } catch (Exception ex) {
                                        failed.add(path.getFileName().toString());
                                        logger.warn("WAV export failed for " + path, ex);
                                    }
                                }
                            }
                        }
                    }
                }

                // 6) JSON, always a single file with all movements
                if (json) {
                    final Path path = base.resolveSibling(bookName + ".json");
                    logger.info("Export: JSON ({} movements) -> {}", allScores.size(), path);

                    try {
                        final StringBuilder sb = new StringBuilder();
                        sb.append("{\"movements\":[");

                        boolean first = true;

                        for (Score score : allScores) {
                            if (!first) {
                                sb.append(',');
                            }

                            first = false;
                            sb.append(ScoreJson.of(score));
                        }

                        sb.append("]}");
                        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
                        done.add(path.getFileName().toString());
                    } catch (Exception ex) {
                        failed.add(path.getFileName().toString());
                        logger.warn("JSON export failed for " + path, ex);
                    }
                }

                logger.info("Export done: ok={} failed={}", done, failed);
            } catch (Exception ex) {
                logger.warn("Export failed", ex);
            }
        }, "export-dialog").start();
    }

    //--------------//
    // bookSuffix //
    //--------------//
    /**
     * Extra book infix when several books are exported, else empty.
     */
    private static String bookSuffix (List<BookScores> prepared,
                                      Book book)
    {
        if (prepared.size() <= 1) {
            return "";
        }

        return "-" + safeRadix(book);
    }

    //------------//
    // safeRadix //
    //------------//
    /**
     * Filesystem-safe book radix.
     */
    private static String safeRadix (Book book)
    {
        try {
            final String radix = book.getRadix();

            if (radix != null && !radix.isBlank()) {
                return radix.replaceAll("[^A-Za-z0-9._-]+", "_");
            }
        } catch (Exception ignored) {
            // Ignore
        }

        return "book";
    }

    //-------------------//
    // movementNumber //
    //-------------------//
    /**
     * Movement number with null-id fallback (1-based).
     */
    private static String movementNumber (Score score,
                                          int index)
    {
        final Integer id = score.getId();

        return String.valueOf((id != null) ? id : (index + 1));
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-------------//
    // BookScores //
    //-------------//
    /** One transcribed book with its scores. */
    private static class BookScores
    {
        final Book book;

        final List<Score> scores;

        BookScores (Book book,
                    List<Score> scores)
        {
            this.book = book;
            this.scores = scores;
        }
    }
}
