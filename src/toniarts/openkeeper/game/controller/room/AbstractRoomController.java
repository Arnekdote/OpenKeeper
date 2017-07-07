/*
 * Copyright (C) 2014-2015 OpenKeeper
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
package toniarts.openkeeper.game.controller.room;

import com.jme3.scene.Node;
import java.awt.Point;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import toniarts.openkeeper.common.RoomInstance;
import toniarts.openkeeper.game.controller.IObjectsController;
import toniarts.openkeeper.tools.convert.map.Room;
import toniarts.openkeeper.world.object.ObjectControl;
import toniarts.openkeeper.world.room.control.RoomObjectControl;

/**
 * Base class for all rooms
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public abstract class AbstractRoomController implements IRoomController {

    /**
     * The type of object the room houses
     */
    public enum ObjectType {

        GOLD, LAIR, SPELL_BOOK, RESEARCHER, PRISONER, TORTUREE;

    };

    /**
     * How the objects are laid out, there is always a 1 tile margin from the
     * sides. I don't know where this information really is, so I hardcoded it.
     */
    public enum RoomObjectLayout {

        /**
         * Allow side-to-side, every tile
         */
        ALLOW_NEIGHBOUR,
        /**
         * Allow a neighbouring object diagonally
         */
        ALLOW_DIAGONAL_NEIGHBOUR_ONLY,
        /**
         * No touching!
         */
        ISOLATED;
    }

    protected final RoomInstance roomInstance;
    private ObjectType defaultObjectType;
    private final Map<ObjectType, RoomObjectControl> objectControls = new HashMap<>();
    protected boolean destroyed = false;
    protected boolean[][] map;
    protected Point start;
    protected final IObjectsController objectsController;
    private final Set<ObjectControl> floorFurniture = new HashSet<>();
    private final Set<ObjectControl> wallFurniture = new HashSet<>();


    public AbstractRoomController(RoomInstance roomInstance, IObjectsController objectsController) {
        this.roomInstance = roomInstance;
        this.objectsController = objectsController;
    }

    protected void setupCoordinates() {
        map = roomInstance.getCoordinatesAsMatrix();
        start = roomInstance.getMatrixStartPoint();
    }

    @Override
    public void construct() {
        setupCoordinates();

        // Construct the room objects
        constructObjects();
        if (hasPillars()) {
            constructPillars();
        }
    }

    private boolean hasPillars() {
        return getPillarObject(roomInstance.getEntity().getRoomId()) != null;
    }

    /**
     * Construct room pillars. Info in:
     * https://github.com/tonihele/OpenKeeper/issues/116
     */
    protected void constructPillars() {
        // TODO: Maybe replace with something similar than the object placement ENUM, there are only few different scenarios of contructing the pillars
    }

    /**
     * Construct room objects
     */
    protected void constructObjects() {
        floorFurniture.clear();
        wallFurniture.clear();

        // Floor objects 0-2
        Room room = roomInstance.getRoom();
        int index = -1;
        Node objects = new Node();
        if (room.getObjects().get(0) > 0 || room.getObjects().get(1) > 0 || room.getObjects().get(2) > 0) {

            // Object map
            boolean[][] objectMap = new boolean[map.length][map[0].length];

            for (int x = 0; x < map.length; x++) {
                for (int y = 0; y < map[x].length; y++) {

                    // Skip non-room tiles
                    if (!map[x][y]) {
                        continue;
                    }

                    // See neighbouring tiles
                    boolean N = hasSameTile(map, x, y - 1);
                    boolean NE = hasSameTile(map, x + 1, y - 1);
                    boolean E = hasSameTile(map, x + 1, y);
                    boolean SE = hasSameTile(map, x + 1, y + 1);
                    boolean S = hasSameTile(map, x, y + 1);
                    boolean SW = hasSameTile(map, x - 1, y + 1);
                    boolean W = hasSameTile(map, x - 1, y);
                    boolean NW = hasSameTile(map, x - 1, y - 1);

                    if (N && NE && E && SE && S && SW && W && NW) {

                        // Building options
                        N = hasSameTile(objectMap, x, y - 1);
                        NE = hasSameTile(objectMap, x + 1, y - 1);
                        E = hasSameTile(objectMap, x + 1, y);
                        SE = hasSameTile(objectMap, x + 1, y + 1);
                        S = hasSameTile(objectMap, x, y + 1);
                        SW = hasSameTile(objectMap, x - 1, y + 1);
                        W = hasSameTile(objectMap, x - 1, y);
                        NW = hasSameTile(objectMap, x - 1, y - 1);
                        if (getRoomObjectLayout() == RoomObjectLayout.ALLOW_DIAGONAL_NEIGHBOUR_ONLY
                                && (N || E || S || W)) {
                            continue;
                        }
                        if (getRoomObjectLayout() == RoomObjectLayout.ISOLATED
                                && (N || E || S || W || NE || SE || SW || NW)) {
                            continue;
                        }
                        do {
                            if (index > 1) {
                                index = -1;
                            }
                            index++;
                        } while (room.getObjects().get(index) == 0);

                        // Add object
                        objectMap[x][y] = true;
                        objectsController.loadObject(room.getObjects().get(index), (short) 0, start.x + x, start.y + y);
                    }
                }
            }
        }

        // Wall objects 3-5
        if (room.getObjects().get(3) > 0 || room.getObjects().get(4) > 0 || room.getObjects().get(5) > 0) {

        }
    }

    protected static boolean hasSameTile(boolean[][] map, int x, int y) {

        // Check for out of bounds
        if (x < 0 || x >= map.length || y < 0 || y >= map[x].length) {
            return false;
        }
        return map[x][y];
    }

    protected RoomObjectLayout getRoomObjectLayout() {
        return RoomObjectLayout.ALLOW_NEIGHBOUR;
    }

    public boolean isTileAccessible(Integer fromX, Integer fromY, int toX, int toY) {
        return true;
    }

    public final boolean isTileAccessible(Point from, Point to) {
        return isTileAccessible(from != null ? from.x : null, (from != null ? from.y : null), to.x, to.y);
    }

    protected final void addObjectControl(RoomObjectControl control) {
//        objectControls.put(control.getObjectType(), control);
//        if (defaultObjectType == null) {
//            defaultObjectType = control.getObjectType();
//        }
    }

    public boolean hasObjectControl(ObjectType objectType) {
        return objectControls.containsKey(objectType);
    }

    public <T extends RoomObjectControl> T getObjectControl(ObjectType objectType) {
        return (T) objectControls.get(objectType);
    }

    /**
     * Destroy the room, marks the room as destroyed and releases all the
     * controls. The room <strong>should not</strong> be used after this.
     */
    public void destroy() {
        destroyed = true;

        // Destroy the controls
        for (RoomObjectControl control : objectControls.values()) {
            control.destroy();
        }
    }

    /**
     * Is this room instance destroyed? Not in the world anymore.
     *
     * @see #destroy()
     * @return is the room destroyed
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Can store gold to the room?
     *
     * @return can store gold
     */
    public boolean canStoreGold() {
        return hasObjectControl(ObjectType.GOLD);
    }

    /**
     * Get room max capacity
     *
     * @return room max capacity
     */
    protected int getMaxCapacity() {
        RoomObjectControl control = getDefaultRoomObjectControl();
        if (control != null) {
            return control.getMaxCapacity();
        }
        return 0;
    }

    /**
     * Get used capacity
     *
     * @return the used capacity of the room
     */
    protected int getUsedCapacity() {
        RoomObjectControl control = getDefaultRoomObjectControl();
        if (control != null) {
            return control.getCurrentCapacity();
        }
        return 0;
    }

    private RoomObjectControl getDefaultRoomObjectControl() {
        if (defaultObjectType != null) {
            return objectControls.get(defaultObjectType);
        }
        return null;
    }

    public RoomInstance getRoomInstance() {
        return roomInstance;
    }

    /**
     * Is the room at full capacity
     *
     * @return max capacity used
     */
    public boolean isFullCapacity() {
        return getUsedCapacity() >= getMaxCapacity();
    }

    /**
     * Get the room type
     *
     * @return the room type
     */
    public Room getRoom() {
        return roomInstance.getRoom();
    }

    /**
     * Are we the dungeon heart?
     *
     * @return are we?
     */
    public boolean isDungeonHeart() {
        return false;
    }

    /**
     * Get the total number of furniture in room
     *
     * @return furniture count
     */
    public int getFurnitureCount() {
        return wallFurniture.size() + floorFurniture.size();
    }

    /**
     * Get the number of floor furniture in a room
     *
     * @return floor furniture count
     */
    public int getFloorFurnitureCount() {
        return floorFurniture.size();
    }

    /**
     * Get the number of wall furniture in a room
     *
     * @return wall furniture count
     */
    public int getWallFurnitureCount() {
        return wallFurniture.size();
    }

    public Set<ObjectControl> getFloorFurniture() {
        return floorFurniture;
    }

    public Set<ObjectControl> getWallFurniture() {
        return wallFurniture;
    }

    /**
     * Get the object ID for the room pillars
     *
     * @param roomId the room ID
     * @return the object ID for the room pillar or {@code null} if not found
     */
    protected final static Short getPillarObject(short roomId) {

        // FIXME: Is this data available somewhere??
        switch (roomId) {
            case 1:  // Treasury
                return 76;
            case 2:  // Lair
                return 77;
            case 4:  // Hatchery
                return 78;
            case 10:  // Workshop
                return 80;
            case 11:  // Prison
                return 81;
            case 12:  // Torture
                return 82;
            case 13:  // Temple
                return 83;
            case 14: // Graveyard
                return 84;
            case 15: // Casino
                return 85;
            case 16: // Pit
                return 79;
            case 26: // Crypt
                return 141;
            default:
                return null; // No pillars
        }
    }
}
