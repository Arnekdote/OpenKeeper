/*
 * Copyright (C) 2014-2016 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.player;

import com.jme3.app.Application;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import toniarts.openkeeper.tools.convert.map.Room;
import toniarts.openkeeper.world.listener.RoomListener;
import toniarts.openkeeper.world.room.GenericRoom;
import toniarts.openkeeper.world.room.RoomInstance;

/**
 * Holds a list of player rooms and functionality related to them
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class PlayerRoomControl extends AbstractPlayerControl<Room, Set<GenericRoom>> implements RoomListener {

    private int roomCount = 0;
    private boolean portalsOpen = true;
    private List<IRoomAvailabilityListener> roomAvailabilityListeners;
    private GenericRoom dungeonHeart;

    public PlayerRoomControl(Application application) {
        super(application);
    }

    public void init(List<Map.Entry<RoomInstance, GenericRoom>> rooms) {
        for (Map.Entry<RoomInstance, GenericRoom> entry : rooms) {
            onBuild(entry.getValue());
        }
    }

    @Override
    public void setTypeAvailable(Room type, boolean available) {

        // Skip non-buildables, I don't know what purpose they serve
        if (!type.getFlags().contains(Room.RoomFlag.BUILDABLE)) {
            return;
        }

        super.setTypeAvailable(type, available);

        // Notify listeners
        if (roomAvailabilityListeners != null) {
            application.enqueue(() -> {
                for (IRoomAvailabilityListener listener : roomAvailabilityListeners) {
                    listener.onChange();
                }
            });
        }
    }

    @Override
    public void onBuild(GenericRoom room) {

        // Add to the list
        Set<GenericRoom> roomSet = get(room.getRoom());
        if (roomSet == null) {
            roomSet = new LinkedHashSet<>();
            put(room.getRoom(), roomSet);
        }
        roomSet.add(room);
        roomCount++;
        if (dungeonHeart == null && room.isDungeonHeart()) {
            dungeonHeart = room;
        }
    }

    @Override
    public void onCaptured(GenericRoom room) {
        onBuild(room);
    }

    @Override
    public void onCapturedByEnemy(GenericRoom room) {
        onSold(room);
    }

    @Override
    public void onSold(GenericRoom room) {

        // Delete
        Set<GenericRoom> roomSet = get(room.getRoom());
        if (roomSet != null) {
            roomSet.remove(room);
            roomCount--;
        }
    }

    /**
     * Get player room count. Even the non-buildables.
     *
     * @return the room count
     */
    @Override
    public int getTypeCount() {
        return roomCount;
    }

    public boolean isPortalsOpen() {
        return portalsOpen;
    }

    public void setPortalsOpen(boolean portalsOpen) {
        this.portalsOpen = portalsOpen;
    }

    /**
     * Get room slab count, all rooms
     *
     * @return slab count
     */
    public int getRoomSlabsCount() {
        int count = 0;
        if (!types.isEmpty()) {
            for (Room room : new ArrayList<>(types.keySet())) {
                Set<GenericRoom> rooms = get(room);
                if (!rooms.isEmpty()) {
                    for (GenericRoom genericRoom : new ArrayList<>(rooms)) {
                        count += genericRoom.getRoomInstance().getCoordinates().size();
                    }
                }
            }
        }
        return count;
    }

    /**
     * Get room slab count, certain type of room
     *
     * @param room the room
     * @return slab count
     */
    public int getRoomSlabsCount(Room room) {
        int count = 0;
        Set<GenericRoom> rooms = get(room);
        if (rooms != null && !rooms.isEmpty()) {
            for (GenericRoom genericRoom : new ArrayList<>(rooms)) {
                count += genericRoom.getRoomInstance().getCoordinates().size();
            }
        }
        return count;
    }

    /**
     * Listen to room availability changes
     *
     * @param listener the listener
     */
    public void addRoomAvailabilityListener(IRoomAvailabilityListener listener) {
        if (roomAvailabilityListeners == null) {
            roomAvailabilityListeners = new ArrayList<>();
        }
        roomAvailabilityListeners.add(listener);
    }

    /**
     * Returns the dungeon heart of the player
     *
     * @return the dungeon heart
     */
    public GenericRoom getDungeonHeart() {
        return dungeonHeart;
    }

    /**
     * A small interface for getting notified about room availability changes
     */
    public interface IRoomAvailabilityListener {

        public void onChange();

    }

}
