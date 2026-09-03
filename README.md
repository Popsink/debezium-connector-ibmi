# Project

Use the IBM I journal as a source of CDC events see https://github.com/debezium/debezium-connector-ibmi/tree/main/journal-parsing/ for the journal fetch/decoding

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
* CLOB, DBCLOB, BLOB and XML columns are captured, but a journal entry carries no lob data - only a
  pointer into the journal receiver that can only be followed on the IBM i itself - so the values are
  read back with `QSYS2.DISPLAY_JOURNAL`. Entries are read a run at a time, so the first lob entry of
  a batch pays for the ones behind it rather than each costing a query of its own. Set
  `lob.fetch=false` to skip the reads entirely: lob columns then stream as null, no extra query is
  made, and the rest of the table is unaffected.
* Every journal entry of a table with a lob column also arrives owning a pointer handle on the IBM i,
  whether or not the lob data is read. Handles are only released when the job that requested them
  ends, and returning them individually would cost a round trip per entry, so they are counted and
  freed together once `journal.pointer.handle.threshold` (default 50000) have accumulated, by
  replacing the connection so that the next retrieve runs in a different host server job. Nothing on
  the system is cancelled or ended - the connection is simply re-established, transparently. Lower it
  on a system with a constrained job storage limit.

# Problems

## running with a self signed certificate

capture your ca cert normally the bottom of the output:
`openssl s_client -showcerts -connect <host>:9471`

between the last pair of `-----BEGIN CERTIFICATE-----` and end and save it to iseries-cert.pem
	

If using docker simply mount your certs at /var/tls

If running natively import the cert
	
	keytool -import -noprompt -alias iseries-cert  -storepass changeit -keystore /usr/lib/jvm/java-1.17.0-openjdk-amd64/lib/security/cacerts -file iseries-cert.pem

## Journals deleted

### Journal position no longer available

If the connector has been down longer than the source keeps its journal receivers, its committed offset
points at a receiver that has since been pruned/rotated. On restart the connector cannot resolve that
position. This is a **non-transient** condition — the receiver will never come back — so retrying the
engine only delays an inevitable failure.

The same thing happens *while streaming*, e.g. when the journal or its receivers are deleted under a
running connector. Both cases apply the same recovery strategy; earlier versions silently reset
streaming to the earliest available receiver instead, which lost data without failing.

Two things are done to keep this from turning into a silent crash-loop:

* A pruned receiver is told apart from a transient RPC/connection error. Only the former is treated as
  "position lost"; a transient failure is retried as before.
