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
package toniarts.openkeeper.game.controller.room;

import toniarts.openkeeper.world.room.*;
import com.jme3.asset.AssetManager;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable.MiscType;
import toniarts.openkeeper.world.WorldState;
import toniarts.openkeeper.world.effect.EffectManagerState;
import toniarts.openkeeper.world.object.ObjectLoader;
import toniarts.openkeeper.world.room.control.RoomGoldControl;

/**
 * The Treasury
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class Treasury extends Normal {

    private Integer goldPerTile;

    public Treasury(AssetManager assetManager, RoomInstance roomInstance, ObjectLoader objectLoader, WorldState worldState, EffectManagerState effectManager) {
        super(assetManager, roomInstance, objectLoader, worldState, effectManager);

        addObjectControl(new RoomGoldControl(this) {

            @Override
            protected int getNumberOfAccessibleTiles() {
                return roomInstance.getCoordinates().size();
            }

            @Override
            protected int getGoldPerObject() {
                return Treasury.this.getGoldPerTile();
            }

        });
    }

    protected int getGoldPerTile() {
        if (goldPerTile == null) {
            goldPerTile = (int) worldState.getLevelVariable(MiscType.MAX_GOLD_PER_TREASURY_TILE);
        }

        return goldPerTile;
    }

}
