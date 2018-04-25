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
package toniarts.openkeeper.view;

import com.jme3.app.Application;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;
import com.jme3.collision.CollisionResults;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import com.jme3.light.PointLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.scene.control.AbstractControl;
import de.lessvoid.nifty.controls.Label;
import de.lessvoid.nifty.elements.Element;
import java.awt.Point;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.console.ConsoleState;
import toniarts.openkeeper.game.data.Settings;
import toniarts.openkeeper.game.state.AbstractPauseAwareState;
import toniarts.openkeeper.game.state.CheatState;
import static toniarts.openkeeper.game.state.CheatState.CheatType.MONEY;
import toniarts.openkeeper.game.state.GameState;
import toniarts.openkeeper.game.state.PlayerScreenController;
import toniarts.openkeeper.game.state.PlayerState;
import toniarts.openkeeper.gui.CursorFactory;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Room;
import toniarts.openkeeper.tools.convert.map.Terrain;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable.MiscType;
import toniarts.openkeeper.utils.WorldUtils;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState.Type;
import toniarts.openkeeper.view.selection.SelectionArea;
import toniarts.openkeeper.view.selection.SelectionHandler;
import toniarts.openkeeper.world.MapLoader;
import static toniarts.openkeeper.world.MapLoader.TILE_WIDTH;
import toniarts.openkeeper.world.TileData;
import toniarts.openkeeper.world.WorldState;
import toniarts.openkeeper.world.control.IInteractiveControl;
import toniarts.openkeeper.world.creature.CreatureControl;
import toniarts.openkeeper.world.room.GenericRoom;
import toniarts.openkeeper.world.room.RoomInstance;

/**
 * State for managing player interactions in the world. Heavily drawn from
 * Philip Willuweit's AgentKeeper code <p.willuweit@gmx.de>.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 * @author ArchDemon
 */
// TODO: States, now only selection
public abstract class PlayerInteractionState extends AbstractPauseAwareState {

    private static final int SPELL_POSSESSION_ID = 2;
    private static final float CURSOR_UPDATE_INTERVAL = 0.25f;

    private Main app;
    private GameState gameState;
    private AssetManager assetManager;
    private AppStateManager stateManager;
    private InputManager inputManager;

    private final Player player;
    private SelectionHandler selectionHandler;
    private Vector2f mousePosition = new Vector2f(Vector2f.ZERO);
    private InteractionState interactionState = new InteractionState();
    private float timeFromLastUpdate = CURSOR_UPDATE_INTERVAL;
    private Element view;
    private PointLight keeperLight;

    private boolean isOnGui = false;
    private boolean isTaggable = false;
    private boolean isOnMap = false;
    private boolean isInteractable = false;

    private RawInputListener inputListener;
    private boolean inputListenerAdded = false;
    private IInteractiveControl interactiveControl;
    private Label tooltip;
    private KeeperHand keeperHand;

    private static final Logger logger = Logger.getLogger(PlayerInteractionState.class.getName());

    public PlayerInteractionState(Player player) {
        this.player = player;

        // The input
        initializeInput();
    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);
        this.app = (Main) app;
        assetManager = this.app.getAssetManager();
        this.stateManager = this.app.getStateManager();
        inputManager = this.app.getInputManager();
        gameState = this.stateManager.getState(GameState.class);

        PlayerScreenController psc = this.stateManager.getState(PlayerState.class).getScreen();
        this.view = psc.getGuiConstraint();
        this.tooltip = psc.getTooltip();
        // Init the keeper hand
        keeperHand = new KeeperHand(assetManager, (int) gameState.getLevelVariable(MiscType.MAX_NUMBER_OF_THINGS_IN_HAND));
        this.app.getGuiNode().attachChild(keeperHand.getNode());

