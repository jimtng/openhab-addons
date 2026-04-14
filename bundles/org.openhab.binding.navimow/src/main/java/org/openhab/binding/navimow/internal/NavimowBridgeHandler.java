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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.navimow.internal.api.NavimowApi;
import org.openhab.binding.navimow.internal.api.NavimowApiException;
import org.openhab.binding.navimow.internal.api.NavimowAuthException;
import org.openhab.binding.navimow.internal.discovery.NavimowDiscoveryService;
import org.openhab.binding.navimow.internal.dto.NavimowDevice;
import org.openhab.binding.navimow.internal.dto.NavimowMowerState;
import org.openhab.binding.navimow.internal.dto.NavimowMqttInfo;
import org.openhab.core.io.transport.mqtt.MqttBrokerConnection;
import org.openhab.core.io.transport.mqtt.MqttConnectionObserver;
import org.openhab.core.io.transport.mqtt.MqttConnectionState;
import org.openhab.core.io.transport.mqtt.MqttMessageSubscriber;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Bridge handler for the Navimow cloud account. Manages the HTTP API connection and the MQTT connection
 * for real-time device updates.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowBridgeHandler extends BaseBridgeHandler implements MqttConnectionObserver, MqttMessageSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(NavimowBridgeHandler.class);
    private static final int MQTT_RECONNECT_SECS = 30;
    private static final int INITIAL_POLLING_DELAY_SECS = 5;

    private final org.eclipse.jetty.client.HttpClient httpClient;

    private @Nullable NavimowApi api;
    private @Nullable MqttBrokerConnection mqttConnection;
    private @Nullable ScheduledFuture<?> pollingJob;

    // deviceId → listener (registered by mower handlers)
    private final Map<String, Consumer<NavimowMowerState>> mowerListeners = new ConcurrentHashMap<>();

    public NavimowBridgeHandler(Bridge bridge, org.eclipse.jetty.client.HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        NavimowBridgeConfiguration config = getConfigAs(NavimowBridgeConfiguration.class);

        if (config.accessToken.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.config-error-no-access-token");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        NavimowApi navimowApi = new NavimowApi(httpClient, config.apiBaseUrl, config.accessToken);
        this.api = navimowApi;

        scheduler.execute(this::connectToCloud);
    }

    @Override
    public void dispose() {
        cancelPollingJob();
        disconnectMqtt();
        api = null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Bridge handler has no channels
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(NavimowDiscoveryService.class);
    }

    /**
     * Registers a mower handler to receive real-time MQTT state updates.
     *
     * @param deviceId the device ID to listen for
     * @param listener callback receiving state updates
     */
    public void registerMowerListener(String deviceId, Consumer<NavimowMowerState> listener) {
        mowerListeners.put(deviceId, listener);
        LOGGER.debug("Registered listener for device {}", deviceId);

        // Subscribe MQTT topic for this device if MQTT is connected
        MqttBrokerConnection conn = mqttConnection;
        if (conn != null) {
            subscribeDeviceTopic(conn, deviceId);
        }
    }

    /**
     * Unregisters a mower handler's listener.
     *
     * @param deviceId the device ID
     */
    public void unregisterMowerListener(String deviceId) {
        mowerListeners.remove(deviceId);
        LOGGER.debug("Unregistered listener for device {}", deviceId);
    }

    /**
     * Returns the API client, or null if not yet initialized.
     */
    public @Nullable NavimowApi getApi() {
        return api;
    }

    /**
     * Fetches the device status via HTTP (polling fallback).
     *
     * @param deviceId the device ID
     * @return the mower state, or null if not available
     */
    public @Nullable NavimowMowerState fetchDeviceStatus(String deviceId) {
        NavimowApi navimowApi = api;
        if (navimowApi == null) {
            return null;
        }
        try {
            return navimowApi.getDeviceStatus(deviceId);
        } catch (NavimowAuthException e) {
            LOGGER.warn("Authentication error polling device {}: {}", deviceId, e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.config-error-auth");
            return null;
        } catch (NavimowApiException e) {
            LOGGER.debug("Error polling device {}: {}", deviceId, e.getMessage());
            return null;
        }
    }

    // --- MqttConnectionObserver ---

    @Override
    public void connectionStateChanged(MqttConnectionState state, @Nullable Throwable error) {
        LOGGER.debug("MQTT connection state changed to {}", state);
        if (state == MqttConnectionState.CONNECTED) {
            LOGGER.debug("MQTT connected");
            // Re-subscribe all registered devices
            MqttBrokerConnection conn = mqttConnection;
            if (conn != null) {
                for (String deviceId : mowerListeners.keySet()) {
                    subscribeDeviceTopic(conn, deviceId);
                }
            }
        } else if (state == MqttConnectionState.DISCONNECTED) {
            LOGGER.warn("MQTT disconnected: {}", error != null ? error.getMessage() : "unknown reason");
        }
    }

    // --- MqttMessageSubscriber ---

    @Override
    public void processMessage(String topic, byte[] payload) {
        String deviceId = extractDeviceIdFromTopic(topic);
        if (deviceId == null) {
            LOGGER.debug("Cannot extract device ID from MQTT topic: {}", topic);
            return;
        }

        String json = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        LOGGER.trace("MQTT message on topic {}: {}", topic, json);

        NavimowApi navimowApi = api;
        if (navimowApi == null) {
            return;
        }

        try {
            JsonObject msgJson = JsonParser.parseString(json).getAsJsonObject();
            NavimowMowerState state = navimowApi.parseMqttStatePayload(msgJson, deviceId);

            Consumer<NavimowMowerState> listener = mowerListeners.get(deviceId);
            if (listener != null) {
                listener.accept(state);
            } else {
                LOGGER.debug("No listener registered for device {}", deviceId);
            }
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Failed to parse MQTT message on topic {}: {}", topic, json);
        }
    }

    // --- Private helpers ---

    private void connectToCloud() {
        NavimowApi navimowApi = api;
        if (navimowApi == null) {
            return;
        }

        try {
            // Verify credentials by fetching device list
            List<NavimowDevice> devices = navimowApi.getDevices();
            LOGGER.debug("Connected to Navimow cloud. Found {} device(s)", devices.size());

            connectMqtt(navimowApi);
            updateStatus(ThingStatus.ONLINE);
            schedulePollingJob();
        } catch (NavimowAuthException e) {
            LOGGER.warn("Authentication failed: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.config-error-auth");
        } catch (NavimowApiException e) {
            LOGGER.warn("Failed to connect to Navimow cloud: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
            scheduleReconnect();
        }
    }

    private void connectMqtt(NavimowApi navimowApi) {
        NavimowBridgeConfiguration config = getConfigAs(NavimowBridgeConfiguration.class);
        String mqttUser = null;
        String mqttPassword = null;
        String resolvedHost = NavimowBindingConstants.MQTT_DEFAULT_BROKER;

        try {
            NavimowMqttInfo mqttInfo = navimowApi.getMqttUserInfo();
            String hostFromInfo = mqttInfo.mqttHost;
            if (hostFromInfo != null && !hostFromInfo.isBlank()) {
                resolvedHost = hostFromInfo;
            }
            mqttUser = mqttInfo.userName;
            mqttPassword = mqttInfo.pwdInfo;
        } catch (NavimowApiException e) {
            LOGGER.warn("Failed to fetch MQTT credentials; using defaults: {}", e.getMessage());
        }

        final String mqttHost = resolvedHost;
        int mqttPort = NavimowBindingConstants.MQTT_DEFAULT_PORT;
        String clientId = "openhab-navimow-" + thing.getUID().getId();
        MqttBrokerConnection conn = new MqttBrokerConnection(mqttHost, mqttPort, false, clientId);

        if (mqttUser != null && mqttPassword != null) {
            conn.setCredentials(mqttUser, mqttPassword);
        } else {
            // Fallback: use access token as password
            conn.setCredentials(config.accessToken, config.accessToken);
        }

        conn.setKeepAliveInterval(30);
        conn.addConnectionObserver(this);

        this.mqttConnection = conn;

        conn.start().whenComplete((connected, error) -> {
            if (Boolean.TRUE.equals(connected)) {
                LOGGER.debug("MQTT broker connected: {}:{}", mqttHost, mqttPort);
                for (String deviceId : mowerListeners.keySet()) {
                    subscribeDeviceTopic(conn, deviceId);
                }
            } else {
                LOGGER.warn("MQTT connection failed: {}", error != null ? error.getMessage() : "unknown");
            }
        });
    }

    private void subscribeDeviceTopic(MqttBrokerConnection conn, String deviceId) {
        String stateTopic = String.format(NavimowBindingConstants.MQTT_STATE_TOPIC_TEMPLATE, deviceId);
        String attrTopic = String.format(NavimowBindingConstants.MQTT_ATTRIBUTES_TOPIC_TEMPLATE, deviceId);
        conn.subscribe(stateTopic, this);
        conn.subscribe(attrTopic, this);
        LOGGER.debug("Subscribed to MQTT topics for device {}", deviceId);
    }

    private void disconnectMqtt() {
        MqttBrokerConnection conn = mqttConnection;
        if (conn != null) {
            conn.removeConnectionObserver(this);
            conn.stop();
            mqttConnection = null;
        }
    }

    private void schedulePollingJob() {
        cancelPollingJob();
        NavimowBridgeConfiguration config = getConfigAs(NavimowBridgeConfiguration.class);
        int interval = config.pollingInterval;
        if (interval > 0) {
            pollingJob = scheduler.scheduleWithFixedDelay(this::pollAllDevices, INITIAL_POLLING_DELAY_SECS, interval,
                    TimeUnit.SECONDS);
            LOGGER.debug("Scheduled polling every {}s", interval);
        }
    }

    private void cancelPollingJob() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    private void pollAllDevices() {
        for (Map.Entry<String, Consumer<NavimowMowerState>> entry : mowerListeners.entrySet()) {
            NavimowMowerState state = fetchDeviceStatus(entry.getKey());
            if (state != null) {
                entry.getValue().accept(state);
            }
        }
    }

    private void scheduleReconnect() {
        scheduler.schedule(this::connectToCloud, MQTT_RECONNECT_SECS, TimeUnit.SECONDS);
    }

    private @Nullable String extractDeviceIdFromTopic(String topic) {
        // Topic format: /downlink/vehicle/{deviceId}/realtimeDate/state
        // or: /downlink/vehicle/{deviceId}/realtimeDate/attributes
        String[] parts = topic.split("/");
        // parts[0]="" parts[1]="downlink" parts[2]="vehicle" parts[3]={deviceId}
        if (parts.length >= 4 && "downlink".equals(parts[1]) && "vehicle".equals(parts[2])) {
            return parts[3];
        }
        return null;
    }
}
