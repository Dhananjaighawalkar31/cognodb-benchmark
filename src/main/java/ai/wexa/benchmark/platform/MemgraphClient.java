package ai.wexa.benchmark.platform;

import ai.wexa.benchmark.config.Config;

public class MemgraphClient extends BoltGraphClient {

    public MemgraphClient() {
        super(Config.require("MEMGRAPH_URI"), Config.getOrDefault("MEMGRAPH_USER", ""), Config.getOrDefault("MEMGRAPH_PASSWORD", ""));
    }

    @Override
    public String platformName() {
        return "Memgraph Cloud (free)";
    }

    @Override
    public void connect() {
        org.neo4j.driver.Config driverConfig = org.neo4j.driver.Config.builder()
                .withTrustStrategy(org.neo4j.driver.Config.TrustStrategy.trustAllCertificates())
                .withEncryption()
                .build();
        driver = org.neo4j.driver.GraphDatabase.driver(
                uri, org.neo4j.driver.AuthTokens.basic(user, password), driverConfig);
        driver.verifyConnectivity();
    }

    @Override
    public void ensureIndexes() {
        try {
            super.ensureIndexes();
        } catch (Exception e) {
            try (var s = driver.session()) {
                s.run("CREATE INDEX ON :Author(id)").consume();
            }
        }
    }
}