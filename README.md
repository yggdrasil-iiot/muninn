Muninn — the northbound **feeder** of the Yggdrasil IIoT governance portfolio: it consumes a *governed* equipment definition, proves the bytes it will publish are the ones governance admitted, and streams live OPC-UA instance data to a Sparkplug B / MQTT UNS. It is a separate app, coupled to the rest only by a published data/wire contract — **zero shared code** (its own value types, its own SHA-256/manifest).

## Role in the governance spine

```
Mímir (model) ──▶ Bifrost (govern) ──▶ Muninn (feed the UNS)
```

Mímir derives a canonical UDT definition, Bifrost's gates admit + provenance-publish it, and **Muninn**:

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
     <instanceNodeIdent> <mqttUrl> <group> <edge> [--inject-bogus <member>]

# Observe (gate harness): capture NBIRTH bytes + NDATA member names (+ optional name=value)
java -jar target/muninn.jar observe <mqttUrl> <group> <birthOutFile> <ndataOutFile> \
     [--expect-ndata N] [--timeout-ms M] [--ndata-values <file>]
```

Exit codes: `0` ok · `1` governance-refuse (provenance mismatch) · `2` usage/error.

## Acceptance gate

`scripts/run-muninn-gate.sh` composes a HiveMQ CE broker (Docker), the Bifrost sim's OPC-UA server, and Bifrost's `gates` jar to prove, end to end: byte-exact NBIRTH, all members reach NDATA, a bogus member is dropped by egress-validation, and a tampered registry is refused (exit 1). Requires Docker Desktop + host port 1883 free.

## Honest scope

- **One-shot feeder**, not a compliant persistent Sparkplug edge node: one NBIRTH (`seq=0`) + one NDATA, then exit. No `bdSeq` / NDEATH-LWT / STATE / rebirth session semantics (the codec already parameterizes `seq`, so this is an additive extension, not a rewrite).
- **Egress validation is structural/type only, not range** — it filters by member name + `MetricDataType`, not engineering range (range is a design-time governance concern in Bifrost's spec gate).
- **Provenance = content-hash vs a self-attested manifest** — integrity-since-publish, not authenticity (no signing / trust anchor).
- Single broker / single edge / single instance / localhost.

## Dependencies

Eclipse Milo 1.0.0 (OPC-UA client) · Eclipse Tahu 1.0.14 (Sparkplug B) · Eclipse Paho v3 (MQTT) · Jackson 2.17 · Logback.

## License

[Apache License 2.0](LICENSE).
