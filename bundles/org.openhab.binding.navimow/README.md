# Navimow Binding

This binding integrates [Segway Navimow](https://www.navimow.com/) robotic lawn mowers with openHAB via the Navimow cloud service.

It provides real-time status updates via MQTT and supports sending control commands (start, stop, pause, resume, return to base).

## Supported Things

| Thing Type | Thing ID | Description |
|------------|----------|-------------|
| Bridge | `bridge` | Navimow cloud account (manages API and MQTT connection) |
| Mower | `mower` | A single Navimow robotic lawn mower |

## Discovery

Once a bridge has been configured with a valid access token, the binding will automatically discover all Navimow mowers associated with the account.

## Thing Configuration

### Bridge (`navimow:bridge`)

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `accessToken` | String | Yes | | OAuth2 access token from the Navimow cloud. |
| `pollingInterval` | Integer | No | `3600` | HTTP polling fallback interval in seconds. Set to 0 to disable. |
| `apiBaseUrl` | String | No | `https://navimow-fra.ninebot.com` | Override the Navimow API base URL. |

#### Obtaining an Access Token

The Navimow access token can be obtained from the Home Assistant Navimow integration at <https://github.com/segwaynavimow/NavimowHA>.
Look for the token in `~/.homeassistant/.storage/navimow.storage` or by enabling debug logging on the HA integration.

### Mower (`navimow:mower`)

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `deviceId` | String | Yes | The unique device ID of the mower. |

## Channels

### Mower Channels

| Channel ID | Type | Read/Write | Description |
|------------|------|------------|-------------|
| `status` | `String` | Read | Operational state: `idle`, `mowing`, `paused`, `docked`, `charging`, `returning`, `error`, `unknown` |
| `battery-level` | `Number:Dimensionless` | Read | Battery level in percent (0-100). |
| `signal-strength` | `Number` | Read | RSSI signal strength in dBm. |
| `latitude` | `Number` | Read | Current GPS latitude. |
| `longitude` | `Number` | Read | Current GPS longitude. |
| `error-code` | `String` | Read | Error code, or `none` if no error. |
| `command` | `String` | Write | Send a command: `start`, `stop`, `pause`, `resume`, `dock` |

## Full Example

### Things File

```java
Bridge navimow:bridge:myaccount "Navimow Account" [accessToken="YOUR_TOKEN_HERE"] {
    Thing mower mymower "Navimow Mower" [deviceId="YOUR_DEVICE_ID"]
}
```

### Items File

```java
String  Navimow_Status    "Mower Status [%s]"        { channel="navimow:mower:myaccount:mymower:status" }
Number  Navimow_Battery   "Battery [%d %%]"          { channel="navimow:mower:myaccount:mymower:battery-level" }
Number  Navimow_Signal    "Signal [%d dBm]"          { channel="navimow:mower:myaccount:mymower:signal-strength" }
Number  Navimow_Latitude  "Latitude [%.6f]"          { channel="navimow:mower:myaccount:mymower:latitude" }
Number  Navimow_Longitude "Longitude [%.6f]"         { channel="navimow:mower:myaccount:mymower:longitude" }
String  Navimow_Error     "Error Code [%s]"          { channel="navimow:mower:myaccount:mymower:error-code" }
String  Navimow_Command   "Command"                  { channel="navimow:mower:myaccount:mymower:command" }
```

### Sitemap

```perl
Text   item=Navimow_Status    label="Status"
Text   item=Navimow_Battery   label="Battery"
Switch item=Navimow_Command   label="Mow" mappings=[start="Start", pause="Pause", resume="Resume", dock="Dock", stop="Stop"]
```
