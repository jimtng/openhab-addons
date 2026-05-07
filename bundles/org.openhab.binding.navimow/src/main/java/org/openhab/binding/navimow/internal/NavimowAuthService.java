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

import static org.openhab.binding.navimow.internal.NavimowBindingConstants.NAVIMOW_SERVLET_ALIAS;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;

import javax.servlet.ServletException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.navimow.internal.api.NavimowApiException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link NavimowAuthService} manages the OAuth2 authorization servlet and bridges that need authorization.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@Component(service = NavimowAuthService.class)
@NonNullByDefault
public class NavimowAuthService {

    private final Logger logger = LoggerFactory.getLogger(NavimowAuthService.class);

    private final List<NavimowBridgeHandler> handlers = new ArrayList<>();

    private @NonNullByDefault({}) HttpService httpService;

    @Activate
    protected void activate() {
        try {
            httpService.registerServlet(NAVIMOW_SERVLET_ALIAS, new NavimowAuthServlet(this), new Hashtable<>(),
                    httpService.createDefaultHttpContext());
            logger.debug("Navimow auth servlet registered at {}", NAVIMOW_SERVLET_ALIAS);
        } catch (NamespaceException | ServletException e) {
            logger.warn("Failed to register Navimow auth servlet", e);
        }
    }

    @Deactivate
    protected void deactivate() {
        httpService.unregister(NAVIMOW_SERVLET_ALIAS);
        logger.debug("Navimow auth servlet unregistered");
    }

    /**
     * Adds a bridge handler to the list of handlers that can be authorized.
     *
     * @param handler the bridge handler to add
     */
    public void addBridgeHandler(NavimowBridgeHandler handler) {
        if (!handlers.contains(handler)) {
            handlers.add(handler);
        }
    }

    /**
     * Removes a bridge handler from the list.
     *
     * @param handler the bridge handler to remove
     */
    public void removeBridgeHandler(NavimowBridgeHandler handler) {
        handlers.remove(handler);
    }

    /**
     * Returns all registered bridge handlers.
     *
     * @return list of bridge handlers
     */
    public List<NavimowBridgeHandler> getBridgeHandlers() {
        return handlers;
    }

    /**
     * Returns the registered bridge handler with the given thing ID.
     *
     * @param thingId the bridge thing ID
     * @return the matching bridge handler, or null if not found
     */
    public @Nullable NavimowBridgeHandler getBridgeHandlerByThingId(String thingId) {
        Optional<NavimowBridgeHandler> match = handlers.stream().filter(h -> h.hasThingId(thingId)).findFirst();
        return match.orElse(null);
    }

    /**
     * Handles the OAuth2 authorization callback. Finds the bridge by the state value (thing UID),
     * exchanges the authorization code for an access token, and triggers reconnection.
     *
     * @param servletBaseURL the redirect_uri (this servlet's URL)
     * @param state the state parameter returned by the OAuth server (used as thing UID)
     * @param code the authorization code returned by the OAuth server
     * @return the label of the authorized bridge
     * @throws NavimowApiException if authorization fails
     */
    public String authorize(String servletBaseURL, String state, String code) throws NavimowApiException {
        NavimowBridgeHandler handler = getHandlerByState(state);
        if (handler == null) {
            throw new NavimowApiException(
                    "No matching bridge found for state '" + state + "'. Has the bridge been removed?");
        }
        return handler.authorize(servletBaseURL, code);
    }

    private @Nullable NavimowBridgeHandler getHandlerByState(String thingUID) {
        Optional<NavimowBridgeHandler> match = handlers.stream().filter(h -> h.equalsThingUID(thingUID)).findFirst();
        return match.orElse(null);
    }

    @Reference
    protected void setHttpService(HttpService httpService) {
        this.httpService = httpService;
    }

    protected void unsetHttpService(HttpService httpService) {
        this.httpService = null; // NOSONAR
    }
}