        // Init handler
        selectionHandler = new SelectionHandler(this.app) {
            @Override
            public boolean isVisible() {
                if (isTaggable || selectionHandler.isActive()) {
                    return true;
                }

                if (!isOnMap) {
                    return false;
                }

                switch (interactionState.getType()) {
                    case NONE:
                        return (keeperHand.getItem() != null);
                    case SELL:
                    case ROOM:
                    case DOOR:
                    case TRAP:
                        return true;
                }

                return false;
            }

            @Override
            protected SelectionHandler.ColorIndicator getColorIndicator() {
                Vector2f pos;
                if (selectionHandler.isActive()) {
                    pos = selectionHandler.getSelectionArea().getRealStart();
                } else {
                    pos = selectionHandler.getPointedTilePosition();
                }
                if (interactionState.getType() == Type.NONE && keeperHand.getItem() != null) {
                    TileData tile = getWorldHandler().getMapData().getTile((int) pos.x, (int) pos.y);
                    if (tile != null) {
                        IInteractiveControl.DroppableStatus status = keeperHand.peek().getDroppableStatus(tile, player.getPlayerId());
                        return (status != IInteractiveControl.DroppableStatus.NOT_DROPPABLE ? ColorIndicator.BLUE : ColorIndicator.RED);
                    }
                    return ColorIndicator.RED;
                }
                if (interactionState.getType() == Type.SELL) {
                    return ColorIndicator.RED;
                } else if (interactionState.getType() == Type.ROOM && !(getWorldHandler().isTaggable((int) pos.x, (int) pos.y) || (getWorldHandler().isBuildable((int) pos.x, (int) pos.y, player, gameState.getLevelData().getRoomById(interactionState.getItemId())) && isPlayerAffordToBuild(player, gameState.getLevelData().getRoomById(interactionState.getItemId()))))) {
                    return ColorIndicator.RED;
                }
                return ColorIndicator.BLUE;
            }

            private boolean isPlayerAffordToBuild(Player player, Room room) {
                int playerMoney = getWorldHandler().getGameState().getPlayer(player.getPlayerId()).getGoldControl().getGold();
                if (playerMoney == 0) {
                    return false;
                }
                int buildablePlots = 0;
                for (int x = (int) Math.max(0, selectionHandler.getSelectionArea().getStart().x); x < Math.min(getWorldHandler().getMapData().getWidth(), selectionHandler.getSelectionArea().getEnd().x + 1); x++) {
                    for (int y = (int) Math.max(0, selectionHandler.getSelectionArea().getStart().y); y < Math.min(getWorldHandler().getMapData().getHeight(), selectionHandler.getSelectionArea().getEnd().y + 1); y++) {
                        if (getWorldHandler().isBuildable(x, y, player, room)) {
                            buildablePlots++;
                        }

                        // See the gold amount
                        if (playerMoney < buildablePlots * room.getCost()) {
                            return false;
                        }
                    }
                }
                return true;
            }
        };

        CheatState cheatState = new CheatState(app) {
            @Override
            public void onSuccess(CheatState.CheatType cheat) {

                switch (cheat) {
                    case MONEY:
                        getWorldHandler().addGold(player.getPlayerId(), 100000);
                        break;
                    case MANA:
                        gameState.getPlayer(player.getPlayerId()).getManaControl().addMana(100000);
                        break;
                    case LEVEL_MAX:
                        Map<Creature, Set<CreatureControl>> creatureMap = stateManager.getState(PlayerState.class).getCreatureControl().getAllCreatures();
                        creatureMap.values().stream().forEach((creatureControlSet) -> {
                            creatureControlSet.stream().forEach((creatureControl) -> {
                                creatureControl.levelMax();
                            });
                        });
                        break;
                    case WIN_LEVEL:
                        gameState.setEnd(true);
                        break;
                    default:
                        logger.log(Level.WARNING, "Cheat {0} not implemented yet!", cheat.toString());
                }
            }
        };
        this.stateManager.attach(cheatState);

        // Create Keeper light
        keeperLight = new PointLight(Vector3f.ZERO, ColorRGBA.Orange, TILE_WIDTH * 2);
        keeperLight.setName("Keeper Hand");

        // Add listener
        if (isEnabled()) {
            setEnabled(true);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        if (enabled && !inputListenerAdded) {
            app.getInputManager().addRawInputListener(inputListener);
            app.getRootNode().addLight(keeperLight);
            inputListenerAdded = true;
        } else if (!enabled && inputListenerAdded) {
            app.getInputManager().removeRawInputListener(inputListener);
            app.getRootNode().removeLight(keeperLight);
            inputListenerAdded = false;
        }
    }

