/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.matter.internal.devices.types;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.matter.internal.client.model.cluster.gen.DeviceTypes;
import org.openhab.binding.matter.internal.handler.EndpointHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Dan Cunningham
 */
@NonNullByDefault
public class DeviceTypeRegistry {
    private static final Logger logger = LoggerFactory.getLogger(DeviceTypeRegistry.class);
    private static final Map<Integer, Class<? extends DeviceType>> deviceTypes = new HashMap();

    static {
        List.of(DeviceTypes.OnOffLight, DeviceTypes.OnOffLightSwitch, DeviceTypes.OnOffPlugInUnit,
                DeviceTypes.DimmableLight, DeviceTypes.DimmablePlugInUnit, DeviceTypes.DimmerSwitch,
                DeviceTypes.ColorDimmerSwitch, DeviceTypes.ExtendedColorLight, DeviceTypes.ColorTemperatureLight)
                .forEach(type -> DeviceTypeRegistry.registerDeviceType(type, LightingType.class));
    }

    /**
     * Register a device type with the device type id.
     * 
     * @param deviceTypeId
     * @param deviceType
     */
    public static void registerDeviceType(Integer deviceTypeId, Class<? extends DeviceType> deviceType) {
        deviceTypes.put(deviceTypeId, deviceType);
    }

    /**
     * Create a device type based on the device type id. If the device type is not found, a generic type is returned.
     * 
     * @param deviceTypeId
     * @param handler
     * @return
     */
    public static DeviceType createDeviceType(Integer deviceTypeId, EndpointHandler handler) {
        Class<? extends DeviceType> clazz = deviceTypes.get(deviceTypeId);
        if (clazz != null) {
            try {
                Class<?>[] constructorParameterTypes = new Class<?>[] { Integer.class, EndpointHandler.class };
                Constructor<? extends DeviceType> constructor = clazz.getConstructor(constructorParameterTypes);
                return constructor.newInstance(deviceTypeId, handler);
            } catch (Exception e) {
                logger.debug("Could not create device type", e);
            }
        }
        return new GenericType(0, handler);
    }
}
