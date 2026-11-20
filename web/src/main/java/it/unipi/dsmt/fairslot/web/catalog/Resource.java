package it.unipi.dsmt.fairslot.web.catalog;

/**
 * A bookable room or piece of equipment, as shown in the UI. The booking state of its
 * slots lives in the Erlang back-end; this is only the descriptive catalog.
 *
 * @param id       contract ID, e.g. {@code room:B1-101} or {@code equip:proj-03}
 * @param kind     {@code room} or {@code equipment}
 * @param building building code for rooms, equipment pool name for equipment
 */
public record Resource(String id, String name, String kind, String building, int capacity) { }
