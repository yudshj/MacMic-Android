# MacMic phone-server protocol

Wire protocol implemented by this app (server side) and by
[MacMic](https://github.com/yudshj/MacMic)'s client (`macmic_protocol.py` / `swift-src/MacMicClient.swift`).
It was documented from observed network behaviour (and from MacMic's own client implementation) for
interoperability; the server here is an independent implementation. It covers the subset of that protocol that
MacMic needs.

## Control channel (TCP 8125)

The server listens on `0.0.0.0:8125` and accepts one client at a time.

Request frame (client → server):

| field | type | notes |
| --- | --- | --- |
| `type` | `u8` | command, see below |
| `payload_len` | `u32` big-endian | length of `payload` |
| `payload` | bytes | command specific |

Response frame (server → client):

| field | type | notes |
| --- | --- | --- |
| `type` | `u8` | echo of the command |
| `len` | `u32` big-endian | `1 + data len` |
| `status` | `u8` | `0` = OK |
| `data` | bytes | optional, `len - 1` bytes |

The MacMic client only checks `type` and `status == 0`, so this app replies with the minimal
`type + len=1 + status=0` (6 bytes).

### Commands

| type | name | payload | reply |
| --- | --- | --- | --- |
| `0x65` | check version | `04 06 03 00 00 00` (`proto=4`, client 6.3, reserved) | `0x65`, status 0 |
| `0x66` | set codec params | `codec=0x02` (Opus), `srIndex=0x02` (48 kHz), `clientMediaPort` `u32 BE` | `0x66`, status 0 |
| `0x67` | start capture | empty | `0x67`, status 0, then media starts |
| `0x68` | stop capture | empty | `0x68`, status 0, session ends |
| `0x69` | heartbeat | empty | **no reply**; every 2 s, resets the idle timer |

`clientMediaPort` (from `0x66`) is where the server sends **UDP** media in Wi-Fi mode.

## Media packets

11-byte header, then an Opus payload. The same framing is used over UDP (Wi-Fi) and over the second TCP
connection (USB/ADB):

| field | type | notes |
| --- | --- | --- |
| `version` | `u16` big-endian | `0x0400` (protocol 4) |
| `data_len` | `u16` big-endian | `opus_len + 7` (sequence + timestamp + flag) |
| `sequence` | `u16` big-endian | increments by 1 per packet |
| `timestamp` | `u32` big-endian | sample counter, `+960` per 20 ms frame at 48 kHz |
| `flag` | `u8` | `0` |
| payload | bytes | Opus frame (48 kHz, mono, 20 ms → 960 samples) |

Audio format: **Opus, 48 kHz, mono, 20 ms frames, VBR ~64 kbps** (libopus 1.5.2, `OPUS_APPLICATION_VOIP`,
complexity 8).

### Transports

| mode | control | media |
| --- | --- | --- |
| Wi-Fi | TCP → `phone:8125` | UDP → `clientMediaPort` declared in `0x66` |
| USB / ADB | TCP → `127.0.0.1:8125` (via `adb forward`) | a second TCP connection to the same port |

The client sends a UDP datagram `00 04 00 00` to port 8125 as a hole-punch / keepalive; the server ignores it
(it only uses it to keep the NAT/firewall path warm). The same datagram is sent every 2 s while streaming.

## Session lifecycle & timeouts

```
accept ──5 s──▶ must receive a complete 0x65, otherwise RST and back to LISTEN
   │
   └─ 0x65 ─ 0x66 ─ 0x67 ─▶ streaming (media out)
                              │
                              ├─ 0x69 every 2 s keeps the session alive
                              ├─ 8 s without any control frame → RST, back to LISTEN
                              └─ 0x68 / EOF / write error → RST, back to LISTEN
```

- Single client at a time. A second client from a **different** IP while a session is live is RST immediately.
- A client from the **same IP** (or while the session has not completed its handshake yet) becomes a
  `pending takeover`: it only replaces the old session once *it* completes `0x65`, so a crashed Mac reconnects
  without waiting for the 8 s timeout.
- Every error path tears down with `SO_LINGER{1,0}` (RST) so the phone never keeps a half-open session.
- In USB mode, after `0x67` the server accepts a second TCP connection from the same IP as the media channel
  (300 ms probe window, then it starts writing frames).

## Compatibility notes

- Implemented for MacMic's client; the official WO Mic desktop client was not tested.
- `flag` is always `0` and `timestamp` counts samples, matching MacMic's client which does not consume either
  value except for bookkeeping.
