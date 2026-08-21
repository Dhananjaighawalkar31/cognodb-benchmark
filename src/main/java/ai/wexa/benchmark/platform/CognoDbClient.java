package ai.wexa.benchmark.platform;

import ai.wexa.benchmark.config.Config;

/**
 * CognoDB Cloud — connection details come from the free-tier console
 * (console.cognodb.com). URI looks like
 * bolt+s://<instance-id>.databases.cognodb.cloud, user is always "cognodb".
 */
public class CognoDbClient extends BoltGraphClient {

    public CognoDbClient() {
        super(Config.require("COGNODB_URI"), "cognodb", Config.require("COGNODB_PASSWORD"));
    }

    @Override
    public String platformName() {
        return "CognoDB Cloud (free c0)";
    }
}
