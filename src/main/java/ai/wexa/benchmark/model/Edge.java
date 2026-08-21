package ai.wexa.benchmark.model;

/** One row of the SNAP edge list: an undirected collaboration edge between two author IDs. */
public record Edge(long src, long dst) {
}
