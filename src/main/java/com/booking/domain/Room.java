package com.booking.domain;

import java.util.UUID;

public class Room {
    private final UUID id;
    private String name;
    private int capacity;

    // Deactivate instead of delete — matches the FK decision on the ER
    // diagram: Room -> Meeting is ON DELETE RESTRICT, not CASCADE. A room
    // with booking history should never be hard-deleted; it gets marked
    // inactive so it stops appearing in "book a room" flows but existing
    // Meeting rows referencing it stay intact.
    private boolean active;

    public Room(UUID id, String name, int capacity) {
        this.id = id;
        this.name = name;
        this.capacity = capacity;
        this.active = true; // rooms are bookable by default when created
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}