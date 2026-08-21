package ai.wexa.benchmark.platform;

import ai.wexa.benchmark.config.Config;
import ai.wexa.benchmark.model.Edge;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;

import java.util.*;

/**
 * Nebula Graph, self-hosted via docker/docker-compose.nebula.yml with CPU/RAM
 * limits set to match CognoDB's free tier (0.5 vCPU / 256 MB) — see the
 * fairness note in the assignment: "self-hosted deployments capped to the
 * same resources are fine."
 *
 * Space: benchmark(vid_type=INT64). Tag: author(id int64). Edge: coauthor().
 *
 * NOTE: nGQL syntax and the exact NebulaPool/Session API can drift slightly
 * between client versions — cross-check against docs.nebula-graph.io for the
 * pinned version in pom.xml before your first real run, and be ready to
 * defend any tweaks in the interview (the assignment explicitly allows AI
 * assistance but expects you to understand every part).
 */
public class NebulaClient implements GraphClient {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String space;
    private NebulaPool pool;
    private Session session;

    public NebulaClient() {
        this.host = Config.require("NEBULA_HOST");
        this.port = Config.getInt("NEBULA_PORT", 9669);
        this.user = Config.getOrDefault("NEBULA_USER", "root");
        this.password = Config.getOrDefault("NEBULA_PASSWORD", "nebula");
        this.space = Config.getOrDefault("NEBULA_SPACE", "benchmark");
    }

    @Override
    public String platformName() {
        return "Nebula Graph (self-hosted, Docker, capped to CognoDB free-tier resources)";
    }

    @Override
    public void connect() {
        try {
            List<HostAddress> addresses = List.of(new HostAddress(host, port));
            NebulaPoolConfig cfg = new NebulaPoolConfig();
            cfg.setMaxConnSize(20);
            pool = new NebulaPool();
            pool.init(addresses, cfg);
            session = pool.getSession(user, password, false);

            activateStorageHost();
            createSpaceWithRetry();
            waitForSpaceReady();
            exec("USE " + space);
            exec("CREATE TAG IF NOT EXISTS author(id int64)");
            exec("CREATE EDGE IF NOT EXISTS coauthor()");
            sleep(6000);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect/init Nebula: " + e.getMessage(), e);
        }
    }

    private void activateStorageHost() throws Exception {
        for (int i = 0; i < 30; i++) {
            ResultSet show = session.execute("SHOW HOSTS");
            if (show.isSucceeded() && show.getRows() != null && !show.getRows().isEmpty()) {
                break;
            }
            sleep(2000);
        }
        try {
            session.execute("ADD HOSTS \"storaged\":9779");
        } catch (Exception ignored) {
            // Already added, or transient — CREATE SPACE retry below will surface any real problem.
        }
        sleep(3000);
    }

    /**
     * "CREATE SPACE" fails with "Host not enough!" if storaged hasn't yet
     * completed its heartbeat-based registration with metad. Poll SHOW HOSTS
     * until at least one storage host shows up (up to ~90s) before creating
     * the space.
     */

    private void createSpaceWithRetry() throws Exception {
        String createSpaceNgql = "CREATE SPACE IF NOT EXISTS " + space
                + "(partition_num=1, replica_factor=1, vid_type=INT64)";
        for (int i = 0; i < 10; i++) {
            ResultSet rs = session.execute(createSpaceNgql);
            if (rs.isSucceeded()) return;
            if (rs.getErrorMessage() != null && rs.getErrorMessage().contains("Host not enough")) {
                sleep(3000);
                continue;
            }
            throw new RuntimeException("nGQL failed [" + createSpaceNgql + "]: " + rs.getErrorMessage());
        }
        throw new RuntimeException("Gave up creating Nebula space after repeated 'Host not enough' errors — "
                + "storaged may not be registering with metad. Check `docker compose logs storaged`.");
    }

    private void waitForSpaceReady() throws Exception {
        for (int i = 0; i < 15; i++) {
            ResultSet rs = session.execute("USE " + space);
            if (rs.isSucceeded()) return;
            sleep(1000);
        }
    }