    @Override
    public void cleanup() {
        app.getRootNode().removeLight(keeperLight);
        app.getGuiNode().detachChild(keeperHand.getNode());
        app.getInputManager().removeRawInputListener(inputListener);
        selectionHandler.cleanup();
        CheatState cheatState = this.stateManager.getState(CheatState.class);
        if (cheatState != null) {
            this.stateManager.detach(cheatState);
        }

        super.cleanup();
    }

    @Override
    public void update(float tpf) {
        super.update(tpf);

        if (!isInitialized() || !isEnabled()) {
            return;
        }

        selectionHandler.update(mousePosition);
        if (isOnMap && !isOnGui && !isTaggable) {
            updateInteractiveObjectOnCursor();
        }

        updateStateFlags();

        timeFromLastUpdate += tpf;
        // Update the cursor, the camera might have moved, a creature might have slipped by us... etc.
        if (timeFromLastUpdate > CURSOR_UPDATE_INTERVAL) {
            //updateCursor();
            //updateStateFlags();
            timeFromLastUpdate = 0;
        }
    }

    @Override
    public boolean isPauseable() {
        return true;
    }

    private WorldState getWorldHandler() {
        return stateManager.getState(WorldState.class);
    }

    /**
     * Set the interaction state for the keeper
     *
     * @param type interaction type
     * @param id object id, i.e. build state requires the room id
     */
    public void setInteractionState(Type type, int id) {
        interactionState.setState(type, id);

        // Call the update
        onInteractionStateChange(interactionState);
    }

    /**
     * Get the current interaction state
     *
     * @return current interaction
     */
    public InteractionState getInteractionState() {
        return interactionState;
    }

    private boolean isCursorOnGUI() {
        int height = app.getContext().getSettings().getHeight();

        if (view.isVisible() && view.isMouseInsideElement((int) mousePosition.x, height - (int) mousePosition.y)) {
            for (Element e : view.getChildren()) {
                if (e.isVisible() && e.isMouseInsideElement((int) mousePosition.x, height - (int) mousePosition.y)) {
                    return true;
                }
            }
            return false;
        }

        return true;
    }

    /**
     * Set the state flags according to what user can do. Rather clumsy. Fix if
     * you can.
     *
     * @return has the state being changed
     *
     */
    private void updateStateFlags() {
        boolean changed = false;

        boolean value = isCursorOnGUI();
        if (isOnGui != value) {
            isOnGui = value;
            changed = true;
        }

        value = isOnMap();
        if (isOnMap != value) {
            isOnMap = value;
            changed = true;
        }

        value = isTaggable();
        if (isTaggable != value) {
            isTaggable = value;
            changed = true;
        }

        value = isInteractable();
        if (isInteractable != value) {
            isInteractable = value;
            changed = true;
        }

        if (changed) {
            updateCursor();
        }
    }

    private boolean isInteractable() {
        if (isOnGui || !isOnMap || isTaggable) {
            setInteractiveControl(null);
        }

        Point p = null;
        if (interactiveControl != null) {

            // Maybe a kinda hack, but set the tooltip here
            tooltip.setText(interactiveControl.getTooltip(player.getPlayerId()));
            interactiveControl.onHover();
        } else if (isOnMap) {

            // Tile tooltip then
            p = selectionHandler.getPointedTileIndex();
            TileData tile = getWorldHandler().getMapData().getTile(p);
            if (tile != null) {
                if (tile.getTerrain().getFlags().contains(Terrain.TerrainFlag.ROOM)) {
                    RoomInstance roomInstance = getWorldHandler().getMapLoader().getRoomCoordinates().get(new Point((int) p.x, (int) p.y));
                    GenericRoom room = getWorldHandler().getMapLoader().getRoomActuals().get(roomInstance);
                    tooltip.setText(room.getTooltip(player.getPlayerId()));
                } else {
                    tooltip.setText(tile.getTooltip());
                }
            } else {
                tooltip.setText("");
            }
        }

        // If debug, show tile coordinate
        if (Main.isDebug() && (interactiveControl != null || isOnMap)) {
            StringBuilder sb = new StringBuilder();
            if (interactiveControl != null) {
                p = WorldUtils.vectorToPoint(((AbstractControl) interactiveControl).getSpatial().getWorldTranslation());
            }
            sb.append("(");
            sb.append(p.x + 1);  // 1-based coordinates
            sb.append(", ");
            sb.append(p.y + 1);  // 1-based coordinates
            sb.append("): ");
            sb.append(tooltip.getText());
            tooltip.setText(sb.toString());
        }

        return (interactiveControl != null);
    }

