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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Exception thrown when the Navimow API returns an authentication error (HTTP 401/403 or API code 401/400003).
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
public class NavimowAuthException extends NavimowApiException {

    private static final long serialVersionUID = 1L;

    public NavimowAuthException(String message) {
        super(message);
    }
}
