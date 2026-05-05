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
package org.openhab.binding.navimow.internal.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.navimow.internal.dto.NavimowDevice;
import org.openhab.binding.navimow.internal.dto.NavimowMowerState;
import org.openhab.binding.navimow.internal.dto.NavimowMqttInfo;
import org.openhab.core.auth.client.oauth2.AccessTokenResponse;
import org.openhab.core.auth.client.oauth2.OAuthClientService;
import org.openhab.core.auth.client.oauth2.OAuthException;
import org.openhab.core.auth.client.oauth2.OAuthResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * HTTP REST API client for the Navimow cloud service.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(NavimowApi.class);
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final int API_SUCCESS_CODE = 1;

    // Raw state values from the Navimow cloud → canonical state
    private static final Map<String, String> RAW_STATE_MAP = new HashMap<>();

    static {
        RAW_STATE_MAP.put("isDocked", "docked");
        RAW_STATE_MAP.put("isIdel", "idle"); // typo present in original API
        RAW_STATE_MAP.put("isIdle", "idle");
        RAW_STATE_MAP.put("isMapping", "mowing");
        RAW_STATE_MAP.put("isRunning", "mowing");
        RAW_STATE_MAP.put("isPaused", "paused");
        RAW_STATE_MAP.put("isDocking", "returning");
        RAW_STATE_MAP.put("Error", "error");
        RAW_STATE_MAP.put("error", "error");
        RAW_STATE_MAP.put("isLifted", "error");
        RAW_STATE_MAP.put("inSoftwareUpdate", "paused");
        RAW_STATE_MAP.put("Self-Checking", "idle");
        RAW_STATE_MAP.put("Self-checking", "idle");
        RAW_STATE_MAP.put("Offline", "unknown");
        RAW_STATE_MAP.put("offline", "unknown");
    }

    private final HttpClient httpClient;
    private final Gson gson;
    private final String baseUrl;
    private final OAuthClientService oAuthService;

    /**
     * Creates a new Navimow API client.
     *
     * @param httpClient the Jetty HTTP client
     * @param baseUrl the Navimow API base URL
     * @param oAuthService the OAuth2 client service providing access tokens
     */
    public NavimowApi(HttpClient httpClient, String baseUrl, OAuthClientService oAuthService) {
        this.httpClient = httpClient;
        this.gson = new Gson();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.oAuthService = oAuthService;
    }

    /**
     * Retrieves the current access token from the OAuth2 service.
     *
     * @return the access token string
     * @throws NavimowAuthException if no valid token is available
     */
    private String getAccessToken() throws NavimowAuthException {
        try {
            AccessTokenResponse response = oAuthService.getAccessTokenResponse();
            String token = response != null ? response.getAccessToken() : null;
            if (token == null) {
                throw new NavimowAuthException(
                        "Not authorized. Please visit http://openhab:8080/navimow to authorize.");
            }
            return token;
        } catch (OAuthException | IOException | OAuthResponseException e) {
            throw new NavimowAuthException("Failed to retrieve access token: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches the list of devices associated with the account.
     *
     * @return list of devices
     * @throws NavimowApiException on communication or authentication error
     */
    public List<NavimowDevice> getDevices() throws NavimowApiException {
        JsonObject data = getRequest("/openapi/smarthome/authList");
        JsonObject payload = getPayload(data);
        JsonArray devicesArray = payload.getAsJsonArray("devices");

        List<NavimowDevice> devices = new ArrayList<>();
        if (devicesArray != null) {
            for (JsonElement element : devicesArray) {
                NavimowDevice device = parseDevice(element.getAsJsonObject());
                devices.add(device);
            }
        }
        return devices;
    }

    /**
     * Fetches the current status of a single device via HTTP.
     *
     * @param deviceId the device ID
     * @return the mower state
     * @throws NavimowApiException on communication or authentication error
     */
    public NavimowMowerState getDeviceStatus(String deviceId) throws NavimowApiException {
        JsonObject requestBody = new JsonObject();
        JsonArray deviceArray = new JsonArray();
        JsonObject deviceEntry = new JsonObject();
        deviceEntry.addProperty("id", deviceId);
        deviceArray.add(deviceEntry);
        requestBody.add("devices", deviceArray);

        JsonObject data = postRequest("/openapi/smarthome/getVehicleStatus", requestBody);
        LOGGER.debug("HTTP getVehicleStatus response: {}", data);
        JsonObject payload = getPayload(data);
        JsonArray devicesArray = payload.getAsJsonArray("devices");

        if (devicesArray != null && devicesArray.size() > 0) {
            return parseDeviceStatus(devicesArray.get(0).getAsJsonObject(), deviceId);
        }
        throw new NavimowApiException("No status returned for device " + deviceId);
    }

    /**
     * Fetches MQTT connection credentials from the cloud API.
     *
     * @return MQTT connection info
     * @throws NavimowApiException on communication or authentication error
     */
    public NavimowMqttInfo getMqttUserInfo() throws NavimowApiException {
        JsonObject data = getRequest("/openapi/mqtt/userInfo/get/v2");
        LOGGER.debug("MQTT userInfo response: {}", data);
        NavimowMqttInfo mqttInfo = gson.fromJson(data, NavimowMqttInfo.class);
        if (mqttInfo != null) {
            LOGGER.debug("Parsed MQTT info: host={} url={} user={} hasPassword={}", mqttInfo.mqttHost, mqttInfo.mqttUrl,
                    mqttInfo.userName, mqttInfo.pwdInfo != null);
        }
        return mqttInfo != null ? mqttInfo : new NavimowMqttInfo();
    }

    /**
     * Sends a mower command to the cloud API.
     *
     * @param deviceId the target device ID
     * @param commandName the Google Home-style command name
     * @param params optional command parameters (may be null)
     * @throws NavimowApiException on communication or authentication error
     */
    public void sendCommand(String deviceId, String commandName, @Nullable JsonObject params)
            throws NavimowApiException {
        JsonObject execution = new JsonObject();
        execution.addProperty("command", commandName);
        if (params != null) {
            execution.add("params", params);
        }

        JsonObject deviceEntry = new JsonObject();
        deviceEntry.addProperty("id", deviceId);
        JsonArray deviceArray = new JsonArray();
        deviceArray.add(deviceEntry);

        JsonObject commandItem = new JsonObject();
        commandItem.add("devices", deviceArray);
        commandItem.add("execution", execution);

        JsonArray commandsArray = new JsonArray();
        commandsArray.add(commandItem);

        JsonObject requestBody = new JsonObject();
        requestBody.add("commands", commandsArray);

        JsonObject data = postRequest("/openapi/smarthome/sendCommands", requestBody);
        JsonObject payload = getPayload(data);
        JsonArray results = payload.getAsJsonArray("commands");
        if (results != null) {
            for (JsonElement element : results) {
                JsonObject result = element.getAsJsonObject();
                if ("ERROR".equals(getStringOrNull(result, "status"))) {
                    String errorCode = getStringOrNull(result, "errorCode");
                    if (!"alreadyInState".equals(errorCode)) {
                        throw new NavimowApiException("Command failed: " + errorCode);
                    }
                }
            }
        }
    }

    /**
     * Sends a start mowing command.
     *
     * @param deviceId device ID
     * @throws NavimowApiException on error
     */
    public void startMowing(String deviceId) throws NavimowApiException {
        JsonObject params = new JsonObject();
        params.addProperty("on", true);
        sendCommand(deviceId, "action.devices.commands.StartStop", params);
    }

    /**
     * Sends a stop mowing command.
     *
     * @param deviceId device ID
     * @throws NavimowApiException on error
     */
    public void stopMowing(String deviceId) throws NavimowApiException {
        JsonObject params = new JsonObject();
        params.addProperty("on", false);
        sendCommand(deviceId, "action.devices.commands.StartStop", params);
    }

    /**
     * Sends a pause command.
     *
     * @param deviceId device ID
     * @throws NavimowApiException on error
     */
    public void pause(String deviceId) throws NavimowApiException {
        JsonObject params = new JsonObject();
        params.addProperty("on", false);
        sendCommand(deviceId, "action.devices.commands.PauseUnpause", params);
    }

    /**
     * Sends a resume mowing command.
     *
     * @param deviceId device ID
     * @throws NavimowApiException on error
     */
    public void resume(String deviceId) throws NavimowApiException {
        JsonObject params = new JsonObject();
        params.addProperty("on", true);
        sendCommand(deviceId, "action.devices.commands.PauseUnpause", params);
    }

    /**
     * Sends a dock (return to base) command.
     *
     * @param deviceId device ID
     * @throws NavimowApiException on error
     */
    public void dock(String deviceId) throws NavimowApiException {
        sendCommand(deviceId, "action.devices.commands.Dock", null);
    }

    /**
     * Parses an MQTT state payload into a {@link NavimowMowerState}.
     *
     * @param json the raw JSON payload
     * @param defaultDeviceId fallback device ID if not present in payload
     * @return the parsed state
     */
    public NavimowMowerState parseMqttStatePayload(JsonObject json, String defaultDeviceId) {
        LOGGER.debug("Parsing MQTT state payload: {}", json);
        JsonObject source = json;
        JsonElement paramsEl = json.get("params");
        if (paramsEl != null && paramsEl.isJsonObject()) {
            source = paramsEl.getAsJsonObject();
        } else {
            JsonElement payloadEl = json.get("payload");
            if (payloadEl != null && payloadEl.isJsonObject()) {
                source = payloadEl.getAsJsonObject();
            }
        }

        NavimowMowerState state = new NavimowMowerState();
        state.deviceId = getStringOrDefault(source, "device_id", defaultDeviceId);

        String rawState = getStringOrNull(source, "state");
        if (rawState == null) {
            rawState = getStringOrNull(source, "status");
        }
        if (rawState == null) {
            rawState = getStringOrNull(source, "vehicleState");
        }
        state.state = normalizeState(rawState);

        state.battery = extractBattery(source);
        state.signalStrength = getIntOrNull(source, "signal_strength");
        if (state.signalStrength == null) {
            state.signalStrength = getIntOrNull(source, "signalStrength");
        }
        if (state.signalStrength == null) {
            state.signalStrength = getIntOrNull(source, "rssi");
        }

        JsonElement positionEl = source.get("position");
        if (positionEl != null && positionEl.isJsonObject()) {
            JsonObject pos = positionEl.getAsJsonObject();
            state.latitude = getDoubleOrNull(pos, "lat");
            if (state.latitude == null) {
                state.latitude = getDoubleOrNull(pos, "latitude");
            }
            state.longitude = getDoubleOrNull(pos, "lng");
            if (state.longitude == null) {
                state.longitude = getDoubleOrNull(pos, "longitude");
            }
        }
        if (state.latitude == null) {
            state.latitude = getDoubleOrNull(source, "lat");
        }
        if (state.latitude == null) {
            state.latitude = getDoubleOrNull(source, "latitude");
        }
        if (state.longitude == null) {
            state.longitude = getDoubleOrNull(source, "lng");
        }
        if (state.longitude == null) {
            state.longitude = getDoubleOrNull(source, "longitude");
        }

        JsonElement errorEl = source.get("error");
        if (errorEl != null && errorEl.isJsonObject()) {
            JsonObject error = errorEl.getAsJsonObject();
            state.errorCode = getStringOrNull(error, "code");
            state.errorMessage = getStringOrNull(error, "message");
        } else {
            state.errorCode = getStringOrNull(source, "error_code");
            state.errorMessage = getStringOrNull(source, "error_message");
        }

        return state;
    }

    /**
     * Parses a MQTT location payload array into a {@link NavimowMowerState}.
     *
     * @param jsonArray the raw JSON array payload
     * @param defaultDeviceId fallback device ID if not present in payload
     * @return the parsed state
     */
    public NavimowMowerState parseMqttLocationPayload(JsonArray jsonArray, String defaultDeviceId) {
        LOGGER.debug("Parsing MQTT location payload: {}", jsonArray);

        NavimowMowerState state = new NavimowMowerState();
        state.deviceId = defaultDeviceId;

        for (JsonElement element : jsonArray) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject source = element.getAsJsonObject();
            JsonObject telemetrySource = source;
            JsonElement paramsEl = source.get("params");
            if (paramsEl != null && paramsEl.isJsonObject()) {
                telemetrySource = paramsEl.getAsJsonObject();
            } else {
                JsonElement payloadEl = source.get("payload");
                if (payloadEl != null && payloadEl.isJsonObject()) {
                    telemetrySource = payloadEl.getAsJsonObject();
                }
            }

            state.deviceId = getStringOrDefault(source, "device_id", state.deviceId);
            state.deviceId = getStringOrDefault(telemetrySource, "device_id", state.deviceId);

            Integer currentMowBoundary = getIntOrNull(telemetrySource, "currentMowBoundary");
            if (currentMowBoundary != null) {
                state.currentMowBoundary = currentMowBoundary;
            }

            Integer currentMowProgressRaw = getIntOrNull(telemetrySource, "currentMowProgress");
            if (currentMowProgressRaw != null) {
                state.currentMowProgress = currentMowProgressRaw / 100.0;
            }

            Integer mowingPercentage = getIntOrNull(telemetrySource, "mowingPercentage");
            if (mowingPercentage != null) {
                state.mowingPercentage = mowingPercentage;
            }

            Double mowingWeekArea = getDoubleOrNull(telemetrySource, "mowingWeekArea");
            if (mowingWeekArea != null) {
                state.mowingWeekArea = mowingWeekArea;
            }

            Double subtotalArea = getDoubleOrNull(telemetrySource, "subtotalArea");
            if (subtotalArea != null) {
                state.subtotalArea = subtotalArea;
            }

            String mowStartType = getStringOrNull(telemetrySource, "mowStartType");
            if (mowStartType != null) {
                state.mowStartType = mowStartType;
            }

            Double postureTheta = getDoubleOrNull(telemetrySource, "postureTheta");
            if (postureTheta != null) {
                state.postureTheta = postureTheta;
            }

            Double postureX = getDoubleOrNull(telemetrySource, "postureX");
            if (postureX != null) {
                state.postureX = postureX;
            }

            Double postureY = getDoubleOrNull(telemetrySource, "postureY");
            if (postureY != null) {
                state.postureY = postureY;
            }
        }

        return state;
    }

    // --- Private helper methods ---

    private JsonObject getRequest(String endpoint) throws NavimowApiException {
        try {
            String url = baseUrl + endpoint;
            String token = getAccessToken();
            Request request = httpClient.newRequest(url).method(HttpMethod.GET)
                    .header("Authorization", "Bearer " + token).header("requestId", UUID.randomUUID().toString())
                    .timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            ContentResponse response = request.send();
            return parseResponse(response, endpoint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NavimowApiException("Request interrupted: " + endpoint, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new NavimowApiException("Request failed: " + endpoint, e);
        }
    }

    private JsonObject postRequest(String endpoint, JsonObject body) throws NavimowApiException {
        try {
            String url = baseUrl + endpoint;
            String token = getAccessToken();
            String jsonBody = gson.toJson(body);
            Request request = httpClient.newRequest(url).method(HttpMethod.POST)
                    .header("Authorization", "Bearer " + token).header("requestId", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json").content(new StringContentProvider(jsonBody))
                    .timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            ContentResponse response = request.send();
            return parseResponse(response, endpoint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NavimowApiException("Request interrupted: " + endpoint, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new NavimowApiException("Request failed: " + endpoint, e);
        }
    }

    private JsonObject parseResponse(ContentResponse response, String endpoint) throws NavimowApiException {
        int status = response.getStatus();
        if (status == HttpStatus.UNAUTHORIZED_401 || status == HttpStatus.FORBIDDEN_403) {
            throw new NavimowAuthException("Authentication failed (HTTP " + status + ") for " + endpoint);
        }
        if (status < 200 || status >= 300) {
            throw new NavimowApiException("HTTP error " + status + " for " + endpoint);
        }

        String content = response.getContentAsString();
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();

        JsonElement codeEl = json.get("code");
        if (codeEl != null && codeEl.getAsInt() != API_SUCCESS_CODE) {
            JsonElement descEl = json.get("desc");
            String desc = descEl != null ? descEl.getAsString() : "unknown error";
            // codes 400003/4005/401 indicate auth/session failure
            if (codeEl.getAsInt() == 400003 || codeEl.getAsInt() == 4005 || codeEl.getAsInt() == 401) {
                throw new NavimowAuthException("API authentication error: " + desc);
            }
            throw new NavimowApiException("API error " + codeEl.getAsInt() + ": " + desc);
        }

        JsonElement dataEl = json.get("data");
        if (dataEl != null && dataEl.isJsonObject()) {
            return dataEl.getAsJsonObject();
        }
        return new JsonObject();
    }

    private JsonObject getPayload(JsonObject data) {
        JsonElement payloadEl = data.get("payload");
        if (payloadEl != null && payloadEl.isJsonObject()) {
            return payloadEl.getAsJsonObject();
        }
        return data;
    }

    private NavimowDevice parseDevice(JsonObject json) {
        NavimowDevice device = new NavimowDevice();
        device.id = getStringOrDefault(json, "id", "");
        device.name = getStringOrDefault(json, "name", "");
        device.model = getStringOrDefault(json, "model", "");
        device.firmwareVersion = getStringOrDefault(json, "firmware_version", "");
        device.serialNumber = getStringOrDefault(json, "serial_number", "");
        device.macAddress = getStringOrNull(json, "mac_address");
        device.productKey = getStringOrNull(json, "productKey");
        device.iotId = getStringOrNull(json, "iotId");
        JsonElement onlineEl = json.get("online");
        if (onlineEl != null && !onlineEl.isJsonNull()) {
            device.online = onlineEl.getAsBoolean();
        }
        return device;
    }

    private NavimowMowerState parseDeviceStatus(JsonObject json, String deviceId) {
        LOGGER.debug("Parsing HTTP device status payload: {}", json);
        NavimowMowerState state = new NavimowMowerState();
        state.deviceId = getStringOrDefault(json, "id", deviceId);

        String rawState = getStringOrNull(json, "vehicleState");
        if (rawState == null) {
            rawState = getStringOrNull(json, "status");
        }
        if (rawState == null) {
            rawState = getStringOrNull(json, "state");
        }
        state.state = normalizeState(rawState);
        state.battery = extractBattery(json);
        state.signalStrength = getIntOrNull(json, "signal_strength");
        if (state.signalStrength == null) {
            state.signalStrength = getIntOrNull(json, "signalStrength");
        }
        if (state.signalStrength == null) {
            state.signalStrength = getIntOrNull(json, "rssi");
        }

        JsonElement positionEl = json.get("position");
        if (positionEl != null && positionEl.isJsonObject()) {
            JsonObject pos = positionEl.getAsJsonObject();
            state.latitude = getDoubleOrNull(pos, "lat");
            if (state.latitude == null) {
                state.latitude = getDoubleOrNull(pos, "latitude");
            }
            state.longitude = getDoubleOrNull(pos, "lng");
            if (state.longitude == null) {
                state.longitude = getDoubleOrNull(pos, "longitude");
            }
        }
        if (state.latitude == null) {
            state.latitude = getDoubleOrNull(json, "lat");
        }
        if (state.latitude == null) {
            state.latitude = getDoubleOrNull(json, "latitude");
        }
        if (state.longitude == null) {
            state.longitude = getDoubleOrNull(json, "lng");
        }
        if (state.longitude == null) {
            state.longitude = getDoubleOrNull(json, "longitude");
        }

        String errorCodeStr = getStringOrNull(json, "error_code");
        if (errorCodeStr != null && !"none".equalsIgnoreCase(errorCodeStr)) {
            state.errorCode = errorCodeStr;
            state.errorMessage = getStringOrNull(json, "error_message");
        }

        return state;
    }

    private String normalizeState(@Nullable String rawState) {
        if (rawState == null || rawState.isEmpty()) {
            return "unknown";
        }
        String canonical = RAW_STATE_MAP.get(rawState);
        if (canonical != null) {
            return canonical;
        }
        // If already a canonical value, keep it
        if (rawState.equals("idle") || rawState.equals("mowing") || rawState.equals("paused")
                || rawState.equals("docked") || rawState.equals("charging") || rawState.equals("returning")
                || rawState.equals("error") || rawState.equals("unknown")) {
            return rawState;
        }
        LOGGER.debug("Unknown mower state value '{}', treating as unknown", rawState);
        return "unknown";
    }

    private @Nullable Integer extractBattery(JsonObject json) {
        // Direct battery field
        Integer battery = getIntOrNull(json, "battery");
        if (battery != null) {
            return battery;
        }
        // HTTP status payload uses capacityRemaining[] with unit=PERCENTAGE
        JsonElement crEl = json.get("capacityRemaining");
        if (crEl != null && crEl.isJsonArray()) {
            JsonArray crArray = crEl.getAsJsonArray();
            for (JsonElement item : crArray) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject itemObj = item.getAsJsonObject();
                String unit = getStringOrDefault(itemObj, "unit", "");
                if ("PERCENTAGE".equalsIgnoreCase(unit)) {
                    return getIntOrNull(itemObj, "rawValue");
                }
            }
            // Fallback: first element
            if (crArray.size() > 0 && crArray.get(0).isJsonObject()) {
                return getIntOrNull(crArray.get(0).getAsJsonObject(), "rawValue");
            }
        }
        return null;
    }

    private @Nullable String getStringOrNull(JsonObject json, String key) {
        JsonElement el = json.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        return el.getAsString();
    }

    private String getStringOrDefault(JsonObject json, String key, String defaultValue) {
        String value = getStringOrNull(json, key);
        return value != null ? value : defaultValue;
    }

    private @Nullable Integer getIntOrNull(JsonObject json, String key) {
        JsonElement el = json.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsInt();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private @Nullable Double getDoubleOrNull(JsonObject json, String key) {
        JsonElement el = json.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsDouble();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
