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
package org.openhab.binding.navimow.internal.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * DTO representing a Navimow mower state received via MQTT or HTTP polling.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowMowerState {

    /** Canonical device ID. */
    public String deviceId = "";

    /**
     * Canonical mower state: idle, mowing, paused, docked, charging, returning, error, unknown.
     */
    public String state = "unknown";

    /** Battery level 0–100, or null if not reported. */
    public @Nullable Integer battery;

    /** RSSI signal strength, or null if not reported. */
    public @Nullable Integer signalStrength;

    /** Latitude from position, or null if not reported. */
    public @Nullable Double latitude;

    /** Longitude from position, or null if not reported. */
    public @Nullable Double longitude;

    /** Error code string, or null if no error. */
    public @Nullable String errorCode;

    /** Human-readable error message, or null if no error. */
    public @Nullable String errorMessage;

    /** Current mowing boundary identifier from location telemetry, or null if not reported. */
    public @Nullable Integer currentMowBoundary;

    /** Current mowing progress percentage from location telemetry, or null if not reported. */
    public @Nullable Double currentMowProgress;

    /** Current mowing completion percentage, or null if not reported. */
    public @Nullable Integer mowingPercentage;

    /** Reported cumulative mowing area for the week, or null if not reported. */
    public @Nullable Double mowingWeekArea;

    /** Reported subtotal area for the current activity, or null if not reported. */
    public @Nullable Double subtotalArea;

    /** Mowing start type indicator from location telemetry, or null if not reported. */
    public @Nullable String mowStartType;

    /** Posture heading angle from location telemetry, or null if not reported. */
    public @Nullable Double postureTheta;

    /** Posture X coordinate inside the current zone from location telemetry, or null if not reported. */
    public @Nullable Double postureX;

    /** Posture Y coordinate inside the current zone from location telemetry, or null if not reported. */
    public @Nullable Double postureY;
}
