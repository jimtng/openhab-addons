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
package org.openhab.binding.luftdateninfo.internal.handler;

import static org.openhab.binding.luftdateninfo.internal.LuftdatenInfoBindingConstants.*;
import static org.openhab.binding.luftdateninfo.internal.handler.HTTPHandler.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.dimension.Density;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.unit.SmartHomeUnits;
import org.eclipse.smarthome.core.thing.Thing;
import org.openhab.binding.luftdateninfo.internal.dto.SensorDataValue;
import org.openhab.binding.luftdateninfo.internal.utils.NumberUtils;

/**
 * The {@link PMHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Bernd Weymann - Initial contribution
 */
@NonNullByDefault
public class PMHandler extends BaseSensorHandler {

    protected QuantityType<Density> pm25Cache = QuantityType.valueOf(-1, SmartHomeUnits.MICROGRAM_PER_CUBICMETRE);
    protected QuantityType<Density> pm100Cache = QuantityType.valueOf(-1, SmartHomeUnits.MICROGRAM_PER_CUBICMETRE);

    public PMHandler(Thing thing) {
        super(thing);
    }

    @Override
    public UpdateStatus updateChannels(@Nullable String json) {
        if (json != null) {
            List<SensorDataValue> valueList = HTTPHandler.getHandler().getLatestValues(json);
            if (valueList != null) {
                if (HTTPHandler.getHandler().isParticulate(valueList)) {
                    valueList.forEach(v -> {
                        if (v.getValueType().equals(P1)) {
                            pm100Cache = QuantityType.valueOf(NumberUtils.round(v.getValue(), 1),
                                    SmartHomeUnits.MICROGRAM_PER_CUBICMETRE);
                            updateState(PM100_CHANNEL, pm100Cache);
                        } else if (v.getValueType().equals(P2)) {
                            pm25Cache = QuantityType.valueOf(NumberUtils.round(v.getValue(), 1),
                                    SmartHomeUnits.MICROGRAM_PER_CUBICMETRE);
                            updateState(PM25_CHANNEL, pm25Cache);
                        }
                    });
                    return UpdateStatus.OK;
                } else {
                    return UpdateStatus.VALUE_ERROR;
                }
            } else {
                return UpdateStatus.VALUE_EMPTY;
            }
        } else {
            return UpdateStatus.CONNECTION_ERROR;
        }
    }

    @Override
    protected void updateFromCache() {
        updateState(PM25_CHANNEL, pm25Cache);
        updateState(PM100_CHANNEL, pm100Cache);
    }
}
