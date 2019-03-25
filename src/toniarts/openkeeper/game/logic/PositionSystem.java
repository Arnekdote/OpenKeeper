/*
 * Copyright (C) 2014-2018 OpenKeeper
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
package toniarts.openkeeper.game.logic;

import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import toniarts.openkeeper.game.component.DoorComponent;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.IDoorsController;
import toniarts.openkeeper.game.controller.IEntityWrapper;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.door.IDoorController;
import toniarts.openkeeper.game.map.MapTile;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * Maintains a tile based position map of all the entities for a quick lookup
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class PositionSystem implements IGameLogicUpdatable, IEntityPositionLookup {

    private final EntityData entityData;
    private final IMapController mapController;
    private final EntitySet positionedEntities;
    private final Map<MapTile, Set<EntityId>> entitiesByMapTile = new HashMap<>();
    private final Map<MapTile, Set<EntityId>> obstaclesByMapTile = new HashMap<>();
    private final Map<EntityId, MapTile> mapTilesByEntities = new HashMap<>();
    private final Map<Class, IEntityWrapper<?>> entityWrappers = new HashMap<>();

    public PositionSystem(IMapController mapController, EntityData entityData, ICreaturesController creaturesController, IDoorsController doorsController) {
        this.entityData = entityData;
        this.mapController = mapController;
        entityWrappers.put(ICreatureController.class, creaturesController);
        entityWrappers.put(IDoorController.class, doorsController);

        positionedEntities = entityData.getEntities(Position.class);
        processAddedEntities(positionedEntities);
    }

    @Override
    public void processTick(float tpf, double gameTime) {
        if (positionedEntities.applyChanges()) {

            processAddedEntities(positionedEntities.getAddedEntities());

            processDeletedEntities(positionedEntities.getRemovedEntities());

            processChangedEntities(positionedEntities.getChangedEntities());
        }
    }

    private void processChangedEntities(Set<Entity> entities) {

        // Update
        for (Entity entity : entities) {
            Point p = WorldUtils.vectorToPoint(entity.get(Position.class).position);
            MapTile currentMapTile = mapController.getMapData().getTile(p);

            MapTile previousMapTile = mapTilesByEntities.get(entity.getId());
            if (!currentMapTile.equals(previousMapTile)) {

                // Moved
                mapTilesByEntities.put(entity.getId(), currentMapTile);
                entitiesByMapTile.get(previousMapTile).remove(entity.getId());

                // Obstacles
                if (obstaclesByMapTile.containsKey(previousMapTile)) {
                    obstaclesByMapTile.get(previousMapTile).remove(entity.getId());
                }

                addEntityToTile(currentMapTile, entity);
            }
        }
    }

    private void addEntityToTile(MapTile mapTile, Entity entity) {
        Set<EntityId> entitiesInTile = entitiesByMapTile.get(mapTile);
        if (entitiesInTile == null) {
            entitiesInTile = new HashSet<>();
        }
        entitiesInTile.add(entity.getId());
        entitiesByMapTile.put(mapTile, entitiesInTile);

        // Obstacles
        if (isObstacle(entityData, entity.getId())) {
            Set<EntityId> obstaclesInTile = entitiesByMapTile.get(mapTile);
            if (obstaclesInTile == null) {
                obstaclesInTile = new HashSet<>();
            }
            obstaclesInTile.add(entity.getId());
            obstaclesByMapTile.put(mapTile, obstaclesInTile);
        }
    }

    private void processDeletedEntities(Set<Entity> entities) {

        // Remove
        for (Entity entity : entities) {
            MapTile mapTile = mapTilesByEntities.remove(entity.getId());
            entitiesByMapTile.get(mapTile).remove(entity.getId());
            if (obstaclesByMapTile.containsKey(mapTile)) {
                obstaclesByMapTile.get(mapTile).remove(entity.getId());
            }
        }
    }

    private void processAddedEntities(Set<Entity> entities) {

        // Add
        for (Entity entity : entities) {
            Point p = WorldUtils.vectorToPoint(entity.get(Position.class).position);
            MapTile mapTile = mapController.getMapData().getTile(p);
            mapTilesByEntities.put(entity.getId(), mapTile);

            addEntityToTile(mapTile, entity);
        }
    }

    @Override
    public List<EntityId> getEntitiesInLocation(Point p) {
        MapTile mapTile = mapController.getMapData().getTile(p);
        return getEntitiesInLocation(mapTile);
    }

    @Override
    public List<EntityId> getEntitiesInLocation(int x, int y) {
        MapTile mapTile = mapController.getMapData().getTile(x, y);
        return getEntitiesInLocation(mapTile);
    }

    @Override
    public List<EntityId> getEntitiesInLocation(MapTile mapTile) {
        Set<EntityId> entityId = entitiesByMapTile.get(mapTile);
        if (entityId != null) {
            return new ArrayList<>(entityId);
        }

        return Collections.emptyList();
    }

    @Override
    public MapTile getEntityLocation(EntityId entityId) {
        return mapTilesByEntities.get(entityId);
    }

    @Override
    public <T> List<T> getEntityTypesInLocation(Point p, Class<T> clazz) {
        MapTile mapTile = mapController.getMapData().getTile(p);
        return getEntityTypesInLocation(mapTile, clazz);
    }

    @Override
    public <T> List<T> getEntityTypesInLocation(int x, int y, Class<T> clazz) {
        MapTile mapTile = mapController.getMapData().getTile(x, y);
        return getEntityTypesInLocation(mapTile, clazz);
    }

    @Override
    public <T> List<T> getEntityTypesInLocation(MapTile mapTile, Class<T> clazz) {
        Set<EntityId> entityIds = entitiesByMapTile.get(mapTile);
        if (entityIds != null) {
            IEntityWrapper<T> entityWrapper = (IEntityWrapper<T>) entityWrappers.get(clazz);
            if (entityWrapper == null) {
                throw new RuntimeException("No entity wrappers registered with type " + clazz + "!");
            }
            List<T> entities = new ArrayList<>(entityIds.size());
            for (EntityId entityId : new ArrayList<>(entityIds)) {
                if (entityWrapper.isValidEntity(entityId)) {
                    entities.add(entityWrapper.createController(entityId));
                }
            }
            return entities;
        }

        return Collections.emptyList();
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        positionedEntities.release();
        entitiesByMapTile.clear();
        mapTilesByEntities.clear();
    }

    private static boolean isObstacle(EntityData entityData, EntityId id) {

        // Objects have solid obstacle property, but that in my opinion doesn't block the whole tile
        // More of a physics thingie that...
        // So only doors here now...
        DoorComponent doorComponent = entityData.getComponent(id, DoorComponent.class);
        if (doorComponent != null && !doorComponent.blueprint) {
            return true;
        }

        return false;
    }

    @Override
    public boolean isTileBlocked(Point p, short playerId) {
        MapTile mapTile = mapController.getMapData().getTile(p);
        return isTileBlocked(mapTile, playerId);
    }

    @Override
    public boolean isTileBlocked(int x, int y, short playerId) {
        MapTile mapTile = mapController.getMapData().getTile(x, y);
        return isTileBlocked(mapTile, playerId);
    }

    @Override
    public boolean isTileBlocked(MapTile mapTile, short playerId) {
        Set<EntityId> entityIds = obstaclesByMapTile.get(mapTile);
        if (entityIds != null) {
            for (EntityId entityId : entityIds) {
                DoorComponent doorComponent = entityData.getComponent(entityId, DoorComponent.class);
                if (doorComponent != null) {
                    if (doorComponent.locked) {
                        return true;
                    }
                    Owner owner = entityData.getComponent(entityId, Owner.class);
                    return owner == null || owner.ownerId != playerId;
                }
            }
        }

        return false;
    }

}
