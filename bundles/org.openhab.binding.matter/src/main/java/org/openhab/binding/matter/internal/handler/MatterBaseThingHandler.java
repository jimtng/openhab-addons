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
package org.openhab.binding.matter.internal.handler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.matter.internal.MatterBindingConstants;
import org.openhab.binding.matter.internal.MatterChannelTypeProvider;
import org.openhab.binding.matter.internal.MatterStateDescriptionOptionProvider;
import org.openhab.binding.matter.internal.client.AttributeListener;
import org.openhab.binding.matter.internal.client.EventTriggeredListener;
import org.openhab.binding.matter.internal.client.MatterWebsocketClient;
import org.openhab.binding.matter.internal.client.model.Endpoint;
import org.openhab.binding.matter.internal.client.model.cluster.BaseCluster;
import org.openhab.binding.matter.internal.client.model.cluster.ClusterCommand;
import org.openhab.binding.matter.internal.client.model.cluster.gen.*;
import org.openhab.binding.matter.internal.client.model.ws.AttributeChangedMessage;
import org.openhab.binding.matter.internal.client.model.ws.EventTriggeredMessage;
import org.openhab.binding.matter.internal.controller.MatterControllerClient;
import org.openhab.binding.matter.internal.controller.devices.types.DeviceType;
import org.openhab.binding.matter.internal.controller.devices.types.DeviceTypeRegistry;
import org.openhab.binding.matter.internal.util.MatterUIDUtils;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.*;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MatterBaseThingHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Dan Cunningham - Initial contribution
 */
