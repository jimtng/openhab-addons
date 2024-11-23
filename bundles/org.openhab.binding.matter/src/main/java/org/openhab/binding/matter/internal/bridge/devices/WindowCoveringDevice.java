/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.matter.internal.bridge.devices;

import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.matter.internal.bridge.MatterBridgeClient;
import org.openhab.core.items.*;
import org.openhab.core.library.items.DimmerItem;
import org.openhab.core.library.items.RollershutterItem;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;

/**
 * The {@link WindowCoveringDevice}
 *
 * @author Dan Cunningham - Initial contribution
 */
@NonNullByDefault
public class WindowCoveringDevice extends GenericDevice {

    public WindowCoveringDevice(MetadataRegistry metadataRegistry, MatterBridgeClient client, GenericItem item) {
        super(metadataRegistry, client, item);
    }

    @Override
    public String deviceType() {
        return "WindowCovering";
    }

    @Override
    public MatterDeviceOptions activate() {
        dispose();
        primaryItem.addStateChangeListener(this);
        MetaDataMapping primaryMetadata = metaDataMapping(primaryItem);
        Map<String, Object> attributeMap = primaryMetadata.getAttributeOptions();
        attributeMap.put("windowCovering.currentPositionLiftPercent100ths",
                Optional.ofNullable(primaryItem.getStateAs(PercentType.class)).orElseGet(() -> new PercentType(0))
                        .intValue() * 100);
        return new MatterDeviceOptions(attributeMap, primaryMetadata.label);
    }

    @Override
    public void dispose() {
        primaryItem.removeStateChangeListener(this);
    }

    @Override
    public void handleMatterEvent(String clusterName, String attributeName, Object data) {
        PercentType percentType = null;
        switch (attributeName) {
            case "targetPositionLiftPercent100ths":
                percentType = new PercentType((int) ((Double) data / 100));
                break;
            case "currentPositionLiftPercentage":
                percentType = new PercentType(((Double) data).intValue());
                break;
            default:
                break;
        }
        if (percentType != null) {
            boolean open = percentType.intValue() > 0;
            Metadata primaryItemMetadata = this.primaryItemMetadata;
            String key = open ? "OPEN" : "CLOSED";

            if (primaryItem instanceof GroupItem groupItem) {
                groupItem.send(percentType);
            } else if (primaryItem instanceof DimmerItem dimmerItem) {
                dimmerItem.send(percentType);
            } else if (primaryItem instanceof RollershutterItem rollerShutterItem) {
                rollerShutterItem.send(percentType);
            } else if (primaryItem instanceof SwitchItem switchItem) {
                String value = open ? "ON" : "OFF";
                if (primaryItemMetadata != null) {
                    value = primaryItemMetadata.getConfiguration().getOrDefault(key, value).toString();
                }
                switchItem.send(OnOffType.from(value));
            } else if (primaryItem instanceof StringItem stringItem) {
                Object value = key;
                if (primaryItemMetadata != null) {
                    value = primaryItemMetadata.getConfiguration().getOrDefault(key, key);
                }
                stringItem.send(new StringType(value.toString()));
            }
        }
    }

    public void updateState(Item item, State state) {
        if (state instanceof PercentType percentType) {
            setEndpointState("windowCovering", "currentPositionLiftPercent100ths", percentType.intValue() * 100);
        } else if (state instanceof OpenClosedType openClosedType) {
            setEndpointState("windowCovering", "currentPositionLiftPercent100ths",
                    openClosedType == OpenClosedType.OPEN ? 10000 : 0);
        } else if (state instanceof OnOffType onOffType) {
            setEndpointState("windowCovering", "currentPositionLiftPercent100ths",
                    onOffType == OnOffType.ON ? 0 : 10000);
        } else if (state instanceof StringType stringType) {
            int pos = 0;
            Metadata primaryItemMetadata = this.primaryItemMetadata;
            if (primaryItemMetadata != null) {
                Object openValue = primaryItemMetadata.getConfiguration().get("OPEN");
                Object closeValue = primaryItemMetadata.getConfiguration().get("CLOSED");
                if (openValue instanceof String && closeValue instanceof String) {
                    if (stringType.equals(openValue)) {
                        pos = 0;
                    } else if (stringType.equals(closeValue)) {
                        pos = 10000;
                    }
                }
            }
            if (pos > -1) {
                setEndpointState("windowCovering", "currentPositionLiftPercent100ths", pos);
            }
        }
    }
}
