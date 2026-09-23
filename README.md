# Project

Use the IBM I journal as a source of CDC events see https://github.com/debezium/debezium-connector-ibmi/tree/main/journal-parsing/ for the journal fetch/decoding

# Popsink fork

This repository is Popsink's fork of [debezium/debezium-connector-ibmi](https://github.com/debezium/debezium-connector-ibmi).
`main` is upstream `v3.6.3.Final` with the patches below rebased on top, so `git log v3.6.3.Final..main` lists exactly the fork delta.
Builds are published to GCP Artifact Registry as `<debezium version>-ppsk-<date>[-N]` by `publish-release.yml`.

Patches that upstream accepts are dropped from the fork at the next rebase. Upstream enforces DCO, so commits proposed there need a `Signed-off-by` line.

## Fork delta

| Patch | Status |
|---|---|
| Release management: GCP Artifact Registry repositories, `gcloud` deploy profile, `publish-release.yml` / `publish-snapshot.yml` / `build-pr.yml`, checkstyle and Sonatype publishing disabled, `version.debezium` pinned | Popsink-only |
| Optional transaction support (`transaction.management`) | Popsink-only until there is a demand upstream |
| Ad-hoc blocking snapshots in journal streaming, journal watchdog suspended during a blocking snapshot | Popsink-only for now; depends on how upstream wants blocking snapshots to interact with the watchdog |
| Blocking-snapshot boundary probe hinted with `OPTIMIZE FOR 1 ROW` (#21), optimisation hints on the snapshot selects, IBM i query governor off by default | Popsink-only; tuned for our customers' systems |
| Better configuration defaults and JDBC validation | Popsink-only |
| RRN retrieved from journal entries and exposed in the source block | Popsink-only |
| `$` in table names | Candidate for upstream (#93) |
| Null handling in the journal decoder | Candidate for upstream (#93) |
| DATE/TIME decoded using the column's DATFMT instead of assuming `*ISO`, PUB400-compatible `DateTimeFormatCache` | Candidate for upstream (#93) |
| Tables across several libraries sharing one journal | Candidate for upstream (#93) |
| Recovery from a pruned journal receiver (#14) | Candidate for upstream (#93) |
| `latest` journal position recovery strategy (#84) | Candidate for upstream (#93) |
| SLF4J placeholder mismatches (#56) | Candidate for upstream (#93) |

Already accepted upstream and no longer carried here: persist and load the incremental snapshot status (debezium/dbz#1861) and the reliable connection close that clears the prepared-statement cache (debezium/dbz#2204).

# Configuration

## IBMI Permissions

```
GRTOBJAUT OBJ(<JRNLIB>) OBJTYPE(*LIB) USER(<CDC_USER>) AUT(*EXECUTE)
GRTOBJAUT OBJ(<JRNLIB>/*ALL) OBJTYPE(*JRNRCV) USER(<CDC_USER>) AUT(*USE)
GRTOBJAUT OBJ(<JRNLIB>/<JRN>) OBJTYPE(*JRN) USER(<CDC_USER>) AUT(*USE *OBJEXIST)
```
```
GRTOBJAUT OBJ(<PROJECT_LIB>) OBJTYPE(*LIB) USER(<CDC_USER>) AUT(*EXECUTE)
GRTOBJAUT OBJ(<PROJECT_LIB>/*ALL) OBJTYPE(*FILE) USER(<CDC_USER>) AUT(*USE)
```
Where:

* `<JRNLIB>` is the library where the journal and receivers reside
* `<JRN>` is the journal name
* `<PROJECT_LIB>` is the Figaro database library
* `<CDC_USER>` is the username of the CDC service account

## Required
The following environment variables are mandatory configuration and have no default values

```
DEBEZIUM_BOOTSTRAP_SERVERS
SCHEMA_REGISTRY_URL

DEBEZIUM_REST_ADVERTISED_HOST_NAME
```

## Production

It is highly recommended that the partitions and replicaion_factor is increased for production

```
PARTITIONS=3
REPLICATION_FACTOR=3
```

# Limitations

* TODO integrate with exit program to prevent journal loss https://github.com/jhc-systems/debezium-ibmi-exitpgm
* Limited support for table changes - the journal entries for table changes are not documented so rely on fetching table structure at runtime and refetching when table change detected
* No support for remote journals and fail over
* No support for clobs/xml and similar large text/blobs

# Problems

## running with a self signed certificate

capture your ca cert normally the bottom of the output:
`openssl s_client -showcerts -connect <host>:9471`

between the last pair of `-----BEGIN CERTIFICATE-----` and end and save it to iseries-cert.pem
	

If using docker simply mount your certs at /var/tls

If running natively import the cert
	
	keytool -import -noprompt -alias iseries-cert  -storepass changeit -keystore /usr/lib/jvm/java-1.17.0-openjdk-amd64/lib/security/cacerts -file iseries-cert.pem

## Journals deleted

If the journal is deleted *while streaming* it logs an error ("Lost journal at position xxx") and resets to the earliest available journal receiver.

### Stored offset no longer available at startup

If the connector has been down longer than the source keeps its journal receivers, its committed offset
points at a receiver that has since been pruned/rotated. On restart the connector cannot resolve that
position. This is a **non-transient** condition — the receiver will never come back — so retrying the
engine only delays an inevitable failure.

Two things are done to keep this from turning into a silent crash-loop:

* A pruned receiver is told apart from a transient RPC/connection error. Only the former is treated as
  "position lost"; a transient failure is retried as before.
* The `journal.unavailable.position.recovery` option controls what happens when the stored position is
  gone:

  | value | behaviour |
  | --- | --- |
  | `fail` (default) | Stop with a distinct, non-transient error (marker `IBMI_OFFSET_NO_LONGER_AVAILABLE`) so an orchestrator can reset the offset / trigger a snapshot / alert, rather than retry to death. |
  | `snapshot` | Reset the offset and let the configured `snapshot.mode` take a fresh snapshot to re-establish table state, then resume streaming from the current journal position. Use with `snapshot.mode=initial`/`always`/`when_needed`. |
  | `earliest` | Reset streaming to the earliest available journal receiver and continue. Intended for streaming-only / `no_data` connectors. Changes between the lost position and the earliest available receiver are **unrecoverable** and a loud warning is logged. Its cost scales with retention x journal rate: everything already delivered is re-read. On a busy journal this is effectively a denial of service against yourself - see below. |
  | `latest` | Reset streaming to the current journal head and continue, replaying nothing. Recommended for streaming-only / `no_data` connectors. Changes between the lost position and the head are **unrecoverable** and a loud warning is logged - exactly the same gap as `earliest`, without the replay. Requires `errors.tolerance=all` - see below. |

  Setting `snapshot.mode=when_needed` also triggers Debezium core's own re-snapshot-on-data-error path,
  which is equivalent to `journal.unavailable.position.recovery=snapshot`.

  `latest` does not recover the gap, it *declares* it, so it is only accepted together with Kafka
  Connect's `errors.tolerance=all`. With any other tolerance - including Connect's default `none` - the
  connector refuses to start with an `IllegalStateException` naming both settings, rather than
  discovering the contradiction at the first pruned receiver, when it would already have cost data.
  Where a dropped record is not acceptable, `snapshot` is the safe recovery: it fills the gap instead
  of skipping it.

Data already lost from pruned receivers cannot be recovered via CDC; recovery is about getting the
connector healthy again, not replaying the gap. Ad-hoc snapshots (both incremental and blocking) are
also supported via the signalling channel.

`earliest` and `latest` therefore leave the same gap: entries between the lost position and the resume
point are gone either way. What `earliest` adds is re-reading everything the target already has. On a
measured deployment with 2.7 days of retention and ~15 000 entries/s, `earliest` reset the connector
3.5 billion entries behind and it needed ~8 days to return to where it already was - longer than the
retention window, so it could never catch up. Prefer `latest` unless the journal is low-volume and you
specifically want the retained entries replayed.

> Not to be confused with a stale JDBC connection detected mid-snapshot, which is a connection-liveness
> issue rather than offset/position recovery.

## Memory

Recommended minimum memory 1GB

This can either be configured with cloud resources or using `JAVA_OPTIONS=-Xmx1g`

## Tracing

```
DEBEZIUM_


```

```
producer.interceptor.classes=brave.kafka.interceptor.TracingProducerInterceptor
consumer.interceptor.classes=brave.kafka.interceptor.TracingConsumerInterceptor

producer.zipkin.sender.type=KAFKA
producer.zipkin.local.service_name=ksql
producer.zipkin.bootstrap.servers=mskafka:9092
```

# Configuring the CDC

## Use the REST interface to post a new connector

Create a new connector configuration `connector-name.json`:

`curl -i -X POST -H "Accept:application/json" -H "Content-Type:application/json" http://localhost:8083/connectors/ -d '@./connector-name.json'`

file connector-name.json:

```
{
  "name": "connector-name",
  "config": {
    "connector.class": "io.debezium.connector.db2as400.As400RpcConnector",
    "sanitize.field.names": "true",
    "tasks.max": "1",
    "database.hostname": "ibmiserver",
    "database.dbname": "database name",
    "database.schema": "SCHEMA",
    "database.user": "xxx",
    "database.password": "xxx",
    "port": "",
    "secure": true
    "poll.interval.ms": "2000",
    "transforms": "unwrap",
    "transforms.unwrap.delete.handling.mode": "rewrite",
    "transforms.unwrap.drop.tombstones": "false",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "table.include.list": "SCHEMA.TABLE1",
    "snapshot.mode": "initial",

    "key.converter.schema.registry.url": "http://schema-registry:8081",
    "value.converter.schema.registry.url": "http://schema-registry:8081",
    "key.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter": "io.confluent.connect.avro.AvroConverter",

    "snapshot.max.threads": 4
  }
}
```


Note the `dbname` can be blank and will be used as part of the jdbc connect string : `dbc://hostname/dbname`


Optional:

```
    "driver.socket timeout": "300000",
    "driver.keep alive": "true",
    "driver.thread used": "false"
```
the above help with connections that can be blocked (firewalled) or dropped due to vpn issues

## CCSID

Unusually we have the incorrect CCSID on all our tables and the data is forced into the tables with the wrong encoding

This issue should really be corrected and the data translated but with thousands of tables and many clients all configured incorrectly this is a huge job with significant risk. Instead we have an additional pair of settings from.ccsid which is the ccsid on the table and to.ccsid which will use this ccsid instead - this is for the entire system and all tables.


## The list of connectors

`curl -i -X GET -H "Accept:application/json" -H "Content-Type:application/json" http://localhost:8083/connectors/`

```
[connector-name]
```

## Deleting a connector

and deleted with

`curl -i -X DELETE -H "Accept:application/json" -H "Content-Type:application/json" http://localhost:8083/connectors/connector-name`

## Getting the running configuration of the connector

`curl -i -X GET -H "Accept:application/json" -H "Content-Type:application/json" http://localhost:8083/connectors/connector-name`

## Adding tables

The configuration can be updated with

`curl -i -X PUT -H "Accept:application/json" -H "Content-Type:application/json" http://localhost:8083/connectors/connector-name/config/ -d "@connector-name-config.json"`

Notes
* the `connector-name` in the url must match the `name` in the json file
* the update file only contains the inner `config`

```
{
    "connector.class": "io.debezium.connector.db2as400.As400RpcConnector",
    "sanitize.field.names": "true",
    "database.hostname": "ibmiserver",
    "database.dbname": "database name",
    "database.schema": "SCHEMA",
    "database.user": "xxx",
    "database.password": "xxx",
    "port": "",
    "poll.interval.ms": "2000",
    "transforms": "unwrap",
    "transforms.unwrap.delete.handling.mode": "rewrite",
    "transforms.unwrap.drop.tombstones": "false",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "table.include.list": "SCHEMA.TABLE1",
    "snapshot.mode": "initial",

    "key.converter.schema.registry.url": "http://schema-registry:8081",
    "value.converter.schema.registry.url": "http://schema-registry:8081",
    "key.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter": "io.confluent.connect.avro.AvroConverter",
    "tasks.max": "1"
}
```

Here we've added TABLE2 to the list.

# monitoring

prometheus stats are avaialble on port 7071

sample prometheus stats are in

* metrics/prometheus

sample grafana charts

* metrics/grafana.txt


# Troubleshooting

See upstream project: https://github.com/debezium/debezium-connector-ibmi/tree/main/journal-parsing

## No journal entries found check journalling is enabled and set to *BOTH

`dspfd MYTABLE`

```
    File is currently journaled . . . . . . . . :            Yes
    Current or last journal . . . . . . . . . . :            MYJRN
      Library . . . . . . . . . . . . . . . . . :            MYLIB
    Journal images  . . . . . . . . . . . . . . : IMAGES     *BOTH

```

# Development

## Class diagram
https://lucid.app/lucidchart/invitations/accept/inv_b0dba11e-fb73-4bfc-9efd-1c14d7ef2642

## Testing/debugging

main class
* `org.apache.kafka.connect.cli.ConnectDistributed`

runtime argument of the configuration e.g. for
* local kafka `src/test/resources/protobuf.properties`
* remote confluent `src/test/resources/confluent.properties`

Logging - vm args `-Dlogback.configurationFile=src/test/resources/logback.xml`

## Running kafka locally
https://bitbucket.org/jhc-systems/kafka-kubernetes/src/master/docker/

## Running debezium locally

Configure the IP addresses in `conf/local.env`, `src/test/resources/protobuf.properties`, and `src/test/resources/confluent.properties` to be your IP address. If running using localhost, you can use `0.0.0.0` for each of these.

### VS Code

To run in VS Code, configure the following launch.json file, and run from the Run and Debug extension.
```
{
    // Use IntelliSense to learn about possible attributes.
    // Hover to view descriptions of existing attributes.
    // For more information, visit: https://go.microsoft.com/fwlink/?linkid=830387
    "version": "0.2.0",
    "configurations": [
        {
            "type": "java",
            "name": "Launch",
            "request": "launch",
            "mainClass": "org.apache.kafka.connect.cli.ConnectDistributed",
            "projectName": "debezium-connector-ibmi",
            "env": {},
            "args": "src/test/resources/protobuf.properties",
            "logback.configurationFile": "src/test/resources/logback.xml"
        }
    ]
}
```

## Release notes

## debezium v3.0.0

`from_ccsid` is now `from.ccsid` and `to_ccsid` is `now to.ccsid` they stay as top level config

jdbc configuration needs the prefix `driver.<parameter>` e.g. `driver.date format` it now defaults to `iso`

### 1.10.4

New configuration paramater `secure` this defaults to true

### 1.10.2

Fixes data loss bugs:
* * when receivers reset
* * after receiving a continuation offset the first entry is occasionally lost (when the next request doesn't contain a continuation offset)

### 1.9

additional configuration parameters required for avro in the submitted json

```
    "key.converter.schema.registry.url": "http://schema-registry:8081",
    "value.converter.schema.registry.url": "http://schema-registry:8081",
    "key.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter": "io.confluent.connect.avro.AvroConverter",
```

optional parameter

```
"snapshot.max.threads": 4
```

# Build

## build docker image and publish to local docker server

mvn compile jib:dockerBuild
