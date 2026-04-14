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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link NavimowBridgeConfiguration} class contains fields mapping bridge configuration parameters.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowBridgeConfiguration {

    /** OAuth2 access token obtained from the Navimow account. */
    public String accessToken = "";

    /**
     * Interval in seconds for HTTP polling fallback when MQTT data is stale.
     * Default: 3600 (1 hour).
     */
    public int pollingInterval = 3600;

    /** Override the Navimow API base URL. Useful for testing. */
    public String apiBaseUrl = NavimowBindingConstants.API_BASE_URL;
}