    private void updateInteractiveObjectOnCursor() {

        // See if we hit a creature/object
        CollisionResults results = new CollisionResults();

        // Convert screen click to 3D position
        Vector3f click3d = app.getCamera().getWorldCoordinates(
                new Vector2f(mousePosition.x, mousePosition.y), 0f);
        Vector3f dir = app.getCamera().getWorldCoordinates(
                new Vector2f(mousePosition.x, mousePosition.y), 1f).subtractLocal(click3d);

        // Aim the ray from the mouse spot forwards
        Ray ray = new Ray(click3d, dir);

        // Collect intersections between ray and all nodes in results list
        getWorldHandler().getThingsNode().collideWith(ray, results);

        // See the results so we see what is going on
        Node object;
        for (int i = 0; i < results.size(); i++) {

            // TODO: Now just creature control, but all interaction objects
            object = results.getCollision(i).getGeometry().getParent().getParent();
            IInteractiveControl control = object.getControl(IInteractiveControl.class);
            if (control != null) {
                setInteractiveControl(control);
                return;
            }
        }
        setInteractiveControl(null);
    }

    private void setInteractiveControl(IInteractiveControl interactiveControl) {

        // If it is the same, don't do anything
        if (interactiveControl != null && interactiveControl.equals(this.interactiveControl)) {
            return;
        }

        // Changed
        if (this.interactiveControl != null) {
            this.interactiveControl.onHoverEnd();
        }
        this.interactiveControl = interactiveControl;
        if (this.interactiveControl != null) {
            this.interactiveControl.onHoverStart();
        }
    }

    private boolean isTaggable() {
        if (isOnGui || !isOnMap) {
            return false;
        }
        Point p = selectionHandler.getPointedTileIndex();
        return (interactionState.getType() == Type.ROOM
                || interactionState.getType() == Type.NONE)
                && isOnMap && getWorldHandler().isTaggable(p.x, p.y);
    }

    private boolean isOnMap() {
        if (isOnGui) {
            return false;
        }
        Point p = selectionHandler.getPointedTileIndex();
        return getWorldHandler().getMapData().getTile(p) != null;
    }

    protected void updateCursor() {
        keeperHand.setVisible(false);
        if (Main.getUserSettings().getSettingBoolean(Settings.Setting.USE_CURSORS)) {
            if (isOnGui || isInteractable || interactionState.getType() == Type.SPELL) {
                inputManager.setMouseCursor(CursorFactory.getCursor(CursorFactory.CursorType.POINTER, assetManager));
            } else if (selectionHandler.isActive() && isTaggable) {
                inputManager.setMouseCursor(CursorFactory.getCursor(CursorFactory.CursorType.HOLD_PICKAXE_TAGGING, assetManager));
            } else if (isTaggable) {
                inputManager.setMouseCursor(CursorFactory.getCursor(CursorFactory.CursorType.HOLD_PICKAXE, assetManager));
            } else if (keeperHand.getItem() != null) {

                // Keeper hand item
                inputManager.setMouseCursor(CursorFactory.getCursor(keeperHand.getItem().getInHandCursor(), assetManager));
                keeperHand.setVisible(true);
            } else {
                inputManager.setMouseCursor(CursorFactory.getCursor(CursorFactory.CursorType.IDLE, assetManager));
            }
        } else if (keeperHand.getItem() != null) {
            keeperHand.setVisible(true);
        }
    }