@NonNullByDefault
public abstract class MatterBaseThingHandler extends BaseThingHandler
        implements AttributeListener, EventTriggeredListener {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final MatterStateDescriptionOptionProvider stateDescriptionProvider;
    protected final MatterChannelTypeProvider channelTypeProvider;
    protected HashMap<Integer, DeviceType> devices = new HashMap<>();
    protected @Nullable MatterControllerClient cachedClient;

    public MatterBaseThingHandler(Thing thing, MatterStateDescriptionOptionProvider stateDescriptionProvider,
            MatterChannelTypeProvider channelTypeProvider) {
        super(thing);
        this.stateDescriptionProvider = stateDescriptionProvider;
        this.channelTypeProvider = channelTypeProvider;
    }

    protected abstract BigInteger getNodeId();

    protected abstract ThingTypeUID getDynamicThingTypeUID();

    protected abstract boolean isBridgeType();

    /**
     * When processing endpoints, give implementers the ability to ignore certain endpoints
     * 
     * @param endpoint
     * @return
     */
    protected boolean shouldAddEndpoint(Endpoint endpoint) {
        return true;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        MatterWebsocketClient client = getClient();
        if (client == null) {
            logger.debug("Matter client not present, ignoring command");
            return;
        }

        if (command instanceof RefreshType) {
            ControllerHandler clusterHandler = controllerHandler();
            if (clusterHandler != null) {
                clusterHandler.updateNode(getNodeId());
            }
            return;
        }
        String endpointIdString = channelUID.getGroupId();
        if (endpointIdString != null) {
            Integer endpointId = Integer.valueOf(endpointIdString);
            DeviceType deviceType = this.devices.get(endpointId);
            if (deviceType != null) {
                deviceType.handleCommand(channelUID, command);
            }
        }
    }

    @Override
    public void dispose() {
        channelTypeProvider.removeChannelGroupTypesForPrefix(getThing().getThingTypeUID().getId());
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    // making this public
    @Override
    public void updateThing(Thing thing) {
        super.updateThing(thing);
    }

    @Override
    public void handleRemoval() {
        channelTypeProvider.removeThingType(getThing().getThingTypeUID());
        channelTypeProvider.removeChannelGroupTypesForPrefix(thing.getThingTypeUID().getId());
    }

    @Override
    protected ThingBuilder editThing() {
        return ThingBuilder.create(getDynamicThingTypeUID(), getThing().getUID()).withBridge(getThing().getBridgeUID())
                .withChannels(getThing().getChannels()).withConfiguration(getThing().getConfiguration())
                .withLabel(getThing().getLabel()).withLocation(getThing().getLocation())
                .withProperties(getThing().getProperties());
    }

    @Override
    public void onEvent(AttributeChangedMessage message) {
        logger.debug("AttributeChangedMessage for endpoint {}", message.path.endpointId);
        DeviceType deviceType = devices.get(message.path.endpointId);
        if (deviceType != null) {
            deviceType.onEvent(message);
        }
    }

    @Override
    public void onEvent(EventTriggeredMessage message) {
        DeviceType deviceType = devices.get(message.path.endpointId);
        if (deviceType != null) {
            deviceType.onEvent(message);
        }
    }

    public void updateState(Integer endpointNumber, ChannelTypeUID channelTypeUID, State state) {
        ChannelGroupUID channelGroupUID = new ChannelGroupUID(getThing().getUID(), endpointNumber.toString());
        ChannelUID channelUID = new ChannelUID(channelGroupUID, channelTypeUID.getId());
        super.updateState(channelUID, state);
    }

    public void triggerChannel(Integer endpointNumber, ChannelTypeUID channelTypeUID, String event) {
        ChannelGroupUID channelGroupUID = new ChannelGroupUID(getThing().getUID(), endpointNumber.toString());
        ChannelUID channelUID = new ChannelUID(channelGroupUID, channelTypeUID.getId());
        super.triggerChannel(channelUID, event);
    }

    /**
     * Main Processing of Matter endpoints. This will create an Channel Group per endpoint
     * 
     * @param endpoint
     */
    protected synchronized void updateEndpoint(Endpoint endpoint) {
        List<ChannelGroupType> groupTypes = new ArrayList<>();
        List<ChannelGroupDefinition> groupDefs = new ArrayList<>();
        List<Channel> channels = new ArrayList<>();

        updateEndpointInternal(endpoint, groupTypes, groupDefs, channels);

        // update our thing using a custom thing type so we can add channel groups dynamically.
        // editThing() is overridden to use the type specific thing type here.
        // See https://github.com/openhab/openhab-addons/pull/16600 for more information on this technique
        ThingTypeUID baseThingTypeUID = MatterUIDUtils.baseTypeForThingType(getDynamicThingTypeUID());
        ThingTypeUID dynamicThingTypeUID = getDynamicThingTypeUID();
        if (baseThingTypeUID == null) {
            throw new RuntimeException("Could not resolve base thing type for " + getThing().getThingTypeUID());
        }
        ThingTypeBuilder thingTypeBuilder = channelTypeProvider.derive(dynamicThingTypeUID, baseThingTypeUID);
        thingTypeBuilder.withChannelGroupDefinitions(groupDefs);
        channelTypeProvider.putThingType(isBridgeType() ? thingTypeBuilder.buildBridge() : thingTypeBuilder.build());
        channelTypeProvider.updateChannelGroupTypesForPrefix(dynamicThingTypeUID.getId(), groupTypes);

        ThingBuilder thingBuilder = editThing().withChannels();
        thingBuilder.withChannels(channels);
        updateThing(thingBuilder.build());
    }

    /**
     * Iterate recursively through endpoints, adding channel groups and channels.
     * 
     * @param endpoint
     */
    private void updateEndpointInternal(Endpoint endpoint, List<ChannelGroupType> groupTypes,
            List<ChannelGroupDefinition> groupDefs, List<Channel> channels) {
        logger.debug("updateEndpointInternal {}", endpoint.number);

        // give implementers the ability to act on and prevent endpoints from being added (mainly for bridge endpoints)
        if (!shouldAddEndpoint(endpoint)) {
            return;
        }

        Map<String, BaseCluster> clusters = endpoint.clusters;
        Object basicInfoObject = clusters.get(BasicInformationCluster.CLUSTER_NAME);

        // The matter spec requires a descriptor cluster and device type, so this should always be present
        DescriptorCluster descriptorCluster = (DescriptorCluster) clusters.get(DescriptorCluster.CLUSTER_NAME);
        Integer deviceTypID = -1;
        if (descriptorCluster != null && !descriptorCluster.deviceTypeList.isEmpty()) {
            deviceTypID = descriptorCluster.deviceTypeList.get(0).deviceType;
        }

        // labels will look like "Device Type : Custom Node Label Or Product Label"
        final StringBuffer label = new StringBuffer(splitAndCapitalize(DeviceTypes.DEVICE_MAPPING.get(deviceTypID)))
                .append(": ");

        // Check if a "nodeLabel" is set, otherwise use the product label. This varies from vendor to vendor
        if (basicInfoObject != null) {
            BasicInformationCluster basicInfo = (BasicInformationCluster) basicInfoObject;
            label.append(basicInfo.nodeLabel != null && basicInfo.nodeLabel.length() > 0 ? basicInfo.nodeLabel
                    : basicInfo.productLabel);
        } else {
            // we need to add a child device, a child instance of this class
            basicInfoObject = clusters.get(BridgedDeviceBasicInformationCluster.CLUSTER_NAME);
            if (basicInfoObject != null) {
                BridgedDeviceBasicInformationCluster basicInfo = (BridgedDeviceBasicInformationCluster) basicInfoObject;
                label.append(basicInfo.nodeLabel != null && basicInfo.nodeLabel.length() > 0 ? basicInfo.nodeLabel
                        : basicInfo.productLabel);
            }
        }

        // Fixed labels are a way of vendors to label endpoints with additional meta data.
        if (clusters.get(FixedLabelCluster.CLUSTER_NAME) instanceof FixedLabelCluster fixedLabelCluster) {
            fixedLabelCluster.labelList
                    .forEach(fixedLabel -> label.append(" " + fixedLabel.label + " " + fixedLabel.value));
        }

        // label for the Group Channel
        String deviceLabel = label.toString().trim();

        // Do we already have a device created yet?
        DeviceType deviceType = devices.get(endpoint.number);
        if (deviceType == null) {
            deviceType = DeviceTypeRegistry.createDeviceType(deviceTypID, this, endpoint.number);
            devices.put(endpoint.number, deviceType);
            logger.debug("updateEndpointInternal added device type {} {}", deviceTypID, endpoint.number);
        }

        // create custom group for endpoint
        ChannelGroupTypeUID channelGroupTypeUID = new ChannelGroupTypeUID(MatterBindingConstants.BINDING_ID,
                getNodeId() + "_" + endpoint.number);
        ChannelGroupDefinition channelGroupDefinition = new ChannelGroupDefinition(endpoint.number.toString(),
                channelGroupTypeUID);
        ChannelGroupType channelGroupType = ChannelGroupTypeBuilder.instance(channelGroupTypeUID, deviceLabel)
                .withDescription(deviceLabel).build();
        groupDefs.add(channelGroupDefinition);
        groupTypes.add(channelGroupType);

        ChannelGroupUID channelGroupUID = new ChannelGroupUID(getThing().getUID(), endpoint.number.toString());

        // add channels, which will be used by the calling 'updateEndpoint' function
        channels.addAll(deviceType.createChannels(endpoint.number, clusters, channelGroupUID));

        final DeviceType dt = deviceType;
        // give the cluster their initial values
        clusters.forEach((clusterName, cluster) -> dt.updateCluster(cluster));

        // add the state descriptions of channels to our custom stateDescriptionProvider
        deviceType.getStateDescriptions().forEach((channelUID, stateDescription) -> {
            if (stateDescription != null) {
                Optional.ofNullable(stateDescription.getOptions())
                        .ifPresent(options -> stateDescriptionProvider.setStateOptions(channelUID, options));

                Optional.ofNullable(stateDescription.getPattern())
                        .ifPresent(pattern -> stateDescriptionProvider.setStatePattern(channelUID, pattern));

                BigDecimal min = stateDescription.getMinimum();
                BigDecimal max = stateDescription.getMaximum();
                if (min != null && max != null) {
                    stateDescriptionProvider.setMinMax(channelUID, min, max, stateDescription.getStep(),
                            stateDescription.getPattern());
                }
            }
        });
        // add any children recursively (endpoints can have child endpoints)
        endpoint.children.forEach(e -> updateEndpointInternal(e, groupTypes, groupDefs, channels));
    }

    public void setEndpointStatus(ThingStatus status, ThingStatusDetail detail, String description) {
        logger.debug("setEndpointStatus {} {} {} {}", status, detail, description, getNodeId());
        updateStatus(status, detail, description);
    }

    public void sendClusterCommand(Integer endpointId, String clusterName, ClusterCommand command) {
        MatterControllerClient client = getClient();
        if (client != null) {
            client.clusterCommand(getNodeId(), endpointId, clusterName, command);
        }
    }

    public void writeAttribute(Integer endpointId, String clusterName, String attributeName, String value) {
        MatterControllerClient ws = getClient();
        if (ws != null) {
            ws.clusterWriteAttribute(getNodeId(), endpointId, clusterName, attributeName, value);
        }
    }

    public CompletableFuture<String> readAttribute(Integer endpointId, String clusterName, String attributeName) {
        MatterControllerClient ws = getClient();
        if (ws != null) {
            return ws.clusterReadAttribute(getNodeId(), endpointId, clusterName, attributeName);
        }
        throw new RuntimeException("Client is null");
    }

    protected @Nullable ControllerHandler controllerHandler() {
        Bridge bridge = getBridge();
        while (bridge != null) {
            BridgeHandler handler = bridge.getHandler();
            if (handler instanceof ControllerHandler controllerHandler) {
                return controllerHandler;
            } else if (handler instanceof MatterBaseThingHandler h) {
                bridge = h.getBridge();
            }

        }
        return null;
    }

    public @Nullable MatterControllerClient getClient() {
        if (cachedClient == null) {
            ControllerHandler c = controllerHandler();
            if (c != null) {
                cachedClient = c.getClient();
            }
        }
        return cachedClient;
    }

    public static boolean areEqual(Object obj1, Object obj2) {
        if (obj1 == null || obj2 == null) {
            return Objects.equals(obj1, obj2); // Return true if both are null, false if one is null
        }

        if (obj1 instanceof BigDecimal && obj2 instanceof BigDecimal) {
            return ((BigDecimal) obj1).compareTo((BigDecimal) obj2) == 0;
        }

        if (obj1 instanceof Number && obj2 instanceof Number) {
            return BigDecimal.valueOf(((Number) obj1).doubleValue())
                    .compareTo(BigDecimal.valueOf(((Number) obj2).doubleValue())) == 0;
        }

        return obj1.equals(obj2); // Fallback to the default equals method
    }

    protected void updateRootProperties(Endpoint root) {
        BaseCluster cluster = root.clusters.get(BasicInformationCluster.CLUSTER_NAME);
        if (cluster != null && cluster instanceof BasicInformationCluster basicCluster) {
            thing.setProperty(Thing.PROPERTY_SERIAL_NUMBER, basicCluster.serialNumber);
            thing.setProperty(Thing.PROPERTY_FIRMWARE_VERSION, basicCluster.softwareVersionString);
            thing.setProperty(Thing.PROPERTY_VENDOR, basicCluster.vendorName);
            thing.setProperty(Thing.PROPERTY_MODEL_ID, basicCluster.productName);
            thing.setProperty(Thing.PROPERTY_HARDWARE_VERSION, basicCluster.hardwareVersionString);
        } else {
            cluster = root.clusters.get(BridgedDeviceBasicInformationCluster.CLUSTER_NAME);
            if (cluster != null && cluster instanceof BridgedDeviceBasicInformationCluster basicCluster) {
                thing.setProperty(Thing.PROPERTY_SERIAL_NUMBER, basicCluster.serialNumber);
                thing.setProperty(Thing.PROPERTY_FIRMWARE_VERSION, basicCluster.softwareVersionString);
                thing.setProperty(Thing.PROPERTY_VENDOR, basicCluster.vendorName);
                thing.setProperty(Thing.PROPERTY_MODEL_ID, basicCluster.productName);
                thing.setProperty(Thing.PROPERTY_HARDWARE_VERSION, basicCluster.hardwareVersionString);
            }
        }
    }

    private String splitAndCapitalize(@Nullable String camelCase) {
        if (camelCase == null) {
            return "";
        }
        return Pattern.compile("(?<=[a-z])(?=[A-Z])").splitAsStream(camelCase)
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}
