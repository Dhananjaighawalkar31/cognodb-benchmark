package ai.wexa.benchmark.platform;

import ai.wexa.benchmark.model.Edge;
import org.neo4j.driver.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Base class for every platform that speaks Bolt + Cypher: CognoDB, Neo4j
 * AuraDB and Memgraph all use the official Neo4j Java driver, since that's
 * exactly what the assignment says CognoDB itself expects ("connect with an
 * official Neo4j driver"). Only the constructor (URI/credentials) and
 * platform name differ between subclasses — see CognoDbClient, AuraDbClient,
 * MemgraphClient.
 */
public abstract class BoltGraphClient implements GraphClient {

    protected final String uri;
    protected final String user;
    protected final String password;
    protected Driver driver;

    protected BoltGraphClient(String uri, String user, String password) {
        this.uri = uri;
        this.user = user;
        this.password = password;
    }

    @Override
    public void connect() {
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        driver.verifyConnectivity();
    }

    @Override
    public void clearData() {
        try (Session s = driver.session()) {
            // Batch delete to avoid one giant transaction on small free-tier instances.
            long deleted;
            do {
                deleted = s.run("MATCH (n:Author) WITH n LIMIT 10000 DETACH DELETE n RETURN count(n) AS c")
                        .single().get("c").asLong();
            } while (deleted > 0);
        }
    }

    @Override
    public void ensureIndexes() {
        try (Session s = driver.session()) {
            s.run("CREATE INDEX author_id_idx IF NOT EXISTS FOR (a:Author) ON (a.id)").consume();
        }
    }

    @Override
    public long loadGraph(List<Long> nodeIds, List<Edge> edges, int batchSize) {
        long start = System.nanoTime();
        try (Session s = driver.session()) {
            for (int i = 0; i < nodeIds.size(); i += batchSize) {
                List<Long> batch = nodeIds.subList(i, Math.min(i + batchSize, nodeIds.size()));
                s.run("UNWIND $ids AS id MERGE (:Author {id: id})", Map.of("ids", batch)).consume();
            }
            for (int i = 0; i < edges.size(); i += batchSize) {
                List<Edge> batch = edges.subList(i, Math.min(i + batchSize, edges.size()));
                List<Map<String, Object>> rows = batch.stream()
                        .map(e -> Map.<String, Object>of("src", e.src(), "dst", e.dst()))
                        .toList();
                s.run("""
                        UNWIND $rows AS row
                        MATCH (a:Author {id: row.src}), (b:Author {id: row.dst})
                        MERGE (a)-[:COAUTHOR]-(b)
                        """, Map.of("rows", rows)).consume();
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long traverse(long startNodeId, int hops) {
        StringBuilder cypher = new StringBuilder("MATCH (start:Author {id: $id})");
        cypher.append("-[:COAUTHOR*").append(hops).append("..").append(hops).append("]-(end:Author) RETURN count(DISTINCT end) AS c");
        try (Session s = driver.session()) {
            return s.run(cypher.toString(), Map.of("id", startNodeId)).single().get("c").asLong();
        }
    }

    @Override
    public boolean pointLookup(long nodeId) {
        try (Session s = driver.session()) {
            return s.run("MATCH (a:Author {id: $id}) RETURN a.id AS id", Map.of("id", nodeId)).hasNext();
        }
    }

    @Override
    public long indexedRangeLookup(long lowId, long highId) {
        try (Session s = driver.session()) {
            return s.run("MATCH (a:Author) WHERE a.id >= $lo AND a.id <= $hi RETURN count(a) AS c",
                    Map.of("lo", lowId, "hi", highId)).single().get("c").asLong();
        }
    }

    @Override
    public long aggregationCountByBucket() {
        try (Session s = driver.session()) {
            return s.run("MATCH (a:Author) RETURN a.id % 10 AS bucket, count(*) AS c ORDER BY bucket")
                    .list().size();
        }
    }

    @Override
    public void mixedWorkloadUnit(long readId, long writeSeed) {
        try (Session s = driver.session()) {
            s.run("MATCH (a:Author {id: $id}) RETURN a.id", Map.of("id", readId)).consume();
            s.run("MERGE (a:Author {id: $id}) SET a.lastTouched = $seed",
                    Map.of("id", readId, "seed", writeSeed)).consume();
        }
    }

    @Override
    public Map<String, Object> footprint() {
        Map<String, Object> m = new LinkedHashMap<>();
        try (Session s = driver.session()) {
            long nodes = s.run("MATCH (a:Author) RETURN count(a) AS c").single().get("c").asLong();
            long rels = s.run("MATCH ()-[r:COAUTHOR]-() RETURN count(r) AS c").single().get("c").asLong() / 2;
            m.put("node_count", nodes);
            m.put("relationship_count", rels);
            m.put("note", "Stored data size / memory usage: check the platform's own console — "
                    + "not exposed via Cypher on most managed tiers.");
        } catch (Exception e) {
            m.put("note", "footprint query failed: " + e.getMessage());
        }
        return m;
    }

    @Override
    public void close() {
        if (driver != null) driver.close();
    }
}
