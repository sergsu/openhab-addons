/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.pioneeravr.internal.handler;

import org.eclipse.smarthome.core.thing.Thing;
import org.openhab.binding.pioneeravr.internal.PioneerAvrBindingConstants;
import org.openhab.binding.pioneeravr.internal.protocol.avr.AvrConnection;
import org.openhab.binding.pioneeravr.internal.protocol.serial.SerialAvrConnection;

/**
 * An handler of an AVR connected through a serial port.
 *
 * @author Antoine Besnard - Initial contribution
 */
public class SerialAvrHandler extends AbstractAvrHandler {

    public SerialAvrHandler(Thing thing) {
        super(thing);
    }

    @Override
    protected AvrConnection createConnection() {
        String serialPort = (String) this.getConfig().get(PioneerAvrBindingConstants.SERIAL_PORT_PARAMETER);

        return new SerialAvrConnection(serialPort);
    }

}
