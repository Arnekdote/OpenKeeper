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
package toniarts.openkeeper.game.task.worker;

import com.jme3.math.Vector2f;
import toniarts.openkeeper.game.task.AbstractTileTask;
import toniarts.openkeeper.tools.convert.map.ArtResource;
import toniarts.openkeeper.utils.WorldUtils;
import toniarts.openkeeper.world.WorldState;
import toniarts.openkeeper.world.creature.CreatureControl;

/**
 * A task for creatures to rescue a fellow comrade from the harsh world
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class CarryCreatureToLairTask extends AbstractTileTask {

    private final CreatureControl creature;
    private boolean executed = false;

    public CarryCreatureToLairTask(WorldState worldState, CreatureControl creature, short playerId) {
        super(worldState, creature.getLairLocation().x, creature.getLairLocation().y, playerId);
        this.creature = creature;
    }

    @Override
    public Vector2f getTarget(CreatureControl creature) {
        return WorldUtils.pointToVector2f(getTaskLocation()); // FIXME 0.5f not needed?
    }

    @Override
    public boolean isValid(CreatureControl creature) {
        return !executed && this.creature.hasLair() && this.creature.isDragged();
    }

    @Override
    public String toString() {
        return "Carrying creature to its lair at " + getTaskLocation();
    }

    @Override
    protected String getStringId() {
        return "2619";
    }

    @Override
    public void executeTask(CreatureControl creature) {
        this.creature.sleepUntilHealed();
        executed = true;
    }

    @Override
    public ArtResource getTaskAnimation(CreatureControl creature) {
        return null;
    }

    @Override
    public String getTaskIcon() {
        return "Textures/GUI/moods/SJ-Take_Crate.png";
    }

    @Override
    public void unassign(CreatureControl creature) {
        super.unassign(creature);

        // Set the dragged state
        creature.setHaulable(null);
    }

}
