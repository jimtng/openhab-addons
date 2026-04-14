/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.navimow.internal.discovery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.navimow.internal.NavimowBindingConstants;
import org.openhab.binding.navimow.internal.NavimowBridgeHandler;
import org.openhab.binding.navimow.internal.api.NavimowApi;
import org.openhab.binding.navimow.internal.api.NavimowApiException;
import org.openhab.binding.navimow.internal.dto.NavimowDevice;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers Navimow mowers associated with the configured cloud account.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
@Component(scope = ServiceScope.PROTOTYPE, service = NavimowDiscoveryService.class)
public class NavimowDiscoveryService extends AbstractThingHandlerDiscoveryService<NavimowBridgeHandler> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NavimowDiscoveryService.class);
    private static final int DISCOVERY_TIMEOUT_SECONDS = 30;

    public NavimowDiscoveryService() {
        super(NavimowBridgeHandler.class, NavimowBindingConstants.SUPPORTED_THING_TYPES_UIDS, DISCOVERY_TIMEOUT_SECONDS,
                false);
    }

    @Override
    protected void startScan() {
        NavimowApi api = thingHandler.getApi();
        if (api == null) {
            LOGGER.debug("Discovery aborted: API not available");
            stopScan();
            return;
        }

        try {
            List<NavimowDevice> devices = api.getDevices();
            LOGGER.debug("Discovery found {} device(s)", devices.size());
            for (NavimowDevice device : devices) {
                discoverDevice(device);
            }
        } catch (NavimowApiException e) {
            LOGGER.warn("Discovery failed: {}", e.getMessage());
        }
        stopScan();
    }

    private void discoverDevice(NavimowDevice device) {
        if (device.id.isBlank()) {
            LOGGER.debug("Skipping device with empty ID");
            return;
        }

        ThingUID bridgeUID = thingHandler.getThing().getUID();
        ThingUID thingUID = new ThingUID(NavimowBindingConstants.THING_TYPE_MOWER, bridgeUID, device.id);

        Map<String, Object> properties = new HashMap<>();
        properties.put(NavimowBindingConstants.PROPERTY_DEVICE_ID, device.id);
        if (!device.name.isBlank()) {
            properties.put(NavimowBindingConstants.PROPERTY_DEVICE_NAME, device.name);
        }
        if (!device.model.isBlank()) {
            properties.put(NavimowBindingConstants.PROPERTY_MODEL, device.model);
        }
        if (!device.serialNumber.isBlank()) {
            properties.put(NavimowBindingConstants.PROPERTY_SERIAL_NUMBER, device.serialNumber);
        }
        if (!device.firmwareVersion.isBlank()) {
            properties.put(NavimowBindingConstants.PROPERTY_FIRMWARE_VERSION, device.firmwareVersion);
        }

        String label = device.name.isBlank() ? "Navimow Mower (" + device.id + ")" : device.name;

        thingDiscovered(DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withLabel(label)
                .withProperties(properties).withRepresentationProperty(NavimowBindingConstants.PROPERTY_DEVICE_ID)
                .build());

        LOGGER.debug("Discovered mower: {} ({})", label, device.id);
    }
}