    private void initializeInput() {
        inputListener = new RawInputListener() {
            @Override
            public void beginInput() {
            }

            @Override
            public void endInput() {
            }

            @Override
            public void onJoyAxisEvent(JoyAxisEvent evt) {
            }

            @Override
            public void onJoyButtonEvent(JoyButtonEvent evt) {
            }

            @Override
            public void onMouseMotionEvent(MouseMotionEvent evt) {
                mousePosition.set(evt.getX(), evt.getY());
                keeperHand.setPosition(evt.getX(), evt.getY());

                // Set the keeper light position
                Camera cam = app.getCamera();
                Vector3f camPos = cam.getLocation();
                Vector3f tmp = cam.getWorldCoordinates(mousePosition, 0f).clone();
                Vector3f dir = cam.getWorldCoordinates(mousePosition, 1f).subtractLocal(tmp).normalizeLocal();
                dir.multLocal((MapLoader.TILE_HEIGHT - camPos.getY()) / dir.getY()).addLocal(camPos);

                Point pos = selectionHandler.getPointedTileIndex();
                float lightHeight = MapLoader.TILE_HEIGHT / 2;
                TileData tile = getWorldHandler().getMapData().getTile((int) pos.x, (int) pos.y);
                if (tile.getTerrain().getFlags().contains(Terrain.TerrainFlag.SOLID)) {
                    lightHeight += MapLoader.TILE_HEIGHT;
                }

                keeperLight.setPosition(new Vector3f(dir.getX(), lightHeight, dir.getZ()));
            }

            @Override
            public void onMouseButtonEvent(MouseButtonEvent evt) {
                timeFromLastUpdate = 0;
                if (isOnGui || !isOnMap) {
                    return;
                }

                if (evt.getButtonIndex() == MouseInput.BUTTON_LEFT) {

                    if (evt.isPressed()) {
                        if (interactionState.getType() == Type.SPELL) {
                            //TODO correct interactiveControl.isPickable
                            if (interactiveControl != null && interactionState.getItemId() == SPELL_POSSESSION_ID
                                    && interactiveControl.isPickable(player.getPlayerId())) {
                                CreatureControl cc = interactiveControl.getSpatial().getControl(CreatureControl.class);
                                if (cc != null) {
                                    onPossession(cc);
                                    // Reset the state
                                    // TODO disable selection box
                                    setInteractionState(Type.NONE, 0);
                                }
                            }
                        } else if (interactionState.getType() == Type.TRAP) {
                            //TODO put trap
                        } else if (interactionState.getType() == Type.DOOR) {
                            //TODO put door
                        } else if (interactionState.getType() == Type.NONE
                                && interactiveControl != null && !keeperHand.isFull()
                                && interactiveControl.isPickable(player.getPlayerId())) {
                            pickupObject(interactiveControl);
                        } else if (interactionState.getType() == Type.NONE
                                && interactiveControl != null
                                && interactiveControl.isInteractable(player.getPlayerId())) {
                            interactiveControl.interact(player.getPlayerId());
                        } else {

                            // Selection stuff
                            if (selectionHandler.isVisible()) {
                                selectionHandler.setActive(true);
                            }

                            // I suppose we are tagging
                            if (isTaggable) {
                                updateCursor();
                                // The tagging sound is positional and played against the cursor change, not the action itself
                                Point pos = selectionHandler.getPointedTileIndex();
                                getWorldHandler().playSoundAtTile(pos.x, pos.y, "/Global/GuiHD/dk1tag.mp2");
                            }
                        }

                    } else if (evt.isReleased() && selectionHandler.isActive()) {
                        SelectionArea selectionArea = selectionHandler.getSelectionArea();
                        if (interactionState.getType() == Type.NONE
                                || (interactionState.getType() == Type.ROOM
                                && getWorldHandler().isTaggable((int) selectionArea.getRealStart().x, (int) selectionArea.getRealStart().y))) {

                            // Determine if this is a select/deselect by the starting tile's status
                            boolean select = !getWorldHandler().isSelected((int) Math.max(0, selectionArea.getRealStart().x), (int) Math.max(0, selectionArea.getRealStart().y));
                            getWorldHandler().selectTiles(selectionArea, select, player.getPlayerId());
                        } else if (interactionState.getType() == Type.ROOM && getWorldHandler().isBuildable((int) selectionArea.getRealStart().x,
                                (int) selectionArea.getRealStart().y, player, gameState.getLevelData().getRoomById(interactionState.getItemId()))) {
                            getWorldHandler().build(selectionArea, player, gameState.getLevelData().getRoomById(interactionState.getItemId()));
                        } else if (interactionState.getType() == Type.SELL) {
                            getWorldHandler().sell(selectionArea, player);
                        }

                        selectionHandler.setActive(false);
                        updateCursor();
                    }
                } else if (evt.getButtonIndex() == MouseInput.BUTTON_RIGHT && evt.isReleased()) {

                    Point p = selectionHandler.getPointedTileIndex();
                    if (interactionState.getType() == Type.NONE) {

                        // Drop
                        if (keeperHand.getItem() != null) {
                            TileData tile = getWorldHandler().getMapData().getTile(p);
                            IInteractiveControl.DroppableStatus status = keeperHand.peek().getDroppableStatus(tile, player.getPlayerId());
                            if (status != IInteractiveControl.DroppableStatus.NOT_DROPPABLE) {

                                // Drop & update cursor
                                keeperHand.pop().drop(tile, selectionHandler.getActualPointedPosition(), interactiveControl);
                                updateCursor();
                            }
                        } else if (interactiveControl != null && interactiveControl.isInteractable(player.getPlayerId())) {
                            getWorldHandler().playSoundAtTile(p.x, p.y, KeeperHand.getSlapSound());
                            interactiveControl.interact(player.getPlayerId());
                        } else if (Main.isDebug()) {
                            // taggable -> "dig"
                            if (getWorldHandler().isTaggable(p.x, p.y)) {
                                getWorldHandler().digTile(p.x, p.y);
                            } // ownable -> "claim"
                            else if (getWorldHandler().isClaimable(p.x, p.y, player.getPlayerId())) {
                                getWorldHandler().claimTile(p.x, p.y, player.getPlayerId());
                            }
                        }
                    }

                    // Reset the state
                    setInteractionState(Type.NONE, 0);
                    updateCursor();

                    selectionHandler.setActive(false);

                } else if (evt.getButtonIndex() == MouseInput.BUTTON_MIDDLE && evt.isReleased()) {
                    if (Main.isDebug()) {
                        Point p = selectionHandler.getPointedTileIndex();
                        getWorldHandler().claimTile(p.x, p.y, player.getPlayerId());
                    }
                }
            }

            @Override
            public void onKeyEvent(KeyInputEvent evt) {
                if (evt.isPressed()) {
                    if (evt.getKeyCode() == KeyInput.KEY_F12) {
                        // FIXME use CTRL + ALT + C to activate cheats!
                        // TODO Disable in multi player!
                        CheatState cheat = stateManager.getState(CheatState.class);
                        if (!cheat.isEnabled()) {
                            cheat.setEnabled(true);
                        }
                    } else if (evt.getKeyCode() == ConsoleState.KEY && Main.isDebug()) {
                        stateManager.getState(ConsoleState.class).setEnabled(true);
                    } else if (evt.getKeyCode() == (Integer) Settings.Setting.TOGGLE_PLAYER_INFORMATION.getDefaultValue()) {
                        Element stats = view.findElementById("statistics");
                        if (stats != null) {
                            if (stats.isVisible()) {
                                stats.hide();
                            } else {
                                stats.show();
                            }
                        }
                    }
                }

            }

            @Override
            public void onTouchEvent(TouchEvent evt) {
            }

        };
    }

