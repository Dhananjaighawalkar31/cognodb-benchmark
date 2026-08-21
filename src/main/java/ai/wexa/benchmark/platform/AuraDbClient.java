package ai.wexa.benchmark.platform;

import ai.wexa.benchmark.config.Config;

/**
 * Neo4j AuraDB Free — create at console.neo4j.io, "AuraDB Free" instance.
 * Free tier spec (documented for README): 1 GB RAM / shared vCPU / 8 GB storage
 * as of instance creation — confirm current numbers in the console when you
 * provision, since Neo4j has changed the free tier limits before, and record
 * exactly what you got in README.md's environment table.
 */
public class AuraDbClient extends BoltGraphClient {

    public AuraDbClient() {
        super(Config.require("AURA_URI"), Config.getOrDefault("AURA_USER", "neo4j"), Config.require("AURA_PASSWORD"));
    }

    @Override
    public String platformName() {
        return "Neo4j AuraDB Free";
    }
}
