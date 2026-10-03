//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     W e b p S u p p o r t T e s t                                //
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
package org.audiveris.omr.image;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks WebP read support (TwelveMonkeys plugin) via a bundled sample.
 */
public class WebpSupportTest
{
    @Test
    public void webpRead ()
        throws Exception
    {
        assertTrue(
                "No WebP reader",
                ImageIO.getImageReadersByFormatName("webp").hasNext());

        try (InputStream in = getClass().getResourceAsStream("/images/sample.webp")) {
            assertTrue("Missing sample", in != null);

            final BufferedImage image = ImageIO.read(in);
            assertTrue("WebP read failed", image != null);
            assertEquals(64, image.getWidth());
            assertEquals(32, image.getHeight());
        }
    }
}