* The `journal.unavailable.position.recovery` option controls what happens when the stored position is
  gone:

  | value | behaviour |
  | --- | --- |
  | `fail` (default) | Stop with a distinct, non-transient error (marker `IBMI_OFFSET_NO_LONGER_AVAILABLE`) so an orchestrator can reset the offset / trigger a snapshot / alert, rather than retry to death. |
  | `snapshot` | Reset the offset and let the configured `snapshot.mode` take a fresh snapshot to re-establish table state, then resume streaming from the current journal position. Use with `snapshot.mode=initial`/`always`/`when_needed`. A snapshot cannot be started from the streaming thread, so when the position is lost mid-stream the task fails with the same marker and the snapshot is taken by the startup recovery on the next start. |
  | `earliest` | Reset streaming to the earliest available journal receiver and continue. Intended for streaming-only / `no_data` connectors. Changes between the lost position and the earliest available receiver are **unrecoverable** and a loud warning is logged. |

  Mid-stream recovery is bounded: 20 consecutive failed polls fail the task rather than looping forever.
  A lost connection (`IOException`, or the jt400 driver's `SQLNonTransientConnectionException`) is
  classified as retriable, so hitting the bound restarts the connector — unlimited by default, see
  `errors.max.retries` — rather than stopping the task. A position that no longer exists is not retriable
  and stops the task; to have the engine restart on that too, set `errors.max.retries` together with
  `custom.retriable.exception=.*IBMI_OFFSET_NO_LONGER_AVAILABLE.*`.

  Setting `snapshot.mode=when_needed` also triggers Debezium core's own re-snapshot-on-data-error path,
  which is equivalent to `journal.unavailable.position.recovery=snapshot`.

Data already lost from pruned receivers cannot be recovered via CDC; recovery is about getting the
connector healthy again, not replaying the gap. Ad-hoc snapshots (both incremental and blocking) are
also supported via the signalling channel.

### Blocking-snapshot pause safety

An ad-hoc **blocking** snapshot is a cooperative handshake: the coordinator pauses streaming and waits
for the streaming thread to acknowledge before the snapshot starts, then resumes it when the snapshot
finishes. The streaming thread only reaches those handshake points between journal polls, so three wedges
are possible while the connector still reports `live`: a requested pause is never honored (the thread
keeps draining a large shared-journal block, issue #27), a finished snapshot never resumes streaming
(#27), or both sides sit on "paused" with no snapshot running at all (#74). Three safeguards prevent
this:

* The journal-drain loop breaks out as soon as a blocking-snapshot pause is requested, so the pause is
  honored within one journal entry rather than after a whole block.
* While paused, the streaming thread polls the coordinator's pause flag and re-acknowledges the pause
  every 250 ms instead of parking on the coordinator's condition variable. Parking there deadlocks when
  the orchestrator queues per-table backfills: the single-threaded blocking-snapshot executor starts the
  next queued snapshot before the woken streaming thread can re-read the flag, so the thread parks again
  while that snapshot waits forever for an acknowledgement it will never get. Re-acknowledging also means
  a snapshot requested while streaming is already paused still runs, instead of being silently dropped.
* `blocking.snapshot.pause.timeout.ms` (default `120000`) bounds how long the handshake may stay stuck in
  any of the three states above — including "paused with no snapshot running", which is invisible to a
  check that only compares the two views against each other. Past that, the `WatchDog` fails the task
  with a retriable error and Debezium restarts it (bounded by `errors.max.retries`), clearing the wedge
  instead of stalling silently — a loud, logged restart rather than an in-place rescue, because
  interrupting the wedged thread cannot reliably resync the handshake. The snapshot itself is never
  bounded by this timeout: a blocking snapshot may run for hours.

> Not to be confused with a stale JDBC connection detected mid-snapshot, which is a connection-liveness
> issue rather than offset/position recovery.

## Tables that cannot be captured

A table listed in `table.include.list` cannot be streamed at all when it does not exist, when it has no
journal (never `STRJRNPF`'d), or when it is an **SQL view** — a view has no journal of its own and the
IBM i journal RPC calls only accept physical files. Every case below is logged at **ERROR** level.

**A table that does not exist is always dropped**, whatever `errors.tolerance` is set to, because
Debezium ignores `table.include.list` entries with no matching table everywhere else (the snapshot
discovers tables from the catalog).

For a table that *does* exist but has no journal, `errors.tolerance` decides:

| value | behaviour |
| --- | --- |
| `none` (default) | Startup fails, so the misconfiguration has to be fixed (or the table removed from the include list) rather than silently never streaming. |
| `all` | The table is left out of the journal filters; the remaining tables stream as usual. |

Two things stay fatal even with `all`, because they are not "one bad table": tables spanning more than
one journal (the offset model is single-journal), and an include list where *no* table can be captured —
an empty filter list would otherwise be read as "no include list", i.e. stream the whole library.

Missing tables and views come from `QSYS2.SYSTABLES` before any RPC call (`TABLE_TYPE = 'V'` is a view);
an unjournaled table shows up when its journal is resolved. That catalog lookup is deliberately loose —
long or system name, any case, and `SYSTEM_TABLE_NAME` may come back delimited (`"Vue10002"`) — because a
miss drops the table. If the lookup itself fails the table is kept, so a catalog hiccup cannot silently
stop a healthy table from being captured.

Snapshots already skipped both (table discovery asks for `TABLE` only), so this is about the streaming
side.

> `errors.tolerance` is the same property name and the same `none`/`all` values as Kafka Connect's own,
> which governs converter/transform/producer errors; the setting is shared and the intent is the same.

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

Character conversion is always done against the CCSID of the **remote IBM i**, never one inferred from
the connector's own locale. jt400's `new AS400Text(length)` constructor falls back to
`ExecutionEnvironment.getBestGuessAS400Ccsid()`, which derives a CCSID from the JVM's default locale -
so a connector container defaulting to `en_US` reading a French or Japanese system would encode journal
API parameters with the wrong EBCDIC page and decode journal headers and record images into mojibake.
Every character `AS400DataType` is therefore built through `As400TextFactory`, which pins the CCSID the
system itself reports at sign-on (`AS400.getCcsid()`).

CCSIDs are resolved in this order:

1. the column's own CCSID from `qsys2.syscolumns` (remapped by `from.ccsid`/`to.ccsid` if configured);
2. the remote system CCSID, for API parameters and headers, and for any column the catalogue has no
   CCSID for (or that is tagged 65535, meaning "no translation");
3. the local locale guess - only if the system could not be reached to ask, which is logged as an error.

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
