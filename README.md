# Kinotic Queue

A durable, sharded, replicated queue for Kinotic clusters, published as `org.kinotic:kinotic-queue`.

A server that includes the module stores queues on its local disk, spread over shards, and replicates every shard to other queue nodes. One node owns each shard: it takes appends, replicates them, commits a record once a majority of the shard's copies holds it, and serves consumers and worker groups. An append completes once a majority of the shard's copies holds the record, so a shard keeps taking and serving records while a majority of its copies is reachable. A shard's owner that no majority of its copies has answered for ten seconds stops serving the shard until it reaches them again. While a shard has no owner, a node keeps the appends made through it for that shard and retries them for up to 30 seconds; once they take 64 MB, further appends to the shard fail at once.

The public API is `org.kinotic.queue.api.services.QueueService`: `createQueueIfNotExist` and `deleteQueue` manage queues, `append` writes a record to the shard its key hashes to, `subscribe` opens a named consumer that resumes after its last commit, and `work` joins a group of workers that lease records with retries and a dead-letter queue. Each method's Javadoc states its guarantees.

## Requirements

- Java 25.
- `org.kinotic:kinotic-core`, which the module depends on and which supplies the Ignite cluster and the Vert.x event bus it runs on. The module declares itself to the cluster through core's `NodeAttribute` beans.
- The JVM flags Chronicle Queue needs to map and unmap the shard files; without them the first shard the server opens fails:

  ```
  --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
  ```

```groovy
dependencies {
    implementation 'org.kinotic:kinotic-queue:1.0.0-SNAPSHOT'
}
```

The module auto-configures itself on a Spring Boot server that includes it.

## Durability

Each queue's `QueueDefinition` decides whether its writes are forced to disk before they count toward the majority, fixed when `QueueService.createQueueIfNotExist` creates the queue. With `syncWrites` (the default), an acknowledged record survives every copy losing power at once. Without it, a record survives as long as a majority of its copies keeps running or shuts down cleanly; a process crash loses nothing, since the operating system still writes what the process handed it. Writes without it are faster, most of all on a single copy, where every append otherwise waits for the disk. A worker group's dead-letter queue forces its writes when its source queue does.

## Retention

Records are deleted by age, and optionally by size, in whole segments. A segment closes at the first write once it is half the retention period old, a day old, or a quarter of the retention size, whether the write is a record or an entry the shard's owner adds on its own, as when the shard's owner or copies change, and is deleted once the segment after it is older than the retention period, or while the shard's copy stays at least the retention size without it. Either condition deletes a segment: the size limit can remove records younger than the retention period, and the retention period can leave a copy smaller than the retention size. The segment taking writes stays until a write closes it, so a shard that stops receiving records keeps its last segment, however old, until its owner next writes to it. Consumers and worker groups that have not reached a deleted record continue at the oldest record kept, and a copy that ends before its owner's oldest record starts over there.

A consumer's or worker group's position on a shard expires once it goes unused for the retention period: no subscription of the consumer was open or committed there, or no worker of the group asked for records there. Open subscriptions and asking workers keep their positions alive even while there is nothing new to read. A consumer or group whose position expired starts again at its start position, and its lag gauge goes away.

## Queues across the cluster

The list of queues, and of deleted queues, is held by the queue nodes alone; servers without the module hold none of it. Every queue node keeps a copy of each queue it creates or holds shards of on its disk, so when every queue node restarts, the queues become known again from those copies.

`QueueService.deleteQueue` deletes a queue with its records and consumer and worker group positions on every node; a node away during the deletion deletes its copy when it returns, and a queue created later under the same name starts empty. Each node records the queues it saw deleted in its data directory, so a deleted queue stays deleted when every node restarts.

## Configuration

Configured under `kinotic.queue.*`, the same on every queue node:

| Property | Default | Meaning |
|---|---|---|
| `dataDirectory` | — | The local directory holding the node's shards and consumer offsets. Required; it must be on a local disk, since shards are memory-mapped files. |
| `replicationFactor` | `3` | How many queue nodes hold a copy of every shard. |
| `retentionPeriod` | `7d` | How long records are kept; at least one second. |
| `retentionBytes` | — | How much of each shard's newest records every copy keeps, such as `10GB`, on top of `retentionPeriod`: a copy that has grown past it is trimmed to at least this much, unless `retentionPeriod` lets records go sooner, and at most one segment more on the shard's owner, or two on its other copies, whose segments do not end where the owner's do; a copy exceeds that until the owner's trim reaches it, and while the shard has no owner. A segment closes at the first write once it has reached a quarter of this, but at least 64 KB and at most 1 GB, so it is smaller than that plus one record. Records are measured as their keys and payloads, so a copy's files take more than this on disk. Unset, shards are limited by age only. |

## Metrics

A server running the module registers these meters with Micrometer's global registry. Counters count what the node did as the owner of shards. Gauges are refreshed every five seconds and sum over the shards the node owns, or for `kinotic.queue.size` the shard copies it holds, so summing a gauge across nodes gives the queue's total.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `kinotic.queue.appends` | counter | `queue` | Records whose append the node acknowledged as their shard's owner |
| `kinotic.queue.append.duration` | timer | `queue` | How long the owner took to commit a batch of appends |
| `kinotic.queue.consumer.lag` | gauge | `queue`, `consumer` | Committed records the consumer has not committed |
| `kinotic.queue.group.lag` | gauge | `queue`, `group` | Committed records the worker group has not finished |
| `kinotic.queue.replica.lag` | gauge | `queue`, `replica` | Records the shard copies on the node `replica` lack |
| `kinotic.queue.dead.letters` | counter | `queue`, `group` | Records appended to the group's dead-letter queue |
| `kinotic.queue.redeliveries` | counter | `queue`, `group` | Records leased to the group again after an earlier lease ended unaccepted |
| `kinotic.queue.takeovers` | counter | `queue` | Times the node took ownership of a shard |
| `kinotic.queue.step.downs` | counter | `queue` | Times the node stopped owning a shard still placed on it, because a newer owner reached its copies, no majority of them answered, or a write failed |
| `kinotic.queue.size` | gauge (bytes) | `queue` | About how much space the node's copies of the queue's shards take |
| `kinotic.queue.shards.owned` | gauge | `queue` | The queue's shards the node owns |

## Building

```bash
./gradlew build
```

The tests start Ignite and Vert.x clusters in the test JVM and need no external services. Every version lives in `gradle.properties` and is pinned once in the `dependencyManagement` block of `build.gradle`; `kinoticCoreVersion` selects the `kinotic-core` the module is built against, resolved from Maven Central or its snapshot repository.

## License

Kinotic Queue is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
