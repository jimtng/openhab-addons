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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.openhab.binding.navimow.internal.api.NavimowApi;
import org.openhab.binding.navimow.internal.api.NavimowApiException;
import org.openhab.binding.navimow.internal.api.NavimowAuthException;
import org.openhab.binding.navimow.internal.discovery.NavimowDiscoveryService;
import org.openhab.binding.navimow.internal.dto.NavimowDevice;
import org.openhab.binding.navimow.internal.dto.NavimowMowerState;
import org.openhab.binding.navimow.internal.dto.NavimowMqttInfo;
import org.openhab.core.auth.client.oauth2.AccessTokenResponse;
import org.openhab.core.auth.client.oauth2.OAuthClientService;
import org.openhab.core.auth.client.oauth2.OAuthException;
import org.openhab.core.auth.client.oauth2.OAuthFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscribe;

/**
 * Bridge handler for the Navimow cloud account. Manages the HTTP API connection and the MQTT connection
 * for real-time device updates.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowBridgeHandler extends BaseBridgeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NavimowBridgeHandler.class);
    private static final int MQTT_RECONNECT_SECS = 30;
    private static final int INITIAL_POLLING_DELAY_SECS = 5;

    private final org.eclipse.jetty.client.HttpClient httpClient;
    private final OAuthFactory oAuthFactory;

    private @Nullable OAuthClientService oAuthService;
    private @Nullable NavimowApi api;
    private @Nullable Mqtt3AsyncClient mqttClient;
    private @Nullable ScheduledFuture<?> pollingJob;
    private volatile boolean disposed;
    private volatile boolean suppressMqttReconnect;
    private volatile boolean cloudAuthenticated;

    // deviceId → listener (registered by mower handlers)
    private final Map<String, Consumer<NavimowMowerState>> mowerListeners = new ConcurrentHashMap<>();
    // deviceId → last known merged state (used to avoid UNDEF regressions from partial MQTT payloads)
    private final Map<String, NavimowMowerState> lastKnownStates = new ConcurrentHashMap<>();

    public NavimowBridgeHandler(Bridge bridge, OAuthFactory oAuthFactory,
            org.eclipse.jetty.client.HttpClient httpClient) {
        super(bridge);
        this.oAuthFactory = oAuthFactory;
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        disposed = false;
        suppressMqttReconnect = false;
        cloudAuthenticated = false;
        NavimowBridgeConfiguration config = getConfigAs(NavimowBridgeConfiguration.class);

        OAuthClientService oAuthClientService = oAuthFactory.createOAuthClientService(thing.getUID().getAsString(),
                NavimowBindingConstants.OAUTH2_TOKEN_URL, NavimowBindingConstants.OAUTH2_AUTHORIZE_URL,
                NavimowBindingConstants.OAUTH2_CLIENT_ID, NavimowBindingConstants.OAUTH2_CLIENT_SECRET, null, false);
        this.oAuthService = oAuthClientService;

        NavimowApi navimowApi = new NavimowApi(httpClient, config.apiBaseUrl, oAuthClientService);
        this.api = navimowApi;

        if (!isAuthorized()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, getAuthorizationRequiredMessage());
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::connectToCloud);
    }

    @Override
    public void dispose() {
        disposed = true;
        cloudAuthenticated = false;
        cancelPollingJob();
        disconnectMqtt();
        api = null;
        OAuthClientService service = oAuthService;
        if (service != null) {
            oAuthFactory.ungetOAuthService(thing.getUID().getAsString());
            oAuthService = null;
        }
    }

    @Override
    public void handleRemoval() {
        oAuthFactory.deleteServiceAndAccessToken(thing.getUID().getAsString());
        super.handleRemoval();
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

        // If MQTT is already connected, subscribe this device's topics immediately.
        Mqtt3AsyncClient client = mqttClient;
        if (client != null) {
            scheduler.execute(() -> subscribeDeviceTopics(client, deviceId));
        }
    }

    /**
     * Unregisters a mower handler's listener.
     *
     * @param deviceId the device ID
     */
    public void unregisterMowerListener(String deviceId) {
        mowerListeners.remove(deviceId);
        lastKnownStates.remove(deviceId);
        LOGGER.debug("Unregistered listener for device {}", deviceId);
    }

    /**
     * Returns whether this bridge is currently authorized (has a valid access token).
     *
     * @return true if authorized
     */
    public boolean isAuthorized() {
        NavimowApi navimowApi = api;
        return navimowApi != null && navimowApi.hasStoredAccessToken();
    }

    /**
     * Returns whether this handler's thing UID equals the given string (used by the auth service).
     *
     * @param thingUID the thing UID string to compare
     * @return true if equal
     */
    public boolean equalsThingUID(String thingUID) {
        return thing.getUID().getAsString().equals(thingUID);
    }

    /**
     * Returns whether this handler's thing ID equals the given string.
     *
     * @param thingId the thing ID string to compare
     * @return true if equal
     */
    public boolean hasThingId(String thingId) {
        return thing.getUID().getId().equalsIgnoreCase(thingId);
    }

    /**
     * Returns the thing label, or the thing UID ID if no label is set.
     *
     * @return a human-readable label for this bridge
     */
    public String getLabel() {
        Object label = thing.getLabel();
        return label != null ? label.toString() : thing.getUID().getId();
    }

    private String getThingAuthorizationPath() {
        return NavimowBindingConstants.NAVIMOW_SERVLET_ALIAS + "/" + thing.getUID().getId();
    }

    private String getAuthorizationRequiredMessage() {
        return "Not authorized. Visit http(s)://[YOUROPENHAB]:[YOURPORT]" + getThingAuthorizationPath()
                + " to authorize.";
    }

    private String getReauthorizationRequiredMessage() {
        return "Authentication failed. Please re-authorize at http(s)://[YOUROPENHAB]:[YOURPORT]"
                + getThingAuthorizationPath();
    }

    /**
     * Builds the OAuth2 authorization URL to redirect the user to.
     *
     * @param redirectUri the redirect URI (this servlet's URL)
     * @return the authorization URL, or empty string on error
     */
    public String formatAuthorizationUrl(String redirectUri) {
        try {
            OAuthClientService service = oAuthService;
            if (service == null) {
                return "";
            }
            return service.getAuthorizationUrl(redirectUri, null, thing.getUID().getAsString());
        } catch (OAuthException e) {
            LOGGER.debug("Error building authorization URL: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Exchanges the given authorization code for an access token and triggers cloud reconnection.
     *
     * @param redirectUri the redirect URI used during authorization
     * @param code the authorization code returned by the OAuth server
     * @return a label identifying this bridge
     * @throws NavimowApiException if the code exchange fails
     */
    public String authorize(String redirectUri, String code) throws NavimowApiException {
        try {
            OAuthClientService service = oAuthService;
            if (service == null) {
                throw new NavimowApiException("OAuth service is not initialized");
            }
            Map<String, String> params = new HashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("grantType", "authorization_code");
            params.put("code", code);
            params.put("redirect_uri", redirectUri);
            AccessTokenResponse tokenResponse = exchangeToken(params);
            service.importAccessTokenResponse(
                    NavimowApi.prepareTokenForStorage(tokenResponse, tokenResponse.getRefreshToken()));
            LOGGER.debug("OAuth2 authorization succeeded for bridge {}", thing.getUID());
            updateStatus(ThingStatus.UNKNOWN);
            scheduler.execute(this::connectToCloud);
            return getLabel();
        } catch (NavimowApiException e) {
            throw e;
        } catch (OAuthException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    getReauthorizationRequiredMessage());
            throw new NavimowApiException("Authorization failed: " + e.getMessage(), e);
        }
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
            cloudAuthenticated = false;
            cancelPollingJob();
            disconnectMqtt();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    getReauthorizationRequiredMessage());
            return null;
        } catch (NavimowApiException e) {
            String message = Objects.requireNonNullElse(e.getMessage(), "");
            if (message.contains("CODE_OAUTH_INFO_ILLEGAL")) {
                LOGGER.warn("OAuth token became invalid while polling device {}: {}", deviceId, message);
                cloudAuthenticated = false;
                cancelPollingJob();
                disconnectMqtt();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        getReauthorizationRequiredMessage());
                return null;
            }
            LOGGER.debug("Error polling device {}: {}", deviceId, e.getMessage());
            return null;
        }
    }

    // --- Private helpers ---

    /**
     * Performs a manual OAuth2 token exchange POST to the Navimow token endpoint.
     * Handles both the standard RFC 6749 JSON response and Navimow's custom
     * {@code {"code":0,"data":{...}}} envelope.
     *
     * @param params form fields to include (e.g. grant_type, code, username …)
     * @return a populated {@link AccessTokenResponse} ready to be imported
     * @throws NavimowApiException if the request fails or the response contains no access_token
     */
    private AccessTokenResponse exchangeToken(Map<String, String> params) throws NavimowApiException {
        try {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                body.append('=');
                body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            body.append("&client_id=")
                    .append(URLEncoder.encode(NavimowBindingConstants.OAUTH2_CLIENT_ID, StandardCharsets.UTF_8));
            body.append("&client_secret=")
                    .append(URLEncoder.encode(NavimowBindingConstants.OAUTH2_CLIENT_SECRET, StandardCharsets.UTF_8));

            ContentResponse response = httpClient.newRequest(NavimowBindingConstants.OAUTH2_TOKEN_URL)
                    .method(HttpMethod.POST).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("requestId", UUID.randomUUID().toString())
                    .content(new StringContentProvider(body.toString())).timeout(30, TimeUnit.SECONDS).send();

            if (response.getStatus() != 200) {
                throw new NavimowApiException("Token exchange failed with HTTP " + response.getStatus() + ": "
                        + response.getContentAsString());
            }

            String responseBody = response.getContentAsString();
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            JsonElement codeEl = json.get("code");
            if (codeEl != null && codeEl.getAsInt() != 1) {
                JsonElement descEl = json.get("desc");
                String desc = descEl != null ? descEl.getAsString() : "unknown error";
                throw new NavimowApiException("Token exchange rejected: " + desc);
            }

            // Navimow may wrap the token fields in {"code":0,"data":{...}}
            if (json.has("data") && json.get("data").isJsonObject()) {
                json = json.getAsJsonObject("data");
            }

            String accessToken = json.has("access_token") ? json.get("access_token").getAsString() : null;
            if (accessToken == null || accessToken.isEmpty()) {
                throw new NavimowApiException("Token exchange response missing access_token: " + responseBody);
            }

            long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 86400L;
            String tokenType = json.has("token_type") ? json.get("token_type").getAsString() : "Bearer";
            String refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;

            AccessTokenResponse tokenResponse = new AccessTokenResponse();
            tokenResponse.setAccessToken(accessToken);
            tokenResponse.setTokenType(tokenType);
            tokenResponse.setExpiresIn(expiresIn);
            if (refreshToken != null && !refreshToken.isEmpty()) {
                tokenResponse.setRefreshToken(refreshToken);
            }
            tokenResponse.setCreatedOn(Instant.now());
            return tokenResponse;
        } catch (ExecutionException | TimeoutException e) {
            throw new NavimowApiException("Token exchange request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NavimowApiException("Token exchange request interrupted", e);
        }
    }

    private void connectToCloud() {
        if (disposed) {
            return;
        }
        NavimowApi navimowApi = api;
        if (navimowApi == null) {
            return;
        }

        try {
            // Verify credentials by fetching device list
            List<NavimowDevice> devices = navimowApi.getDevices();
            LOGGER.debug("Connected to Navimow cloud. Found {} device(s)", devices.size());

            cloudAuthenticated = true;
            connectMqtt(navimowApi);
            updateStatus(ThingStatus.ONLINE);
            schedulePollingJob();
        } catch (NavimowAuthException e) {
            LOGGER.warn("Authentication failed: {}", e.getMessage());
            cloudAuthenticated = false;
            cancelPollingJob();
            disconnectMqtt();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    getReauthorizationRequiredMessage());
        } catch (NavimowApiException e) {
            LOGGER.warn("Failed to connect to Navimow cloud: {}", e.getMessage());
            cloudAuthenticated = false;
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/offline.communication-error");
            scheduleReconnect();
        }
    }

    private void connectMqtt(NavimowApi navimowApi) {
        if (disposed || !cloudAuthenticated) {
            LOGGER.debug("Skipping MQTT connect because bridge is disposed or not cloud-authenticated");
            return;
        }
        // Disconnect old client if one exists (for reconnects with fresh credentials)
        Mqtt3AsyncClient oldClient = mqttClient;
        if (oldClient != null) {
            LOGGER.debug("Disconnecting old MQTT client to prepare for fresh connection");
            suppressMqttReconnect = true;
            oldClient.disconnect();
            mqttClient = null;
            suppressMqttReconnect = false;
        }

        String mqttUser = null;
        String mqttPassword = null;
        String resolvedHost = NavimowBindingConstants.MQTT_DEFAULT_BROKER;
        int resolvedPort = NavimowBindingConstants.MQTT_DEFAULT_PORT;
        boolean useWebSocket = false;
        boolean useSecure = false;
        String wsPath = "/mqtt";

        // Retrieve current access token for WebSocket Authorization header
        // This must be done EVERY TIME we connect, not just once, because the token can expire
        String accessToken = null;
        try {
            accessToken = navimowApi.getCurrentAccessToken();
            LOGGER.debug("OAuth access token prepared for MQTT WebSocket Authorization header");
        } catch (NavimowAuthException e) {
            LOGGER.warn("Could not retrieve access token for MQTT WebSocket header: {}", e.getMessage());
        }

        try {
            LOGGER.debug("Fetching MQTT credentials from Navimow API...");
            NavimowMqttInfo mqttInfo = navimowApi.getMqttUserInfo();
            String hostFromInfo = mqttInfo.mqttHost;
            if (hostFromInfo != null && !hostFromInfo.isBlank()) {
                try {
                    URI uri = new URI(hostFromInfo);
                    String scheme = uri.getScheme();
                    if ("wss".equals(scheme) || "ws".equals(scheme)) {
                        useWebSocket = true;
                        useSecure = "wss".equals(scheme);
                        resolvedHost = uri.getHost() != null ? uri.getHost() : hostFromInfo;
                        resolvedPort = uri.getPort() > 0 ? uri.getPort() : (useSecure ? 443 : 80);
                    } else if ("ssl".equals(scheme) || "mqtts".equals(scheme)) {
                        useSecure = true;
                        resolvedHost = uri.getHost() != null ? uri.getHost() : hostFromInfo;
                        resolvedPort = uri.getPort() > 0 ? uri.getPort() : 8883;
                    } else if (uri.getHost() != null) {
                        resolvedHost = uri.getHost();
                        resolvedPort = uri.getPort() > 0 ? uri.getPort() : NavimowBindingConstants.MQTT_DEFAULT_PORT;
                    } else {
                        resolvedHost = hostFromInfo;
                    }
                } catch (URISyntaxException e) {
                    LOGGER.debug("Could not parse MQTT host as URI '{}', using as plain hostname", hostFromInfo);
                    resolvedHost = hostFromInfo;
                }
            }
            // Use the WebSocket path provided by the API
            String urlFromInfo = mqttInfo.mqttUrl;
            if (urlFromInfo != null && !urlFromInfo.isBlank()) {
                wsPath = urlFromInfo;
            }
            mqttUser = mqttInfo.userName;
            mqttPassword = mqttInfo.pwdInfo;
            LOGGER.debug("MQTT credentials fetched: user={} password={} (length={} bytes)", mqttUser,
                    mqttPassword != null ? "***" : "null", mqttPassword != null ? mqttPassword.length() : 0);
        } catch (NavimowApiException e) {
            LOGGER.warn("Failed to fetch MQTT credentials; skipping MQTT connect: {}", e.getMessage());
            return;
        }

        if (mqttUser == null || mqttPassword == null) {
            LOGGER.warn("MQTT credentials are missing, skipping MQTT connect to avoid unauthenticated broker attempts");
            return;
        }

        LOGGER.debug("Connecting to MQTT broker: host={} port={} webSocket={} secure={} wsPath={}", resolvedHost,
                resolvedPort, useWebSocket, useSecure, wsPath);

        String clientId = buildWebClientId(mqttUser);
        LOGGER.debug("Using MQTT client ID: {}", clientId);

        // Build HiveMQ client with WebSocket support, custom Authorization header
        // DO NOT use automaticReconnectWithDefaultConfig() because MQTT credentials are time-limited
        // We need to fetch fresh credentials on reconnect, not use stale ones
        final Mqtt3AsyncClient[] listenerClientRef = new Mqtt3AsyncClient[1];
        var builder = Mqtt3Client.builder().serverHost(resolvedHost).serverPort(resolvedPort).identifier(clientId)
                .addDisconnectedListener(context -> {
                    Mqtt3AsyncClient listenerClient = listenerClientRef[0];
                    if (listenerClient == null || mqttClient != listenerClient) {
                        LOGGER.debug("MQTT disconnected from stale client: {}", context.getCause().getMessage());
                        return;
                    }
                    if (disposed || suppressMqttReconnect || !cloudAuthenticated) {
                        LOGGER.debug("MQTT disconnected: {} (reconnect suppressed)", context.getCause().getMessage());
                        return;
                    }
                    LOGGER.warn("MQTT disconnected: {} (will attempt reconnect with fresh credentials)",
                            context.getCause().getMessage());
                    // Schedule reconnect with fresh credentials after auth failures.
                    scheduler.schedule(() -> {
                        if (disposed || !cloudAuthenticated) {
                            LOGGER.debug("Skipping MQTT reconnect because bridge is disposed or unauthenticated");
                            return;
                        }
                        LOGGER.debug(
                                "Attempting MQTT reconnect with fresh credentials (auth token may have expired)...");
                        NavimowApi currentApi = api;
                        if (currentApi != null) {
                            connectMqtt(currentApi);
                        } else {
                            LOGGER.warn("Cannot reconnect - API is null");
                        }
                    }, 10, TimeUnit.SECONDS);
                });

        if (useWebSocket) {
            Map<String, String> wsHeaders = new HashMap<>();
            if (accessToken != null) {
                wsHeaders.put("Authorization", "Bearer " + accessToken);
                LOGGER.debug("WebSocket Authorization header set with Bearer token (length={} chars)",
                        accessToken.length());
            } else {
                LOGGER.warn("WebSocket Authorization header NOT set - accessToken is null!");
            }
            String wsServerPath = wsPath;
            String wsQuery = null;
            int queryStart = wsPath.indexOf('?');
            if (queryStart >= 0) {
                wsServerPath = wsPath.substring(0, queryStart);
                wsQuery = wsPath.substring(queryStart + 1);
            }
            var wsBuilder = builder.webSocketConfig().serverPath(wsServerPath).httpHeaders(wsHeaders);
            if (wsQuery != null && !wsQuery.isBlank()) {
                wsBuilder.queryString(wsQuery);
            }
            wsBuilder.applyWebSocketConfig();
        }

        if (useSecure) {
            builder.sslWithDefaultConfig();
        }

        Mqtt3AsyncClient client = builder.buildAsync();
        listenerClientRef[0] = client;
        this.mqttClient = client;

        // Set global PUBLISH handler — all incoming MQTT messages arrive here
        client.publishes(MqttGlobalPublishFilter.ALL, publish -> scheduler
                .execute(() -> handleMqttMessage(publish.getTopic().toString(), publish.getPayloadAsBytes())));

        // Connect with MQTT credentials - wait for CONNACK before subscribing
        final String finalMqttUser = mqttUser;
        final String finalMqttPassword = mqttPassword;
        var connectBuilder = client.connectWith().keepAlive(2400);
        LOGGER.debug("MQTT connecting WITH credentials: user={} password length={} bytes", finalMqttUser,
                finalMqttPassword.length());
        connectBuilder.simpleAuth().username(finalMqttUser).password(finalMqttPassword.getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth();
        connectBuilder.send().whenComplete((ack, ex) -> {
            if (ex != null) {
                LOGGER.warn("MQTT CONNACK failed: {}", ex.getMessage());
            } else {
                LOGGER.debug("MQTT CONNACK received - credentials accepted");
                // Only subscribe AFTER CONNACK is confirmed
                scheduler.execute(this::onMqttConnected);
            }
        });
    }

    private void onMqttConnected() {
        LOGGER.debug("MQTT connected - now subscribing to topics");
        Mqtt3AsyncClient client = mqttClient;
        if (client == null) {
            LOGGER.debug("MQTT subscribe skipped because client is null");
            return;
        }

        List<String> deviceIds = List.copyOf(mowerListeners.keySet());
        if (!deviceIds.isEmpty()) {
            LOGGER.debug("Subscribing to exact MQTT topics for {} registered device(s): {}", deviceIds.size(),
                    deviceIds);
            for (String deviceId : deviceIds) {
                subscribeDeviceTopics(client, deviceId);
            }
        } else {
            LOGGER.debug(
                    "No registered device listeners found yet; waiting for listener registration before subscribing");
        }
    }

    private void subscribeDeviceTopics(Mqtt3AsyncClient client, String deviceId) {
        String[] topics = new String[] { String.format(NavimowBindingConstants.MQTT_STATE_TOPIC_TEMPLATE, deviceId),
                String.format(NavimowBindingConstants.MQTT_ATTRIBUTES_TOPIC_TEMPLATE, deviceId),
                String.format(NavimowBindingConstants.MQTT_EVENT_TOPIC_TEMPLATE, deviceId),
                String.format(NavimowBindingConstants.MQTT_LOCATION_TOPIC_TEMPLATE, deviceId) };

        for (int i = 0; i < topics.length; i++) {
            final String topic = topics[i];
            final int delayMs = i * 200;
            scheduler.schedule(() -> subscribeTopic(client, topic), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void subscribeTopic(Mqtt3AsyncClient client, String topic) {
        LOGGER.debug("Subscribing to MQTT topic: {}", topic);
        Mqtt3Subscribe subscribe = Mqtt3Subscribe.builder().addSubscription().topicFilter(topic)
                .qos(MqttQos.AT_MOST_ONCE).applySubscription().build();
        client.subscribe(subscribe).whenComplete((ack, ex) -> {
            if (ex != null) {
                LOGGER.warn("MQTT subscription failed for topic {}: {}", topic, ex.getMessage());
            } else {
                LOGGER.debug("MQTT successfully subscribed to: {}", topic);
            }
        });
    }

    private void handleMqttMessage(String topic, byte[] payload) {
        LOGGER.debug("MQTT received topic={} bytes={}", topic, payload.length);

        if (payload.length == 0) {
            LOGGER.debug("MQTT rejected: empty payload on topic {}", topic);
            return;
        }

        String payloadText = new String(payload, StandardCharsets.UTF_8);

        String deviceId = extractDeviceIdFromTopic(topic);
        if (deviceId == null) {
            LOGGER.debug("MQTT rejected: cannot extract device ID from topic {} payload={}", topic, payloadText);
            return;
        }

        String topicChannel = extractTopicChannelFromTopic(topic);
        if (topicChannel == null) {
            LOGGER.debug("MQTT rejected: unsupported topic format for device {} topic={} payload={}", deviceId, topic,
                    payloadText);
            return;
        }

        if ("event".equals(topicChannel)) {
            // Event payloads are not full status snapshots; refresh via HTTP to keep channels accurate.
            LOGGER.debug("MQTT event received for device {}. Triggering immediate HTTP refresh.", deviceId);
            Consumer<NavimowMowerState> listener = mowerListeners.get(deviceId);
            if (listener != null) {
                NavimowMowerState polledState = fetchDeviceStatus(deviceId);
                if (polledState != null) {
                    rememberLastState(deviceId, polledState);
                    listener.accept(polledState);
                } else {
                    LOGGER.debug("MQTT event refresh produced no HTTP state for device {}", deviceId);
                }
            } else {
                LOGGER.debug("MQTT event ignored: no listener registered for device {}", deviceId);
            }
            return;
        }

        String json = payloadText;
        LOGGER.debug("MQTT received on topic {}: {}", topic, json);

        NavimowApi navimowApi = api;
        if (navimowApi == null) {
            LOGGER.debug("MQTT rejected for device {}: API not initialized", deviceId);
            return;
        }

        try {
            var parsedElement = JsonParser.parseString(json);
            NavimowMowerState parsedState;
            if ("location".equals(topicChannel) && parsedElement.isJsonArray()) {
                parsedState = navimowApi.parseMqttLocationPayload(parsedElement.getAsJsonArray(), deviceId);
            } else if (parsedElement.isJsonObject()) {
                JsonObject msgJson = parsedElement.getAsJsonObject();
                parsedState = navimowApi.parseMqttStatePayload(msgJson, deviceId);
            } else {
                LOGGER.debug("MQTT rejected for device {}: unsupported payload root on channel {}: {}", deviceId,
                        topicChannel, json);
                return;
            }
            NavimowMowerState mergedState = mergeWithLastKnownState(deviceId, parsedState);

            LOGGER.debug(
                    "MQTT accepted device={} channel={} parsed[state={},battery={},signal={},lat={},lon={},error={},boundary={},progress={},percentage={},weekArea={},subtotalArea={},mowStartType={},postureTheta={},postureX={},postureY={}] merged[state={},battery={},signal={},lat={},lon={},error={},boundary={},progress={},percentage={},weekArea={},subtotalArea={},mowStartType={},postureTheta={},postureX={},postureY={}] (raw payload: {})",
                    deviceId, topicChannel, parsedState.state, parsedState.battery, parsedState.signalStrength,
                    parsedState.latitude, parsedState.longitude, parsedState.errorCode, parsedState.currentMowBoundary,
                    parsedState.currentMowProgress, parsedState.mowingPercentage, parsedState.mowingWeekArea,
                    parsedState.subtotalArea, parsedState.mowStartType, parsedState.postureTheta, parsedState.postureX,
                    parsedState.postureY, mergedState.state, mergedState.battery, mergedState.signalStrength,
                    mergedState.latitude, mergedState.longitude, mergedState.errorCode, mergedState.currentMowBoundary,
                    mergedState.currentMowProgress, mergedState.mowingPercentage, mergedState.mowingWeekArea,
                    mergedState.subtotalArea, mergedState.mowStartType, mergedState.postureTheta, mergedState.postureX,
                    mergedState.postureY, json);

            Consumer<NavimowMowerState> listener = mowerListeners.get(deviceId);
            if (listener != null) {
                listener.accept(mergedState);
            } else {
                LOGGER.debug("MQTT accepted but not forwarded: no listener registered for device {}", deviceId);
            }
        } catch (JsonSyntaxException | IllegalStateException e) {
            LOGGER.warn("MQTT rejected: JSON parse failed on topic {} payload={} error={}", topic, json,
                    e.getMessage());
        }
    }

    private void disconnectMqtt() {
        Mqtt3AsyncClient client = mqttClient;
        if (client != null) {
            client.disconnect();
            mqttClient = null;
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
                rememberLastState(entry.getKey(), state);
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
        int offset = parts.length > 0 && parts[0].isEmpty() ? 1 : 0;
        if (parts.length >= offset + 4 && "downlink".equals(parts[offset]) && "vehicle".equals(parts[offset + 1])) {
            return parts[offset + 2];
        }
        return null;
    }

    private @Nullable String extractTopicChannelFromTopic(String topic) {
        String[] parts = topic.split("/");
        int offset = parts.length > 0 && parts[0].isEmpty() ? 1 : 0;
        if (parts.length >= offset + 5 && "downlink".equals(parts[offset]) && "vehicle".equals(parts[offset + 1])
                && ("realtimeDate".equals(parts[offset + 3]) || "realtimeData".equals(parts[offset + 3]))) {
            return parts[offset + 4];
        }
        return null;
    }

    private void rememberLastState(String deviceId, NavimowMowerState state) {
        lastKnownStates.put(deviceId, cloneState(state));
    }

    private String buildWebClientId(@Nullable String username) {
        String base = (username == null || username.isBlank()) ? "unknown" : username;
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return "web_" + base + "_" + randomSuffix;
    }

    private NavimowMowerState mergeWithLastKnownState(String deviceId, NavimowMowerState incoming) {
        NavimowMowerState merged = cloneState(incoming);
        NavimowMowerState previous = lastKnownStates.get(deviceId);
        if (previous != null) {
            if ((merged.state == null || merged.state.isBlank() || "unknown".equals(merged.state))
                    && previous.state != null && !previous.state.isBlank()) {
                merged.state = previous.state;
            }
            if (merged.battery == null) {
                merged.battery = previous.battery;
            }
            if (merged.signalStrength == null) {
                merged.signalStrength = previous.signalStrength;
            }
            if (merged.latitude == null) {
                merged.latitude = previous.latitude;
            }
            if (merged.longitude == null) {
                merged.longitude = previous.longitude;
            }
            if (merged.errorCode == null) {
                merged.errorCode = previous.errorCode;
            }
            if (merged.errorMessage == null) {
                merged.errorMessage = previous.errorMessage;
            }
            if (merged.currentMowBoundary == null) {
                merged.currentMowBoundary = previous.currentMowBoundary;
            }
            if (merged.currentMowProgress == null) {
                merged.currentMowProgress = previous.currentMowProgress;
            }
            if (merged.mowingPercentage == null) {
                merged.mowingPercentage = previous.mowingPercentage;
            }
            if (merged.mowingWeekArea == null) {
                merged.mowingWeekArea = previous.mowingWeekArea;
            }
            if (merged.subtotalArea == null) {
                merged.subtotalArea = previous.subtotalArea;
            }
            if (merged.mowStartType == null) {
                merged.mowStartType = previous.mowStartType;
            }
            if (merged.postureTheta == null) {
                merged.postureTheta = previous.postureTheta;
            }
            if (merged.postureX == null) {
                merged.postureX = previous.postureX;
            }
            if (merged.postureY == null) {
                merged.postureY = previous.postureY;
            }
        }
        rememberLastState(deviceId, merged);
        return merged;
    }

    private NavimowMowerState cloneState(NavimowMowerState source) {
        NavimowMowerState clone = new NavimowMowerState();
        clone.deviceId = source.deviceId;
        clone.state = source.state;
        clone.battery = source.battery;
        clone.signalStrength = source.signalStrength;
        clone.latitude = source.latitude;
        clone.longitude = source.longitude;
        clone.errorCode = source.errorCode;
        clone.errorMessage = source.errorMessage;
        clone.currentMowBoundary = source.currentMowBoundary;
        clone.currentMowProgress = source.currentMowProgress;
        clone.mowingPercentage = source.mowingPercentage;
        clone.mowingWeekArea = source.mowingWeekArea;
        clone.subtotalArea = source.subtotalArea;
        clone.mowStartType = source.mowStartType;
        clone.postureTheta = source.postureTheta;
        clone.postureX = source.postureX;
        clone.postureY = source.postureY;
        return clone;
    }
}
