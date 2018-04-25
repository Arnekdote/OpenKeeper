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

import toniarts.openkeeper.tools.convert.map.KeeperSpell;
import toniarts.openkeeper.world.object.SpellBookObjectControl;

/**
 * Player's spell (Keeper Spell)
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class PlayerSpell {

    private final KeeperSpell keeperSpell;
    private boolean upgraded = false;
    private boolean discovered = false;
    private int research = 0;
    private SpellBookObjectControl spellBookObjectControl;

    public PlayerSpell(KeeperSpell keeperSpell) {
        this(keeperSpell, false);
    }

    public PlayerSpell(KeeperSpell keeperSpell, boolean discovered) {
        this.keeperSpell = keeperSpell;
        this.discovered = discovered;
    }

    public boolean isUpgraded() {
        return upgraded;
    }

    public KeeperSpell getKeeperSpell() {
        return keeperSpell;
    }

    public boolean isDiscovered() {
        return discovered;
    }

    protected void setDiscovered(boolean discovered) {
        this.discovered = discovered;
    }

    protected boolean research(int researchAmount) {
        research += researchAmount;
        if (discovered && research >= keeperSpell.getBonusRTime()) {
            upgraded = true;
            research = 0;
            return true;
        } else if (!discovered && research >= keeperSpell.getResearchTime()) {
            discovered = true;
            research = 0;
            return true;
        }
        return false;
    }

    public void setSpellBookObjectControl(SpellBookObjectControl spellBookObjectControl) {
        this.spellBookObjectControl = spellBookObjectControl;
    }

    public SpellBookObjectControl getSpellBookObjectControl() {
        return spellBookObjectControl;
    }

}
