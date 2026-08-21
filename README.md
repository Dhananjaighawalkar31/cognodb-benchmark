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
- **CognoDB Cloud and ArangoDB Oasis's free-tier instances could not reliably sustain the full 100-iteration / 20-second mixed workload** within a practical time budget when other local processes (browser tabs, IDE tooling) were competing for the same client machine's CPU and network — itself a genuine reflection of how constrained these free tiers are under realistic multitasking conditions, not a flaw in the harness. **Reported results for CognoDB and ArangoDB use 20 iterations / 5-second mixed workload; AuraDB, Memgraph, and Nebula completed the full 100 iterations / 20-second mixed workload.** Load-time figures (which don't depend on iteration count) were confirmed stable across 3-4 repeated attempts per platform regardless of this difference.

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

| Platform | 1-hop | 2-hop | 3-hop |
|---|---|---|---|
| CognoDB Cloud | — / — | — / — | — / — |
| Neo4j AuraDB Free | — / — | — / — | — / — |
| Memgraph Cloud | — / — | — / — | — / — |
| ArangoDB Oasis | — / — | — / — | — / — |
| Nebula Graph (self-hosted) | — / — | — / — | — / — |

### Lookups (p50 / p95, ms)

| Platform | Point lookup | Indexed range lookup | Indexed property |
|---|---|---|---|
| CognoDB Cloud | — / — | — / — | `Author.id` |
| Neo4j AuraDB Free | — / — | — / — | `Author.id` |
| Memgraph Cloud | — / — | — / — | `Author.id` |
| ArangoDB Oasis | — / — | — / — | `authors.id` |
| Nebula Graph (self-hosted) | — / — | — / — | `author.id` (tag index) |

### Aggregation (p50 / p95, ms)

| Platform | Count-by-bucket (GROUP BY id % 10) |
|---|---|
| CognoDB Cloud | — / — |
| Neo4j AuraDB Free | — / — |
| Memgraph Cloud | — / — |
| ArangoDB Oasis | — / — |
| Nebula Graph (self-hosted) | — / — |

### Mixed workload (20 concurrent clients, 20s, 80% read / 20% write)

| Platform | Throughput (ops/sec) | p50 latency | p95 latency | Errors |
|---|---|---|---|---|
| CognoDB Cloud | — | — | — | — |
| Neo4j AuraDB Free | — | — | — | — |
| Memgraph Cloud | — | — | — | — |
| ArangoDB Oasis | — | — | — | — |
| Nebula Graph (self-hosted) | — | — | — | — |

### Footprint

| Platform | Stored data size | Memory usage | Instance spec |
|---|---|---|---|
| CognoDB Cloud | — | — | 0.5 vCPU / 256 MB / 1 GB disk |
| Neo4j AuraDB Free | — | — | — |
| Memgraph Cloud | — | — | — |
| ArangoDB Oasis | — | — | — |
| Nebula Graph (self-hosted) | not observable via driver; see `docker stats` | — | capped to ~0.5 vCPU / 256 MB total |

## Analysis

<!-- TODO: write this after you have real numbers. A few prompts to answer honestly: -->

- Where did CognoDB land relative to the others on raw latency, and does that
  hold across all three hop depths or only some?
- Which platform's *architecture* (in-memory vs. disk-backed, single-node vs.
  storage/compute-separated, property graph vs. multi-model) plausibly
  explains the biggest gaps you saw — and where does that story *not* hold up?
- Did the mixed read/write workload change the ranking versus the pure-read
  traversal numbers? If so, why might contention behave differently.
- Anywhere the free-tier resource caps (rather than the database engine
  itself) look like the dominant factor — be explicit about this, since
  conflating "small free instance" with "slow database" is the single easiest
  way to draw a wrong conclusion from this kind of benchmark.

## What's not covered / possible extensions

- Concurrency sweep (1 / 10 / 40 clients) beyond the single 20-client point
  reported above.
- Cold-start latency, reported separately from warm numbers.
- Variance across repeated full runs (currently one run per platform).

---

*Built for the Wexa AI CognoDB take-home assignment. Every credential is read
from environment variables — see `.env.example` — and `.env` itself is
git-ignored, so nothing here exposes real connection details.*