/*
 * Copyright (C) 2014-2017 OpenKeeper
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
package toniarts.openkeeper.game.task.objective;

import com.badlogic.gdx.ai.pfa.GraphPath;
import com.jme3.math.Vector2f;
import java.util.Iterator;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.map.MapTile;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.TaskType;
import toniarts.openkeeper.tools.convert.map.Terrain;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * Kill player objective for those goodly heroes. Can create a complex set of
 * tasks
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class KillPlayer extends AbstractObjectiveTask {

    protected final short targetPlayerId;
    protected final ICreatureController creature;
    protected final ILevelInfo levelInfo;

    public KillPlayer(final INavigationService navigationService, final IMapController mapController, final ILevelInfo levelInfo, short targetPlayerId, ICreatureController creature) {
        super(navigationService, mapController, levelInfo.getPlayer(targetPlayerId).getDungeonHeartLocation().x - 2, levelInfo.getPlayer(targetPlayerId).getDungeonHeartLocation().y - 2, creature.getOwnerId());

        this.targetPlayerId = targetPlayerId;
        this.creature = creature;
        this.levelInfo = levelInfo;
        createSubTasks();
    }

    @Override
    public boolean isValid(ICreatureController creature) {
        if (!isPlayerDestroyed() && creature != null && !getTaskQueue().isEmpty()) {

            // Check that the objectives are still the same
            return Thing.HeroParty.Objective.KILL_PLAYER.equals(creature.getObjective()) && Short.valueOf(targetPlayerId).equals(creature.getObjectiveTargetPlayerId());
        }
        return !isPlayerDestroyed() && !getTaskQueue().isEmpty();
    }

    @Override
    public Vector2f getTarget(ICreatureController creature) {
        return WorldUtils.pointToVector2f(getTaskLocation()); // FIXME 0.5f not needed?
    }

    @Override
    public void executeTask(ICreatureController creature, float executionDuration) {

    }

    private boolean isPlayerDestroyed() {
        return levelInfo.getPlayer(targetPlayerId).isDestroyed();
    }

    private void createSubTasks() {

        // See if we can navigate there
        GraphPath<MapTile> outPath = navigationService.findPath(creature.getCreatureCoordinates(), getTaskLocation(), creature.getParty() != null ? creature.getParty() : creature);
        if (outPath != null) {
            Iterator<MapTile> iter = outPath.iterator();
            MapTile lastPoint = null;
            boolean first = true;
            int i = 0;
            while (iter.hasNext()) {
                MapTile tile = iter.next();
                if (!navigationService.isAccessible(lastPoint, tile, creature)) {

                    // Add task to last accessible point
                    if (i != 1 && first && lastPoint != null) {
                        addSubTask(new ObjectiveTaskDecorator(getId(), new GoToTask(navigationService, mapController, lastPoint.getX(), lastPoint.getY(), playerId)));
                        first = false;
                    }

                    // See if we should dig
                    Terrain terrain = levelInfo.getLevelData().getTerrain(tile.getTerrainId());
                    if (terrain.getFlags().contains(Terrain.TerrainFlag.SOLID) && (creature.isWorker() || (creature.getParty() != null && creature.getParty().isWorkersAvailable())) && (terrain.getFlags().contains(Terrain.TerrainFlag.DWARF_CAN_DIG_THROUGH) || terrain.getFlags().contains(Terrain.TerrainFlag.ATTACKABLE))) {
                        addSubTask(new ObjectiveTaskDecorator(getId(), new ObjectiveDigTileTask(navigationService, mapController, tile.getX(), tile.getY(), playerId)) {

                            @Override
                            public boolean isWorkerPartyTask() {
                                return true;
                            }

                        });
                        addSubTask(new ObjectiveTaskDecorator(getId(), new GoToTask(navigationService, mapController, tile.getX(), tile.getY(), playerId)));
                    } else {

                        // Hmm, this is it, should we have like attack target type tasks? Or let the AI just figure out itself
                        return;
                    }
                } else {
                    first = true;
                }
                lastPoint = tile;
                i++;
            }
        }
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.KILL_PLAYER;
    }

}
