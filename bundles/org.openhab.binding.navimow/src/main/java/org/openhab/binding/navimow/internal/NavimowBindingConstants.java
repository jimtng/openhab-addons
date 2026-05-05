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
    public static final String CHANNEL_LOCATION = "location";
    public static final String CHANNEL_ERROR_CODE = "error-code";
    public static final String CHANNEL_CURRENT_MOW_BOUNDARY = "current-mow-boundary";
    public static final String CHANNEL_CURRENT_MOW_PROGRESS = "current-mow-progress";
    public static final String CHANNEL_MOWING_PERCENTAGE = "mowing-percentage";
    public static final String CHANNEL_MOWING_WEEK_AREA = "mowing-week-area";
    public static final String CHANNEL_SUBTOTAL_AREA = "subtotal-area";
    public static final String CHANNEL_MOW_START_TYPE = "mow-start-type";
    public static final String CHANNEL_POSTURE_THETA = "posture-theta";
    public static final String CHANNEL_POSTURE_X = "posture-x";
    public static final String CHANNEL_POSTURE_Y = "posture-y";
    public static final String CHANNEL_COMMAND = "command";

    // API constants
    public static final String API_BASE_URL = "https://navimow-fra.ninebot.com";
    public static final String MQTT_DEFAULT_BROKER = "mqtt.navimow.com";
    public static final int MQTT_DEFAULT_PORT = 1883;

    // MQTT topic templates (per-device, used only for extracting device ID from incoming topics)
    public static final String MQTT_STATE_TOPIC_TEMPLATE = "/downlink/vehicle/%s/realtimeDate/state";
    public static final String MQTT_ATTRIBUTES_TOPIC_TEMPLATE = "/downlink/vehicle/%s/realtimeDate/attributes";
    public static final String MQTT_EVENT_TOPIC_TEMPLATE = "/downlink/vehicle/%s/realtimeDate/event";
    public static final String MQTT_LOCATION_TOPIC_TEMPLATE = "/downlink/vehicle/%s/realtimeDate/location";

    // MQTT wildcard subscriptions used only as a fallback before mower listeners are registered
    public static final String MQTT_STATE_TOPIC_WILDCARD = "/downlink/vehicle/+/realtimeDate/state";
    public static final String MQTT_ATTRIBUTES_TOPIC_WILDCARD = "/downlink/vehicle/+/realtimeDate/attributes";
    public static final String MQTT_EVENT_TOPIC_WILDCARD = "/downlink/vehicle/+/realtimeDate/event";
    public static final String MQTT_LOCATION_TOPIC_WILDCARD = "/downlink/vehicle/+/realtimeDate/location";

    // Property keys
    public static final String PROPERTY_DEVICE_ID = "deviceId";
    public static final String PROPERTY_DEVICE_NAME = "deviceName";
    public static final String PROPERTY_MODEL = "model";
    public static final String PROPERTY_FIRMWARE_VERSION = "firmwareVersion";
    public static final String PROPERTY_SERIAL_NUMBER = "serialNumber";

    // OAuth2 servlet
    public static final String NAVIMOW_SERVLET_ALIAS = "/navimow";

    // OAuth2 endpoints and credentials
    public static final String OAUTH2_AUTHORIZE_URL = "https://navimow-h5-fra.willand.com/smartHome/login";
    public static final String OAUTH2_TOKEN_URL = "https://navimow-fra.ninebot.com/openapi/oauth/getAccessToken";
    public static final String OAUTH2_CLIENT_ID = "homeassistant";
    public static final String OAUTH2_CLIENT_SECRET = "57056e15-722e-42be-bbaa-b0cbfb208a52";
}
