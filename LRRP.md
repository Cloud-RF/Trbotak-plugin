# LRRP — Location Request and Response Protocol

This document describes the parts of Motorola MOTOTRBO LRRP that TrboTAK uses, as
implemented in
[`LrrpCodec.java`](app/src/main/java/com/atakmap/android/trbotak/lrrp/LrrpCodec.java)
and
[`LrrpPoller.java`](app/src/main/java/com/atakmap/android/trbotak/lrrp/LrrpPoller.java).
It is not a full specification of LRRP. It covers the messages and tokens the plugin
sends, and the ones it can decode.

Back to the [README](README.md).

## Contents

- [Overview](#overview)
- [Transport](#transport)
- [Addressing a radio](#addressing-a-radio)
- [Message structure](#message-structure)
- [Data encodings](#data-encodings)
- [Message types](#message-types)
- [Tokens](#tokens)
- [Message flow](#message-flow)
- [Example packets](#example-packets)
- [How TrboTAK parses responses](#how-trbotak-parses-responses)
- [Quick reference](#quick-reference)

## Overview

LRRP is the binary protocol MOTOTRBO radios use to report GNSS position over their
IP data service. A client (here, the ATAK device) sends a **request** to the radio,
and the radio replies with **responses** that carry its position, or a status code
explaining why it can't give one.

Requests come in two kinds:

- **Immediate.** Ask for one position, get one reply.
- **Triggered.** Subscribe once, and the radio pushes a position at a set interval
  until it gets a stop request.

TrboTAK uses **triggered** mode. It subscribes each radio at the Refresh interval in
Settings, and sends a stop request when a radio is removed or the plugin unloads.

## Transport

| Property | Value |
|---|---|
| Protocol | UDP over IPv4 |
| Radio port | 4001 |
| Local port | 4001. The plugin binds 4001 locally and the radio replies to the port the request came from. |
| Byte order | Big-endian (network order) for fixed-width fields |
| Datagram | One LRRP message per UDP datagram |

## Addressing a radio

On a tethered link (Bluetooth PAN or USB), every MOTOTRBO radio has an address in
`12.0.0.0/8`, worked out from its DMR radio ID:

```
IP = 12.0.0.0 + radio_id        (treated as a 32-bit number)
```

Worked example for radio ID **30491**:

| Step | Value |
|---|---|
| Radio ID (decimal) | 30491 |
| Radio ID (hex) | `0x0000771B` |
| + `0x0C000000` (12.0.0.0) | `0x0C00771B` |
| Dotted quad | **12.0.119.27** |

You can also enrol a radio by any literal IPv4 address (the PAN gateway, or the
radio's Wi-Fi IP). Then the request goes straight to that address.

### Request ID (transaction ID)

Every request carries a 4-byte **request ID**. The radio echoes it in every response
to that request. TrboTAK matches replies to radios by this ID, **not** by source IP,
because a PAN gateway may reply from its own address instead of the radio's.

TrboTAK sets the top two bits and puts the low 24 bits of the radio ID (or of the IP
address) in the rest:

```
request_id = 0xC0000000 | (radio_id & 0x00FFFFFF)
```

| Input | Request ID |
|---|---|
| Radio ID 30491 (`0x771B`) | `C0 00 77 1B` |
| IP 192.168.10.1 → low 24 bits `A8 0A 01` | `C0 A8 0A 01` |

If two radios would get the same ID, TrboTAK refuses the second one and logs a
`tx_id collision`.

## Message structure

Every LRRP message has the same shape: a two-byte header, then a body made of
**tokens**.

```
+--------+--------+---------------------------------------------+
|  Type  | Length |  Body: sequence of tokens                   |
| 1 byte | 1 byte |  Length bytes                               |
+--------+--------+---------------------------------------------+
```

| Field | Size | Description |
|---|---|---|
| Type | 1 byte | Message type, e.g. `0x09` Triggered Location Start Request. See [Message types](#message-types). |
| Length | 1 byte | Number of body bytes after this byte. Every message TrboTAK builds is under 128 bytes, so this always fits in one byte. |
| Body | *Length* bytes | Tokens. The first is always the Request ID token. |

Each token is a **one-byte token code** followed by its fields. Token widths are set
by the code, not by a length prefix. The one exception is the Request ID token, which
carries its own length byte. So a parser that meets a token it doesn't know can't skip
it.

> **Tokens depend on direction.** The same byte can mean different things in a request
> and in a response. For example, `0x34` is *periodic trigger* in a request and
> *timestamp* in a response.

## Data encodings

LRRP uses four field encodings.

### 1. Fixed big-endian integer

Used for the Request ID and for latitude and longitude. Four bytes, most significant
byte first.

### 2. LRRP integer (variable-length, base-128)

Used for intervals, status codes and the integer part of floats.

- The value is split into 7-bit groups, **most significant group first**.
- Every byte **except the last** has its top bit (`0x80`) set to mean "more to come".
- The last byte has its top bit clear.

| Value | Groups (7-bit) | Encoded |
|---|---|---|
| 0 | `0` | `00` |
| 10 | `10` | `0A` |
| 30 | `30` | `1E` |
| 123 | `123` | `7B` |
| 200 | `1`, `72` | `81 48` |
| 300 | `2`, `44` | `82 2C` |
| 512 (`0x200`) | `4`, `0` | `84 00` |

Decoding `81 48`:

```
81 → top bit set, keep 0x01, continue       value = 1
48 → top bit clear, last byte                value = 1 × 128 + 0x48 (72) = 200
```

LRRP integers can't be negative.

### 3. LRRP float

Two LRRP integers back to back: the **integer part**, then the **fraction**:

```
value = integer + fraction / 128^(bytes in fraction)
```

| Bytes | Integer | Fraction | Value |
|---|---|---|---|
| `7B 40` | 123 | 64 / 128¹ | **123.5** |
| `78 40` | 120 | 64 / 128¹ | **120.5** |
| `78 81 00` | 120 | 128 / 128² | **120.0078125** |
| `05 00` | 5 | 0 / 128 | **5.0** |

If the frame ends after the integer part, TrboTAK uses the integer on its own.
Floats have no sign bit, so altitudes below the ellipsoid can't be represented.

### 4. Latitude and longitude

Each is a **signed 32-bit two's-complement big-endian integer**, scaled over 2³¹:

```
latitude  = raw × 90  / 2^31        (range ±90°)
longitude = raw × 180 / 2^31        (range ±180°)
```

Resolution is about 4.2 × 10⁻⁸ degrees for latitude, under 1 cm.

| Coordinate | Raw (decimal) | Raw (hex) |
|---|---|---|
| Latitude 51.5074 | 1229014436 | `49 41 45 A4` |
| Longitude −0.1278 | −1524713 | `FF E8 BC 17` |
| Latitude −33.8688 | — | `CF D4 BF 0A` |
| Longitude 151.2093 | — | `6B 86 D0 22` |

The value **must** be read as signed. Read as unsigned, positions south of the equator
or west of Greenwich come out wrong.

Worked example, latitude `49 41 45 A4`:

```
0x494145A4 = 1229014436
1229014436 × 90 / 2147483648 = 51.507400°
```

Worked example, longitude `FF E8 BC 17`:

```
0xFFE8BC17 as signed 32-bit = −1524713
−1524713 × 180 / 2147483648 = −0.127800°
```

## Message types

| Code | Name | Direction | TrboTAK use |
|---|---|---|---|
| `0x05` | Immediate Location Request | Client → Radio | Built (`MODE_IMMEDIATE`), not used by default |
| `0x07` | Immediate Location Response | Radio → Client | Parsed. Also what **Simulate** sends. |
| `0x09` | Triggered Location Start Request | Client → Radio | **Sent** for each radio |
| `0x0B` | Triggered Location Start Response | Radio → Client | Parsed (acknowledgement / status) |
| `0x0D` | Triggered Location Data | Radio → Client | **Parsed**: the periodic position reports |
| `0x0F` | Triggered Location Stop Request | Client → Radio | **Sent** on remove or unload |
| `0x11` | Triggered Location Stop Response | Radio → Client | Parsed (acknowledgement) |

Request types are odd and each response is request + 2. TrboTAK's parser ignores the
type byte and only walks the tokens, so all the response types go through the same
decoder.

## Tokens

### Common

| Code | Name | Fields | Size |
|---|---|---|---|
| `0x22` | Request ID | length (1 byte) + ID (*length* bytes, big-endian) | 2 + *length* |

### Request tokens (client → radio)

| Code | Name | Fields | Meaning |
|---|---|---|---|
| `0x54` | Request altitude | — | Ask the radio to include altitude |
| `0x34` | Periodic trigger | — | Report on a timer (triggered start only) |
| `0x31` | Trigger interval | LRRP integer (seconds) | Reporting interval |
| `0x52` | Request time | — | Ask for a timestamp. *Defined; not sent by TrboTAK.* |
| `0x62` | Request horizontal speed | — | *Defined; not sent by TrboTAK.* |

### Response tokens (radio → client)

| Code | Name | Fields | TrboTAK handling |
|---|---|---|---|
| `0x66` | Location (2D) | lat (4) + lon (4) | Decoded |
| `0x69` | Location + altitude | lat (4) + lon (4) + altitude (float) | Decoded |
| `0x51` | Location + radius | lat (4) + lon (4) + radius (float) | Lat/lon decoded, radius skipped |
| `0x55` | Location + radius + altitude | lat (4) + lon (4) + radius (float) + altitude (float) + altitude accuracy (float) | Lat/lon/altitude decoded, radius and accuracy skipped |
| `0x34` | Timestamp | 5 bytes, packed date/time | Skipped |
| `0x56` | Direction | 1 byte, heading | Skipped |
| `0x6C` | Horizontal speed | float | Skipped |
| `0x70` | Vertical speed | float | Skipped |
| `0x37` | Result code | LRRP integer | Decoded, see below |
| `0x38` | Result OK | — | Decoded as code 0 |

### Result codes (token `0x37`)

| Code | Name shown in TrboTAK | Meaning |
|---|---|---|
| `0x000` | `success` | Request accepted |
| `0x00A` | `bad-command` | Radio rejected the request (unsupported or malformed) |
| `0x010` | `no-gps` | Radio reachable, no satellite fix yet |
| `0x200` | `not-enough-gps` | Tracking, but too few satellites for a fix |
| *other* | `unknown` | Not in TrboTAK's table |

## Message flow

```
 ATAK device (TrboTAK)                                MOTOTRBO radio
 UDP :4001                                            12.0.119.27 :4001
      |                                                     |
      |  0x09 Triggered Location Start (interval 10 s)      |
      |---------------------------------------------------->|
      |                                                     |
      |  0x0B Triggered Location Start Response (OK)        |
      |<----------------------------------------------------|
      |                                                     |
      |  0x0D Triggered Location Data (position)            |
      |<----------------------------------------------------|   every 10 s
      |  0x0D Triggered Location Data (position)            |
      |<----------------------------------------------------|
      |                       ...                           |
      |                                                     |
      |  0x0F Triggered Location Stop                       |
      |---------------------------------------------------->|   on remove / unload
      |                                                     |
      |  0x11 Triggered Location Stop Response              |
      |<----------------------------------------------------|
```

How TrboTAK schedules requests:

- **Triggered (default):** it sends the start request once. If nothing comes back from
  the radio for **3 × the Refresh interval**, it assumes the subscription has been lost
  (the radio rebooted, or the link dropped) and sends the start request again.
- **Changing Refresh** in Settings rebuilds the start request with the new interval
  and sends it to every radio straight away.
- **Immediate** (not the default): it sends a new request one Refresh interval after
  the last reply, or 3 × Refresh after the last attempt if there was no reply.

## Example packets

All examples are for **radio ID 30491** (address `12.0.119.27`, request ID
`C0 00 77 1B`) and are exactly what TrboTAK sends or decodes. The UDP payload is
shown; IP and UDP headers are left out.

### Example 1: Triggered Location Start Request (10 s, with altitude)

Sent by TrboTAK from port 4001 to `12.0.119.27:4001` when a radio is added.

```
09 0A 22 04 C0 00 77 1B 54 34 31 0A
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `09` | Message type | Triggered Location Start Request |
| 1 | `0A` | Body length | 10 bytes follow |
| 2 | `22` | Token | Request ID |
| 3 | `04` | ID length | 4 bytes |
| 4–7 | `C0 00 77 1B` | Request ID | `0xC000771B` (radio 30491) |
| 8 | `54` | Token | Request altitude |
| 9 | `34` | Token | Periodic trigger |
| 10 | `31` | Token | Trigger interval |
| 11 | `0A` | LRRP integer | 10 seconds |

With Refresh set to 20 s, the last byte becomes `14`. An interval of 200 s would need
two bytes (`81 48`) and the length would become `0B`.

### Example 2: Triggered Location Start Response (OK)

The radio accepts the subscription.

```
0B 07 22 04 C0 00 77 1B 38
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `0B` | Message type | Triggered Location Start Response |
| 1 | `07` | Body length | 7 bytes |
| 2 | `22` | Token | Request ID |
| 3 | `04` | ID length | 4 bytes |
| 4–7 | `C0 00 77 1B` | Request ID | Matches radio 30491 |
| 8 | `38` | Token | Result OK |

TrboTAK has no position yet, so the row shows `ack`. A radio may instead send result
code `37 00` (success), which TrboTAK shows as `success`.

### Example 3: Triggered Location Data (2D position)

A periodic report: London, 51.5074° N, 0.1278° W, no altitude.

```
0D 0F 22 04 C0 00 77 1B 66 49 41 45 A4 FF E8 BC 17
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `0D` | Message type | Triggered Location Data |
| 1 | `0F` | Body length | 15 bytes |
| 2 | `22` | Token | Request ID |
| 3 | `04` | ID length | 4 bytes |
| 4–7 | `C0 00 77 1B` | Request ID | Radio 30491 |
| 8 | `66` | Token | Location (2D) |
| 9–12 | `49 41 45 A4` | Latitude | 1229014436 × 90 / 2³¹ = **51.507400°** |
| 13–16 | `FF E8 BC 17` | Longitude | −1524713 × 180 / 2³¹ = **−0.127800°** |

The CoT marker gets altitude *unknown*, or the last altitude the radio reported if it
has sent one before.

### Example 4: Location response with altitude

The same position with an altitude of 123.5 m. This is exactly what the **Simulate**
button sends to `127.0.0.1:4001` (shown here for the London position; Simulate uses
the map centre).

```
07 11 22 04 C0 00 77 1B 69 49 41 45 A4 FF E8 BC 17 7B 40
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `07` | Message type | Immediate Location Response |
| 1 | `11` | Body length | 17 bytes |
| 2 | `22` | Token | Request ID |
| 3 | `04` | ID length | 4 bytes |
| 4–7 | `C0 00 77 1B` | Request ID | Radio 30491 |
| 8 | `69` | Token | Location + altitude |
| 9–12 | `49 41 45 A4` | Latitude | **51.507400°** |
| 13–16 | `FF E8 BC 17` | Longitude | **−0.127800°** |
| 17 | `7B` | Altitude, integer part | 123 |
| 18 | `40` | Altitude, fraction | 64 / 128 = 0.5 → **123.5 m** |

### Example 5: Location with radius and altitude (token `0x55`)

The 3D form some radios send. Here: radius 5.0, altitude 120.5, altitude accuracy 5.0.

```
0D 15 22 04 C0 00 77 1B 55 49 41 45 A4 FF E8 BC 17 05 00 78 40 05 00
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `0D` | Message type | Triggered Location Data |
| 1 | `15` | Body length | 21 bytes |
| 2–7 | `22 04 C0 00 77 1B` | Request ID token | Radio 30491 |
| 8 | `55` | Token | Location + radius + altitude |
| 9–12 | `49 41 45 A4` | Latitude | 51.507400° |
| 13–16 | `FF E8 BC 17` | Longitude | −0.127800° |
| 17–18 | `05 00` | Radius (float) | 5.0 (skipped) |
| 19–20 | `78 40` | Altitude (float) | **120.5 m** |
| 21–22 | `05 00` | Altitude accuracy (float) | 5.0 (skipped) |

### Example 6: No GPS fix

The radio is reachable but has no satellite lock.

```
07 08 22 04 C0 00 77 1B 37 10
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `07` | Message type | Immediate Location Response |
| 1 | `08` | Body length | 8 bytes |
| 2–7 | `22 04 C0 00 77 1B` | Request ID token | Radio 30491 |
| 8 | `37` | Token | Result code |
| 9 | `10` | LRRP integer | `0x010` → **`no-gps`** |

### Example 7: Not enough satellites (multi-byte result code)

```
07 09 22 04 C0 00 77 1B 37 84 00
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `07` | Message type | Immediate Location Response |
| 1 | `09` | Body length | 9 bytes |
| 2–7 | `22 04 C0 00 77 1B` | Request ID token | Radio 30491 |
| 8 | `37` | Token | Result code |
| 9–10 | `84 00` | LRRP integer | 4 × 128 + 0 = 512 = `0x200` → **`not-enough-gps`** |

### Example 8: Triggered Location Stop Request

Sent when a radio is removed from the list or the plugin unloads. It carries only the
request ID, with no extra tokens, so a radio that rejects extras can't also reject the
cancellation.

```
0F 06 22 04 C0 00 77 1B
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `0F` | Message type | Triggered Location Stop Request |
| 1 | `06` | Body length | 6 bytes |
| 2 | `22` | Token | Request ID |
| 3 | `04` | ID length | 4 bytes |
| 4–7 | `C0 00 77 1B` | Request ID | Same ID as the start request |

### Example 9: Triggered Location Stop Response

```
11 07 22 04 C0 00 77 1B 38
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `11` | Message type | Triggered Location Stop Response |
| 1 | `07` | Body length | 7 bytes |
| 2–7 | `22 04 C0 00 77 1B` | Request ID token | Radio 30491 |
| 8 | `38` | Token | Result OK |

### Example 10: Immediate Location Request (not used by default)

The single-shot form, built by `LrrpCodec.buildImmediateRequest`.

```
05 07 22 04 C0 00 77 1B 54
```

| Offset | Bytes | Field | Value |
|---|---|---|---|
| 0 | `05` | Message type | Immediate Location Request |
| 1 | `07` | Body length | 7 bytes |
| 2–7 | `22 04 C0 00 77 1B` | Request ID token | Radio 30491 |
| 8 | `54` | Token | Request altitude |

The radio replies with a `0x07` response like Examples 4, 6 or 7.

## How TrboTAK parses responses

`LrrpCodec.parseResponse` works through a received datagram like this:

1. **Too short?** Datagrams under 4 bytes are dropped.
2. **Skip the header.** The type and length bytes are skipped. The parser walks to the
   end of the datagram and ignores the length byte, so a wrong length does no harm.
3. **Walk the tokens** in order:
   - `0x22` reads the request ID. A zero length, or a length that runs past the end,
     drops the frame.
   - Location tokens read signed lat/lon, then the trailing floats for that variant.
   - Tokens it knows but doesn't need (time, direction, speed) are skipped by their
     known width.
   - `0x37` / `0x38` record a status.
   - **An unknown token stops the walk.** Its width is unknown, so carrying on would
     misread every byte after it. Whatever was decoded up to that point is kept.
4. **No request ID?** The frame is dropped, because it can't be matched to a radio.
5. **Match the radio.** The request ID is looked up in the poller's table. An unknown
   ID is logged and ignored.
6. **Dispatch:**
   - The frame has lat/lon → **fix**: the map marker is updated, CoT is sent, and the
     row turns green.
   - Otherwise → **status**: the row shows the status name, or `ack` if the frame had
     no status token.

The parser never throws: it is fuzz-tested with random and truncated frames in
[`LrrpCodecTest.java`](app/src/test/java/com/atakmap/android/trbotak/lrrp/LrrpCodecTest.java).
This matters because it runs on ATAK's main thread.

Frames it can't parse are logged under the `LrrpPoller` tag as hex, for example:

```
LrrpPoller: rx 12.0.119.27 unrecognised frame hex=...
LrrpPoller: rx 12.0.119.27 radio=30491 LOCATION tok=0x66 lat=51.507400 lon=-0.127800 alt=none
```

### Limitations

- A **truncated location token** leaves no lat/lon, so the frame is shown as `ack`
  instead of an error.
- A **result code token cut off before its value** decodes as `0` and is shown as
  `success`.
- **Accuracy figures** (radius, altitude accuracy) are skipped, and the CoT `ce`/`le`
  are left unknown.
- **Timestamps** are skipped. The CoT event time is the time the frame was received.

## Quick reference

```
Header        [type:1][length:1]
Request ID    22 04 <id:4 big-endian>              id = 0xC0000000 | (radio_id & 0xFFFFFF)
Radio IP      12.0.0.0 + radio_id

Requests      05 Immediate   09 Triggered start   0F Triggered stop
Responses     07 Immediate   0B Start resp        0D Triggered data   11 Stop resp

Req tokens    54 altitude    34 periodic   31 <int> interval (s)
Loc tokens    66 lat lon
              69 lat lon alt
              51 lat lon radius
              55 lat lon radius alt alt_acc
Status        37 <int> result code     38 OK
Other         34 time (5)   56 direction (1)   6C speed (float)   70 vspeed (float)

lat = int32 × 90 / 2^31        lon = int32 × 180 / 2^31          (signed, big-endian)
int   = base-128, MSB group first, 0x80 = continue
float = int + int / 128^(bytes in fraction)
```
