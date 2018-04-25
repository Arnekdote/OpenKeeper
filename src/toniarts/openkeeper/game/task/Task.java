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
package toniarts.openkeeper.game.task;

import com.jme3.math.Vector2f;
import java.awt.Point;
import java.util.Date;
import toniarts.openkeeper.tools.convert.map.ArtResource;
import toniarts.openkeeper.world.creature.CreatureControl;

/**
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public interface Task {

    /**
     * Assing an entity to the task
     *
     * @param creature entity to be assigned
     * @param setToCreature set the task to creature right away, typically yes
     */
    void assign(CreatureControl creature, boolean setToCreature);

    /**
     * Can the entity be assigned to this task
     *
     * @param creature the tested entity
     * @return returns tru if the entity can be assigned to the task
     */
    boolean canAssign(CreatureControl creature);

    /**
     * Execute task!
     *
     * @param creature creature executing the task
     */
    void executeTask(CreatureControl creature);

    /**
     * How many workers have already been assigned to this task
     *
     * @return number of assignees on duty
     */
    int getAssigneeCount();

    /**
     * Amount of assignees this task can be assigned on
     *
     * @return max number of assignees
     */
    int getMaxAllowedNumberOfAsignees();

    /**
     * Task priority, added to distance when evaluating tasks to give out. The
     * bigger the number, the less urgent the task is
     *
     * @return task priority
     */
    int getPriority();

    /**
     * Get the target coordinates to navigate to for accomplishing the task
     *
     * @param creature who wants to know?
     * @return the target coordinates
     */
    Vector2f getTarget(CreatureControl creature);

    /**
     * Get the animation used for the task. Might be null if no animation is
     * tied to the task, sufficient to have the creature visit the location
     *
     * @param creature executing the task
     * @return the animation
     */
    ArtResource getTaskAnimation(CreatureControl creature);

    Date getTaskCreated();

    /**
     * The task icon for unit flowers
     *
     * @return the path t the icon
     */
    String getTaskIcon();

    /**
     * Task location, the task it self not necessarily the target for navigating
     *
     * @return the task location
     */
    Point getTaskLocation();

    /**
     * Get the task tooltip
     *
     * @return the task tooltip
     */
    String getTooltip();

    /**
     * Should the creature face the task it is doing
     *
     * @return true to face the task
     */
    boolean isFaceTarget();

    /**
     * Is the task reachable by the given creature. Ask this last if determining
     * validity etc. As the method might be heavy
     *
     * @param creature the creature trying to reach this
     * @return is the task reachable
     */
    boolean isReachable(CreatureControl creature);

    /**
     * Evaluates the task validity
     *
     * @param creature who wants to know? Maybe null if testing for general
     * validity
     * @return the task validity
     */
    boolean isValid(CreatureControl creature);

    /**
     * Unassing a creature from the job. A place for doing some cleanup
     *
     * @param creature
     */
    void unassign(CreatureControl creature);

    /**
     * Evaluates the task validity, in a way that is it valid ever again and
     * should it be removed from any task queue
     *
     * @return should the task be removed
     */
    boolean isRemovable();

}
