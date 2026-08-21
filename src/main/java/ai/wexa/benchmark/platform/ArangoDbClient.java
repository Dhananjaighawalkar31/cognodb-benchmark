package ai.wexa.benchmark.platform;

import ai.wexa.benchmark.config.Config;
import ai.wexa.benchmark.model.Edge;

import com.arangodb.ArangoCollection;
import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.entity.BaseDocument;
import com.arangodb.entity.BaseEdgeDocument;
import com.arangodb.model.PersistentIndexOptions;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ArangoDbClient implements GraphClient {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String dbName;

    private ArangoDB arangoDB;
    private ArangoDatabase db;

    public ArangoDbClient() {
        this.host = Config.require("ARANGO_HOST");
        this.port = Config.getInt("ARANGO_PORT", 8529);
        this.user = Config.getOrDefault("ARANGO_USER", "root");
        this.password = Config.require("ARANGO_PASSWORD");
        this.dbName = Config.getOrDefault("ARANGO_DB", "benchmark");
    }

    @Override
    public String platformName() {
        return "ArangoDB Oasis (free trial)";
    }

    @Override
    public void connect() {

        arangoDB = new ArangoDB.Builder()
                .host(host, port)
                .user(user)
                .password(password)
                .useSsl(true)
                .build();

        if (!arangoDB.db(dbName).exists()) {
            arangoDB.createDatabase(dbName);
        }

        db = arangoDB.db(dbName);

        if (!db.collection("authors").exists()) {
            db.createCollection("authors");
        }

        if (!db.collection("coauthor").exists()) {
            db.createCollection(
                    "coauthor",
                    new com.arangodb.model.CollectionCreateOptions()
                            .type(com.arangodb.entity.CollectionType.EDGES)
            );
        }
    }

    @Override
    public void clearData() {
        db.collection("authors").truncate();
        db.collection("coauthor").truncate();
    }

    @Override
    public void ensureIndexes() {
        db.collection("authors")
                .ensurePersistentIndex(
                        List.of("id"),
                        new PersistentIndexOptions()
                );
    }

    @Override
    public long loadGraph(
            List<Long> nodeIds,
            List<Edge> edges,
            int batchSize
    ) {

        long start = System.nanoTime();

        ArangoCollection authors = db.collection("authors");
        ArangoCollection coauthor = db.collection("coauthor");

        // Insert authors in batches
        for (int i = 0; i < nodeIds.size(); i += batchSize) {

            List<Long> batch = nodeIds.subList(
                    i,
                    Math.min(i + batchSize, nodeIds.size())
            );

            List<BaseDocument> docs = batch.stream()
                    .map(id -> {
                        BaseDocument document =
                                new BaseDocument(String.valueOf(id));

                        document.addAttribute("id", id);

                        return document;
                    })
                    .collect(Collectors.toList());

            authors.insertDocuments(docs);
        }

        // Insert edges in batches
        for (int i = 0; i < edges.size(); i += batchSize) {

            List<Edge> batch = edges.subList(
                    i,
                    Math.min(i + batchSize, edges.size())
            );

            List<BaseEdgeDocument> docs = batch.stream()
                    .map(edge -> new BaseEdgeDocument(
                            "authors/" + edge.src(),
                            "authors/" + edge.dst()
                    ))
                    .collect(Collectors.toList());

            coauthor.insertDocuments(docs);
        }

        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long traverse(long startNodeId, int hops) {

        // NOTE: the leading "WITH authors" is required by ArangoDB's AQL
        // query planner for graph traversals that don't go through a named
        // graph object — without it, the planner can't infer which document
        // collection the traversed vertices belong to, and the query fails
        // with "collection not known to traversal".
        String aql =
                "WITH authors " +
                        "FOR v IN " +
                        hops +
                        ".." +
                        hops +
                        " ANY @start coauthor " +
                        "RETURN DISTINCT v._key";

        Map<String, Object> bind =
                Map.of(
                        "start",
                        "authors/" + startNodeId
                );

        try (ArangoCursor<String> cursor =
                     db.query(aql, String.class, bind)) {

            long count = 0;

            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }

            return count;

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to close ArangoDB cursor during traversal",
                    e
            );
        }
    }

    @Override
    public boolean pointLookup(long nodeId) {

        return db.collection("authors")
                .documentExists(String.valueOf(nodeId));
    }

    @Override
    public long indexedRangeLookup(
            long lowId,
            long highId
    ) {

        String aql =
                "FOR a IN authors " +
                        "FILTER a.id >= @lo AND a.id <= @hi " +
                        "COLLECT WITH COUNT INTO c " +
                        "RETURN c";

        try (ArangoCursor<Long> cursor =
                     db.query(
                             aql,
                             Long.class,
                             Map.of(
                                     "lo", lowId,
                                     "hi", highId
                             )
                     )) {

            return cursor.hasNext()
                    ? cursor.next()
                    : 0;

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to close ArangoDB cursor during range lookup",
                    e
            );
        }
    }

    @Override
    public long aggregationCountByBucket() {

        String aql =
                "FOR a IN authors " +
                        "COLLECT bucket = a.id % 10 WITH COUNT INTO c " +
                        "RETURN {bucket, c}";

        try (ArangoCursor<Object> cursor =
                     db.query(aql, Object.class)) {

            long groups = 0;

            while (cursor.hasNext()) {
                cursor.next();
                groups++;
            }

            return groups;

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to close ArangoDB cursor during aggregation",
                    e
            );
        }
    }

    @Override
    public void mixedWorkloadUnit(
            long readId,
            long writeSeed
    ) {

        db.collection("authors")
                .getDocument(
                        String.valueOf(readId),
                        BaseDocument.class
                );

        String aql =
                "UPSERT { _key: @key } " +
                        "INSERT { " +
                        "_key: @key, " +
                        "id: @id, " +
                        "lastTouched: @seed " +
                        "} " +
                        "UPDATE { lastTouched: @seed } " +
                        "IN authors";

        try (ArangoCursor<Void> cursor =
                     db.query(
                             aql,
                             Void.class,
                             Map.of(
                                     "key",
                                     String.valueOf(readId),
                                     "id",
                                     readId,
                                     "seed",
                                     writeSeed
                             )
                     )) {

            while (cursor.hasNext()) {
                cursor.next();
            }

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to close ArangoDB cursor during mixed workload",
                    e
            );
        }
    }

    @Override
    public Map<String, Object> footprint() {

        Map<String, Object> result =
                new LinkedHashMap<>();

        try {

            result.put(
                    "node_count",
                    db.collection("authors")
                            .count()
                            .getCount()
            );

            result.put(
                    "edge_count",
                    db.collection("coauthor")
                            .count()
                            .getCount()
            );

            result.put(
                    "note",
                    "Figure size (bytes) is visible in the Oasis console "
                            + "under Collections; not reliably exposed "
                            + "through the Java driver on the free tier."
            );

        } catch (Exception e) {

            result.put(
                    "note",
                    "Footprint query failed: "
                            + e.getMessage()
            );
        }

        return result;
    }

    @Override
    public void close() {

        if (arangoDB != null) {
            arangoDB.shutdown();
        }
    }
}