    private ResultSet exec(String ngql) {
        try {
            ResultSet rs = session.execute(ngql);
            if (!rs.isSucceeded()) {
                throw new RuntimeException("nGQL failed [" + ngql + "]: " + rs.getErrorMessage());
            }
            return rs;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }




    @Override
    public void clearData() {
        exec("CLEAR SPACE " + space);
        sleep(2000);
    }

    @Override
    public void ensureIndexes() {
        exec("CREATE TAG INDEX IF NOT EXISTS author_id_index ON author(id)");
        RuntimeException last = null;
        for (int i = 0; i < 10; i++) {
            try {
                exec("REBUILD TAG INDEX author_id_index");
                last = null;
                break;
            } catch (RuntimeException e) {
                last = e;
                sleep(2000);
            }
        }
        if (last != null) throw last;
        sleep(3000);
    }

    @Override
    public long loadGraph(List<Long> nodeIds, List<Edge> edges, int batchSize) {
        long start = System.nanoTime();
        for (int i = 0; i < nodeIds.size(); i += batchSize) {
            List<Long> batch = nodeIds.subList(i, Math.min(i + batchSize, nodeIds.size()));
            StringBuilder sb = new StringBuilder("INSERT VERTEX author(id) VALUES ");
            for (int j = 0; j < batch.size(); j++) {
                long id = batch.get(j);
                sb.append(id).append(":(").append(id).append(")");
                if (j < batch.size() - 1) sb.append(", ");
            }
            exec(sb.toString());
        }
        for (int i = 0; i < edges.size(); i += batchSize) {
            List<Edge> batch = edges.subList(i, Math.min(i + batchSize, edges.size()));
            StringBuilder sb = new StringBuilder("INSERT EDGE coauthor() VALUES ");
            for (int j = 0; j < batch.size(); j++) {
                Edge e = batch.get(j);
                sb.append(e.src()).append("->").append(e.dst()).append(":()");
                if (j < batch.size() - 1) sb.append(", ");
            }
            exec(sb.toString());
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public long traverse(long startNodeId, int hops) {
        String ngql = "GO " + hops + " STEPS FROM " + startNodeId
                + " OVER coauthor BIDIRECT YIELD DISTINCT id($$) AS vid";
        ResultSet rs = exec(ngql);
        return rs.getRows().size();
    }

    @Override
    public boolean pointLookup(long nodeId) {
        ResultSet rs = exec("FETCH PROP ON author " + nodeId + " YIELD author.id");
        return rs.getRows() != null && !rs.getRows().isEmpty();
    }

    @Override
    public long indexedRangeLookup(long lowId, long highId) {
        String ngql = "LOOKUP ON author WHERE author.id >= " + lowId + " AND author.id <= " + highId
                + " YIELD COUNT(*) AS c";
        // Nebula's LOOKUP doesn't support COUNT(*) directly in all versions; fall back to a plain LOOKUP + row count.
        try {
            ResultSet rs = exec("LOOKUP ON author WHERE author.id >= " + lowId + " AND author.id <= " + highId
                    + " YIELD author.id");
            return rs.getRows().size();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public long aggregationCountByBucket() {
        // nGQL's aggregation pipe syntax varies by version; a pragmatic approach is a full scan
        // via a MATCH statement (openCypher subset, Nebula 3.x) grouping by id % 10.
        try {
            ResultSet rs = exec("MATCH (a:author) RETURN a.id % 10 AS bucket, count(*) AS c ORDER BY bucket");
            return rs.getRows().size();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void mixedWorkloadUnit(long readId, long writeSeed) {
        exec("FETCH PROP ON author " + readId + " YIELD author.id");
        exec("INSERT VERTEX author(id) VALUES " + readId + ":(" + readId + ")"); // upsert-by-overwrite
    }

    @Override
    public Map<String, Object> footprint() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            ResultSet rs = exec("SHOW STATS");
            m.put("show_stats_row_count", rs.getRows().size());
            m.put("note", "Parse `SHOW STATS` output for exact vertex/edge counts; "
                    + "disk footprint is visible via `du` on the Docker volume, recorded manually in README.");
        } catch (Exception e) {
            m.put("note", "footprint query failed: " + e.getMessage());
        }
        return m;
    }

    @Override
    public void close() {
        if (session != null) session.release();
        if (pool != null) pool.close();
    }
}
