package ai.wexa.benchmark.platform;

import ai.wexa.benchmark.model.Edge;

import java.util.List;

/**
 * A single, platform-agnostic surface that the loader and workload runner
 * drive. Every platform implementation (CognoDB, AuraDB, Memgraph, ArangoDB,
 * Nebula) does the same logical operations, in its own query language, so the
 * workload harness never needs to know which platform it's talking to.
 *
 * Data model is deliberately minimal: nodes are "Author" vertices identified
 * by a numeric id property, edges are undirected "COAUTHOR" relationships
 * (this matches the ca-HepPh collaboration-network dataset — see
 * data/download_dataset.sh). If you swap in a different dataset, only the
 * label/relationship-type names need to change, in one place per client.
 */
public interface GraphClient extends AutoCloseable {

    String platformName();

    /** Open the connection / session pool. Throws on auth or connectivity failure. */
    void connect();

    /** Best-effort wipe so re-runs start from a clean graph. */
    void clearData();

    /** Create whatever index the platform needs on the id property before loading. */
    void ensureIndexes();

    /**
     * Bulk-load nodes then edges using whichever batching approach is
     * idiomatic for the platform (UNWIND batches for Bolt/Cypher stores, AQL
     * bulk import for ArangoDB, INSERT VERTEX/EDGE batches for Nebula).
     * Returns wall-clock milliseconds for the whole load.
     */
    long loadGraph(List<Long> nodeIds, List<Edge> edges, int batchSize);

    /** N-hop traversal outward from a single start node id. Returns result size (for sanity, not correctness). */
    long traverse(long startNodeId, int hops);

    /** Single point lookup by id (uses the index from ensureIndexes()). */
    boolean pointLookup(long nodeId);

    /** Filtered/indexed lookup, e.g. authors with id in a numeric range. */
    long indexedRangeLookup(long lowId, long highId);

    /** Aggregation: count of nodes grouped by a derived bucket (id % 10), returns number of groups seen. */
    long aggregationCountByBucket();

    /** One mixed read/write unit of work for the concurrent workload: a read plus a small write. */
    void mixedWorkloadUnit(long readId, long writeSeed);

    /**
     * Whatever storage/footprint info the platform's API exposes (e.g. stored
     * size, node/edge counts). Return an empty map + note if not observable.
     */
    java.util.Map<String, Object> footprint();
}
