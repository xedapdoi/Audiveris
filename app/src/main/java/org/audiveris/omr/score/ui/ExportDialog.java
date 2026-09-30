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
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Class <code>ExportDialog</code> lets the user choose which formats to export
 * (MusicXML, MXL opus, MIDI, MP3, WAV, JSON) and whether movements are merged
 * into a single file. Only checked formats are produced.
 *
 * @author Audiveris contributors
 */
public class ExportDialog
        extends JDialog
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ExportDialog.class);

    //~ Instance fields ----------------------------------------------------------------------------

    private final Book book;

    private final List<Score> scores;

    private final JTextField baseField;

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
                          Book book,
                          List<Score> scores)
    {
        super(owner, "Export book as...", true);
        this.book = book;
        this.scores = List.copyOf(scores);

        final Path sansExt = BookManager.getDefaultExportPathSansExt(book);

        setLayout(new BorderLayout(8, 8));

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
     * Open the export dialog for the provided book.
     *
     * @param book   the book to export
     * @param scores the transcribed scores
     */
    public static void show (Book book,
                             List<Score> scores)
    {
        new ExportDialog(OMR.gui.getFrame(), book, scores).setVisible(true);
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-----------//
    // runExport //
    //-----------//
    /**
     * Run the selected exports in the background.
     */
    private void runExport ()
    {
        final Path base = Paths.get(baseField.getText());
        final boolean musicxml = musicxmlBox.isSelected();
        final boolean opus = opusBox.isSelected();
        final boolean midi = midiBox.isSelected();
        final boolean mp3 = mp3Box.isSelected();
        final boolean wav = wavBox.isSelected();
        final boolean json = jsonBox.isSelected();
        final boolean merge = mergeBox.isSelected();
        final int tempo = ScorePlayer.getInstance().getTempo();
        final String bookName = base.getFileName().toString();
        final boolean signed = BookManager.useSignature();

        new Thread(() -> {
            try {
                if (musicxml) {
                    for (Score score : scores) {
                        final String name = (scores.size() > 1)
                                ? (bookName + OMR.MOVEMENT_EXTENSION + score.getId()) : bookName;
                        new ScoreExporter(score).export(
                                base.resolveSibling(name + OMR.SCORE_EXTENSION),
                                name,
                                signed,
                                false);
                    }
                }

                if (opus) {
                    new OpusExporter(book).export(
                            base.resolveSibling(bookName + ".opus" + OMR.COMPRESSED_SCORE_EXTENSION),
                            bookName,
                            signed,
                            scores);
                }

                if (midi) {
                    if (merge || scores.size() == 1) {
                        final MidiExporter exporter = new MidiExporter(scores.get(0));
                        exporter.setTempoOverride(tempo);
                        exporter.exportMerged(
                                base.resolveSibling(bookName + MidiAbstractions.MIDI_EXTENSION),
                                scores);
                    } else {
                        for (Score score : scores) {
                            final String name = bookName + OMR.MOVEMENT_EXTENSION + score.getId();
                            final MidiExporter exporter = new MidiExporter(score);
                            exporter.setTempoOverride(tempo);
                            exporter.export(
                                    base.resolveSibling(
                                            name + MidiAbstractions.MIDI_EXTENSION));
                        }
                    }
                }

                if (mp3 || wav) {
                    if (merge || scores.size() == 1) {
                        if (mp3) {
                            new AudioExporter(scores.get(0)).exportMergedMp3(
                                    base.resolveSibling(bookName + ".mp3"),
                                    scores,
                                    tempo);
                        }

                        if (wav) {
                            new AudioExporter(scores.get(0)).exportMergedWav(
                                    base.resolveSibling(bookName + ".wav"),
                                    scores,
                                    tempo);
                        }
                    } else {
                        for (Score score : scores) {
                            final String name = bookName + OMR.MOVEMENT_EXTENSION + score.getId();

                            if (mp3) {
                                new AudioExporter(score).exportMp3(
                                        base.resolveSibling(name + ".mp3"),
                                        tempo);
                            }

                            if (wav) {
                                new AudioExporter(score).exportMergedWav(
                                        base.resolveSibling(name + ".wav"),
                                        java.util.Collections.singletonList(score),
                                        tempo);
                            }
                        }
                    }
                }

                if (json) {
                    final StringBuilder sb = new StringBuilder();
                    sb.append("{\"movements\":[");

                    boolean first = true;

                    for (Score score : scores) {
                        if (!first) {
                            sb.append(',');
                        }

                        first = false;
                        sb.append(ScoreJson.of(score));
                    }

                    sb.append("]}");
                    Files.write(
                            base.resolveSibling(bookName + ".json"),
                            sb.toString().getBytes(StandardCharsets.UTF_8));
                }

                logger.info("Export dialog done");
            } catch (Exception ex) {
                logger.warn("Export failed", ex);
            }
        }, "export-dialog").start();
    }
}
