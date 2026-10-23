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

import static org.openhab.binding.navimow.internal.NavimowBindingConstants.*;

import javax.measure.quantity.Angle;
import javax.measure.quantity.Area;
import javax.measure.quantity.Dimensionless;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.navimow.internal.api.NavimowApi;
import org.openhab.binding.navimow.internal.api.NavimowApiException;
import org.openhab.binding.navimow.internal.dto.NavimowMowerState;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link NavimowHandler} is responsible for handling commands and state updates for a single Navimow mower.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(NavimowHandler.class);

    private String deviceId = "";

    public NavimowHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        NavimowConfiguration config = getConfigAs(NavimowConfiguration.class);
        deviceId = config.deviceId;

        if (deviceId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.config-error-no-device-id");
            return;
        }

        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                    "@text/offline.bridge-not-configured");
            return;
        }

        NavimowBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                    "@text/offline.bridge-not-configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);
        bridgeHandler.registerMowerListener(deviceId, this::onMowerStateUpdate);

        // Trigger an immediate poll so the thing gets real state quickly
        scheduler.execute(this::refreshFromBridge);
    }

    @Override
    public void dispose() {
        NavimowBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler != null) {
            bridgeHandler.unregisterMowerListener(deviceId);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            refreshFromBridge();
            return;
        }

        if (CHANNEL_COMMAND.equals(channelUID.getId())) {
            handleMowerCommand(command.toString().toLowerCase());
        }
    }

    private void handleMowerCommand(String commandStr) {
        NavimowBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler == null) {
            logger.warn("Bridge handler not available for device {}", deviceId);
            return;
        }

        NavimowApi api = bridgeHandler.getApi();
        if (api == null) {
            logger.warn("API not available for device {}", deviceId);
            return;
        }

        try {
            switch (commandStr) {
                case "start":
                case "mow":
                    api.startMowing(deviceId);
                    break;
                case "stop":
                    api.stopMowing(deviceId);
                    break;
                case "pause":
                    api.pause(deviceId);
                    break;
                case "resume":
                    api.resume(deviceId);
                    break;
                case "dock":
                case "return_to_base":
                    api.dock(deviceId);
                    break;
                default:
                    logger.warn("Unknown command '{}' for device {}", commandStr, deviceId);
                    return;
            }
            logger.debug("Sent command '{}' to device {}", commandStr, deviceId);
            // Schedule a status refresh 2 seconds after the command so the UI reflects the new state promptly
            scheduler.schedule(this::refreshFromBridge, 2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (NavimowApiException e) {
            logger.warn("Failed to send command '{}' to device {}: {}", commandStr, deviceId, e.getMessage());
        }
    }

    private void refreshFromBridge() {
        NavimowBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler == null) {
            return;
        }
        NavimowMowerState state = bridgeHandler.fetchDeviceStatus(deviceId);
        if (state != null) {
            onMowerStateUpdate(state);
        }
    }

    private void onMowerStateUpdate(NavimowMowerState state) {
        updateStatus(ThingStatus.ONLINE);
        updateChannel(CHANNEL_STATUS, () -> updateState(CHANNEL_STATUS, new StringType(state.state)));
        Integer battery = state.battery;
        if (battery != null) {
            updateChannel(CHANNEL_BATTERY,
                    () -> updateState(CHANNEL_BATTERY, new QuantityType<Dimensionless>(battery, Units.PERCENT)));
        }
        Integer signalStrength = state.signalStrength;
        if (signalStrength != null) {
            updateChannel(CHANNEL_SIGNAL_STRENGTH,
                    () -> updateState(CHANNEL_SIGNAL_STRENGTH, new DecimalType(signalStrength)));
        }
        Double latitude = state.latitude;
        Double longitude = state.longitude;
        if (latitude != null && longitude != null) {
            updateChannel(CHANNEL_LOCATION, () -> updateState(CHANNEL_LOCATION,
                    new PointType(new DecimalType(latitude), new DecimalType(longitude))));
        }
        updateChannel(CHANNEL_ERROR_CODE, () -> updateState(CHANNEL_ERROR_CODE,
                new StringType(state.errorCode != null ? state.errorCode : "none")));
        Integer currentMowBoundary = state.currentMowBoundary;
        if (currentMowBoundary != null) {
            updateChannel(CHANNEL_CURRENT_MOW_BOUNDARY,
                    () -> updateState(CHANNEL_CURRENT_MOW_BOUNDARY, new DecimalType(currentMowBoundary)));
        }
        Double currentMowProgress = state.currentMowProgress;
        if (currentMowProgress != null) {
            updateChannel(CHANNEL_CURRENT_MOW_PROGRESS, () -> updateState(CHANNEL_CURRENT_MOW_PROGRESS,
                    new QuantityType<Dimensionless>(currentMowProgress, Units.PERCENT)));
        }
        Integer mowingPercentage = state.mowingPercentage;
        if (mowingPercentage != null) {
            updateChannel(CHANNEL_MOWING_PERCENTAGE, () -> updateState(CHANNEL_MOWING_PERCENTAGE,
                    new QuantityType<Dimensionless>(mowingPercentage, Units.PERCENT)));
        }
        Double mowingWeekArea = state.mowingWeekArea;
        if (mowingWeekArea != null) {
            updateChannel(CHANNEL_MOWING_WEEK_AREA, () -> updateState(CHANNEL_MOWING_WEEK_AREA,
                    new QuantityType<Area>(mowingWeekArea, SIUnits.SQUARE_METRE)));
        }
        Double subtotalArea = state.subtotalArea;
        if (subtotalArea != null) {
            updateChannel(CHANNEL_SUBTOTAL_AREA, () -> updateState(CHANNEL_SUBTOTAL_AREA,
                    new QuantityType<Area>(subtotalArea, SIUnits.SQUARE_METRE)));
        }
        String mowStartType = state.mowStartType;
        if (mowStartType != null) {
            updateChannel(CHANNEL_MOW_START_TYPE,
                    () -> updateState(CHANNEL_MOW_START_TYPE, new StringType(mowStartType)));
        }
        Double postureTheta = state.postureTheta;
        if (postureTheta != null) {
            updateChannel(CHANNEL_POSTURE_THETA,
                    () -> updateState(CHANNEL_POSTURE_THETA, new QuantityType<Angle>(postureTheta, Units.RADIAN)));
        }
        Double postureX = state.postureX;
        if (postureX != null) {
            updateChannel(CHANNEL_POSTURE_X, () -> updateState(CHANNEL_POSTURE_X, new DecimalType(postureX)));
        }
        Double postureY = state.postureY;
        if (postureY != null) {
            updateChannel(CHANNEL_POSTURE_Y, () -> updateState(CHANNEL_POSTURE_Y, new DecimalType(postureY)));
        }
    }

    private void updateChannel(String channel, Runnable update) {
        try {
            update.run();
        } catch (RuntimeException e) {
            logger.warn("Failed to update Navimow channel {} for device {}: {}", channel, deviceId, e.getMessage(), e);
        }
    }

    private @Nullable NavimowBridgeHandler getBridgeHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        }
        BridgeHandler handler = bridge.getHandler();
        if (handler instanceof NavimowBridgeHandler bridgeHandler) {
            return bridgeHandler;
        }
        return null;
    }
}
