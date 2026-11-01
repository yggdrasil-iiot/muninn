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
     [--hierarchy <mappingsDir> <stream> <equipmentInstanceId>]

# Observe (gate harness): capture NBIRTH bytes + NDATA/DDATA member names (+ optional name=value)
java -jar target/muninn.jar observe <mqttUrl> <group> <birthOutFile> <ndataOutFile> \
     [--expect-ndata N] [--timeout-ms M] [--ndata-values <file>]
```

Exit codes: `0` ok · `1` governance-refuse (provenance mismatch, or a hierarchy that does not verify) · `2` usage/error.

## ISA-95 location for the UNS

Optional. Without `--hierarchy` the feed is byte-for-byte what it was: NBIRTH then NDATA on the edge node.

With `--hierarchy <mappingsDir> <stream> <equipmentInstanceId>`, Muninn reads the ISA-95 equipment hierarchy (Tyr/Bifrost contract 2.23.0, `equipment-hierarchy`) from a mapping release Bifrost published, and publishes the equipment as a Sparkplug B device: NBIRTH unchanged (same `governed_definition` bytes), then DBIRTH (seq 1) and DDATA (seq 2) on `spBv1.0/<group>/{DBIRTH,DDATA}/<edge>/<device>`, carrying the same egress-validated members.

The device ID is the `equipmentInstanceId`s from the hierarchy root down to the instance, joined by `:` — e.g. `synthetic-site:record-line-a`. This is the form UMH's Sparkplug B input reads as a location path (its "Modified Parris Method": the device ID's colons become the dots of `location_path`, so the example becomes `synthetic-site.record-line-a`). Muninn's metric names (`Rpm`, `Temp`, ...) contain no separator, so UMH reads them as tag names with no virtual path.

Muninn follows the published convention only; it has no UMH code or dependency (UMH's README says Apache-2.0 while its 2025-12 Community/Enterprise license documents differ, so the format is the only thing taken).

Verification chain, each link raw-byte SHA-256, the way any consumer outside Bifrost verifies a release: `heads/<stream>.json` names `releases/<id>/<version>/release.json` and its digest; `release.json` lists each payload file with its digest; the payload's `proposal.json` names the single `equipment-hierarchy` file. Muninn's own reader, no Bifrost code.

Muninn **refuses** (exit 1) before any MQTT or OPC-UA connect when: a digest does not match; a file is missing or not listed; the release has zero or several hierarchies; the document is not `equipment-hierarchy` 2.23.0; the instance is not in the hierarchy; a parent is missing; a cycle; a path deeper than 64; a stream or head segment that would leave the mappings directory; an ID containing `/` (cannot be a Sparkplug topic token) or `.` (UMH would read it as an extra level), or `:`, `+`, `#`. The contract's ID pattern allows `/` and `.`, so such IDs are refused rather than rewritten.

## Acceptance gate

`scripts/run-muninn-gate.sh` composes a HiveMQ CE broker (Docker), the Bifrost sim's OPC-UA server, and Bifrost's `gates` jar to prove, end to end: byte-exact NBIRTH, all members reach NDATA, a bogus member is dropped by egress-validation, a tampered registry is refused (exit 1), with `--hierarchy` against a mapping release Bifrost published (committed under `src/test/resources/bifrost-mappings`, Bifrost's own synthetic plant) the NBIRTH stays byte-identical and all members arrive as DDATA on device `synthetic-site:record-line-a`, and one changed byte in a copy of that release's hierarchy file is refused (exit 1). Requires Docker Desktop + host port 1883 free.

## Honest scope

- **One-shot feeder**, not a compliant persistent Sparkplug edge node: one NBIRTH (`seq=0`) + one NDATA, or with `--hierarchy` one NBIRTH + one DBIRTH + one DDATA, then exit. No `bdSeq` / NDEATH-LWT / STATE / rebirth session semantics (the codec already parameterizes `seq`, so this is an additive extension, not a rewrite).
- **Egress validation is structural/type only, not range** — it filters by member name + `MetricDataType`, not engineering range (range is a design-time governance concern in Bifrost's spec gate).
- **Provenance = content-hash vs a self-attested manifest** — integrity-since-publish, not authenticity (no signing / trust anchor). The `--hierarchy` release chain is read the same way: Bifrost's head attestation is not checked.
- Single broker / single edge / single instance / localhost.

## Dependencies

Eclipse Milo 1.1.8 (OPC-UA client) · Eclipse Tahu 1.0.14 (Sparkplug B) · Eclipse Paho v3 (MQTT) · Jackson 2.17 · Logback.

## License

[Apache License 2.0](LICENSE).
