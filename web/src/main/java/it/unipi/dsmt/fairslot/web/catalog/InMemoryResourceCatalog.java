package it.unipi.dsmt.fairslot.web.catalog;

import java.util.List;

/** Fixed demo catalog: two buildings of rooms and a shared equipment pool. */
public class InMemoryResourceCatalog implements ResourceCatalog {

    private static final List<Resource> DEMO = List.of(
            new Resource("room:B1-101", "Study room B1-101", "room", "B1", 6),
            new Resource("room:B1-102", "Study room B1-102", "room", "B1", 4),
            new Resource("room:B2-201", "Group room B2-201", "room", "B2", 10),
            new Resource("room:B2-202", "Group room B2-202", "room", "B2", 8),
            new Resource("equip:proj-01", "Projector 01", "equipment", "pool", 1),
            new Resource("equip:proj-02", "Projector 02", "equipment", "pool", 1),
            new Resource("equip:ws-01", "Lab workstation 01", "equipment", "pool", 1));

    @Override
    public List<Resource> all() {
        return DEMO;
    }
}
