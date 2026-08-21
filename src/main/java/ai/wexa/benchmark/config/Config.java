package ai.wexa.benchmark.config;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Every credential is read from the environment (or a local .env file that is
 * git-ignored — see .env.example). Nothing here is ever hard-coded, per the
 * assignment's "no passwords/URIs in the repo" rule.
 */
public final class Config {

    private static final Dotenv DOTENV = Dotenv.configure()
            .ignoreIfMissing()
            .ignoreIfMalformed()
            .load();

    private Config() {}

    public static String get(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = DOTENV.get(key);
        return v;
    }

    public static String getOrDefault(String key, String def) {
        String v = get(key);
        return v == null || v.isBlank() ? def : v;
    }

    public static String require(String key) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key +
                    " (set it in your shell or in a local .env file — see .env.example)");
        }
        return v;
    }

    public static int getInt(String key, int def) {
        String v = get(key);
        return v == null ? def : Integer.parseInt(v.trim());
    }
}
