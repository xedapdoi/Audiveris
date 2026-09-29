//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                         O m r S e r v e r                                        //
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
package org.audiveris.omr.server;

import org.audiveris.omr.Main;
import org.audiveris.omr.OMR;
import org.audiveris.omr.log.LogUtil;
import org.audiveris.omr.score.MidiExporter;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.score.ScoreExporter;
import org.audiveris.omr.score.ScoreJson;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.BookManager;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.util.OmrExecutors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Class <code>OmrServer</code> is a tiny embeddable REST microservice around the
 * Audiveris OMR engine (JDK HTTP server, no extra dependency).
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code GET /health} -&gt; {@code {"ok":true,...}}
 * <li>{@code POST /omr?filename=page.png} with raw image bytes -&gt; JSON with,
 * per movement, uncompressed MusicXML text, base64 MIDI and a compact score
 * structure (parts/measures/voices/chords/notes).
 * </ul>
 * One request is processed at a time (the engine is single-threaded).
 * <p>
 * Run: {@code gradlew :app:run -PmainClass=org.audiveris.omr.server.OmrServer
 * --args="8765"}. First request warms up classifiers and takes minutes;
 * later ones reuse the warm engine.
 * <p>
 * NOTE on licensing: exposing this service over a network triggers the AGPL
 * share-alike duty on the service code. Fine for personal use.
 *
 * @author Audiveris contributors
 */
public class OmrServer
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(OmrServer.class);

    /** Max upload size (50 MB). */
    private static final int MAX_BYTES = 50 * 1024 * 1024;

    //~ Methods ------------------------------------------------------------------------------------

    //------//
    // main //
    //------//
    /**
     * Start the server.
     *
     * @param args optional port (default 8765)
     */
    public static void main (String[] args)
        throws Exception
    {
        final int port = (args.length > 0) ? Integer.parseInt(args[0]) : 8765;

        // Same bootstrap as batch mode, without GUI
        LogUtil.addFileAppender();
        Main.initHeadlessCli();
        OmrExecutors.restart();
        OMR.engine = BookManager.getInstance();

        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", OmrServer::handleHealth);
        server.createContext("/omr", OmrServer::handleOmr);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        logger.info("OmrServer listening on port {}", port);
        System.out.println("OmrServer listening on port " + port);
    }

    //--------------//
    // handleHealth //
    //--------------//
    private static void handleHealth (HttpExchange exchange)
        throws IOException
    {
        send(exchange, 200, "{\"ok\":true,\"service\":\"audiveris-omr\"}");
    }

    //------------//
    // handleOmr //
    //------------//
    private static void handleOmr (HttpExchange exchange)
        throws IOException
    {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"ok\":false,\"error\":\"POST only\"}");

            return;
        }

        final byte[] image;

        try {
            image = exchange.getRequestBody().readAllBytes();
        } catch (Exception ex) {
            send(exchange, 400, "{\"ok\":false,\"error\":\"cannot read body\"}");

            return;
        }

        if (image.length == 0 || image.length > MAX_BYTES) {
            send(exchange, 400, "{\"ok\":false,\"error\":\"empty or oversized image\"}");

            return;
        }

        String filename = "page.png";
        final String query = exchange.getRequestURI().getRawQuery();

        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("filename=") && param.length() > 9) {
                    filename = param.substring(9).replaceAll("[^A-Za-z0-9._-]", "_");
                }
            }
        }

        final String response;

        synchronized (OmrServer.class) {
            response = transcribe(image, filename);
        }

        send(exchange, 200, response);
    }

    //------------//
    // transcribe //
    //------------//
    /**
     * Run the full OMR pipeline on the uploaded image.
     *
     * @param image    image bytes
     * @param filename file name (for format detection)
     * @return JSON response
     */
    private static String transcribe (byte[] image,
                                      String filename)
    {
        Path workDir = null;

        try {
            workDir = Files.createTempDirectory("omr-server-");

            final Path imagePath = workDir.resolve(filename);
            Files.write(imagePath, image);

            final Book book = OMR.engine.loadInput(imagePath);

            if (book == null) {
                return error("unsupported image format");
            }

            if (book.getStubs().isEmpty()) {
                book.createStubs();
            }

            final List<SheetStub> validStubs = Book.getValidStubs(book.getStubs(null));

            // No swap: CLI-less server keeps sheets in memory (no output folder)
            final List<Score> scores = new ArrayList<>();
            final boolean ok = book.transcribe(validStubs, scores, false);

            if (!ok || scores.isEmpty()) {
                return error("transcription failed");
            }

            final StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"movements\":[");

            boolean first = true;
            int index = 0;

            for (Score score : scores) {
                if (!first) {
                    sb.append(',');
                }

                first = false;
                index++;

                // MusicXML (uncompressed text)
                final ByteArrayOutputStream xmlOut = new ByteArrayOutputStream();
                new ScoreExporter(score).export(xmlOut, true, "movement", false);
                final String musicxml = xmlOut.toString(StandardCharsets.UTF_8);

                // MIDI (base64)
                final Path midiPath = Files.createTempFile(workDir, "movement-", ".mid");
                new MidiExporter(score).export(midiPath);
                final String midi = Base64.getEncoder()
                        .encodeToString(Files.readAllBytes(midiPath));
                Files.deleteIfExists(midiPath);

                sb.append("{\"scoreId\":")
                        .append(score.getId() != null ? score.getId() : index);
                sb.append(",\"musicxml\":\"").append(escape(musicxml)).append('"');
                sb.append(",\"midi\":\"").append(midi).append('"');
                sb.append(",\"score\":").append(ScoreJson.of(score));
                sb.append('}');
            }

            sb.append("]}");
            logger.info("Transcribed {} score(s) from {}", scores.size(), filename);

            return sb.toString();
        } catch (Exception ex) {
            logger.warn("Transcription failed for " + filename, ex);

            return error("transcription failed: " + ex.getMessage());
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    //-------//
    // error //
    //-------//
    private static String error (String message)
    {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    //--------//
    // escape //
    //--------//
    /**
     * Escape text for JSON string embedding.
     */
    static String escape (String text)
    {
        final StringBuilder sb = new StringBuilder(text.length() + 16);

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);

            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }

        return sb.toString();
    }

    //------//
    // send //
    //------//
    private static void send (HttpExchange exchange,
                              int status,
                              String body)
        throws IOException
    {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    //-------------------//
    // deleteRecursively //
    //-------------------//
    private static void deleteRecursively (Path dir)
    {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Ignore
                }
            });
        } catch (IOException ignored) {
            // Ignore
        }
    }
}
