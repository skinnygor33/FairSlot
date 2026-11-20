package it.unipi.dsmt.fairslot.web.catalog;

import java.util.List;
import java.util.Optional;

/** Read-only list of the bookable resources. */
public interface ResourceCatalog {

    List<Resource> all();

    default Optional<Resource> find(String id) {
        return all().stream().filter(r -> r.id().equals(id)).findFirst();
    }
}
