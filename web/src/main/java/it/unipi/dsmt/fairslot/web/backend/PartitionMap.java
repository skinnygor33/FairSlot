package it.unipi.dsmt.fairslot.web.backend;

import java.util.Map;

/**
 * Which Erlang node owns which resource (contract §4). Fetched from the cluster at
 * start-up and again after any routing error.
 *
 * @param partitions partition name -> node name
 * @param resources  resource ID -> partition name
 */
public record PartitionMap(long epoch, String coordinator, Map<String, String> partitions,
                           Map<String, String> resources) {

    public PartitionMap {
        partitions = Map.copyOf(partitions);
        resources = Map.copyOf(resources);
    }

    /**
     * Node that owns the resource; the coordinator when the resource is unknown or the op
     * has no resource (bundles, quota). Before the coordinator exists (week 1), a single
     * node answers with itself as coordinator.
     */
    public String nodeFor(String resourceId) {
        if (resourceId != null) {
            String partition = resources.get(resourceId);
            String node = partition == null ? null : partitions.get(partition);
            if (node != null) {
                return node;
            }
        }
        return coordinator;
    }

    @SuppressWarnings("unchecked")
    static PartitionMap fromReply(Map<String, Object> m) {
        return new PartitionMap(((Number) m.get("epoch")).longValue(), (String) m.get("coordinator"),
                (Map<String, String>) (Map<?, ?>) m.getOrDefault("partitions", Map.of()),
                (Map<String, String>) (Map<?, ?>) m.getOrDefault("resources", Map.of()));
    }
}
