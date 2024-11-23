export interface Request {
    id: string;
    namespace: string;
    function: string;
    args?: any[];
}

export interface Response {
    type: string;
    id: string;
    result?: any;
    error?: string;
}

export interface Event {
    type: string;
    data?: any;
}

export enum EventType {
    AttributeChanged = "attributeChanged",
    EventTriggered = "eventTriggered",
    NodeStateInformation = "nodeStateInformation",
    BridgeEvent = "bridgeEvent"
}

export interface Message {
    type: string;
    message: any;
}

export enum MessageType {
    Result = "result",
    ResultError = "resultError",
    ResultSuccess = "resultSuccess",
}

export enum BridgeEventType {
    AttributeChanged = "attributeChanged",
    EventTriggered = "eventTriggered",
}
export interface BridgeEvent {
    type: string;
    data: any;
}

export interface BridgeAttributeChangedEvent {
    endpointId: string;
    clusterName: string;
    attributeName: string;
    data: any;
}

export interface BridgeEventTrigger {
    eventName: string;
    data: any;
}