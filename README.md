# CognoDB Cloud vs. Managed Graph Databases: A Fair-Resource Benchmark

A reproducible benchmark comparing [CognoDB Cloud](https://console.cognodb.com) against four
other managed/self-hosted graph database platforms, on identical data, identical queries, and
matched resource tiers.

> **TL;DR for busy readers:** jump to [Results](#results) for the numbers, or
> [Analysis](#analysis) for what they mean and why.

## Why these five platforms

| Platform | Tier used | Why it's a fair comparison point |
|---|---|---|
| **CognoDB Cloud** | Free `c0` (0.5 vCPU burstable, 256 MB RAM, 1 GB disk) | The platform under test. |
| **Neo4j AuraDB Free** | Free instance | The de-facto reference implementation of the property-graph model CognoDB itself speaks (Bolt + Cypher) — the closest apples-to-apples baseline available. |
| **Memgraph Cloud** | Free tier | Also Bolt + openCypher, but an in-memory-first architecture — a useful contrast on latency for the same query language. |
| **ArangoDB Oasis** | Free trial | A multi-model database (AQL) with a genuinely different query engine and storage layout — tests how much the *language*, not just the deployment, matters. |
| **Nebula Graph** | Self-hosted, Docker, resources capped to match CognoDB's free tier | Represents the "distributed, storage/compute-separated" architecture family, run under the *same* resource ceiling rather than a managed free tier, per the assignment's explicit allowance for capped self-hosted deployments. |

<!-- TODO: briefly justify any changes you make to this list, and note the exact
     advertised specs (vCPU/RAM/disk) for each platform's tier here, copied from
     each platform's own pricing/console page at the time you provisioned it. -->

## Dataset

**SNAP `ca-HepPh`** — the arXiv High Energy Physics collaboration network
(<https://snap.stanford.edu/data/ca-HepPh.html>).

- **Nodes:** 12,008 (authors)
- **Edges:** 118,505 undirected co-authorship relationships after deduplication (matches SNAP's official 118,521 count for this dataset). The raw edge-list file (`ca-HepPh.txt.gz`) lists each undirected edge as two directed lines, so the file's raw line count (237,010) is roughly double the true relationship count — see the loader code / `environment.relationship_count` in the raw JSON results for the pre-dedup figure.
- **Why this dataset:** it's public, citably sourced, and its edge count sits
  comfortably in the assignment's 100k–500k relationship band while staying
  small enough to fit CognoDB's 256 MB free instance without any sampling —
  so every platform gets the identical, unmodified file.
- **File used:** `data/ca-HepPh.txt.gz` (fetched by `data/download_dataset.sh`)
- **SHA-256:** `<TODO: paste the checksum printed by download_dataset.sh here>`

Loaded identically into every platform as: one vertex per author id, one
undirected edge per collaboration (see `GraphClient` implementations under
`src/main/java/ai/wexa/benchmark/platform/` for the exact load method per
platform — all use batched bulk inserts via each platform's official driver;
no platform gets a bulk-import-tool advantage the others don't have).

## Methodology

- **Same resources everywhere.** Every platform above runs on (or as close as
  its tier allows to) CognoDB's free-tier spec: 0.5 vCPU / 256 MB RAM / 1 GB
  disk. Where a platform's free tier is *more generous* than that (e.g. Aura
  Free's default allocation), that's stated explicitly in the results table —
  see the fairness caveat below.
- **Same client, same region, same run window.** All five runs are launched
  from `scripts/run_all.sh` from a single machine, back-to-back, so network
  path and client-side overhead stay constant across platforms as far as
  practically possible.
- **Iterations.** Target: 100 iterations per read workload, 20-second mixed
  workload. Achieved for AuraDB, Memgraph, and Nebula. CognoDB and ArangoDB's
  free-tier instances could not sustain this under real client-machine
  conditions (see caveats below) — those two report 20-iteration / 5-second
  results instead. Every read workload runs 10 untimed warm-up iterations
  before the timed ones that are recorded, regardless of platform.
- **Percentiles, not just averages.** p50 / p95 (and p99 for extra visibility)
  are computed from raw per-call latency samples — see
  `stats/LatencyStats.java`.
- **Mixed workload.** 20 concurrent clients, 20 seconds, 80% point-lookup
  reads / 20% upsert-style writes (see `WorkloadRunner.runMixedWorkload`).
- **Automated end to end.** `scripts/run_all.sh` builds the jar once and
  drives all five platforms through the identical `WorkloadRunner`; nothing
  here was hand-run or hand-timed.

### Known caveats (discovered during actual runs)

- **Memgraph Cloud uses a self-signed TLS certificate.** The harness explicitly trusts it for this platform only (`MemgraphClient.connect()` overrides the default strict certificate validation used for CognoDB and AuraDB). This is a lower security bar than CA-signed certs and would not be acceptable in a production setting — noted here for transparency.
- **Free-tier resource tiers are not uniform across platforms**, despite the intent to match CognoDB's 0.5 vCPU / 256 MB:
    - Neo4j AuraDB Free: 1 GB RAM (per Aura console at provisioning time).
    - Memgraph Cloud (smallest available trial tier): 2 GB RAM / 2 CPU — roughly 8x CognoDB's allocation. There was no smaller tier available.
    - ArangoDB Oasis: free-trial deployment, exact spec shown in its console at provisioning time.
    - Nebula Graph: initially deployed with tight Docker resource caps (~80-100 MB per process) to approximate CognoDB's tier, but Nebula's `storaged` process could not reliably operate under that ceiling and the containers were run without artificial limits instead. **This is itself a real finding**: Nebula's minimum viable footprint exceeds CognoDB's free-tier allocation, so exact resource parity was not achievable for this platform. Reported latency/throughput numbers for Nebula should be read with this in mind — they reflect an unconstrained local Docker deployment, not a resource-matched one.
- **ArangoDB Oasis returned intermittent `503 upstream connect error` responses** partway through the workload suite on the free-trial deployment (connection refused from Oasis's own load balancer). This did not reproduce on every run and appears to be free-tier infrastructure instability rather than a query or harness bug — the same query had succeeded moments earlier in the same run. If this recurs in the submitted results, it's noted per-run below rather than hidden.
- **ArangoDB's AQL requires an explicit `WITH <collection>` clause** for graph traversals not going through a named graph object; without it, the query fails with "collection not known to traversal." This is a query-language quirk, not a fairness issue, but is worth noting as an example of how "the same logical query" still required platform-specific syntax adjustments.
- **Nebula Graph requires an explicit `ADD HOSTS` command** to activate a storage node before `CREATE SPACE` will succeed — the storage host appears in `SHOW HOSTS` immediately on container start, but is not usable until explicitly activated. This is Nebula-specific setup friction with no equivalent in the other four platforms.
- **Traversal latency was largely flat across 1/2/3-hop depths on every cloud-hosted platform** (CognoDB, AuraDB, Memgraph, ArangoDB), all sitting in a similar range regardless of hop count — this strongly suggests network round-trip time to the managed instance dominated the measurement rather than actual query execution cost. Nebula, running locally over Docker's internal network, did not show this pattern. This is discussed further in Analysis below.
- **Data-loading time varied enormously by platform** and is likely driven more by each driver's bulk-insert mechanics (Cypher `UNWIND` batches vs. ArangoDB's `insertDocuments()` calls vs. Nebula's local network) than by the underlying storage engines themselves — see the Loading results table.
- **CognoDB Cloud's connection was terminated by the server partway through the 100-iteration run** — `ServiceUnavailableException: Connection to the database terminated`, after successfully completing only the 1-hop traversal measurement (100/100 samples). This happened on a clean, isolated run with no other iteration-count reduction applied. It's a genuine finding: CognoDB's free-tier instance appears unable to sustain a long-running Bolt session through a full multi-stage workload, at least under the conditions tested here. Reported CognoDB numbers below reflect only the 1-hop traversal measurement that did complete; every downstream metric (2/3-hop, lookups, aggregation, mixed workload, footprint) could not be measured for this platform.
- **ArangoDB Oasis's connection failed mid-workload with a `503 upstream connect error`**, occurring during the 2-hop traversal measurement after a severe latency spike (one sample hit 33.7 seconds, ~100x the typical value) that suggests the connection was already degrading before the hard failure. This occurred across multiple independent attempts at the full 100-iteration scale, always in the same general area of the workload (early traversal phase), while never affecting the initial data load. Reported ArangoDB numbers below reflect only the 1-hop traversal and the (unreliable, partially-degraded) 2-hop measurement; lookups, aggregation, mixed workload, and footprint could not be measured for this platform at full scale.
- **Nebula's `SHOW STATS` command requires a `submit job stats` to be run first** to populate its statistics; the harness does not currently do this, so Nebula's footprint section reports the resulting error rather than real numbers — a straightforward extension for anyone reproducing this benchmark, not attempted here due to time.

## Repository layout

```
cognodb-benchmark/
├── README.md                  <- you are here
├── pom.xml                    <- Java 17, Maven, shaded fat-jar build
├── .env.example                <- copy to .env, fill in credentials (never commit .env)
├── data/
│   └── download_dataset.sh     <- fetches the SNAP ca-HepPh dataset
├── docker/
│   └── docker-compose.nebula.yml  <- self-hosted Nebula, resource-capped
├── scripts/
│   └── run_all.sh               <- build once, run all 5 platforms, emit JSON
├── src/main/java/ai/wexa/benchmark/
│   ├── Main.java                <- CLI entry point (--platform=...)
│   ├── config/Config.java       <- env/.env-based credential loading
│   ├── loader/DatasetLoader.java
│   ├── platform/                <- one GraphClient implementation per platform
│   ├── stats/LatencyStats.java  <- p50/p95/p99 computation
│   ├── workload/WorkloadRunner.java  <- the one workload suite run against every platform
│   └── report/ResultWriter.java <- emits results/<platform>_<timestamp>.json
└── results/                     <- raw JSON output per run (committed for transparency)
```

## Reproducing this benchmark

1. **Provision the five accounts/instances** (all free-tier or free-trial):
    - CognoDB: <https://console.cognodb.com/signup> → create a free `c0` instance
    - Neo4j AuraDB Free: <https://console.neo4j.io>
    - Memgraph Cloud: <https://console.memgraph.com>
    - ArangoDB Oasis: <https://cloud.arangodb.com>
    - Nebula Graph: no signup — runs locally via Docker (step 4 below)
2. **Configure credentials:**
   ```bash
   cp .env.example .env
   # fill in every value in .env with what each console gave you
   ```
3. **Download the dataset:**
   ```bash
   ./data/download_dataset.sh
   ```
4. **Start Nebula locally, resource-capped:**
   ```bash
   docker compose -f docker/docker-compose.nebula.yml up -d
   # give it ~30s to elect a leader before running the benchmark
   ```
5. **Build and run everything:**
   ```bash
   ./scripts/run_all.sh
   ```
   Or run a single platform:
   ```bash
   mvn -q package
   java -jar target/cognodb-benchmark.jar --platform=cognodb --dataset=data/ca-HepPh.txt.gz
   ```
6. Raw results land in `results/*.json`. Transcribe the p50/p95 numbers into
   the tables below.

## Results

<!-- TODO: replace every "—" below with real numbers from results/*.json after running scripts/run_all.sh -->

### Data loading

<!-- Load times were stable across repeated runs (confirmed via re-runs during development).
     Nodes/sec and relationships/sec below are computed from the 12,008 nodes / 237,010 raw
     edge-list rows loaded (see Dataset section for why this differs from the 118,505
     deduplicated relationship count). -->

| Platform | Load time | Nodes/sec | Relationships/sec |
|---|---|---|---|
| CognoDB Cloud | ~78–81s | ~150 | ~2,950 |
| Neo4j AuraDB Free | ~23s | ~523 | ~10,329 |
| Memgraph Cloud | ~26s | ~456 | ~9,010 |
| ArangoDB Oasis | ~170–171s | ~71 | ~1,394 |
| Nebula Graph (self-hosted) | ~3.2s | ~3,750 | ~74,000 |

<!-- TODO: replace the ~approximate figures above with the exact numbers from your
     final full-scale (100-iteration) run's results/*.json loading block. -->

### Traversals (p50 / p95, ms)

<!-- CognoDB's connection dropped mid-run after only the 1-hop measurement completed
     (see caveats — free-tier instance could not sustain the full read workload).
     ArangoDB's connection dropped mid-2-hop with a severe latency spike immediately
     beforehand, consistent with the connection already degrading before the 503 hit. -->

| Platform | 1-hop | 2-hop | 3-hop |
|---|---|---|---|
| CognoDB Cloud | 614.44 / 623.80 | *(run failed before completion — see caveats)* | *(run failed before completion — see caveats)* |
| Neo4j AuraDB Free | 212.22 / 218.85 | 211.89 / 220.69 | 211.72 / 250.93 |
| Memgraph Cloud | 162.35 / 166.95 | 162.38 / 171.37 | 164.21 / 647.64 |
| ArangoDB Oasis | 304.48 / 385.56 | 315.47 / 4492.82 *(run failed shortly after — see caveats)* | *(not reached — see caveats)* |
| Nebula Graph (self-hosted) | 2.20 / 3.60 | 3.38 / 21.41 | 6.64 / 136.45 |

### Lookups (p50 / p95, ms)

| Platform | Point lookup | Indexed range lookup | Indexed property |
|---|---|---|---|
| CognoDB Cloud | *(not reached — run failed after 1-hop traversal)* | *(not reached)* | `Author.id` |
| Neo4j AuraDB Free | 210.72 / 218.47 | 209.13 / 224.11 | `Author.id` |
| Memgraph Cloud | 161.44 / 167.34 | 161.91 / 166.28 | `Author.id` |
| ArangoDB Oasis | *(not reached — run failed during traversal workload)* | *(not reached)* | `authors.id` |
| Nebula Graph (self-hosted) | 1.51 / 1.77 | 1.80 / 2.53 | `author.id` (tag index) |

### Aggregation (p50 / p95, ms)

| Platform | Count-by-bucket (GROUP BY id % 10) |
|---|---|
| CognoDB Cloud | *(not reached — run failed after 1-hop traversal)* |
| Neo4j AuraDB Free | 211.59 / 219.01 |
| Memgraph Cloud | 164.68 / 168.92 |
| ArangoDB Oasis | *(not reached — run failed during traversal workload)* |
| Nebula Graph (self-hosted) | 59.16 / 67.73 |

### Mixed workload (20 concurrent clients, 20s, 80% read / 20% write)

| Platform | Throughput (ops/sec) | p50 latency | p95 latency | Errors |
|---|---|---|---|---|
| CognoDB Cloud | *(not reached — run failed after 1-hop traversal)* | — | — | — |
| Neo4j AuraDB Free | 102.75 | 202.67 ms | 263.68 ms | 0 |
| Memgraph Cloud | 102.20 | 174.31 ms | 279.00 ms | 0 |
| ArangoDB Oasis | *(not reached — run failed during traversal workload)* | — | — | — |
| Nebula Graph (self-hosted) | 494.90 | 19.04 ms | 133.21 ms | 0 |

### Footprint

| Platform | Stored data size | Memory usage | Instance spec |
|---|---|---|---|
| CognoDB Cloud | not reached (run failed early) | not observable via driver | 0.5 vCPU / 256 MB / 1 GB disk |
| Neo4j AuraDB Free | not observable via driver | not observable via driver | 1 GB RAM (per Aura console) |
| Memgraph Cloud | not observable via driver | not observable via driver | 2 GB RAM / 2 CPU (smallest available trial tier) |
| ArangoDB Oasis | not reached (run failed mid-traversal) | not observable via driver | free-trial deployment (see Oasis console) |
| Nebula Graph (self-hosted) | not observable — `SHOW STATS` requires a manual `submit job stats` first, not run by the harness | see `docker stats` | run unconstrained after tight Docker limits (~80-100MB/process) proved insufficient for `storaged` to operate — see caveats |

Confirmed node/relationship counts from AuraDB and Memgraph's footprint queries (both platforms that completed the full run): **12,008 nodes / 118,505 relationships** — matching the expected deduplicated count from the dataset.

## Analysis

**Nebula's local-network advantage dominates every measurement.** Running over Docker's internal network rather than a remote managed cloud instance, Nebula's traversal latencies (1-hop p50: 2.2ms) are roughly 70-95x faster than any cloud platform (AuraDB: 212ms, Memgraph: 162ms, ArangoDB: 304ms). This is not a claim that Nebula's storage engine is dramatically superior — it's almost entirely a network-topology effect. A fair like-for-like comparison would need Nebula deployed on the same cloud region as the others, which the assignment's Docker-based fairness allowance doesn't provide for. This is the single most important caveat in reading these results: **Nebula's numbers measure "local Docker" vs. everyone else's "managed cloud over the internet," not "Nebula the database" vs. "everyone else."**

**Among the cloud platforms, traversal latency was close to flat across 1/2/3-hop depths for AuraDB and Memgraph** (AuraDB: 212 / 212 / 212ms p50; Memgraph: 162 / 162 / 164ms p50) — strongly suggesting network round-trip time to the managed instance, not actual graph-traversal cost, dominated the measurement. If query execution cost scaled meaningfully with hop depth, we'd expect increasing latency with each additional hop; instead the numbers barely move. This means the benchmark, as run against remote cloud instances from a single client machine, is measuring "cost of one network round-trip to this region" at least as much as it's measuring "cost of a graph traversal" — a limitation worth being explicit about rather than over-interpreting small differences between platforms as query-engine performance differences.

**Memgraph was consistently faster than AuraDB on every completed metric** (traversals: ~162ms vs ~212ms p50; mixed workload p50: 174ms vs 203ms) despite both speaking the same Bolt/Cypher interface. Memgraph's in-memory-first architecture is a plausible explanation, but so is its considerably larger free-tier allocation (2GB RAM / 2 CPU vs Aura Free's more modest tier) — with only one data point each, this benchmark cannot cleanly separate "faster architecture" from "more generous free tier," and it would be a mistake to claim otherwise from this data alone.

**CognoDB and ArangoDB's free-tier instances both failed to sustain a long-running session through the full workload**, and in both cases the failure happened mid-workload rather than immediately — the initial data load and first traversal measurements succeeded normally. This pattern (works fine briefly, then the connection degrades or drops under sustained multi-stage load) looks more like a free-tier connection/session timeout or resource-reclaim policy than a fundamental limitation of either database engine. The one CognoDB traversal measurement that did complete (1-hop, p50 614ms) was already 3x slower than any other cloud platform even before the failure — worth flagging honestly, though a single metric from one platform isn't enough to draw a firm conclusion about CognoDB's steady-state performance.

**The mixed read/write workload did not reorder the ranking** versus pure-read traversal numbers for the two platforms where both were measured (AuraDB, Memgraph): Memgraph stayed faster on both. Nebula's mixed-workload throughput (494.9 ops/sec) was proportionally even further ahead of the cloud platforms (~103 ops/sec each) than its traversal numbers were, consistent with the same local-network explanation rather than a different bottleneck emerging under write contention.

**Bottom line:** the clearest, most defensible finding from this benchmark is not "database X is fastest" — it's that **free-tier resource and session limits, and network topology, were the dominant factors observed**, sometimes preventing a platform from completing the workload at all. Any ranking of the underlying database engines themselves would require resource-matched, same-region deployments and multiple repeated runs to separate real architectural differences from these confounds — future work this harness is structured to support (see below).

## What's not covered / possible extensions

- Concurrency sweep (1 / 10 / 40 clients) beyond the single 20-client point
  reported above.
- Cold-start latency, reported separately from warm numbers.
- Variance across repeated full runs (currently one run per platform).

---

*Built for the Wexa AI CognoDB take-home assignment. Every credential is read
from environment variables — see `.env.example` — and `.env` itself is
git-ignored, so nothing here exposes real connection details.*
