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
package org.openhab.binding.navimow.internal;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link NavimowBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowBindingConstants {

    public static final String BINDING_ID = "navimow";

    // Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_BRIDGE = new ThingTypeUID(BINDING_ID, "bridge");
    public static final ThingTypeUID THING_TYPE_MOWER = new ThingTypeUID(BINDING_ID, "mower");

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_BRIDGE, THING_TYPE_MOWER);

    // Channel IDs
    public static final String CHANNEL_STATUS = "status";
    public static final String CHANNEL_BATTERY = "battery-level";
    public static final String CHANNEL_SIGNAL_STRENGTH = "signal-strength";
    public static final String CHANNEL_LATITUDE = "latitude";
    public static final String CHANNEL_LONGITUDE = "longitude";
    public static final String CHANNEL_ERROR_CODE = "error-code";
    public static final String CHANNEL_COMMAND = "command";

    // API constants
    public static final String API_BASE_URL = "https://navimow-fra.ninebot.com";
    public static final String MQTT_DEFAULT_BROKER = "mqtt.navimow.com";
    public static final int MQTT_DEFAULT_PORT = 1883;

    // MQTT topic templates
    public static final String MQTT_STATE_TOPIC_TEMPLATE = "/downlink/vehicle/%s/realtimeDate/state";
    public static final String MQTT_ATTRIBUTES_TOPIC_TEMPLATE = "/downlink/vehicle/%s/realtimeDate/attributes";

    // Property keys
    public static final String PROPERTY_DEVICE_ID = "deviceId";
    public static final String PROPERTY_DEVICE_NAME = "deviceName";
    public static final String PROPERTY_MODEL = "model";
    public static final String PROPERTY_FIRMWARE_VERSION = "firmwareVersion";
    public static final String PROPERTY_SERIAL_NUMBER = "serialNumber";
}
