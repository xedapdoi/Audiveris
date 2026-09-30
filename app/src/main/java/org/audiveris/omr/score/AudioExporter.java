//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     A u d i o E x p o r t e r                                    //
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

import com.sun.media.sound.AudioSynthesizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Class <code>AudioExporter</code> exports a {@link Score} to MP3.
 * <p>
 * Pipeline: score -&gt; MIDI ({@link MidiExporter}, pure Java) -&gt; WAV
 * (offline render through the JDK software synthesizer, pure Java) -&gt; MP3
 * (external {@code ffmpeg} encoder). Only the last step needs an external tool;
 * its path is configurable, so nothing heavy is bundled with the app. If the
 * encoder is missing or fails, the intermediate WAV is kept next to the target.
 *
 * @author Audiveris contributors
 */
public class AudioExporter
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(AudioExporter.class);

    /** Sample rate for the intermediate WAV. */
    private static final float SAMPLE_RATE = 44100f;

    /** Extra tail in seconds so reverb/ring-out is not cut. */
    private static final int TAIL_SECONDS = 2;

    //~ Instance fields ----------------------------------------------------------------------------

    private final Score score;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new <code>AudioExporter</code> object.
     *
     * @param score the score to export
     */
    public AudioExporter (Score score)
    {
        this.score = score;
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //----------------//
    // getFfmpegPath //
    //----------------//
    /**
     * Report the configured ffmpeg path.
     *
     * @return ffmpeg executable path (or plain "ffmpeg" from PATH)
     */
    public static String getFfmpegPath ()
    {
        return constants.ffmpegPath.getValue();
    }

    //----------------//
    // setFfmpegPath //
    //----------------//
    /**
     * Define the ffmpeg executable path.
     *
     * @param path ffmpeg path
     */
    public static void setFfmpegPath (String path)
    {
        constants.ffmpegPath.setValue(path);
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-----------//
    // exportMp3 //
    //-----------//
    /**
     * Export one score to the provided MP3 file path.
     *
     * @param mp3Path       target .mp3 path (parent folders are created)
     * @param tempoOverride explicit tempo in qpm, null for default
     * @throws Exception if anything goes wrong
     */
    public void exportMp3 (Path mp3Path,
                           Integer tempoOverride)
        throws Exception
    {
        exportMergedMp3(mp3Path, java.util.Collections.singletonList(score), tempoOverride);
    }

    //------------------//
    // exportMergedMp3 //
    //------------------//
    /**
     * Export several scores (movements) into a single MP3 file, played one
     * after the other.
     *
     * @param mp3Path       target .mp3 path (parent folders are created)
     * @param scores        scores in play order
     * @param tempoOverride explicit tempo in qpm, null for default
     * @throws Exception if anything goes wrong
     */
    public void exportMergedMp3 (Path mp3Path,
                                 java.util.List<Score> scores,
                                 Integer tempoOverride)
        throws Exception
    {
        mp3Path.toFile().getParentFile().mkdirs();

        final Path wavFile = Files.createTempFile("audiveris-audio-", ".wav");

        try {
            final MidiExporter midiExporter = new MidiExporter(
                    scores.isEmpty() ? score : scores.get(0));
            midiExporter.setTempoOverride(tempoOverride);
            final Sequence sequence = midiExporter.buildSequence(
                    scores.isEmpty() ? java.util.Collections.singletonList(score) : scores);
            renderWav(sequence, wavFile);
            encodeMp3(wavFile, mp3Path);
            logger.info("Exported merged MP3 {}", mp3Path);
        } finally {
            if (Files.exists(mp3Path)) {
                Files.deleteIfExists(wavFile);
            } else {
                final Path kept = mp3Path.resolveSibling(
                        mp3Path.getFileName().toString().replaceAll("\\.[^.]*$", "")
                                + ".wav");
                Files.move(wavFile, kept);
                logger.warn("Kept intermediate WAV {}", kept);
            }
        }
    }

    //------------------//
    // exportMergedWav //
    //------------------//
    /**
     * Export several scores (movements) into a single WAV file.
     *
     * @param wavPath       target .wav path (parent folders are created)
     * @param scores        scores in play order
     * @param tempoOverride explicit tempo in qpm, null for default
     * @throws Exception if anything goes wrong
     */
    public void exportMergedWav (Path wavPath,
                                 java.util.List<Score> scores,
                                 Integer tempoOverride)
        throws Exception
    {
        wavPath.toFile().getParentFile().mkdirs();

        final MidiExporter midiExporter = new MidiExporter(
                scores.isEmpty() ? score : scores.get(0));
        midiExporter.setTempoOverride(tempoOverride);
        final Sequence sequence = midiExporter.buildSequence(
                scores.isEmpty() ? java.util.Collections.singletonList(score) : scores);
        renderWav(sequence, wavPath);
        logger.info("Exported merged WAV {}", wavPath);
    }

    //------------//
    // renderWav //
    //------------//
    /**
     * Render a MIDI sequence to WAV (pure Java, no external tool).
     * Reads are paced to realtime so the sequencer events line up with the
     * rendered samples (unpaced offline pulls run ahead and capture silence).
     */
    private static void renderWav (Sequence sequence,
                                   Path wavFile)
        throws Exception
    {
        final AudioSynthesizer synthesizer = (AudioSynthesizer) MidiSystem.getSynthesizer();
        final AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
        final Map<String, Object> info = new HashMap<>();
        final AudioInputStream raw = synthesizer.openStream(format, info);
        final long frames = (long) (sequence.getMicrosecondLength() / 1000000.0 * SAMPLE_RATE)
                + (long) SAMPLE_RATE * TAIL_SECONDS;

        final Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.open();
        sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
        sequencer.setSequence(sequence);

        try {
            sequencer.start();

            final byte[] buffer = new byte[(int) (SAMPLE_RATE * 4 / 10)]; // 0.1 s
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final long startNanos = System.nanoTime();
            long got = 0;
            int n;

            while (got < frames) {
                final int want = (int) Math.min(buffer.length, (frames - got) * 4);
                n = raw.read(buffer, 0, want);

                if (n <= 0) {
                    break;
                }

                out.write(buffer, 0, n);
                got += n / 4;

                final long elapsedMs = (System.nanoTime() - startNanos) / 1000000;
                final long expectedMs = got * 1000 / (long) SAMPLE_RATE;
                final long sleepMs = expectedMs - elapsedMs;

                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }

            final byte[] data = out.toByteArray();
            final AudioInputStream fin = new AudioInputStream(
                    new java.io.ByteArrayInputStream(data),
                    format,
                    data.length / 4);
            AudioSystem.write(fin, AudioFileFormat.Type.WAVE, wavFile.toFile());
        } finally {
            try {
                sequencer.close();
            } catch (Exception ignored) {
                // Ignore
            }

            try {
                synthesizer.close();
            } catch (Exception ignored) {
                // Ignore
            }
        }
    }

    //------------//
    // encodeMp3 //
    //------------//
    /**
     * Encode a WAV file to MP3 through the external ffmpeg.
     */
    private static void encodeMp3 (Path wavFile,
                                   Path mp3Path)
        throws Exception
    {
        final List<String> command = new ArrayList<>();
        command.add(getFfmpegPath());
        command.add("-y");
        command.add("-i");
        command.add(wavFile.toString());
        command.add("-codec:a");
        command.add("libmp3lame");
        command.add("-b:a");
        command.add(constants.mp3Bitrate.getValue());
        command.add(mp3Path.toString());

        logger.info("Running {}", String.join(" ", command));

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes());
        final int status = process.waitFor();

        if (status != 0) {
            throw new IllegalStateException(
                    "ffmpeg failed with status " + status + ": " + output);
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.String ffmpegPath = new Constant.String(
                "ffmpeg",
                "Path to the external ffmpeg executable used for MP3 encoding");

        private final Constant.String mp3Bitrate = new Constant.String(
                "192k",
                "MP3 bitrate for audio export");
    }
}
