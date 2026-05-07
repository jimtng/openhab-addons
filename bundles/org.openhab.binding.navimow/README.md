# Navimow Binding

This binding integrates [Segway Navimow](https://www.navimow.com/) robotic lawn mowers with openHAB via the Navimow cloud service.

It provides real-time status updates and supports sending control commands (start, stop, pause, resume, return to base).

## Supported Things

| Thing Type | Thing ID | Description                         |
|------------|----------|-------------------------------------|
| Bridge     | `bridge` | Navimow cloud account               |
| Mower      | `mower`  | A single Navimow robotic lawn mower |

## Authorization

This binding uses OAuth2 to authenticate with the Navimow cloud.

1. Add a `navimow:bridge` thing in the openHAB UI or things file.
   The bridge will initially appear as **OFFLINE** with the message _"Not authorized"_.

1. Open a browser and navigate to:

   ```text
   http://<openhab-host>:8080/navimow
   ```

   To authorize a single bridge directly, open `http://<openhab-host>:8080/navimow/<thingId>` instead.

1. Click the **Authorize** button next to your bridge.
   You will be redirected to the Navimow login page.

1. Log in with your Navimow account credentials.

1. After a successful login you are redirected back to openHAB.
   The binding exchanges the authorization code for an access token and the bridge goes **ONLINE** automatically.

### Token Expiry

Navimow access tokens are typically short-lived (often around 1–2 days).

- If Navimow provides a `refresh_token` during authorization, the binding will try to refresh the access token automatically before expiry and retry once after an authentication rejection.
- If no `refresh_token` is available, or the refresh token is no longer accepted by Navimow, the bridge goes **OFFLINE** with _"Authentication failed"_ and you must reauthorize via `http://<openhab-host>:8080/navimow/<thingId>`.

## Discovery

Once the bridge has been authorized, the binding will automatically discover all Navimow mowers associated with the account.
Go to **Settings → Things → +** and scan for new things under the Navimow binding.
Each discovered mower appears in the inbox with its name and device ID pre-filled.
This is the recommended way to add mower things.

## Thing Configuration

### Bridge (`navimow:bridge`)

- `pollingInterval` (Integer, optional, default `3600`): HTTP polling fallback interval in seconds. Set to 0 to disable.
- `apiBaseUrl` (String, optional, default `https://navimow-fra.ninebot.com`)

### Mower (`navimow:mower`)

| Parameter  | Type   | Required | Description                        |
|------------|--------|----------|------------------------------------|
| `deviceId` | String | Yes      | The unique device ID of the mower. |

#### Finding Your Device ID

The Device ID is your mower's serial number which can be found in the `Navimow App -> Settings -> Device Information -> Basic info -> Serial number`.

The easiest way to get the `deviceId` is through **discovery**: once the bridge is authorized, run a scan and the mower will appear in the inbox with its device ID already set.

## Channels

### Mower Channels

| Channel ID             | Type                   | Read/Write | Description                                                                                           |
|------------------------|------------------------|------------|-------------------------------------------------------------------------------------------------------|
| `status`               | `String`               | Read       | Operational state: `idle`, `mowing`, `paused`, `docked`, `charging`, `returning`, `error`, `unknown`. |
| `command`              | `String`               | Write      | Send a command: `start`, `stop`, `pause`, `resume`, `dock`.                                           |
| `battery-level`        | `Number:Dimensionless` | Read       | Battery level in percent (0–100).                                                                     |
| `mow-start-type`       | `String`               | Read       | Start type indicator.                                                                                 |
| `current-mow-boundary` | `Number`               | Read       | Index / ID of the current mowing boundary / zone.                                                     |
| `current-mow-progress` | `Number:Dimensionless` | Read       | The mowing progress within the current boundary.                                                      |
| `mowing-percentage`    | `Number:Dimensionless` | Read       | The overall mowing progress for the current task.                                                     |
| `subtotal-area`        | `Number:Area`          | Read       | Current activity subtotal area, published as square metres (`m²`).                                    |
| `mowing-week-area`     | `Number:Area`          | Read       | Total area mowed this week, published as square metres (`m²`).                                        |
| `posture-theta`        | `Number:Angle`         | Read       | The mower's current heading angle. The binding publishes an `Angle` QuantityType in radians.          |
| `posture-x`            | `Number`               | Read       | Posture X relative coordinate.                                                                        |
| `posture-y`            | `Number`               | Read       | Posture Y relative coordinate.                                                                        |
| `error-code`           | `String`               | Read       | Error code, or `none` if no error.                                                                    |

## Full Example

### Things File

```java
Bridge navimow:bridge:myaccount "Navimow Account" {
    Thing mower mymower "My Mower" [deviceId="YOUR_DEVICE_ID"]
}
```

After adding the bridge, authorize it at `http://<openhab-host>:8080/navimow/<thingId>` or use `http://<openhab-host>:8080/navimow` to choose from all configured bridges.
The recommended approach is to add only the bridge in the things file and let discovery populate the mower thing.

### Items File

```java
String                 Navimow_Status             "Mower Status [%s]"                 { channel="navimow:mower:myaccount:mymower:status" }
String                 Navimow_Command            "Command"                           { channel="navimow:mower:myaccount:mymower:command" }
Number:Dimensionless   Navimow_Battery            "Battery [%d %%]"                   { channel="navimow:mower:myaccount:mymower:battery-level" }
String                 Navimow_MowStartType       "Mow Start Type [%s]"               { channel="navimow:mower:myaccount:mymower:mow-start-type" }
Number                 Navimow_CurrentBoundary    "Current Boundary [%d]"             { channel="navimow:mower:myaccount:mymower:current-mow-boundary" }
Number:Dimensionless   Navimow_CurrentMowProgress "Current Mow Progress [%d %%]"      { channel="navimow:mower:myaccount:mymower:current-mow-progress" }
Number:Dimensionless   Navimow_MowingPercentage   "Mowing Percentage [%d %%]"         { channel="navimow:mower:myaccount:mymower:mowing-percentage" }
Number:Area            Navimow_SubtotalArea       "Subtotal Area [%.1f m²]"           { channel="navimow:mower:myaccount:mymower:subtotal-area" }
Number:Area            Navimow_MowingWeekArea     "Mowing This Week [%.1f m²]"        { channel="navimow:mower:myaccount:mymower:mowing-week-area" }
Number:Angle           Navimow_PostureTheta       "Heading [%.2f °]"                  { channel="navimow:mower:myaccount:mymower:posture-theta" }
Number                 Navimow_PostureX           "Posture X [%.2f]"                  { channel="navimow:mower:myaccount:mymower:posture-x" }
Number                 Navimow_PostureY           "Posture Y [%.2f]"                  { channel="navimow:mower:myaccount:mymower:posture-y" }
String                 Navimow_ErrorCode          "Error Code [%s]"                   { channel="navimow:mower:myaccount:mymower:error-code" }
```

### Sitemap

```perl
Text   item=Navimow_Status    label="Status"
Text   item=Navimow_Battery   label="Battery"
Switch item=Navimow_Command   label="Mow" mappings=[start="Start", pause="Pause", resume="Resume", dock="Dock", stop="Stop"]
```
