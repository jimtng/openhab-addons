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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.core.auth.client.oauth2.OAuthFactory;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link NavimowHandlerFactory} is responsible for creating things and thing handlers.
 *
 * @author Jimmy Tanagra - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.navimow", service = ThingHandlerFactory.class)
public class NavimowHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_BRIDGE, THING_TYPE_MOWER);

    private final HttpClient httpClient;
    private final OAuthFactory oAuthFactory;
    private final NavimowAuthService authService;

    @Activate
    public NavimowHandlerFactory(@Reference HttpClientFactory httpClientFactory, @Reference OAuthFactory oAuthFactory,
            @Reference NavimowAuthService authService) {
        this.httpClient = httpClientFactory.getCommonHttpClient();
        this.oAuthFactory = oAuthFactory;
        this.authService = authService;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_BRIDGE.equals(thingTypeUID)) {
            NavimowBridgeHandler handler = new NavimowBridgeHandler((Bridge) thing, oAuthFactory, httpClient);
            authService.addBridgeHandler(handler);
            return handler;
        }

        if (THING_TYPE_MOWER.equals(thingTypeUID)) {
            return new NavimowHandler(thing);
        }

        return null;
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof NavimowBridgeHandler bridgeHandler) {
            authService.removeBridgeHandler(bridgeHandler);
        }
    }
}
