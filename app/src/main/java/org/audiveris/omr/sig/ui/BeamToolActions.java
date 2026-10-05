//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                  B e a m T o o l A c t i o n s                                   //
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
package org.audiveris.omr.sig.ui;

import org.audiveris.omr.OMR;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.ui.StubsController;

import org.jdesktop.application.Action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionEvent;

/**
 * Class <code>BeamToolActions</code> gathers actions for the beam tools
 * (patterns palette).
 *
 * @author Audiveris contributors
 */
public class BeamToolActions
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(BeamToolActions.class);

    //~ Methods ------------------------------------------------------------------------------------

    //------------------//
    // openBeamPatterns //
    //------------------//
    /**
     * Open the beam patterns palette for the current sheet.
     *
     * @param e the event that triggered this action
     */
    @Action
    public void openBeamPatterns (ActionEvent e)
    {
        try {
            final Sheet sheet = StubsController.getCurrentStub().getSheet();

            if (sheet == null) {
                logger.warn("No current sheet for beam patterns");

                return;
            }

            BeamPatternsDialog.open(OMR.gui.getFrame(), sheet);
        } catch (Exception ex) {
            logger.warn("Could not open beam patterns", ex);
        }
    }
}