    /**
     * Picks up an object, places it in Keeper's hand
     *
     * @param object the object to pickup
     * @return picked or not
     */
    public boolean pickupObject(IInteractiveControl object) {
        if (object == null || keeperHand.isFull() || !object.isPickable(player.getPlayerId())) {
            return false;
        }

        keeperHand.push(object.pickUp(player.getPlayerId()));
        updateCursor();
        return true;
    }

    /**
     * Checks if the Keeper hand is full
     *
     * @return is keeper hand full
     */
    public boolean isKeeperHandFull() {
        return keeperHand.isFull();
    }

    /**
     * A callback for changing the interaction state
     *
     * @param interactionState new state
     */
    protected abstract void onInteractionStateChange(InteractionState interactionState);

    protected abstract void onPossession(CreatureControl creature);

    public static class InteractionState {

        public enum Type {

            NONE, ROOM, SELL, SPELL, TRAP, DOOR, STUFF_IN_HAND
        }

        private int itemId = 0;
        private Type type = Type.NONE;

        public InteractionState() {
            itemId = 0;
            type = Type.NONE;
        }

        public InteractionState(Type type, int itemId) {
            this.type = type;
            this.itemId = itemId;
        }

        public InteractionState(Type type) {
            this(type, 0);
        }

        protected void setState(Type type, int itemId) {
            this.type = type;
            this.itemId = itemId;

            //PlayerInteractionState.this.onInteractionStateChange(this);
        }

        protected void setState(Type type) {
            setState(type, 0);
        }

        public Type getType() {
            return type;
        }

        public int getItemId() {
            return itemId;
        }
    }
}
