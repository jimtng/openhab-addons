# Navimow Binding

This binding integrates [Segway Navimow](https://www.navimow.com/) robotic lawn mowers with openHAB via the Navimow cloud service.

It provides real-time status updates via MQTT and supports sending control commands (start, stop, pause, resume, return to base).

## Supported Things

| Thing Type | Thing ID | Description                                             |
|------------|----------|---------------------------------------------------------|
| Bridge     | `bridge` | Navimow cloud account (manages API and MQTT connection) |
| Mower      | `mower`  | A single Navimow robotic lawn mower                     |

## Discovery

Once the bridge has been authorized, the binding will automatically discover all Navimow mowers associated with the account.
Go to **Settings → Things → +** and scan for new things under the Navimow binding.
Each discovered mower appears in the inbox with its name and device ID pre-filled.
This is the recommended way to add mower things — it avoids having to look up the device ID manually.

## Authorization

This binding uses OAuth2 to authenticate with the Navimow cloud.

1. Add a `navimow:bridge` thing in the openHAB UI or things file.
   The bridge will initially appear as **OFFLINE** with the message _"Not authorized"_.

1. Open a browser and navigate to:

   ```text
   http://<openhab-host>:8080/navimow
   ```

1. Click the **Authorize** button next to your bridge.
   You will be redirected to the Navimow login page.

1. Log in with your Navimow account credentials.

1. After a successful login you are redirected back to openHAB.
   The binding exchanges the authorization code for an access token and the bridge goes **ONLINE** automatically.

### Token Expiry

Navimow access tokens are valid for approximately 1–2 days and cannot be refreshed automatically.
When the token expires the bridge goes **OFFLINE** with the message _"Authentication failed"_.
Revisit `http://<openhab-host>:8080/navimow` and click **Re-authorize** to obtain a new token.

## Thing Configuration

### Bridge (`navimow:bridge`)

| Parameter         | Type    | Required | Default                           | Description                                                     |
|-------------------|---------|----------|-----------------------------------|-----------------------------------------------------------------|
| `pollingInterval` | Integer | No       | `3600`                            | HTTP polling fallback interval in seconds. Set to 0 to disable. |
| `apiBaseUrl`      | String  | No       | `https://navimow-fra.ninebot.com` | Override the Navimow API base URL (see Regional Servers below). |

#### Regional Servers

The default API base URL (`https://navimow-fra.ninebot.com`) points to the European server (Frankfurt, Germany).
The MQTT broker address is fetched automatically from the API and is also region-specific — you do not need to configure it separately.

If you are outside Europe and have connection problems, you may need to use the API URL for your region.
The Navimow app uses region-based routing, so the correct URL depends on where your account was registered.
Common known endpoints follow the pattern `https://navimow-<region>.ninebot.com`.
If you are unsure which server your account uses, check the Navimow community forum or contact Navimow support.

### Mower (`navimow:mower`)

| Parameter  | Type   | Required | Description                        |
|------------|--------|----------|------------------------------------|
| `deviceId` | String | Yes      | The unique device ID of the mower. |

#### Finding Your Device ID

The easiest way to get the `deviceId` is through **discovery**: once the bridge is authorized, run a scan and the mower will appear in the inbox with its device ID already set.

If you prefer to configure things manually via a `.things` file, you can find the device ID by:

1. Authorizing the bridge in the UI.
1. Running discovery (Settings → Things → + → Navimow Binding → Scan).
1. Checking the discovered thing — the device ID is shown in the thing properties and is used as the thing's ID suffix.

The device ID is an opaque string assigned by the Navimow cloud (e.g. `NB1234567890`).
It is **not** the serial number printed on the mower, though they may look similar.

## Channels
### Mower Channels

| Channel ID             | Type                   | Read/Write | Description                                                                                                       |
|------------------------|------------------------|------------|-------------------------------------------------------------------------------------------------------------------|
| `status`               | `String`               | Read       | Operational state: `idle`, `mowing`, `paused`, `docked`, `charging`, `returning`, `error`, `unknown`.             |
| `battery-level`        | `Number:Dimensionless` | Read       | Battery level in percent (0–100).                                                                                 |
| `signal-strength`      | `Number`               | Read       | RSSI signal strength in dBm.                                                                                      |
| `location`             | `Location`             | Read       | Current GPS position of the mower (latitude, longitude).                                                          |
| `error-code`           | `String`               | Read       | Error code, or `none` if no error.                                                                                |
| `command`              | `String`               | Write      | Send a command: `start`, `stop`, `pause`, `resume`, `dock`.                                                       |
| `current-mow-progress` | `Number:Dimensionless` | Read       | Current mowing progress expressed as a percentage (0–100). Raw value from telemetry is scaled by dividing by 100. |
| `mowing-percentage`    | `Number:Dimensionless` | Read       | Mowing completion percentage reported in the telemetry stream (0–100).                                            |
| `current-mow-boundary` | `Number`               | Read       | Index / ID of the current mowing boundary / zone.                                                                 |
| `mowing-week-area`     | `Number:Area`          | Read       | Area mowed this week, published as square metres (`m²`).                                                          |
| `subtotal-area`        | `Number:Area`          | Read       | Current activity subtotal area, published as square metres (`m²`).                                                |
| `mow-start-type`       | `String`               | Read       | Start type indicator from telemetry. Values may be numeric in payloads; semantics (manual/scheduled) not mapped.  |
| `posture-theta`        | `Number:Angle`         | Read       | Heading angle reported by the telemetry stream. The binding publishes an `Angle` QuantityType in radians.         |
| `posture-x`            | `Number`               | Read       | Posture X coordinate inside the current zone (raw units).                                                         |
| `posture-y`            | `Number`               | Read       | Posture Y coordinate inside the current zone (raw units).                                                         |

#### Telemetry details

- The `/location` telemetry payload is a JSON array with mixed entry types. The binding parses the array and extracts entries by their `type` field:
   - `type: 1` — posture entries (populates `posture-theta`, `posture-x`, `posture-y`).
   - `type: 2` — mowing metrics (populates `current-mow-progress`, `mowing-percentage`, `current-mow-boundary`, `mowing-week-area`, `subtotal-area`, `mow-start-type`).
- `current-mow-progress` is calculated by dividing the raw integer value from telemetry by `100.0` to produce a percentage (e.g. raw `8534` → `85.34%`).
- Area values reported by the telemetry are raw square metres; the binding publishes them as `Number:Area` (m²).
- `posture-theta` is published as an `Angle` QuantityType in radians.
- `mow-start-type` is exposed as a `String` value. The numeric meanings (manual, scheduled, etc.) are not yet mapped by the binding.
- `mapWorkPosition` payloads are not decoded by the binding at this time and remain available in raw telemetry.

#### Merge & subscription behaviour

- The bridge merges incoming partial telemetry updates with the last-known state before forwarding updates to mower handlers to avoid transient `UNDEF` regressions.
- To improve broker compatibility, the bridge subscribes to per-device MQTT topics only when a mower thing registers a listener (no wildcard startup subscribe fallback).


## Full Example

### Things File

```java
Bridge navimow:bridge:myaccount "Navimow Account" {
    Thing mower mymower "Navimow Mower" [deviceId="YOUR_DEVICE_ID"]
}
```

After adding the bridge, authorize it at `http://<openhab-host>:8080/navimow`.
The recommended approach is to add only the bridge in the things file and let discovery populate the mower thing.

### Items File

```java
String    Navimow_Status    "Mower Status [%s]"        { channel="navimow:mower:myaccount:mymower:status" }
Number    Navimow_Battery   "Battery [%d %%]"          { channel="navimow:mower:myaccount:mymower:battery-level" }
Number    Navimow_Signal    "Signal [%d dBm]"          { channel="navimow:mower:myaccount:mymower:signal-strength" }
Location  Navimow_Location  "Location"                 { channel="navimow:mower:myaccount:mymower:location" }
String    Navimow_Error     "Error Code [%s]"          { channel="navimow:mower:myaccount:mymower:error-code" }
String    Navimow_Command   "Command"                  { channel="navimow:mower:myaccount:mymower:command" }
```

### Sitemap

```perl
Text   item=Navimow_Status    label="Status"
Text   item=Navimow_Battery   label="Battery"
Switch item=Navimow_Command   label="Mow" mappings=[start="Start", pause="Pause", resume="Resume", dock="Dock", stop="Stop"]
```
