//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                  D i g i t a l S h e e t F r a m e                               //
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

import org.audiveris.omr.score.Page;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.grid.LineInfo;
import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.Voice;
import org.audiveris.omr.sig.inter.AbstractBeamInter;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.ClefInter;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.RestInter;
import org.audiveris.omr.sig.inter.StemInter;
import static org.audiveris.omr.util.HorizontalSide.LEFT;
import static org.audiveris.omr.util.HorizontalSide.RIGHT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;

/**
 * Class <code>DigitalSheetFrame</code> is a clean notation preview of the
 * recognized scores (no OMR overlays): staves, clefs, note heads, stems,
 * beams and barlines, with Play/Stop reusing {@link ScorePlayer} and a red
 * playhead line following the sounding chord.
 *
 * @author Audiveris contributors
 */
public class DigitalSheetFrame
        extends JFrame
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(DigitalSheetFrame.class);

    /** Target system width in pixels. */
    private static final int TARGET_WIDTH = 1100;

    /** Vertical gap between systems. */
    private static final int SYSTEM_GAP = 60;

    //~ Instance fields ----------------------------------------------------------------------------

    private final Book book;

    private final SheetPanel sheetPanel;

    private final Consumer<AbstractChordInter> playheadListener = this::onChord;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a digital sheet preview for the provided book.
     *
     * @param book the book to preview
     */
    public DigitalSheetFrame (Book book)
    {
        super("Digital sheet - " + book.getRadix());
        this.book = book;

        sheetPanel = new SheetPanel();
        sheetPanel.setBackground(Color.WHITE);

        final JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        final JButton playButton = new JButton("Play");
        playButton.addActionListener(e -> ScorePlayer.getInstance().play(book));
        bar.add(playButton);

        final JButton stopButton = new JButton("Stop");
        stopButton.addActionListener(e -> ScorePlayer.getInstance().stop());
        bar.add(stopButton);

        setLayout(new BorderLayout());
        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(sheetPanel), BorderLayout.CENTER);
        setSize(1200, 800);

        ScorePlayer.getInstance().addChordListener(playheadListener);

        addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosed (WindowEvent e)
            {
                ScorePlayer.getInstance().removeChordListener(playheadListener);
            }
        });
    }

    //----------//
    // onChord //
    //----------//
    /**
     * Forward a sounding chord to the sheet panel playhead.
     */
    private void onChord (AbstractChordInter chord)
    {
        sheetPanel.movePlayhead(chord);
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //------------//
    // SheetPanel //
    //------------//
    /**
     * Paints all systems of all scores, then the playhead line.
     */
    private class SheetPanel
            extends JPanel
    {
        /** System rendering blocks. */
        final List<SystemBlock> blocks = new ArrayList<>();

        /** Playhead model point (system block + x), or null. */
        private SystemBlock playBlock;

        private double playX;

        SheetPanel ()
        {
            int y = 20;

            for (Score score : book.getScores()) {
                for (Page page : score.getPages()) {
                    for (SystemInfo system : page.getSystems()) {
                        final Rectangle bounds = system.getBounds();

                        if (bounds == null || bounds.width <= 0) {
                            continue;
                        }

                        final double scale = TARGET_WIDTH / (double) bounds.width;
                        final int height = (int) Math.round(bounds.height * scale);
                        final SystemBlock block = new SystemBlock(system, 20, y, scale);
                        blocks.add(block);
                        y += height + SYSTEM_GAP;
                    }
                }
            }

            setPreferredSize(new Dimension(TARGET_WIDTH + 40, y));
        }

        /**
         * Move the playhead to the provided chord.
         */
        void movePlayhead (AbstractChordInter chord)
        {
            try {
                final Point2D center = chord.getCenter();

                for (SystemBlock block : blocks) {
                    if (block.system == chord.getSig().getSystem()) {
                        playBlock = block;
                        playX = block.x + center.getX() * block.scale;
                        repaint();

                        return;
                    }
                }
            } catch (Exception ex) {
                logger.debug("Could not move preview playhead", ex);
            }
        }

        @Override
        protected void paintComponent (Graphics g)
        {
            super.paintComponent(g);

            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            for (SystemBlock block : blocks) {
                paintSystem(g2, block);
            }

            if (playBlock != null) {
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(
                        (int) Math.round(playX),
                        playBlock.y - 10,
                        (int) Math.round(playX),
                        playBlock.y + playBlock.height + 10);
            }

            g2.dispose();
        }

        /**
         * Paint one system in model coordinates scaled to the block.
         */
        private void paintSystem (Graphics2D g2,
                                  SystemBlock block)
        {
            final SystemInfo system = block.system;
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));

            // Staff lines
            for (Part part : system.getParts()) {
                for (Staff staff : part.getStaves()) {
                    for (LineInfo line : staff.getLines()) {
                        final Point2D p1 = line.getEndPoint(LEFT);
                        final Point2D p2 = line.getEndPoint(RIGHT);
                        g2.drawLine(
                                toX(block, p1.getX()),
                                toY(block, p1.getY()),
                                toX(block, p2.getX()),
                                toY(block, p2.getY()));
                    }
                }
            }

            // Clef letters (first clef per staff)
            g2.setFont(new Font(Font.SERIF, Font.BOLD, 28));

            for (Part part : system.getParts()) {
                for (Staff staff : part.getStaves()) {
                    final String letter = clefLetter(system, staff);

                    if (letter != null) {
                        final List<LineInfo> lines = staff.getLines();

                        if (!lines.isEmpty()) {
                            final Point2D p = lines.get(2).getEndPoint(LEFT);
                            g2.drawString(letter, toX(block, p.getX()) - 34, toY(
                                    block,
                                    p.getY()) + 10);
                        }
                    }
                }
            }

            // Chords: heads, stems, beams, barlines
            for (Part part : system.getParts()) {
                for (Measure measure : part.getMeasures()) {
                    int measureRight = Integer.MIN_VALUE;
                    int staffTop = Integer.MAX_VALUE;
                    int staffBottom = Integer.MIN_VALUE;

                    for (Voice voice : measure.getVoices()) {
                        for (AbstractChordInter chord : voice.getChords()) {
                            paintChord(g2, block, chord);

                            final Rectangle box = chord.getBounds();
                            measureRight = Math.max(measureRight, toX(block, box.x + box.width));

                            try {
                                final Staff staff = chord.getStaff();

                                if (staff != null) {
                                    final List<LineInfo> lines = staff.getLines();

                                    if (!lines.isEmpty()) {
                                        staffTop = Math.min(
                                                staffTop,
                                                toY(
                                                        block,
                                                        lines.get(0).getEndPoint(LEFT).getY()));
                                        staffBottom = Math.max(
                                                staffBottom,
                                                toY(
                                                        block,
                                                        lines.get(lines.size() - 1).getEndPoint(
                                                                LEFT).getY()));
                                    }
                                }
                            } catch (Exception ignored) {
                                // Ignore
                            }
                        }
                    }

                    if (measureRight > Integer.MIN_VALUE && staffBottom > staffTop) {
                        g2.drawLine(measureRight + 6, staffTop, measureRight + 6, staffBottom);
                    }
                }
            }

            // Beams (after chords so they stay on top)
            for (Part part : system.getParts()) {
                for (Measure measure : part.getMeasures()) {
                    for (Voice voice : measure.getVoices()) {
                        for (AbstractChordInter chord : voice.getChords()) {
                            final StemInter stem = chord.getStem();

                            if (stem == null) {
                                continue;
                            }

                            for (org.audiveris.omr.sig.relation.Relation rel : stem.getSig()
                                    .getRelations(
                                            stem,
                                            org.audiveris.omr.sig.relation.BeamStemRelation.class)) {
                                final Inter beam = stem.getSig().getOppositeInter(stem, rel);

                                if (beam instanceof AbstractBeamInter beamInter) {
                                    paintBeam(g2, block, beamInter);
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * Paint one chord (heads + stem, or rest block).
         */
        private void paintChord (Graphics2D g2,
                                 SystemBlock block,
                                 AbstractChordInter chord)
        {
            if (chord.isRest()) {
                final Rectangle box = chord.getBounds();
                g2.fillRect(
                        toX(block, box.x + box.width / 4),
                        toY(block, box.y + box.height / 4),
                        Math.max(3, (int) Math.round(box.width * block.scale / 2)),
                        Math.max(3, (int) Math.round(box.height * block.scale / 2)));

                return;
            }

            for (Inter inter : chord.getNotes()) {
                if (inter instanceof RestInter) {
                    continue;
                }

                if (inter instanceof HeadInter head) {
                    final Rectangle box = head.getBounds();
                    final int x = toX(block, box.x);
                    final int y = toY(block, box.y);
                    final int w = Math.max(4, (int) Math.round(box.width * block.scale));
                    final int h = Math.max(3, (int) Math.round(box.height * block.scale));
                    final boolean hollow = head.getShape().name().contains("VOID");

                    if (hollow) {
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawOval(x, y, w, h);
                        g2.setStroke(new BasicStroke(1f));
                    } else {
                        g2.fillOval(x, y, w, h);
                    }
                }
            }

            final StemInter stem = chord.getStem();

            if (stem != null) {
                final Line2D median = stem.getMedian();
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(
                        toX(block, median.getX1()),
                        toY(block, median.getY1()),
                        toX(block, median.getX2()),
                        toY(block, median.getY2()));
                g2.setStroke(new BasicStroke(1f));
            }
        }

        /**
         * Paint one beam as a filled parallelogram.
         */
        private void paintBeam (Graphics2D g2,
                                SystemBlock block,
                                AbstractBeamInter beam)
        {
            final Line2D median = beam.getMedian();

            if (median == null) {
                return;
            }

            final double half = beam.getHeight() / 2;
            final int[] xs = { toX(block, median.getX1()), toX(block, median.getX2()),
                    toX(block, median.getX2()), toX(block, median.getX1()) };
            final int[] ys = { toY(block, median.getY1() - half), toY(block, median.getY2() - half),
                    toY(block, median.getY2() + half), toY(block, median.getY1() + half) };
            g2.fillPolygon(xs, ys, 4);
        }

        /**
         * Report the clef letter for a staff (first clef found).
         */
        private String clefLetter (SystemInfo system,
                                   Staff staff)
        {
            try {
                final List<Inter> clefs = new ArrayList<>(
                        system.getSig().inters(ClefInter.class));
                Collections.sort(clefs, (a, b) -> Integer.compare(a.getId(), b.getId()));

                for (Inter inter : clefs) {
                    final ClefInter clef = (ClefInter) inter;

                    if (clef.getStaff() == staff) {
                        final String name = clef.getShape().name();

                        if (name.startsWith("G_")) {
                            return "G";
                        } else if (name.startsWith("F_")) {
                            return "F";
                        } else if (name.startsWith("C_")) {
                            return "C";
                        }

                        return "?";
                    }
                }
            } catch (Exception ignored) {
                // Ignore
            }

            return null;
        }

        private int toX (SystemBlock block,
                         double modelX)
        {
            return (int) Math.round(block.x + (modelX - block.originX) * block.scale);
        }

        private int toY (SystemBlock block,
                         double modelY)
        {
            return (int) Math.round(block.y + (modelY - block.originY) * block.scale);
        }
    }

    //-------------//
    // SystemBlock //
    //-------------//
    /**
     * Placement of one system in the panel.
     */
    private static class SystemBlock
    {
        final SystemInfo system;

        final int x;

        final int y;

        final double scale;

        final double originX;

        final double originY;

        final int height;

        SystemBlock (SystemInfo system,
                     int x,
                     int y,
                     double scale)
        {
            this.system = system;
            this.x = x;
            this.y = y;
            this.scale = scale;

            final Rectangle bounds = system.getBounds();
            this.originX = bounds.x;
            this.originY = bounds.y;
            this.height = (int) Math.round(bounds.height * scale);
        }
    }
}
