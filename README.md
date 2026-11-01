[![CI](https://github.com/yggdrasil-iiot/muninn/actions/workflows/ci.yml/badge.svg)](https://github.com/yggdrasil-iiot/muninn/actions/workflows/ci.yml)

Muninn — the northbound **feeder** of the Yggdrasil IIoT governance portfolio: it consumes a *governed* equipment definition, proves the bytes it will publish are the ones governance admitted, and streams live OPC-UA instance data to a Sparkplug B / MQTT UNS. It is a separate app, coupled to the rest only by a published data/wire contract — **zero shared code** (its own value types, its own SHA-256/manifest).

## Role in the governance spine

```
Mimir (model) ──▶ Bifrost (govern) ──▶ Muninn (feed the UNS)
```

Mimir (Mímir) derives a canonical UDT definition, Bifrost's gates admit + provenance-publish it, and **Muninn**:

1. **Provenance-verifies** the governed definition — recomputes SHA-256 over the raw registry bytes, compares to the sibling `manifest.json`, and **refuses to birth** on mismatch (no verify-one-birth-another).
2. Emits a Sparkplug B **NBIRTH** whose `governed_definition` `Bytes` metric carries the **byte-exact** verified definition (a lossy Tahu UDT Template would not be byte-equal).
3. **Egress-validates** each live `Line1/Mixer1.*` sample it reads over OPC-UA against the governed definition (member name + type) and **drops** non-conformant metrics before publishing **NDATA**.

## Build

```bash
mvn install        # Java 17; shades an executable target/muninn.jar
```

## Run

```bash
# Feed: provenance-verify -> NBIRTH -> sample OPC-UA instance -> egress-validate -> NDATA
java -jar target/muninn.jar feed <registryDir> <ref> <version> <opcEndpoint> <nsUri> \
     <instanceNodeIdent> <mqttUrl> <group> <edge> [--inject-bogus <member>] \
     [--hierarchy <mappingsDir> <stream> <equipmentInstanceId>] \
     [--session <periodMs> [--max-cycles N] [--primary-host <hostId>]]

# Observe (gate harness): capture NBIRTH bytes + NDATA/DDATA member names (+ optional name=value)
java -jar target/muninn.jar observe <mqttUrl> <group> <birthOutFile> <ndataOutFile> \
     [--expect-ndata N] [--timeout-ms M] [--ndata-values <file>]

# Host (gate harness): primary host state and rebirth control
java -jar target/muninn.jar host <mqttUrl> state <hostId> <online> <timestamp>
java -jar target/muninn.jar host <mqttUrl> rebirth <group> <edge>
```

`--max-cycles` and `--primary-host` are accepted only together with `--session` (otherwise exit 2).

`observe` prints one line per received message: `[OBSERVE] <type> seq=<n> bdSeq=<n>`, omitting each field when absent from the payload.

`host state` publishes the host's STATE JSON `{"online":..,"timestamp":..}` on `spBv1.0/STATE/<hostId>` (QoS 1, retained). `host rebirth` publishes an NCMD with `Node Control/Rebirth = true`.

Exit codes: `0` ok · `1` governance-refuse (provenance mismatch, a hierarchy that does not verify, or a session whose definition or hierarchy no longer verifies at a rebirth) · `2` usage/error.

## ISA-95 location for the UNS

Optional. Without `--hierarchy` the feed is byte-for-byte what it was: NBIRTH then NDATA on the edge node.

With `--hierarchy <mappingsDir> <stream> <equipmentInstanceId>`, Muninn reads the ISA-95 equipment hierarchy (Tyr/Bifrost contract 2.23.0, `equipment-hierarchy`) from a mapping release Bifrost published, and publishes the equipment as a Sparkplug B device: NBIRTH unchanged (same `governed_definition` bytes), then DBIRTH (seq 1) and DDATA (seq 2) on `spBv1.0/<group>/{DBIRTH,DDATA}/<edge>/<device>`, carrying the same egress-validated members.

The device ID is the `equipmentInstanceId`s from the hierarchy root down to the instance, joined by `:` — e.g. `synthetic-site:record-line-a`. This is the form UMH's Sparkplug B input reads as a location path (its "Modified Parris Method": the device ID's colons become the dots of `location_path`, so the example becomes `synthetic-site.record-line-a`). Muninn's metric names (`Rpm`, `Temp`, ...) contain no separator, so UMH reads them as tag names with no virtual path.

Muninn follows the published convention only; it has no UMH code or dependency (UMH's README says Apache-2.0 while its 2025-12 Community/Enterprise license documents differ, so the format is the only thing taken).

Verification chain, each link raw-byte SHA-256, the way any consumer outside Bifrost verifies a release: `heads/<stream>.json` names `releases/<id>/<version>/release.json` and its digest; `release.json` lists each payload file with its digest; the payload's `proposal.json` names the single `equipment-hierarchy` file. Muninn's own reader, no Bifrost code.

Muninn **refuses** (exit 1) before any MQTT or OPC-UA connect when: a digest does not match; a file is missing or not listed; the release has zero or several hierarchies; the document is not `equipment-hierarchy` 2.23.0; the instance is not in the hierarchy; a parent is missing; a cycle; a path deeper than 64; a stream or head segment that would leave the mappings directory; an ID containing `/` (cannot be a Sparkplug topic token) or `.` (UMH would read it as an extra level), or `:`, `+`, `#`. The contract's ID pattern allows `/` and `.`, so such IDs are refused rather than rewritten.

## Persistent Sparkplug session

`--session <periodMs>` turns the feed into a persistent Sparkplug B 3.0 edge-node session. Without `--session`, the feed remains a one-shot run (used by Bifrost's gates).

The governed inputs are verified once before any MQTT or OPC-UA connect, exactly as in the one-shot feeder. A refusal exits 1 before connecting.

Every MQTT CONNECT is a clean session with client ID `muninn-edge-<edge>`, carrying an NDEATH Will (QoS 1, not retained) with an Int64 `bdSeq` metric. The NBIRTH carries the same `bdSeq`. The next CONNECT increments `bdSeq`, wrapping from 255 to 0.

NBIRTH has `seq` 0 and carries `bdSeq`, the same byte-exact `governed_definition` metric as the one-shot, and `Node Control/Rebirth = false`. With `--hierarchy`, DBIRTH follows. Every subsequent message increments `seq` by 1, wrapping from 255 to 0. NDEATH carries no `seq`.

Every `periodMs`, the session samples the OPC-UA instance, egress-validates the samples as the one-shot does, and publishes NDATA (or DDATA with `--hierarchy`).

An NCMD with `Node Control/Rebirth = true` makes the session read and verify the registry (and hierarchy, when given) again, then publish a complete NBIRTH (`seq` 0, same `bdSeq`) and DBIRTH. If the inputs no longer verify, the session publishes NDEATH, disconnects, and exits 1. A rebirth request before the first birth is ignored. Every other NCMD metric and every DCMD is ignored: Muninn reads the plant and never writes to it.

With `--primary-host <hostId>`, the session subscribes to `spBv1.0/STATE/<hostId>` and publishes no birth until that host's STATE says `online: true`. A STATE `online: false` whose timestamp is at least that of the last `online: true` makes the session publish NDEATH, disconnect, connect again with the next `bdSeq`, and wait for the host. An older `online: false` is ignored.

`--max-cycles N` ends the session after N data messages and exits 0. Ctrl-C (or any JVM shutdown) ends it as well. Both publish NDEATH before disconnecting. If the process dies without that, the broker publishes the Will NDEATH.

One thread owns every decision: MQTT callbacks and the shutdown hook only queue events (rebirth, host state, lost connection, stop), and the session loop takes them between data cycles. A rebirth or a host going offline never interleaves with a publish.

## Sample quality and timestamps

Muninn preserves the entire OPC UA DataValue for every instance member rather than just the value. Every sample metric published in DBIRTH, NDATA, and DDATA includes explicit OPC UA quality metadata alongside requested source and server timestamps.

| Field | Type | Rule |
| :--- | :--- | :--- |
| `Quality` | Int32 | Sparkplug B 3.0 well-known property: 0 (BAD) when StatusCode severity is Bad or reserved; 500 (STALE) for `Uncertain_LastUsableValue`; 192 (GOOD) for Good and all other Uncertain codes. Always present. |
| `ua_statuscode` | UInt32 | Raw 32-bit StatusCode containing all severity, sub-code, and flag bits. Always present. |
| `ua_server_timestamp` | UInt64 | OPC UA server timestamp in epoch milliseconds. Omitted when the server returns none. |
| `timestamp` | UInt64 | Metric timestamp carrying the OPC UA source timestamp in epoch milliseconds. Omitted when the server returns none; never populated with the send time. |

Downstream consumers requiring exact status codes should read `ua_statuscode`, while `Quality` offers a lossy projection for standard Sparkplug tooling. The payload-level timestamp continues to reflect the transmission time.

## Acceptance gate

`scripts/run-muninn-gate.sh` composes a HiveMQ CE broker (Docker), the Bifrost sim's OPC-UA server, and Bifrost's `gates` jar to prove, end to end:
- byte-exact NBIRTH;
- all members reach NDATA;
- every member (Rpm, Temp, Running, Secret) arrives with Quality 192, ua_statuscode 0, a source timestamp and a server timestamp from the live sim;
- a bogus member is dropped by egress-validation;
- a tampered registry is refused (exit 1);
- with `--hierarchy` against a mapping release Bifrost published (committed under `src/test/resources/bifrost-mappings`, Bifrost's own synthetic plant), the NBIRTH stays byte-identical and all members arrive as DDATA on device `synthetic-site:record-line-a`;
- one changed byte in a copy of that release's hierarchy file is refused (exit 1).

The gate also proves, against the same broker, for `--session`:
- with `--max-cycles 4`: NBIRTH seq 0 bdSeq 0 byte-identical to the registry, NDATA seq 1 to 4, then NDEATH bdSeq 0 and exit 0;
- an NCMD rebirth sent with the `host` subcommand: a second NBIRTH seq 0 with the same bdSeq, byte-identical, and data seq restarting at 1;
- a registry copy changed under a live session: the rebirth is refused, NDEATH bdSeq 0, exit 1, no second NBIRTH;
- the session process killed: the broker delivers the Will NDEATH bdSeq 0;
- `--primary-host`: no birth until the host's STATE is online; an older offline STATE is ignored; offline publishes NDEATH; online again brings an NBIRTH with bdSeq 1;
- the broker container stopped while a session started with `--primary-host` is still waiting for its host's STATE: the session ends with exit 2 and logs "broker connection lost".

Requires Docker Desktop + host port 1883 free.

## Honest scope

- **Session lifecycle and delivery**: one-shot is the default; `--session` provides the persistent edge node. The session does not reconnect by itself after the broker connection is lost: the broker publishes the Will and the process ends with exit 2, so a supervisor (systemd, a container restart policy) restarts it and the restart is a new session with a fresh birth. No store-and-forward: nothing is sampled or buffered while the Primary Host is offline. Every data message carries all conformant members each period (no report-by-exception).
- **Egress validation is structural/type only, not range** — it filters by member name + `MetricDataType`, not engineering range (range is a design-time governance concern in Bifrost's spec gate).
- **Provenance = content-hash vs a self-attested manifest** — integrity-since-publish, not authenticity (no signing / trust anchor). The `--hierarchy` release chain is read the same way: Bifrost's head attestation is not checked.
- Single broker / single edge / single instance / localhost.

## Dependencies

Eclipse Milo 1.1.8 (OPC-UA client) · Eclipse Tahu 1.0.14 (Sparkplug B) · Eclipse Paho v3 (MQTT) · Jackson 2.17 · Logback.

## License

[Apache License 2.0](LICENSE).
