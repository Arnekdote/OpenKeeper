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
package toniarts.openkeeper.tools.convert.map;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import toniarts.openkeeper.tools.convert.ConversionUtils;
import toniarts.openkeeper.tools.convert.map.ArtResource.ArtResourceType;
import toniarts.openkeeper.tools.convert.map.Creature.AnimationType;
import toniarts.openkeeper.tools.convert.map.Creature.Attraction;
import toniarts.openkeeper.tools.convert.map.Creature.JobAlternative;
import toniarts.openkeeper.tools.convert.map.Creature.OffsetType;
import toniarts.openkeeper.tools.convert.map.Creature.Spell;
import toniarts.openkeeper.tools.convert.map.Creature.X1323;
import toniarts.openkeeper.tools.convert.map.Door.DoorFlag;
import toniarts.openkeeper.tools.convert.map.GameLevel.LevFlag;
import toniarts.openkeeper.tools.convert.map.GameLevel.LevelReward;
import toniarts.openkeeper.tools.convert.map.GameLevel.TextTable;
import static toniarts.openkeeper.tools.convert.map.MapDataTypeEnum.MAP;
import toniarts.openkeeper.tools.convert.map.Thing.ActionPoint;
import toniarts.openkeeper.tools.convert.map.Thing.ActionPoint.ActionPointFlag;
import toniarts.openkeeper.tools.convert.map.Thing.GoodCreature;
import toniarts.openkeeper.tools.convert.map.Thing.HeroParty;
import toniarts.openkeeper.tools.convert.map.Thing.KeeperCreature;
import toniarts.openkeeper.tools.convert.map.Thing.NeutralCreature;
import toniarts.openkeeper.tools.convert.map.Variable.Availability;
import toniarts.openkeeper.tools.convert.map.Variable.CreatureFirstPerson;
import toniarts.openkeeper.tools.convert.map.Variable.CreaturePool;
import toniarts.openkeeper.tools.convert.map.Variable.CreatureStats;
import toniarts.openkeeper.tools.convert.map.Variable.CreatureStats.StatType;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable;
import toniarts.openkeeper.tools.convert.map.Variable.Sacrifice;
import toniarts.openkeeper.utils.PathUtils;

/**
 * Reads a DK II map file, the KWD is the file name of the main map identifier,
 * reads the KLDs actually<br>
 * The files are LITTLE ENDIAN I might say<br>
 * Some values are 3D coordinates or scale values presented in fixed point
 * integers. They are automatically converted to floats (divided by 2^12 = 4096
 * or 2^16 = 65536)<br>
 * Many parts adapted from C code by:
 * <li>George Gensure (werkt)</li>
 * And another C code implementation by:
 * <li>Thomasz Lis</li>
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class KwdFile {

    // These are needed in various places, I don't know how to else regognize these
    private final static short ROOM_PORTAL_ID = 3;
    private final static short TRIGGER_GENERIC = 213;
    private final static short TRIGGER_ACTION = 214;

    private GameLevel gameLevel;
    private GameMap map;
    private Map<Short, Player> players;
    private Map<Short, Terrain> terrainTiles;
    private Map<Short, Door> doors;
    private Map<Short, Trap> traps;
    private Map<Short, Room> rooms;
    private Map<Short, Room> roomsByTerrainId; // Maps have rooms by the terrain ID
    private Map<Short, Creature> creatures;
    private Map<Short, GameObject> objects;
    private Map<Short, CreatureSpell> creatureSpells;
    private Map<Integer, EffectElement> effectElements;
    private Map<Integer, Effect> effects;
    private Map<Short, KeeperSpell> keeperSpells;
    private List<Thing> things;
    private Map<Short, Shot> shots;
    private Map<Integer, Trigger> triggers;
    // Variables
    private List<Availability> availabilities;
    private Map<Integer, Map<Integer, CreaturePool>> creaturePools;
    private Map<Integer, Map<StatType, CreatureStats>> creatureStatistics;
    private Map<Integer, Map<StatType, CreatureFirstPerson>> creatureFirstPersonStatistics;
    private Map<MiscVariable.MiscType, MiscVariable> variables;
    private Set<Sacrifice> sacrifices;
    private Set<Variable.Unknown> unknownVariables;
    //
    private boolean customOverrides = false;
    private boolean loaded = false;
    private Creature imp;
    private final String basePath;
    private GameObject levelGem;
    private static final Logger logger = Logger.getLogger(KwdFile.class.getName());

    /**
     * Constructs a new KWD file reader<br>
     * Reads the whole map and its catalogs (either standard ones or custom
     * ones)
     *
     * @param basePath path to DK II main path (or where ever is the "root")
     * @param file the KWD file to read
     */
    public KwdFile(String basePath, File file) {
        this(basePath, file, true);
    }

    /**
     * Constructs a new KWD file reader<br>
     *
     * @param basePath path to DK II main path (or where ever is the "root")
     * @param file the KWD file to read
     * @param load whether to actually load the map data, or just get the
     * general info
     */
    public KwdFile(String basePath, File file, boolean load) {

        // Load the actual main map info (paths to catalogs most importantly)
        // Read the file
        try {
            readFileContents(file);
        } catch (Exception e) {
            //Fug
            throw new RuntimeException("Failed to read the file " + file + "!", e);
        }
        this.basePath = PathUtils.fixFilePath(basePath);

        // See if we need to load the actual data
        if (load) {
            load();
        } else {

            // We need map width & height if not loaded fully, I couldn't figure out where, except the map data
            try (RandomAccessFile data = new RandomAccessFile(ConversionUtils.getRealFileName(basePath, gameLevel.getFile(MAP)), "r")) {
                KwdHeader header = readKwdHeader(data);
                map = new GameMap(header.getWidth(), header.getHeight());
            } catch (Exception e) {

                //Fug
                throw new RuntimeException("Failed to read the file " + gameLevel.getFile(MAP) + "!", e);
            }
        }
    }

    private void readFileContents(File file) throws IOException {
        try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
            while (data.getFilePointer() < data.length()) {

                // Read header (and put the file pointer to the data start)
                KwdHeader header = readKwdHeader(data);
                readFileContents(header, data);
            }

            if (data.getFilePointer() != data.length()) {
                throw new RuntimeException("Failed to parse file");
            }
        }
    }

    /**
     * Loads the map data
     *
     * @throws RuntimeException level file fails to parse
     */
    public void load() throws RuntimeException {
        if (!loaded) {

            // Read the map data first (we store some data to the map)
            for (FilePath path : gameLevel.getPaths()) {
                if (path.getId() == MapDataTypeEnum.MAP) {
                    readFilePath(path);
                    break;
                }
            }

            // Now we have the paths, read all of those in order
            for (FilePath path : gameLevel.getPaths()) {

                if (path.getId() == MapDataTypeEnum.MAP) {
                    continue;
                }

                // Open the file
                readFilePath(path);
            }
            loaded = true;
        }
    }

    private void readFilePath(FilePath path) {
        File file = null;
        try {
            file = new File(ConversionUtils.getRealFileName(basePath, path.getPath()));
            readFileContents(file);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read the file " + file + "!", e);
        }
    }

    /**
     * Reads the common KWD header
     *
     * @param data the data file
     * @return the header
     * @throws IOException may fail reading
     */
    private KwdHeader readKwdHeader(RandomAccessFile data) throws IOException {

        KwdHeader header = new KwdHeader();
        header.setId(ConversionUtils.parseEnum(ConversionUtils.readUnsignedInteger(data), MapDataTypeEnum.class));
        int size = ConversionUtils.readUnsignedInteger(data); // Bytes in the real size indicator, well seems to be 4 always
        if (size == 2) {
            header.setSize(ConversionUtils.readUnsignedShort(data));
        } else if (size == 4) {
            header.setSize(ConversionUtils.readUnsignedInteger(data));
        }
        header.setCheckOne(ConversionUtils.readUnsignedInteger(data));
        header.setHeaderEndOffset(ConversionUtils.readUnsignedInteger(data));
        //Mark the position
        long offset = data.getFilePointer();

        switch (header.getId()) {
            case MAP:
                header.setHeaderSize(36);
                header.setWidth(ConversionUtils.readUnsignedInteger(data));
                header.setHeight(ConversionUtils.readUnsignedInteger(data));
                break;

            case TRIGGERS:
                header.setHeaderSize(60);
                header.setItemCount(ConversionUtils.readUnsignedInteger(data) + ConversionUtils.readUnsignedInteger(data));
                header.setUnknown(ConversionUtils.readUnsignedInteger(data));

                header.setDateCreated(ConversionUtils.readTimestamp(data));
                header.setDateModified(ConversionUtils.readTimestamp(data));
                break;

            case LEVEL:
                header.setItemCount(ConversionUtils.readUnsignedShort(data));
                header.setHeight(ConversionUtils.readUnsignedShort(data));
                header.setUnknown(ConversionUtils.readUnsignedInteger(data));

                header.setDateCreated(ConversionUtils.readTimestamp(data));
                header.setDateModified(ConversionUtils.readTimestamp(data));
                break;
            default:
                header.setItemCount(ConversionUtils.readUnsignedInteger(data));
                header.setUnknown(ConversionUtils.readUnsignedInteger(data));

                header.setDateCreated(ConversionUtils.readTimestamp(data));
                header.setDateModified(ConversionUtils.readTimestamp(data));
                break;
        }

        if (data.getFilePointer() != offset + header.getHeaderEndOffset()) {
            logger.warning("Incorrect parsing of file header");
        }
        //header.setHeaderSize(28 + header.getHeaderEndOffset());
        header.setCheckTwo(ConversionUtils.readUnsignedInteger(data));
        header.setDataSize(ConversionUtils.readUnsignedInteger(data));

        return header;
    }

    private void readFileContents(KwdHeader header, RandomAccessFile data) throws IOException {
        // Handle all the cases (we kinda skip the globals with this logic, so no need)
        // All readers must read the whole data they intend to read
        switch (header.getId()) {
            case LEVEL:
                // check header.getCheckOne() != 221 || header.getCheckTwo() != 223
                readMapInfo(header, data);
                break;

            case CREATURES:
                // check header.getCheckOne() != 171 || header.getCheckTwo() != 172
                readCreatures(header, data);
                break;

            case CREATURE_SPELLS:
                if (header.getCheckOne() != 161 || header.getCheckTwo() != 162) {
                    throw new RuntimeException("Creature spells file is corrupted");
                }
                readCreatureSpells(header, data);
                break;

            case DOORS:
                // check header.getCheckOne() != 141 || header.getCheckTwo() != 142
                readDoors(header, data);
                break;

            case EFFECTS:
                // check header.getCheckOne() != 271 || header.getCheckTwo() != 272
                readEffects(header, data);
                break;

            case EFFECT_ELEMENTS:
                // check header.getCheckOne() != 251 || header.getCheckTwo() != 252
                readEffectElements(header, data);
                break;

            case KEEPER_SPELLS:
                // check header.getCheckOne() != 151 || header.getCheckTwo() != 152
                readKeeperSpells(header, data);
                break;

            case MAP:
                // check header.getCheckOne() != 101 || header.getCheckTwo() != 102
                readMap(header, data);
                break;

            case OBJECTS:
                // check header.getCheckOne() != 241 || header.getCheckTwo() != 242
                readObjects(header, data);
                break;

            case PLAYERS:
                // check header.getCheckOne() != 181 || header.getCheckTwo() != 182
                readPlayers(header, data);
                break;

            case ROOMS:
                // check header.getCheckOne() != 121 || header.getCheckTwo() != 122
                readRooms(header, data);
                break;

            case SHOTS:
                // check header.getCheckOne() != 261 || header.getCheckTwo() != 262
                readShots(header, data);
                break;

            case TERRAIN:
                // check header.getCheckOne() != 111 || header.getCheckTwo() != 112
                readTerrain(header, data);
                break;

            case THINGS:
                // check header.getCheckOne() != 191 || header.getCheckTwo() != 192
                readThings(header, data);
                break;

            case TRAPS:
                // check header.getCheckOne() != 131 || header.getCheckTwo() != 132
                readTraps(header, data);
                break;

            case TRIGGERS:
                // check header.getCheckOne() != 211 || header.getCheckTwo() != 212
                readTriggers(header, data);
                break;

            case VARIABLES:
                // check header.getCheckOne() != 231 || header.getCheckTwo() != 232
                readVariables(header, data);
                break;

            default:
                logger.log(Level.WARNING, "File type {0} have no reader", header.getId());
                break;
        }
    }

    /**
     * Reads the *Map.kld
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readMap(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the requested MAP file
        logger.info("Reading map!");
        if (map == null) {
            map = new GameMap(header.getWidth(), header.getHeight());
        }
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                Tile tile = new Tile();
                tile.setTerrainId((short) file.readUnsignedByte());
                tile.setPlayerId((short) file.readUnsignedByte());
                tile.setFlag(ConversionUtils.parseEnum(file.readUnsignedByte(), Tile.BridgeTerrainType.class));
                tile.setUnknown((short) file.readUnsignedByte());
                map.setTile(x, y, tile);
            }
        }
    }

    /**
     * Reads the *Players.kld
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readPlayers(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the requested PLAYER file
        if (players == null) {
            logger.info("Reading players!");
            players = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides players!");
        }

        for (int playerIndex = 0; playerIndex < header.getItemCount(); playerIndex++) {
            long offset = file.getFilePointer();
            Player player = new Player();
            player.setStartingGold(ConversionUtils.readInteger(file));
            player.setAi(ConversionUtils.readInteger(file) == 1);

            AI ai = new AI();
            ai.setAiType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.AIType.class));
            ai.setSpeed((short) file.readUnsignedByte());
            ai.setOpenness((short) file.readUnsignedByte());
            ai.setRemoveCallToArmsIfTotalCreaturesLessThan((short) file.readUnsignedByte());
            ai.setBuildLostRoomAfterSeconds((short) file.readUnsignedByte());
            short[] unknown1 = new short[3];
            for (int i = 0; i < unknown1.length; i++) {
                unknown1[i] = (short) file.readUnsignedByte();
            }
            ai.setUnknown1(unknown1);
            ai.setCreateEmptyAreasWhenIdle(ConversionUtils.readInteger(file) == 1);
            ai.setBuildBiggerLairAfterClaimingPortal(ConversionUtils.readInteger(file) == 1);
            ai.setSellCapturedRoomsIfLowOnGold(ConversionUtils.readInteger(file) == 1);
            ai.setMinTimeBeforePlacingResearchedRoom((short) file.readUnsignedByte());
            ai.setDefaultSize((short) file.readUnsignedByte());
            ai.setTilesLeftBetweenRooms((short) file.readUnsignedByte());
            ai.setDistanceBetweenRoomsThatShouldBeCloseMan(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                    AI.Distance.class));
            ai.setCorridorStyle(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.CorridorStyle.class));
            ai.setWhenMoreSpaceInRoomRequired(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                    AI.RoomExpandPolicy.class));
            ai.setDigToNeutralRoomsWithinTilesOfHeart((short) file.readUnsignedByte());
            List<Short> buildOrder = new ArrayList<>(15);
            for (int i = 0; i < 15; i++) {
                buildOrder.add((short) file.readUnsignedByte());
            }
            ai.setBuildOrder(buildOrder);
            ai.setFlexibility((short) file.readUnsignedByte());
            ai.setDigToNeutralRoomsWithinTilesOfClaimedArea((short) file.readUnsignedByte());
            ai.setRemoveCallToArmsAfterSeconds(ConversionUtils.readUnsignedShort(file));
            ai.setBoulderTrapsOnLongCorridors(ConversionUtils.readInteger(file) == 1);
            ai.setBoulderTrapsOnRouteToBreachPoints(ConversionUtils.readInteger(file) == 1);
            ai.setTrapUseStyle((short) file.readUnsignedByte());
            ai.setDoorTrapPreference((short) file.readUnsignedByte());
            ai.setDoorUsage(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.DoorUsagePolicy.class));
            ai.setChanceOfLookingToUseTrapsAndDoors((short) file.readUnsignedByte());
            ai.setRequireMinLevelForCreatures(ConversionUtils.readInteger(file) == 1);
            ai.setRequireTotalThreatGreaterThanTheEnemy(ConversionUtils.readInteger(file) == 1);
            ai.setRequireAllRoomTypesPlaced(ConversionUtils.readInteger(file) == 1);
            ai.setRequireAllKeeperSpellsResearched(ConversionUtils.readInteger(file) == 1);
            ai.setOnlyAttackAttackers(ConversionUtils.readInteger(file) == 1);
            ai.setNeverAttack(ConversionUtils.readInteger(file) == 1);
            ai.setMinLevelForCreatures((short) file.readUnsignedByte());
            ai.setTotalThreatGreaterThanTheEnemy((short) file.readUnsignedByte());
            ai.setFirstAttemptToBreachRoom(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.BreachRoomPolicy.class));
            ai.setFirstDigToEnemyPoint(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.DigToPolicy.class));
            ai.setBreachAtPointsSimultaneously((short) file.readUnsignedByte());
            ai.setUsePercentageOfTotalCreaturesInFirstFightAfterBreach((short) file.readUnsignedByte());
            ai.setManaValue(ConversionUtils.readUnsignedShort(file));
            ai.setPlaceCallToArmsWhereThreatValueIsGreaterThan(ConversionUtils.readUnsignedShort(file));
            ai.setRemoveCallToArmsIfLessThanEnemyCreatures((short) file.readUnsignedByte());
            ai.setRemoveCallToArmsIfLessThanEnemyCreaturesWithinTiles((short) file.readUnsignedByte());
            ai.setPullCreaturesFromFightIfOutnumberedAndUnableToDropReinforcements(ConversionUtils.readInteger(file) == 1);
            ai.setThreatValueOfDroppedCreaturesIsPercentageOfEnemyThreatValue((short) file.readUnsignedByte());
            ai.setSpellStyle((short) file.readUnsignedByte());
            ai.setAttemptToImprisonPercentageOfEnemyCreatures((short) file.readUnsignedByte());
            ai.setIfCreatureHealthIsPercentageAndNotInOwnRoomMoveToLairOrTemple((short) file.readUnsignedByte());
            ai.setGoldValue(ConversionUtils.readUnsignedShort(file));
            ai.setTryToMakeUnhappyOnesHappy(ConversionUtils.readInteger(file) == 1);
            ai.setTryToMakeAngryOnesHappy(ConversionUtils.readInteger(file) == 1);
            ai.setDisposeOfAngryCreatures(ConversionUtils.readInteger(file) == 1);
            ai.setDisposeOfRubbishCreaturesIfBetterOnesComeAlong(ConversionUtils.readInteger(file) == 1);
            ai.setDisposalMethod(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.CreatureDisposalPolicy.class));
            ai.setMaximumNumberOfImps((short) file.readUnsignedByte());
            ai.setWillNotSlapCreatures((short) file.readUnsignedByte() == 0);
            ai.setAttackWhenNumberOfCreaturesIsAtLeast((short) file.readUnsignedByte());
            ai.setUseLightningIfEnemyIsInWater(ConversionUtils.readInteger(file) == 1);
            ai.setUseSightOfEvil(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.SightOfEvilUsagePolicy.class));
            ai.setUseSpellsInBattle((short) file.readUnsignedByte());
            ai.setSpellsPowerPreference((short) file.readUnsignedByte());
            ai.setUseCallToArms(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.CallToArmsUsagePolicy.class));
            short[] unknown2 = new short[2];
            for (int i = 0; i < unknown2.length; i++) {
                unknown2[i] = (short) file.readUnsignedByte();
            }
            ai.setUnknown2(unknown2);
            ai.setMineGoldUntilGoldHeldIsGreaterThan(ConversionUtils.readUnsignedShort(file));
            ai.setWaitSecondsAfterPreviousAttackBeforeAttackingAgain(ConversionUtils.readUnsignedShort(file));
            ai.setStartingMana(ConversionUtils.readUnsignedInteger(file));
            ai.setExploreUpToTilesToFindSpecials(ConversionUtils.readUnsignedShort(file));
            ai.setImpsToTilesRatio(ConversionUtils.readUnsignedShort(file));
            ai.setBuildAreaStartX(ConversionUtils.readUnsignedShort(file));
            ai.setBuildAreaStartY(ConversionUtils.readUnsignedShort(file));
            ai.setBuildAreaEndX(ConversionUtils.readUnsignedShort(file));
            ai.setBuildAreaEndY(ConversionUtils.readUnsignedShort(file));
            ai.setLikelyhoodToMovingCreaturesToLibraryForResearching(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                    AI.MoveToResearchPolicy.class));
            ai.setChanceOfExploringToFindSpecials((short) file.readUnsignedByte());
            ai.setChanceOfFindingSpecialsWhenExploring((short) file.readUnsignedByte());
            ai.setFateOfImprisonedCreatures(ConversionUtils.parseEnum((short) file.readUnsignedByte(), AI.ImprisonedCreatureFatePolicy.class));
            player.setAiAttributes(ai);

            player.setTriggerId(ConversionUtils.readUnsignedShort(file));
            player.setPlayerId((short) file.readUnsignedByte());
            player.setStartingCameraX(ConversionUtils.readUnsignedShort(file));
            player.setStartingCameraY(ConversionUtils.readUnsignedShort(file));

            player.setName(ConversionUtils.readString(file, 32).trim());

            // Add to the hash by the player ID
            players.put(player.getPlayerId(), player);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the Terrain.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readTerrain(KwdHeader header, RandomAccessFile file) throws RuntimeException, IOException {

        // Read the terrain catalog
        if (terrainTiles == null) {
            logger.info("Reading terrain!");
            terrainTiles = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides terrain!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            Terrain terrain = new Terrain();

            terrain.setName(ConversionUtils.readString(file, 32).trim());
            terrain.setCompleteResource(readArtResource(file));
            terrain.setSideResource(readArtResource(file));
            terrain.setTopResource(readArtResource(file));
            terrain.setTaggedTopResource(readArtResource(file));
            terrain.setStringIds(readStringId(file));
            terrain.setDepth(ConversionUtils.readIntegerAsFloat(file));
            terrain.setLightHeight(ConversionUtils.readIntegerAsFloat(file));
            terrain.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedIntegerAsLong(file), Terrain.TerrainFlag.class));
            terrain.setDamage(ConversionUtils.readUnsignedShort(file));
            terrain.setEditorTextureId(ConversionUtils.readUnsignedShort(file));
            terrain.setUnk198(ConversionUtils.readUnsignedShort(file));
            terrain.setGoldValue(ConversionUtils.readUnsignedShort(file));
            terrain.setManaGain(ConversionUtils.readUnsignedShort(file));
            terrain.setMaxManaGain(ConversionUtils.readUnsignedShort(file));
            terrain.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            terrain.setNameStringId(ConversionUtils.readUnsignedShort(file));
            terrain.setMaxHealthEffectId(ConversionUtils.readUnsignedShort(file));
            terrain.setDestroyedEffectId(ConversionUtils.readUnsignedShort(file));
            terrain.setGeneralDescriptionStringId(ConversionUtils.readUnsignedShort(file));
            terrain.setStrengthStringId(ConversionUtils.readUnsignedShort(file));
            terrain.setWeaknessStringId(ConversionUtils.readUnsignedShort(file));
            int[] unk1ae = new int[16];
            for (int x = 0; x < unk1ae.length; x++) {
                unk1ae[x] = ConversionUtils.readUnsignedShort(file);
            }
            terrain.setUnk1ae(unk1ae);
            terrain.setWibbleH((short) file.readUnsignedByte());
            short[] leanH = new short[3];
            for (int x = 0; x < leanH.length; x++) {
                leanH[x] = (short) file.readUnsignedByte();
            }
            terrain.setLeanH(leanH);
            terrain.setWibbleV((short) file.readUnsignedByte());
            short[] leanV = new short[3];
            for (int x = 0; x < leanV.length; x++) {
                leanV[x] = (short) file.readUnsignedByte();
            }
            terrain.setLeanV(leanV);
            terrain.setTerrainId((short) file.readUnsignedByte());
            terrain.setStartingHealth(ConversionUtils.readUnsignedShort(file));
            terrain.setMaxHealthTypeTerrainId((short) file.readUnsignedByte());
            terrain.setDestroyedTypeTerrainId((short) file.readUnsignedByte());
            terrain.setTerrainLight(new Color(file.readUnsignedByte(), file.readUnsignedByte(), file.readUnsignedByte()));
            terrain.setTextureFrames((short) file.readUnsignedByte());

            terrain.setSoundCategory(ConversionUtils.readString(file, 32).trim());
            terrain.setMaxHealth(ConversionUtils.readUnsignedShort(file));
            terrain.setAmbientLight(new Color(file.readUnsignedByte(), file.readUnsignedByte(), file.readUnsignedByte()));

            terrain.setSoundCategoryFirstPerson(ConversionUtils.readString(file, 32).trim());
            terrain.setUnk224(ConversionUtils.readUnsignedInteger(file));

            // Add to the hash by the terrain ID
            terrainTiles.put(terrain.getTerrainId(), terrain);

            // See that we have water & lava set
            if (map.getWater() == null && terrain.getFlags().contains(Terrain.TerrainFlag.WATER)) {
                map.setWater(terrain);
            }
            if (map.getLava() == null && terrain.getFlags().contains(Terrain.TerrainFlag.LAVA)) {
                map.setLava(terrain);
            }

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads and parses an ArtResource object from the current file location (84
     * bytes)
     *
     * @param file the file stream to parse from
     * @return an ArtResource
     */
    private ArtResource readArtResource(RandomAccessFile file) throws IOException {
        ArtResource artResource = new ArtResource();

        // Read the data
        artResource.setName(ConversionUtils.readString(file, 64).trim());
        artResource.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedIntegerAsLong(file),
                ArtResource.ArtResourceFlag.class));

        long pointer = file.getFilePointer();
        file.seek(pointer + 12);
        artResource.setType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), ArtResource.ArtResourceType.class));
        if (artResource.getType() == ArtResourceType.ANIMATING_MESH) {
            artResource.setData("startAf", (short) file.readUnsignedByte()); // if HAS_START_ANIMATION
            artResource.setData("endAf", (short) file.readUnsignedByte()); // if HAS_END_ANIMATION
        } else {
            artResource.setData("unknown_n", ConversionUtils.readUnsignedShort(file));
        }
        artResource.setSometimesOne((short) file.readUnsignedByte());

        file.seek(pointer);
        switch (artResource.getType()) {
            case NONE: // skip empty type
                ConversionUtils.checkNull(file, 12);
                break;

            case SPRITE: // And alphas and images probably share the same attributes
            case ALPHA:
            case ADDITIVE_ALPHA:  // Images of different type
                artResource.setData("width", ConversionUtils.readIntegerAsFloat(file));
                artResource.setData("height", ConversionUtils.readIntegerAsFloat(file));
                artResource.setData("frames", ConversionUtils.readUnsignedInteger(file)); // if (ANIMATING_TEXTURE)
                break;

            case TERRAIN_MESH:
                artResource.setData("unknown_1", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("unknown_2", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("unknown_3", ConversionUtils.readUnsignedInteger(file));
                break;

            case MESH:
                artResource.setData("scale", ConversionUtils.readIntegerAsFloat(file));
                artResource.setData("frames", ConversionUtils.readUnsignedInteger(file)); // if (ANIMATING_TEXTURE)
                artResource.setData("unknown_1", ConversionUtils.readUnsignedInteger(file));
                break;

            case ANIMATING_MESH:
                artResource.setData("frames", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("fps", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("startDist", ConversionUtils.readUnsignedShort(file));
                artResource.setData("endDist", ConversionUtils.readUnsignedShort(file));
                break;

            case PROCEDURAL_MESH:
                artResource.setData("id", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("unknown_1", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("unknown_2", ConversionUtils.readUnsignedInteger(file));
                break;

            case MESH_COLLECTION: // FIXME nothing todo ?! has just the name, reference to GROP meshes probably
            case UNKNOWN:
                artResource.setData("unknown_1", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("unknown_2", ConversionUtils.readUnsignedInteger(file));
                artResource.setData("unknown_3", ConversionUtils.readUnsignedInteger(file));
                break;

            default:
                ConversionUtils.checkNull(file, 12);
                logger.log(Level.WARNING, "Unknown artResource type {0}", artResource.getType());
                break;
        }

        file.skipBytes(4);
        // If it has no name or the type is not known, return null
        if (artResource.getName().isEmpty() || artResource.getType() == null) {
            return null;
        }

        return artResource;
    }

    /**
     * Reads and parses an StringId object from the current file location
     *
     * @param file the file stream to parse from
     * @return an StringId
     */
    private StringId readStringId(RandomAccessFile file) throws IOException {

        // Read the IDs
        int[] ids = new int[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = ConversionUtils.readUnsignedInteger(file);
        }

        // And the unknowns
        short[] x14 = new short[4];
        for (int i = 0; i < x14.length; i++) {
            x14[i] = (short) file.readUnsignedByte();
        }

        return new StringId(ids, x14);
    }

    /**
     * Reads the Doors.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readDoors(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the doors catalog
        if (doors == null) {
            logger.info("Reading doors!");
            doors = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides doors!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            Door door = new Door();

            door.setName(ConversionUtils.readString(file, 32).trim());
            door.setMesh(readArtResource(file));
            door.setGuiIcon(readArtResource(file));
            door.setEditorIcon(readArtResource(file));
            door.setFlowerIcon(readArtResource(file));
            door.setOpenResource(readArtResource(file));
            door.setCloseResource(readArtResource(file));
            door.setHeight(ConversionUtils.readIntegerAsFloat(file));
            door.setHealthGain(ConversionUtils.readUnsignedShort(file));
            door.setUnknown1(ConversionUtils.readUnsignedShort(file));
            door.setUnknown2(ConversionUtils.readUnsignedInteger(file));
            door.setResearchTime(ConversionUtils.readUnsignedShort(file));
            door.setMaterial(ConversionUtils.parseEnum(file.readUnsignedByte(), Material.class));
            door.setTrapTypeId((short) file.readUnsignedByte());
            door.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file),
                    DoorFlag.class));
            door.setHealth(ConversionUtils.readUnsignedShort(file));
            door.setGoldCost(ConversionUtils.readUnsignedShort(file));
            short[] unknown3 = new short[2];
            for (int x = 0; x < unknown3.length; x++) {
                unknown3[x] = (short) file.readUnsignedByte();
            }
            door.setUnknown3(unknown3);
            door.setDeathEffectId(ConversionUtils.readUnsignedShort(file));
            door.setManufToBuild(ConversionUtils.readUnsignedInteger(file));
            door.setManaCost(ConversionUtils.readUnsignedShort(file));
            door.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            door.setNameStringId(ConversionUtils.readUnsignedShort(file));
            door.setGeneralDescriptionStringId(ConversionUtils.readUnsignedShort(file));
            door.setStrengthStringId(ConversionUtils.readUnsignedShort(file));
            door.setWeaknessStringId(ConversionUtils.readUnsignedShort(file));
            door.setDoorId((short) file.readUnsignedByte());
            door.setOrderInEditor((short) file.readUnsignedByte());
            door.setManufCrateObjectId((short) file.readUnsignedByte());
            door.setKeyObjectId((short) file.readUnsignedByte());

            door.setSoundCategory(ConversionUtils.readString(file, 32).trim());

            doors.put(door.getDoorId(), door);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the Traps.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readTraps(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the traps catalog
        if (traps == null) {
            logger.info("Reading traps!");
            traps = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides traps!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            Trap trap = new Trap();

            trap.setName(ConversionUtils.readString(file, 32).trim());
            trap.setMeshResource(readArtResource(file));
            trap.setGuiIcon(readArtResource(file));
            trap.setEditorIcon(readArtResource(file));
            trap.setFlowerIcon(readArtResource(file));
            trap.setFireResource(readArtResource(file));
            trap.setHeight(ConversionUtils.readIntegerAsFloat(file));
            trap.setRechargeTime(ConversionUtils.readIntegerAsFloat(file));
            trap.setChargeTime(ConversionUtils.readIntegerAsFloat(file));
            trap.setThreatDuration(ConversionUtils.readIntegerAsFloat(file));
            trap.setManaCostToFire(ConversionUtils.readUnsignedInteger(file));
            trap.setIdleEffectDelay(ConversionUtils.readIntegerAsFloat(file));
            trap.setTriggerData(ConversionUtils.readUnsignedInteger(file));
            trap.setShotData1(ConversionUtils.readUnsignedInteger(file));
            trap.setShotData2(ConversionUtils.readUnsignedInteger(file));
            trap.setResearchTime(ConversionUtils.readUnsignedShort(file));
            trap.setThreat(ConversionUtils.readUnsignedShort(file));
            trap.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file), Trap.TrapFlag.class));
            trap.setHealth(ConversionUtils.readUnsignedShort(file));
            trap.setManaCost(ConversionUtils.readUnsignedShort(file));
            trap.setPowerlessEffectId(ConversionUtils.readUnsignedShort(file));
            trap.setIdleEffectId(ConversionUtils.readUnsignedShort(file));
            trap.setDeathEffectId(ConversionUtils.readUnsignedShort(file));
            trap.setManufToBuild(ConversionUtils.readUnsignedShort(file));
            trap.setGeneralDescriptionStringId(ConversionUtils.readUnsignedShort(file));
            trap.setStrengthStringId(ConversionUtils.readUnsignedShort(file));
            trap.setWeaknessStringId(ConversionUtils.readUnsignedShort(file));
            trap.setManaUsage(ConversionUtils.readUnsignedShort(file));
            short[] unknown4 = new short[2];
            for (int x = 0; x < unknown4.length; x++) {
                unknown4[x] = (short) file.readUnsignedByte();
            }
            trap.setUnknown4(unknown4);
            trap.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            trap.setNameStringId(ConversionUtils.readUnsignedShort(file));
            trap.setShotsWhenArmed((short) file.readUnsignedByte());
            trap.setTriggerType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Trap.TriggerType.class));
            trap.setTrapId((short) file.readUnsignedByte());
            trap.setShotTypeId((short) file.readUnsignedByte());
            trap.setManufCrateObjectId((short) file.readUnsignedByte());

            trap.setSoundCategory(ConversionUtils.readString(file, 32).trim());
            trap.setMaterial(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Material.class));
            trap.setOrderInEditor((short) file.readUnsignedByte());
            trap.setShotOffset(ConversionUtils.readIntegerAsFloat(file),
                    ConversionUtils.readIntegerAsFloat(file),
                    ConversionUtils.readIntegerAsFloat(file));
            trap.setShotDelay(ConversionUtils.readIntegerAsFloat(file));
            trap.setHealthGain(ConversionUtils.readUnsignedShort(file));

            traps.put(trap.getTrapId(), trap);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the Rooms.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readRooms(KwdHeader header, RandomAccessFile file) throws RuntimeException, IOException {

        // Read the rooms catalog
        if (rooms == null) {
            logger.info("Reading rooms!");
            rooms = new HashMap<>(header.getItemCount());
            roomsByTerrainId = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides rooms!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            Room room = new Room();

            room.setName(ConversionUtils.readString(file, 32).trim());
            room.setGuiIcon(readArtResource(file));
            room.setEditorIcon(readArtResource(file));
            room.setCompleteResource(readArtResource(file));
            room.setStraightResource(readArtResource(file));
            room.setInsideCornerResource(readArtResource(file));
            room.setUnknownResource(readArtResource(file));
            room.setOutsideCornerResource(readArtResource(file));
            room.setWallResource(readArtResource(file));
            room.setCapResource(readArtResource(file));
            room.setCeilingResource(readArtResource(file));
            room.setCeilingHeight(ConversionUtils.readIntegerAsFloat(file));
            room.setResearchTime(ConversionUtils.readUnsignedShort(file));
            room.setTorchIntensity(ConversionUtils.readUnsignedShort(file));
            room.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file),
                    Room.RoomFlag.class));
            room.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            room.setNameStringId(ConversionUtils.readUnsignedShort(file));
            room.setCost(ConversionUtils.readUnsignedShort(file));
            room.setFightEffectId(ConversionUtils.readUnsignedShort(file));
            room.setGeneralDescriptionStringId(ConversionUtils.readUnsignedShort(file));
            room.setStrengthStringId(ConversionUtils.readUnsignedShort(file));
            room.setTorchHeight(ConversionUtils.readShortAsFloat(file));
            List<Integer> roomEffects = new ArrayList<>(8);
            for (int x = 0; x < 8; x++) {
                int effectId = ConversionUtils.readUnsignedShort(file);
                roomEffects.add(effectId);
            }
            room.setEffects(roomEffects);
            room.setRoomId((short) file.readUnsignedByte());
            room.setReturnPercentage((short) file.readUnsignedByte());
            room.setTerrainId((short) file.readUnsignedByte());
            room.setTileConstruction(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Room.TileConstruction.class));
            room.setCreatedCreatureId((short) file.readUnsignedByte());
            room.setTorchColor(new Color(file.readUnsignedByte(), file.readUnsignedByte(), file.readUnsignedByte())); // This is the editor is rather weird
            List<Short> roomObjects = new ArrayList<>(8);
            for (int x = 0; x < 8; x++) {
                short objectId = (short) file.readUnsignedByte();
                roomObjects.add(objectId);
            }
            room.setObjects(roomObjects);

            room.setSoundCategory(ConversionUtils.readString(file, 32).trim());
            room.setOrderInEditor((short) file.readUnsignedByte());
            room.setTorchRadius(ConversionUtils.readIntegerAsFloat(file));
            room.setTorch(readArtResource(file));
            room.setRecommendedSizeX((short) file.readUnsignedByte());
            room.setRecommendedSizeY((short) file.readUnsignedByte());
            room.setHealthGain(ConversionUtils.readShort(file));

            // Add to the hash by the room ID
            rooms.put(room.getRoomId(), room);

            // And by the terrain ID
            roomsByTerrainId.put(room.getTerrainId(), room);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the *.kwd
     *
     * @param file the original map KWD file
     * @throws RuntimeException reading may fail
     */
    private void readMapInfo(KwdHeader header, RandomAccessFile data) throws IOException {

        //Additional header data
        if (gameLevel == null) {
            logger.info("Reading level info!");
            gameLevel = new GameLevel();
        } else {
            logger.warning("Overrides level!");
        }

        //Property data
        String name = ConversionUtils.readStringUtf16(data, 64).trim();
        if (name != null && !name.isEmpty() && name.toLowerCase().endsWith(".kwd")) {
            name = name.substring(0, name.length() - 4);
        }
        gameLevel.setName(name);
        gameLevel.setDescription(ConversionUtils.readStringUtf16(data, 1024).trim());
        gameLevel.setAuthor(ConversionUtils.readStringUtf16(data, 64).trim());
        gameLevel.setEmail(ConversionUtils.readStringUtf16(data, 64).trim());
        gameLevel.setInformation(ConversionUtils.readStringUtf16(data, 1024).trim());

        gameLevel.setTriggerId(ConversionUtils.readUnsignedShort(data));
        gameLevel.setTicksPerSec(ConversionUtils.readUnsignedShort(data));
        short[] x01184 = new short[520];
        for (int x = 0; x < x01184.length; x++) {
            x01184[x] = (short) data.readUnsignedByte();
        }
        gameLevel.setX01184(x01184);
        List<String> messages = new ArrayList<>(); // I don't know if we need the index, level 19 & 3 has messages, but they are rare
        for (int x = 0; x < 512; x++) {
            String message = ConversionUtils.readStringUtf16(data, 20).trim();
            if (!message.isEmpty()) {
                messages.add(message);
            }
        }
        gameLevel.setMessages(messages);

        gameLevel.setLvlFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedShort(data), LevFlag.class));
        gameLevel.setSoundCategory(ConversionUtils.readString(data, 32).trim());
        gameLevel.setTalismanPieces((short) data.readUnsignedByte());

        for (int x = 0; x < 4; x++) {
            LevelReward reward = ConversionUtils.parseEnum((short) data.readUnsignedByte(), LevelReward.class);
            gameLevel.addRewardPrev(reward);
        }

        for (int x = 0; x < 4; x++) {
            LevelReward reward = ConversionUtils.parseEnum((short) data.readUnsignedByte(), LevelReward.class);
            gameLevel.addRewardNext(reward);
        }

        gameLevel.setSoundTrack((short) data.readUnsignedByte());
        gameLevel.setTextTableId(ConversionUtils.parseEnum((short) data.readUnsignedByte(), TextTable.class));
        gameLevel.setTextTitleId(ConversionUtils.readUnsignedShort(data));
        gameLevel.setTextPlotId(ConversionUtils.readUnsignedShort(data));
        gameLevel.setTextDebriefId(ConversionUtils.readUnsignedShort(data));
        gameLevel.setTextObjectvId(ConversionUtils.readUnsignedShort(data));
        gameLevel.setX063c3(ConversionUtils.readUnsignedShort(data));
        gameLevel.setTextSubobjctvId1(ConversionUtils.readUnsignedShort(data));
        gameLevel.setTextSubobjctvId2(ConversionUtils.readUnsignedShort(data));
        gameLevel.setTextSubobjctvId3(ConversionUtils.readUnsignedShort(data));
        gameLevel.setSpeclvlIdx(ConversionUtils.readUnsignedShort(data));

        // Swap the arrays for more convenient data format
        short[] textIntrdcOverrdObj = new short[8];
        for (int x = 0; x < textIntrdcOverrdObj.length; x++) {
            textIntrdcOverrdObj[x] = (short) data.readUnsignedByte();
        }
        int[] textIntrdcOverrdId = new int[8];
        for (int x = 0; x < textIntrdcOverrdId.length; x++) {
            textIntrdcOverrdId[x] = ConversionUtils.readUnsignedShort(data);
        }
        Map<Short, Integer> introductionOverrideTextIds = new HashMap<>(8);
        for (int x = 0; x < textIntrdcOverrdObj.length; x++) {
            if (textIntrdcOverrdObj[x] > 0) {

                // Over 0 is a valid creature ID
                introductionOverrideTextIds.put(textIntrdcOverrdObj[x], textIntrdcOverrdId[x]);
            }
        }
        gameLevel.setIntroductionOverrideTextIds(introductionOverrideTextIds);

        gameLevel.setTerrainPath(ConversionUtils.readString(data, 32).trim());
        if (header.dataSize > 25603) { // Some very old files are smaller, namely the FrontEnd3DLevel map in some version
            gameLevel.setOneShotHornyLev((short) data.readUnsignedByte());
            gameLevel.setPlayerCount((short) data.readUnsignedByte());
            gameLevel.addRewardPrev(ConversionUtils.parseEnum((short) data.readUnsignedByte(),
                    LevelReward.class));
            gameLevel.addRewardNext(ConversionUtils.parseEnum((short) data.readUnsignedByte(),
                    LevelReward.class));
            gameLevel.setSpeechHornyId(ConversionUtils.readUnsignedShort(data));
            gameLevel.setSpeechPrelvlId(ConversionUtils.readUnsignedShort(data));
            gameLevel.setSpeechPostlvlWin(ConversionUtils.readUnsignedShort(data));
            gameLevel.setSpeechPostlvlLost(ConversionUtils.readUnsignedShort(data));
            gameLevel.setSpeechPostlvlNews(ConversionUtils.readUnsignedShort(data));
            gameLevel.setSpeechPrelvlGenr(ConversionUtils.readUnsignedShort(data));
            gameLevel.setHeroName(ConversionUtils.readStringUtf16(data, 32).trim());
        }

        // Paths and the unknown array
        int checkThree = ConversionUtils.readUnsignedInteger(data);
        if (checkThree != 222) {
            throw new RuntimeException("Level file is corrupted");
        }
        // the last part of file have size contentSize
        int contentSize = ConversionUtils.readUnsignedInteger(data);

        List<FilePath> paths = new ArrayList<>(header.getItemCount());
        for (int x = 0; x < header.getItemCount(); x++) {
            FilePath filePath = new FilePath();
            filePath.setId(ConversionUtils.parseEnum(ConversionUtils.readUnsignedInteger(data),
                    MapDataTypeEnum.class));
            filePath.setUnknown2(ConversionUtils.readInteger(data));
            String path = ConversionUtils.readString(data, 64).trim();

            // Tweak the paths
            // Paths are relative to the base path, may or may not have an extension (assume kwd if none found)
            path = ConversionUtils.convertFileSeparators(path);
            if (!".".equals(path.substring(path.length() - 4, path.length() - 3))) {
                path = path.concat(".kwd");
            }

            // See if the globals are present
            if (filePath.getId() == MapDataTypeEnum.GLOBALS) {
                customOverrides = true;
                logger.info("The map uses custom overrides!");
            }

            filePath.setPath(path);

            paths.add(filePath);
        }
        gameLevel.setPaths(paths);

        // Hmm, seems that normal maps don't refer the effects nor effect elements
        if (!customOverrides) {
            FilePath file = new FilePath(MapDataTypeEnum.EFFECTS, PathUtils.DKII_EDITOR_FOLDER + "Effects.kwd");
            if (!gameLevel.getPaths().contains(file)) {
                gameLevel.getPaths().add(file);
            }

            file = new FilePath(MapDataTypeEnum.EFFECT_ELEMENTS, PathUtils.DKII_EDITOR_FOLDER + "EffectElements.kwd");
            if (!gameLevel.getPaths().contains(file)) {
                gameLevel.getPaths().add(file);
            }
        }

        int[] unknown = new int[header.getHeight()];
        for (int x = 0; x < unknown.length; x++) {
            unknown[x] = ConversionUtils.readUnsignedInteger(data);
        }
        gameLevel.setUnknown(unknown);
    }

    /**
     * Reads the Creatures.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readCreatures(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the creatures catalog
        if (creatures == null) {
            logger.info("Reading creatures!");
            creatures = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides creatures!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            Creature creature = new Creature();

            creature.setName(ConversionUtils.readString(file, 32).trim());
            // 39 ArtResources (with IMPs these are not 100% same)
            byte[] bytes = new byte[84];
            file.read(bytes);
            creature.setUnknown1Resource(bytes);  // all 0: Maiden Of The Nest, Prince Balder, Horny. Other the same
            creature.setAnimation(AnimationType.WALK, readArtResource(file));
            creature.setAnimation(AnimationType.RUN, readArtResource(file));
            creature.setAnimation(AnimationType.DRAGGED, readArtResource(file));
            creature.setAnimation(AnimationType.RECOIL_FORWARDS, readArtResource(file));
            creature.setAnimation(AnimationType.MELEE_ATTACK, readArtResource(file));
            creature.setAnimation(AnimationType.CAST_SPELL, readArtResource(file));
            creature.setAnimation(AnimationType.DIE, readArtResource(file));
            creature.setAnimation(AnimationType.HAPPY, readArtResource(file));
            creature.setAnimation(AnimationType.ANGRY, readArtResource(file));
            creature.setAnimation(AnimationType.STUNNED, readArtResource(file));
            creature.setAnimation(AnimationType.IN_HAND, readArtResource(file));
            creature.setAnimation(AnimationType.SLEEPING, readArtResource(file));
            creature.setAnimation(AnimationType.EATING, readArtResource(file));
            creature.setAnimation(AnimationType.RESEARCHING, readArtResource(file));
            creature.setAnimation(AnimationType.NULL_2, readArtResource(file));
            creature.setAnimation(AnimationType.NULL_1, readArtResource(file));
            creature.setAnimation(AnimationType.TORTURED_WHEEL, readArtResource(file));
            creature.setAnimation(AnimationType.NULL_3, readArtResource(file));
            creature.setAnimation(AnimationType.DRINKING, readArtResource(file));
            creature.setAnimation(AnimationType.IDLE_1, readArtResource(file));
            creature.setAnimation(AnimationType.RECOIL_BACKWARDS, readArtResource(file));
            creature.setAnimation(AnimationType.MANUFACTURING, readArtResource(file));
            creature.setAnimation(AnimationType.PRAYING, readArtResource(file));
            creature.setAnimation(AnimationType.FALLBACK, readArtResource(file));
            creature.setAnimation(AnimationType.TORTURED_CHAIR, readArtResource(file));
            creature.setAnimation(AnimationType.TORTURED_CHAIR_SKELETON, readArtResource(file));
            creature.setAnimation(AnimationType.GET_UP, readArtResource(file));
            creature.setAnimation(AnimationType.DANCE, readArtResource(file));
            creature.setAnimation(AnimationType.DRUNK, readArtResource(file));
            creature.setAnimation(AnimationType.ENTRANCE, readArtResource(file));
            creature.setAnimation(AnimationType.IDLE_2, readArtResource(file));
            creature.setAnimation(AnimationType.SPECIAL_1, readArtResource(file));
            creature.setAnimation(AnimationType.SPECIAL_2, readArtResource(file));
            creature.setAnimation(AnimationType.DRUNKED_WALK, readArtResource(file));
            creature.setAnimation(AnimationType.ROAR, readArtResource(file)); // FIXME
            creature.setAnimation(AnimationType.NULL_4, readArtResource(file));

            creature.setIcon1Resource(readArtResource(file));
            creature.setIcon2Resource(readArtResource(file));
            //
            creature.setUnkcec(ConversionUtils.readUnsignedShort(file));
            creature.setUnkcee(ConversionUtils.readUnsignedInteger(file));
            creature.setUnkcf2(ConversionUtils.readUnsignedInteger(file));
            creature.setOrderInEditor((short) file.readUnsignedByte());
            creature.setAngerStringIdGeneral(ConversionUtils.readUnsignedShort(file));
            creature.setShotDelay(ConversionUtils.readIntegerAsFloat(file));
            creature.setOlhiEffectId(ConversionUtils.readUnsignedShort(file));
            creature.setIntroductionStringId(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setPerceptionRange(ConversionUtils.readIntegerAsFloat(file));
            creature.setAngerStringIdLair(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdFood(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdPay(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdWork(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdSlap(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdHeld(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdLonely(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdHatred(ConversionUtils.readUnsignedShort(file));
            creature.setAngerStringIdTorture(ConversionUtils.readUnsignedShort(file));

            creature.setTranslationSoundGategory(ConversionUtils.readString(file, 32).trim());
            creature.getAttributes().setShuffleSpeed(ConversionUtils.readIntegerAsFloat(file));
            creature.setCloneCreatureId((short) file.readUnsignedByte());
            creature.setFirstPersonGammaEffect(ConversionUtils.parseEnum(file.readUnsignedByte(), Creature.GammaEffect.class));
            creature.setFirstPersonWalkCycleScale((short) file.readUnsignedByte());
            creature.setIntroCameraPathIndex((short) file.readUnsignedByte());
            creature.setUnk2e2((short) file.readUnsignedByte());
            creature.setPortraitResource(readArtResource(file));
            creature.setLight(readLight(file));
            Attraction[] attractions = new Attraction[2];
            for (int x = 0; x < attractions.length; x++) {
                Attraction attraction = creature.new Attraction();
                attraction.setPresent(ConversionUtils.readUnsignedInteger(file));
                attraction.setRoomId(ConversionUtils.readUnsignedShort(file));
                attraction.setRoomSize(ConversionUtils.readUnsignedShort(file));
                attractions[x] = attraction;
            }
            creature.setAttractions(attractions);
            creature.setFirstPersonWaddleScale(ConversionUtils.readIntegerAsFloat(file));
            creature.setFirstPersonOscillateScale(ConversionUtils.readIntegerAsFloat(file));
            List<Spell> spells = new ArrayList<>(3);
            for (int x = 0; x < 3; x++) {
                Spell spell = creature.new Spell();
                spell.setShotOffset(ConversionUtils.readIntegerAsFloat(file),
                        ConversionUtils.readIntegerAsFloat(file),
                        ConversionUtils.readIntegerAsFloat(file));
                spell.setX0c((short) file.readUnsignedByte());
                spell.setPlayAnimation(((short) file.readUnsignedByte() == 1));
                spell.setX0e((short) file.readUnsignedByte()); // This value can changed when you not change anything on map, only save it
                spell.setX0f((short) file.readUnsignedByte());
                spell.setShotDelay(ConversionUtils.readIntegerAsFloat(file));
                spell.setX14((short) file.readUnsignedByte());
                spell.setX15((short) file.readUnsignedByte());
                spell.setCreatureSpellId((short) file.readUnsignedByte());
                spell.setLevelAvailable((short) file.readUnsignedByte());
                if (spell.getCreatureSpellId() != 0) {
                    spells.add(spell);
                }
            }
            creature.setSpells(spells);
            Creature.Resistance[] resistances = new Creature.Resistance[4];
            for (int x = 0; x < resistances.length; x++) {
                Creature.Resistance resistance = creature.new Resistance();
                resistance.setAttackType(ConversionUtils.parseEnum(file.readUnsignedByte(),
                        Creature.AttackType.class));
                resistance.setValue((short) file.readUnsignedByte());
                resistances[x] = resistance;
            }
            creature.setResistances(resistances);
            creature.setHappyJobs(readJobPreferences(3, creature, file));
            creature.setUnhappyJobs(readJobPreferences(2, creature, file));
            creature.setAngryJobs(readJobPreferences(3, creature, file));
            Creature.JobType[] hateJobs = new Creature.JobType[2];
            for (int x = 0; x < hateJobs.length; x++) {
                hateJobs[x] = ConversionUtils.parseEnum(ConversionUtils.readUnsignedInteger(file),
                        Creature.JobType.class);
            }
            creature.setHateJobs(hateJobs);
            JobAlternative[] alternatives = new JobAlternative[3];
            for (int x = 0; x < alternatives.length; x++) {
                JobAlternative alternative = creature.new JobAlternative();
                alternative.setJobType(ConversionUtils.parseEnum(ConversionUtils.readUnsignedInteger(file),
                        Creature.JobType.class));
                alternative.setMoodChange(ConversionUtils.readUnsignedShort(file));
                alternative.setManaChange(ConversionUtils.readUnsignedShort(file));
            }
            creature.setAlternativeJobs(alternatives);
            creature.setAnimationOffsets(OffsetType.PORTAL_ENTRANCE,
                    ConversionUtils.readIntegerAsFloat(file),
                    ConversionUtils.readIntegerAsFloat(file),
                    ConversionUtils.readIntegerAsFloat(file)
            );
            creature.setUnkea0(ConversionUtils.readInteger(file));
            creature.getAttributes().setHeight(ConversionUtils.readIntegerAsFloat(file));
            creature.setUnkea8(ConversionUtils.readIntegerAsFloat(file));
            creature.setUnk3ab(ConversionUtils.readUnsignedInteger(file));
            creature.getAttributes().setEyeHeight(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setSpeed(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setRunSpeed(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setHungerRate(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setTimeAwake(ConversionUtils.readUnsignedInteger(file));
            creature.getAttributes().setTimeSleep(ConversionUtils.readUnsignedInteger(file));
            creature.getAttributes().setDistanceCanSee(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setDistanceCanHear(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setStunDuration(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setGuardDuration(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setIdleDuration(ConversionUtils.readIntegerAsFloat(file));
            creature.getAttributes().setSlapFearlessDuration(ConversionUtils.readIntegerAsFloat(file));
            creature.setUnkee0(ConversionUtils.readInteger(file));
            creature.setUnkee4(ConversionUtils.readInteger(file));
            creature.getAttributes().setPossessionManaCost(ConversionUtils.readShort(file));
            creature.getAttributes().setOwnLandHealthIncrease(ConversionUtils.readShort(file));
            creature.setMeleeRange(ConversionUtils.readIntegerAsFloat(file));
            creature.setUnkef0(ConversionUtils.readUnsignedInteger(file));
            creature.getAttributes().setTortureTimeToConvert(ConversionUtils.readIntegerAsFloat(file));
            creature.setMeleeRecharge(ConversionUtils.readIntegerAsFloat(file));
            // The flags is actually very big, pushing the boundaries, a true uint32, need to -> long
            creature.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedIntegerAsLong(file),
                    Creature.CreatureFlag.class));
            creature.getAttributes().setExpForNextLevel(ConversionUtils.readUnsignedShort(file));
            creature.setJobClass(ConversionUtils.parseEnum(file.readUnsignedByte(), Creature.JobClass.class));
            creature.setFightStyle(ConversionUtils.parseEnum(file.readUnsignedByte(), Creature.FightStyle.class));
            creature.getAttributes().setExpPerSecond(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setExpPerSecondTraining(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setResearchPerSecond(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setManufacturePerSecond(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setHp(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setHpFromChicken(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setFear(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setThreat(ConversionUtils.readUnsignedShort(file));
            creature.setMeleeDamage(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setSlapDamage(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setManaGenPrayer(ConversionUtils.readUnsignedShort(file));
            creature.setUnk3cb(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setPay(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setMaxGoldHeld(ConversionUtils.readUnsignedShort(file));
            creature.setUnk3cc(ConversionUtils.readShortAsFloat(file));
            creature.getAttributes().setDecomposeValue(ConversionUtils.readUnsignedShort(file));
            creature.setNameStringId(ConversionUtils.readUnsignedShort(file));
            creature.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            creature.getAttributes().setAngerNoLair(ConversionUtils.readShort(file));
            creature.getAttributes().setAngerNoFood(ConversionUtils.readShort(file));
            creature.getAttributes().setAngerNoPay(ConversionUtils.readShort(file));
            creature.getAttributes().setAngerNoWork(ConversionUtils.readShort(file));
            creature.getAttributes().setAngerSlap(ConversionUtils.readShort(file));
            creature.getAttributes().setAngerInHand(ConversionUtils.readShort(file));
            creature.getAttributes().setInitialGoldHeld(ConversionUtils.readShort(file));
            creature.setEntranceEffectId(ConversionUtils.readUnsignedShort(file));
            creature.setGeneralDescriptionStringId(ConversionUtils.readUnsignedShort(file));
            creature.setStrengthStringId(ConversionUtils.readUnsignedShort(file));
            creature.setWeaknessStringId(ConversionUtils.readUnsignedShort(file));
            creature.setSlapEffectId(ConversionUtils.readUnsignedShort(file));
            creature.setDeathEffectId(ConversionUtils.readUnsignedShort(file));
            creature.setMelee1Swipe(ConversionUtils.parseEnum(file.readUnsignedByte(), Creature.Swipe.class));
            creature.setMelee2Swipe(ConversionUtils.parseEnum(file.readUnsignedByte(), Creature.Swipe.class));
            creature.setMelee3Swipe(ConversionUtils.parseEnum(file.readUnsignedByte(), Creature.Swipe.class));
            creature.setSpellSwipe(ConversionUtils.parseEnum(file.readUnsignedByte(), Creature.Swipe.class));
            creature.setFirstPersonSpecialAbility1(ConversionUtils.parseEnum(file.readUnsignedByte(),
                    Creature.SpecialAbility.class));
            creature.setFirstPersonSpecialAbility2(ConversionUtils.parseEnum(file.readUnsignedByte(),
                    Creature.SpecialAbility.class));
            short[] unkf48 = new short[3];
            for (int x = 0; x < unkf48.length; x++) {
                unkf48[x] = (short) file.readUnsignedByte();
            }
            creature.setUnkf48(unkf48);
            creature.setCreatureId((short) file.readUnsignedByte());
            short[] unk3ea = new short[2];
            for (int x = 0; x < unk3ea.length; x++) {
                unk3ea[x] = (short) file.readUnsignedByte();
            }
            creature.setUnk3ea(unk3ea);
            creature.getAttributes().setHungerFill((short) file.readUnsignedByte());
            creature.getAttributes().setUnhappyThreshold((short) file.readUnsignedByte());
            creature.setMeleeAttackType(ConversionUtils.parseEnum(file.readUnsignedByte(),
                    Creature.AttackType.class));
            creature.setUnk3eb2((short) file.readUnsignedByte());
            creature.setLairObjectId((short) file.readUnsignedByte());
            creature.setUnk3f1((short) file.readUnsignedByte());
            creature.setDeathFallDirection(ConversionUtils.parseEnum(file.readUnsignedByte(),
                    Creature.DeathFallDirection.class));
            creature.setUnk3f2((short) file.readUnsignedByte());

            creature.setSoundCategory(ConversionUtils.readString(file, 32).trim());
            creature.setMaterial(ConversionUtils.parseEnum(file.readUnsignedByte(), Material.class));
            creature.setFirstPersonFilterResource(readArtResource(file));
            creature.setUnkfcb(ConversionUtils.readUnsignedShort(file));
            creature.setUnk4(ConversionUtils.readIntegerAsFloat(file));
            creature.setAnimation(AnimationType.DRUNKED_IDLE, readArtResource(file));
            creature.setSpecial1Swipe(ConversionUtils.parseEnum(file.readUnsignedByte(),
                    Creature.Swipe.class));
            creature.setSpecial2Swipe(ConversionUtils.parseEnum(file.readUnsignedByte(),
                    Creature.Swipe.class));
            creature.setFirstPersonMeleeResource(readArtResource(file));
            creature.setUnk6(ConversionUtils.readUnsignedInteger(file));
            creature.getAttributes().setTortureHpChange(ConversionUtils.readShort(file));
            creature.getAttributes().setTortureMoodChange(ConversionUtils.readShort(file));
            creature.setAnimation(AnimationType.SWIPE, readArtResource(file));
            creature.setAnimation(AnimationType.IDLE_3, readArtResource(file));
            creature.setAnimation(AnimationType.IDLE_4, readArtResource(file));
            creature.setAnimation(AnimationType.IDLE_3_1, readArtResource(file));
            creature.setAnimation(AnimationType.IDLE_4_1, readArtResource(file));
            creature.setAnimation(AnimationType.DIG, readArtResource(file));

            OffsetType[] offsetTypes = new OffsetType[] {OffsetType.FALL_BACK_GET_UP,
                OffsetType.PRAYING, OffsetType.CORPSE, OffsetType.OFFSET_5,
                OffsetType.OFFSET_6, OffsetType.OFFSET_7, OffsetType.OFFSET_8};
            for (OffsetType type : offsetTypes) {
                creature.setAnimationOffsets(type,
                        ConversionUtils.readIntegerAsFloat(file),
                        ConversionUtils.readIntegerAsFloat(file),
                        ConversionUtils.readIntegerAsFloat(file)
                );
            }
            creature.setAnimation(AnimationType.BACK_OFF, readArtResource(file));
            X1323[] x1323s = new X1323[48];
            for (int x = 0; x < x1323s.length; x++) {
                X1323 x1323 = creature.new X1323();
                x1323.setX00(ConversionUtils.readUnsignedShort(file));
                x1323.setX02(ConversionUtils.readUnsignedShort(file));
                x1323s[x] = x1323;
            }
            creature.setX1323(x1323s);
            creature.setAnimation(AnimationType.STAND_STILL, readArtResource(file));
            creature.setAnimation(AnimationType.STEALTH_WALK, readArtResource(file));
            creature.setAnimation(AnimationType.DEATH_POSE, readArtResource(file));
            creature.setUniqueNameTextId(ConversionUtils.readUnsignedShort(file));
            int[] x14e1 = new int[2];
            for (int x = 0; x < x14e1.length; x++) {
                x14e1[x] = ConversionUtils.readUnsignedInteger(file);
            }
            creature.setX14e1(x14e1);
            creature.setFirstPersonSpecialAbility1Count(ConversionUtils.readUnsignedInteger(file));
            creature.setFirstPersonSpecialAbility2Count(ConversionUtils.readUnsignedInteger(file));
            creature.setUniqueResource(readArtResource(file));
            creature.setFlags3(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedIntegerAsLong(file),
                    Creature.CreatureFlag3.class));

            // The normal file stops here, but if it is the bigger one, continue
            if (header.getItemSize() >= 5537l) {
                short[] unknownExtraBytes = new short[80];
                for (int x = 0; x < unknownExtraBytes.length; x++) {
                    unknownExtraBytes[x] = (short) file.readUnsignedByte();
                }
                creature.setUnknownExtraBytes(unknownExtraBytes);
                creature.setFlags2(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedIntegerAsLong(file),
                        Creature.CreatureFlag2.class));
                creature.setUnknown(ConversionUtils.readUnsignedShort(file));
                creature.setUnknown_1(ConversionUtils.readShortAsFloat(file));
            }

            // Add to the hash by the creature ID
            creatures.put(creature.getCreatureId(), creature);

            // Set the imp
            if (imp == null && creature.getFlags().contains(Creature.CreatureFlag.IS_WORKER) && creature.getFlags().contains(Creature.CreatureFlag.IS_EVIL)) {
                imp = creature;
            }

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Read job preferences for a creature
     *
     * @param count amount of job preference records
     * @param creature creature instance, just for creating a job preference
     * instance
     * @param file the file to read the data from
     * @return job preferences
     * @throws IOException may fail
     */
    private Creature.JobPreference[] readJobPreferences(int count, Creature creature, RandomAccessFile file) throws IOException {
        Creature.JobPreference[] preferences = new Creature.JobPreference[count];
        for (int x = 0; x < preferences.length; x++) {
            Creature.JobPreference jobPreference = creature.new JobPreference();
            jobPreference.setJobType(ConversionUtils.parseEnum(ConversionUtils.readUnsignedInteger(file), Creature.JobType.class));
            jobPreference.setMoodChange(ConversionUtils.readUnsignedShort(file));
            jobPreference.setManaChange(ConversionUtils.readUnsignedShort(file));
            jobPreference.setChance((short) file.readUnsignedByte());
            jobPreference.setX09((short) file.readUnsignedByte());
            jobPreference.setX0a((short) file.readUnsignedByte());
            jobPreference.setX0b((short) file.readUnsignedByte());
            preferences[x] = jobPreference;
        }
        return preferences;
    }

    /**
     * Reads and parses an Light object from the current file location (24
     * bytes)
     *
     * @param file the file stream to parse from
     * @return a Light
     */
    private Light readLight(RandomAccessFile file) throws IOException {
        Light light = new Light();

        // Read the data
        light.setmKPos(ConversionUtils.readIntegerAsFloat(file),
                ConversionUtils.readIntegerAsFloat(file),
                ConversionUtils.readIntegerAsFloat(file));
        light.setRadius(ConversionUtils.readIntegerAsFloat(file));
        light.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file),
                Light.LightFlag.class));
        light.setColor(file.readUnsignedByte(), file.readUnsignedByte(), file.readUnsignedByte(), file.readUnsignedByte());

        return light;
    }

    /**
     * Reads the Objects.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readObjects(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the objects catalog
        if (objects == null) {
            logger.info("Reading objects!");
            objects = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides objects!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            GameObject object = new GameObject();

            object.setName(ConversionUtils.readString(file, 32).trim());
            object.setMeshResource(readArtResource(file));
            object.setGuiIconResource(readArtResource(file));
            object.setInHandIconResource(readArtResource(file));
            object.setInHandMeshResource(readArtResource(file));
            object.setkUnknownResource(readArtResource(file));
            List<ArtResource> additionalResources = new ArrayList<>(4);
            for (int x = 0; x < 4; x++) {
                ArtResource resource = readArtResource(file);
                if (resource != null) {
                    additionalResources.add(resource);
                }
            }
            object.setAdditionalResources(additionalResources);
            object.setLight(readLight(file));
            object.setWidth(ConversionUtils.readIntegerAsFloat(file));
            object.setHeight(ConversionUtils.readIntegerAsFloat(file));
            object.setMass(ConversionUtils.readIntegerAsFloat(file));
            object.setSpeed(ConversionUtils.readIntegerAsFloat(file));
            object.setAirFriction(ConversionUtils.readIntegerAsDouble(file));
            object.setMaterial(ConversionUtils.parseEnum(file.readUnsignedByte(), Material.class));
            short[] unknown3 = new short[3];
            for (int x = 0; x < unknown3.length; x++) {
                unknown3[x] = (short) file.readUnsignedByte();
            }
            object.setUnknown3(unknown3);
            object.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedIntegerAsLong(file), GameObject.ObjectFlag.class));
            object.setHp(ConversionUtils.readUnsignedShort(file));
            object.setMaxAngle(ConversionUtils.readUnsignedShort(file));
            object.setX34c(ConversionUtils.readUnsignedShort(file));
            object.setManaValue(ConversionUtils.readUnsignedShort(file));
            object.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            object.setNameStringId(ConversionUtils.readUnsignedShort(file));
            object.setSlapEffectId(ConversionUtils.readUnsignedShort(file));
            object.setDeathEffectId(ConversionUtils.readUnsignedShort(file));
            object.setMiscEffectId(ConversionUtils.readUnsignedShort(file));
            object.setObjectId((short) file.readUnsignedByte());
            object.setStartState(ConversionUtils.parseEnum((short) file.readUnsignedByte(), GameObject.State.class));
            object.setRoomCapacity((short) file.readUnsignedByte());
            object.setPickUpPriority((short) file.readUnsignedByte());

            object.setSoundCategory(ConversionUtils.readString(file, 32).trim());

            // Add to the hash by the object ID
            objects.put(object.getObjectId(), object);

            // See special objects
            if (levelGem == null && object.getFlags().contains(GameObject.ObjectFlag.OBJECT_TYPE_LEVEL_GEM)) {
                levelGem = object;
            }

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the CreatureSpells.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readCreatureSpells(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the creature spells catalog
        if (creatureSpells == null) {
            logger.info("Reading creature spells!");
            creatureSpells = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides creature spells!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            CreatureSpell creatureSpell = new CreatureSpell();

            creatureSpell.setName(ConversionUtils.readString(file, 32).trim());
            creatureSpell.setEditorIcon(readArtResource(file));
            creatureSpell.setGuiIcon(readArtResource(file));
            creatureSpell.setShotData1(ConversionUtils.readUnsignedInteger(file));
            creatureSpell.setShotData2(ConversionUtils.readUnsignedInteger(file));
            creatureSpell.setRange(ConversionUtils.readIntegerAsFloat(file));
            creatureSpell.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file),
                    CreatureSpell.CreatureSpellFlag.class));
            creatureSpell.setCombatPoints(ConversionUtils.readUnsignedShort(file));
            creatureSpell.setSoundEvent(ConversionUtils.readUnsignedShort(file));
            creatureSpell.setNameStringId(ConversionUtils.readUnsignedShort(file));
            creatureSpell.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            creatureSpell.setGeneralDescriptionStringId(ConversionUtils.readUnsignedShort(file));
            creatureSpell.setStrengthStringId(ConversionUtils.readUnsignedShort(file));
            creatureSpell.setWeaknessStringId(ConversionUtils.readUnsignedShort(file));
            creatureSpell.setCreatureSpellId((short) file.readUnsignedByte());
            creatureSpell.setShotTypeId((short) file.readUnsignedByte());
            creatureSpell.setAlternativeShotId((short) file.readUnsignedByte());
            creatureSpell.setAlternativeRoomId((short) file.readUnsignedByte());
            creatureSpell.setRechargeTime(ConversionUtils.readIntegerAsFloat(file));
            creatureSpell.setAlternativeShot(ConversionUtils.parseEnum(file.readUnsignedByte(),
                    CreatureSpell.AlternativeShot.class));
            short[] data3 = new short[27];
            for (int x = 0; x < data3.length; x++) {
                data3[x] = (short) file.readUnsignedByte();
            }
            creatureSpell.setUnused(data3);

            // Add to the list
            creatureSpells.put(creatureSpell.getCreatureSpellId(), creatureSpell);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the EffectElements.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readEffectElements(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the effect elements catalog
        if (effectElements == null) {
            logger.info("Reading effect elements!");
            effectElements = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides effect elements!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            EffectElement effectElement = new EffectElement();

            effectElement.setName(ConversionUtils.readString(file, 32).trim());
            effectElement.setArtResource(readArtResource(file));
            effectElement.setMass(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setAirFriction(ConversionUtils.readIntegerAsDouble(file));
            effectElement.setElasticity(ConversionUtils.readIntegerAsDouble(file));
            effectElement.setMinSpeedXy(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setMaxSpeedXy(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setMinSpeedYz(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setMaxSpeedYz(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setMinScale(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setMaxScale(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setScaleRatio(ConversionUtils.readIntegerAsFloat(file));
            effectElement.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file),
                    EffectElement.EffectElementFlag.class));
            effectElement.setEffectElementId(ConversionUtils.readUnsignedShort(file));
            effectElement.setMinHp(ConversionUtils.readUnsignedShort(file));
            effectElement.setMaxHp(ConversionUtils.readUnsignedShort(file));
            effectElement.setDeathElementId(ConversionUtils.readUnsignedShort(file));
            effectElement.setHitSolidElementId(ConversionUtils.readUnsignedShort(file));
            effectElement.setHitWaterElementId(ConversionUtils.readUnsignedShort(file));
            effectElement.setHitLavaElementId(ConversionUtils.readUnsignedShort(file));
            effectElement.setColor(new Color(file.readUnsignedByte(), file.readUnsignedByte(), file.readUnsignedByte()));
            effectElement.setRandomColorIndex((short) file.readUnsignedByte());
            effectElement.setTableColorIndex((short) file.readUnsignedByte());
            effectElement.setFadePercentage((short) file.readUnsignedByte());
            effectElement.setNextEffectId(ConversionUtils.readUnsignedShort(file));

            // Add to the hash by the effect element ID
            effectElements.put(effectElement.getEffectElementId(), effectElement);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the Effects.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readEffects(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the effects catalog
        if (effects == null) {
            logger.info("Reading effects!");
            effects = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides effects!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            Effect effect = new Effect();

            effect.setName(ConversionUtils.readString(file, 32).trim());
            effect.setArtResource(readArtResource(file));
            effect.setLight(readLight(file));
            effect.setMass(ConversionUtils.readIntegerAsFloat(file));
            effect.setAirFriction(ConversionUtils.readIntegerAsDouble(file));
            effect.setElasticity(ConversionUtils.readIntegerAsDouble(file));
            effect.setRadius(ConversionUtils.readIntegerAsFloat(file));
            effect.setMinSpeedXy(ConversionUtils.readIntegerAsFloat(file));
            effect.setMaxSpeedXy(ConversionUtils.readIntegerAsFloat(file));
            effect.setMinSpeedYz(ConversionUtils.readIntegerAsFloat(file));
            effect.setMaxSpeedYz(ConversionUtils.readIntegerAsFloat(file));
            effect.setMinScale(ConversionUtils.readIntegerAsFloat(file));
            effect.setMaxScale(ConversionUtils.readIntegerAsFloat(file));
            effect.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file),
                    Effect.EffectFlag.class));
            effect.setEffectId(ConversionUtils.readUnsignedShort(file));
            effect.setMinHp(ConversionUtils.readUnsignedShort(file));
            effect.setMaxHp(ConversionUtils.readUnsignedShort(file));
            effect.setFadeDuration(ConversionUtils.readUnsignedShort(file));
            effect.setNextEffectId(ConversionUtils.readUnsignedShort(file));
            effect.setDeathEffectId(ConversionUtils.readUnsignedShort(file));
            effect.setHitSolidEffectId(ConversionUtils.readUnsignedShort(file));
            effect.setHitWaterEffectId(ConversionUtils.readUnsignedShort(file));
            effect.setHitLavaEffectId(ConversionUtils.readUnsignedShort(file));
            List<Integer> generateIds = new ArrayList<>(8);
            for (int x = 0; x < 8; x++) {
                int id = ConversionUtils.readUnsignedShort(file);
                if (id > 0) {
                    generateIds.add(id);
                }
            }
            effect.setGenerateIds(generateIds);
            effect.setOuterOriginRange(ConversionUtils.readUnsignedShort(file));
            effect.setLowerHeightLimit(ConversionUtils.readUnsignedShort(file));
            effect.setUpperHeightLimit(ConversionUtils.readUnsignedShort(file));
            effect.setOrientationRange(ConversionUtils.readUnsignedShort(file));
            effect.setSpriteSpinRateRange(ConversionUtils.readUnsignedShort(file));
            effect.setWhirlpoolRate(ConversionUtils.readUnsignedShort(file));
            effect.setDirectionalSpread(ConversionUtils.readUnsignedShort(file));
            effect.setCircularPathRate(ConversionUtils.readUnsignedShort(file));
            effect.setInnerOriginRange(ConversionUtils.readUnsignedShort(file));
            effect.setGenerateRandomness(ConversionUtils.readUnsignedShort(file));
            effect.setMisc2(ConversionUtils.readUnsignedShort(file));
            effect.setMisc3(ConversionUtils.readUnsignedShort(file));
            effect.setGenerationType(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                    Effect.GenerationType.class));
            effect.setElementsPerTurn((short) file.readUnsignedByte());
            effect.setUnknown3(ConversionUtils.readUnsignedShort(file));

            // Add to the hash by the effect ID
            effects.put(effect.getEffectId(), effect);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the KeeperSpells.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readKeeperSpells(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the keeper spells catalog
        if (keeperSpells == null) {
            logger.info("Reading keeper spells!");
            keeperSpells = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides keeper spells!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();
            KeeperSpell keeperSpell = new KeeperSpell();

            keeperSpell.setName(ConversionUtils.readString(file, 32).trim());
            keeperSpell.setGuiIcon(readArtResource(file));
            keeperSpell.setEditorIcon(readArtResource(file));
            keeperSpell.setXc8(ConversionUtils.readInteger(file));
            keeperSpell.setRechargeTime(ConversionUtils.readIntegerAsFloat(file));
            keeperSpell.setShotData1(ConversionUtils.readInteger(file));
            keeperSpell.setShotData2(ConversionUtils.readInteger(file));
            keeperSpell.setResearchTime(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setTargetRule(ConversionUtils.parseEnum((short) file.readUnsignedByte(), KeeperSpell.TargetRule.class));
            keeperSpell.setOrderInEditor((short) file.readUnsignedByte());
            keeperSpell.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file), KeeperSpell.KeeperSpellFlag.class));
            keeperSpell.setXe0Unreferenced(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setManaDrain(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setTooltipStringId(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setNameStringId(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setGeneralDescriptionStringId(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setStrengthStringId(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setWeaknessStringId(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setKeeperSpellId((short) file.readUnsignedByte());
            keeperSpell.setCastRule(ConversionUtils.parseEnum((short) file.readUnsignedByte(), KeeperSpell.CastRule.class));
            keeperSpell.setShotTypeId((short) file.readUnsignedByte());

            keeperSpell.setSoundCategory(ConversionUtils.readString(file, 32).trim());
            keeperSpell.setBonusRTime(ConversionUtils.readUnsignedShort(file));
            keeperSpell.setBonusShotTypeId((short) file.readUnsignedByte());
            keeperSpell.setBonusShotData1(ConversionUtils.readInteger(file));
            keeperSpell.setBonusShotData2(ConversionUtils.readInteger(file));
            keeperSpell.setManaCost(ConversionUtils.readInteger(file));
            keeperSpell.setBonusIcon(readArtResource(file));

            keeperSpell.setSoundCategoryGui(ConversionUtils.readString(file, 32).trim());
            keeperSpell.setHandAnimId(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                    KeeperSpell.HandAnimId.class));
            keeperSpell.setNoGoHandAnimId(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                    KeeperSpell.HandAnimId.class));

            // Add to the hash by the keeper spell ID
            keeperSpells.put(keeperSpell.getKeeperSpellId(), keeperSpell);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the *Things.kld
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readThings(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the requested Things file
        if (things == null) {
            logger.info("Reading things!");
            things = new ArrayList<>(header.getItemCount());
        } else {
            logger.warning("Overrides things!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            Thing thing = null;
            int[] thingTag = new int[2];
            for (int x = 0; x < thingTag.length; x++) {
                thingTag[x] = ConversionUtils.readUnsignedInteger(file);
            }
            long offset = file.getFilePointer();

            // Figure out the type
            switch (thingTag[0]) {
                case 194: {

                    // Object (door & trap crates, objects...)
                    thing = new Thing.Object();
                    ((Thing.Object) thing).setPosX(ConversionUtils.readInteger(file));
                    ((Thing.Object) thing).setPosY(ConversionUtils.readInteger(file));
                    short unknown1[] = new short[4];
                    for (int x = 0; x < unknown1.length; x++) {
                        unknown1[x] = (short) file.readUnsignedByte();
                    }
                    ((Thing.Object) thing).setUnknown1(unknown1);
                    ((Thing.Object) thing).setKeeperSpellId(ConversionUtils.readInteger(file));
                    ((Thing.Object) thing).setMoneyAmount(ConversionUtils.readInteger(file));
                    ((Thing.Object) thing).setTriggerId(ConversionUtils.readUnsignedShort(file));
                    ((Thing.Object) thing).setObjectId((short) file.readUnsignedByte());
                    ((Thing.Object) thing).setPlayerId((short) file.readUnsignedByte());
                    break;
                }
                case 195: {

                    // Trap
                    thing = new Thing.Trap();
                    ((Thing.Trap) thing).setPosX(ConversionUtils.readInteger(file));
                    ((Thing.Trap) thing).setPosY(ConversionUtils.readInteger(file));
                    ((Thing.Trap) thing).setUnknown1(ConversionUtils.readInteger(file));
                    ((Thing.Trap) thing).setNumberOfShots((short) file.readUnsignedByte());
                    ((Thing.Trap) thing).setTrapId((short) file.readUnsignedByte());
                    ((Thing.Trap) thing).setPlayerId((short) file.readUnsignedByte());
                    ((Thing.Trap) thing).setUnknown2((short) file.readUnsignedByte());
                    break;
                }
                case 196: {

                    // Door
                    thing = new Thing.Door();
                    ((Thing.Door) thing).setPosX(ConversionUtils.readInteger(file));
                    ((Thing.Door) thing).setPosY(ConversionUtils.readInteger(file));
                    ((Thing.Door) thing).setUnknown1(ConversionUtils.readInteger(file));
                    ((Thing.Door) thing).setTriggerId(ConversionUtils.readUnsignedShort(file));
                    ((Thing.Door) thing).setDoorId((short) file.readUnsignedByte());
                    ((Thing.Door) thing).setPlayerId((short) file.readUnsignedByte());
                    ((Thing.Door) thing).setFlag(ConversionUtils.parseEnum(file.readUnsignedByte(), Thing.Door.DoorFlag.class));
                    short unknown2[] = new short[3];
                    for (int x = 0; x < unknown2.length; x++) {
                        unknown2[x] = (short) file.readUnsignedByte();
                    }
                    ((Thing.Door) thing).setUnknown2(unknown2);
                    break;
                }
                case 197: {

                    // ActionPoint
                    thing = new ActionPoint();
                    ((ActionPoint) thing).setStartX(ConversionUtils.readInteger(file));
                    ((ActionPoint) thing).setStartY(ConversionUtils.readInteger(file));
                    ((ActionPoint) thing).setEndX(ConversionUtils.readInteger(file));
                    ((ActionPoint) thing).setEndY(ConversionUtils.readInteger(file));
                    ((ActionPoint) thing).setWaitDelay(ConversionUtils.readUnsignedShort(file));
                    ((ActionPoint) thing).setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedShort(file), ActionPointFlag.class));
                    ((ActionPoint) thing).setTriggerId(ConversionUtils.readUnsignedShort(file));
                    ((ActionPoint) thing).setId((short) file.readUnsignedByte());
                    ((ActionPoint) thing).setNextWaypointId((short) file.readUnsignedByte());

                    ((ActionPoint) thing).setName(ConversionUtils.readString(file, 32).trim());
                    break;
                }
                case 198: {

                    // Neutral creature
                    thing = new Thing.NeutralCreature();
                    ((NeutralCreature) thing).setPosX(ConversionUtils.readInteger(file));
                    ((NeutralCreature) thing).setPosY(ConversionUtils.readInteger(file));
                    ((NeutralCreature) thing).setPosZ(ConversionUtils.readInteger(file));
                    ((NeutralCreature) thing).setGoldHeld(ConversionUtils.readUnsignedShort(file));
                    ((NeutralCreature) thing).setLevel((short) file.readUnsignedByte());
                    ((NeutralCreature) thing).setFlags(ConversionUtils.parseFlagValue((short) file.readUnsignedByte(), Thing.Creature.CreatureFlag.class));
                    ((NeutralCreature) thing).setInitialHealth(ConversionUtils.readInteger(file));
                    ((NeutralCreature) thing).setTriggerId(ConversionUtils.readUnsignedShort(file));
                    ((NeutralCreature) thing).setCreatureId((short) file.readUnsignedByte());
                    ((NeutralCreature) thing).setUnknown1((short) file.readUnsignedByte());
                    break;
                }
                case 199: {

                    // Good creature
                    thing = new Thing.GoodCreature();
                    ((GoodCreature) thing).setPosX(ConversionUtils.readInteger(file));
                    ((GoodCreature) thing).setPosY(ConversionUtils.readInteger(file));
                    ((GoodCreature) thing).setPosZ(ConversionUtils.readInteger(file));
                    ((GoodCreature) thing).setGoldHeld(ConversionUtils.readUnsignedShort(file));
                    ((GoodCreature) thing).setLevel((short) file.readUnsignedByte());
                    ((GoodCreature) thing).setFlags(ConversionUtils.parseFlagValue((short) file.readUnsignedByte(), Thing.Creature.CreatureFlag.class));
                    ((GoodCreature) thing).setObjectiveTargetActionPointId(ConversionUtils.readInteger(file));
                    ((GoodCreature) thing).setInitialHealth(ConversionUtils.readInteger(file));
                    ((GoodCreature) thing).setTriggerId(ConversionUtils.readUnsignedShort(file));
                    ((GoodCreature) thing).setObjectiveTargetPlayerId((short) file.readUnsignedByte());
                    ((GoodCreature) thing).setObjective(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Thing.HeroParty.Objective.class));
                    ((GoodCreature) thing).setCreatureId((short) file.readUnsignedByte());
                    short unknown1[] = new short[2];
                    for (int x = 0; x < unknown1.length; x++) {
                        unknown1[x] = (short) file.readUnsignedByte();
                    }
                    ((GoodCreature) thing).setUnknown1(unknown1);
                    ((GoodCreature) thing).setFlags2(ConversionUtils.parseFlagValue((short) file.readUnsignedByte(), Thing.Creature.CreatureFlag2.class));
                    break;
                }
                case 200: {

                    // Creature
                    thing = new Thing.KeeperCreature();
                    ((KeeperCreature) thing).setPosX(ConversionUtils.readInteger(file));
                    ((KeeperCreature) thing).setPosY(ConversionUtils.readInteger(file));
                    ((KeeperCreature) thing).setPosZ(ConversionUtils.readInteger(file));
                    ((KeeperCreature) thing).setGoldHeld(ConversionUtils.readUnsignedShort(file));
                    ((KeeperCreature) thing).setLevel((short) file.readUnsignedByte());
                    ((KeeperCreature) thing).setFlags(ConversionUtils.parseFlagValue((short) file.readUnsignedByte(), KeeperCreature.CreatureFlag.class));
                    ((KeeperCreature) thing).setInitialHealth(ConversionUtils.readInteger(file));
                    ((KeeperCreature) thing).setObjectiveTargetActionPointId(ConversionUtils.readInteger(file));
                    ((KeeperCreature) thing).setTriggerId(ConversionUtils.readUnsignedShort(file));
                    ((KeeperCreature) thing).setCreatureId((short) file.readUnsignedByte());
                    ((KeeperCreature) thing).setPlayerId((short) file.readUnsignedByte());
                    break;
                }
                case 201: {

                    // HeroParty
                    thing = new HeroParty();

                    ((HeroParty) thing).setName(ConversionUtils.readString(file, 32).trim());
                    ((HeroParty) thing).setTriggerId(ConversionUtils.readUnsignedShort(file));
                    ((HeroParty) thing).setId((short) file.readUnsignedByte());
                    ((HeroParty) thing).setX23(ConversionUtils.readInteger(file));
                    ((HeroParty) thing).setX27(ConversionUtils.readInteger(file));
                    List<GoodCreature> heroPartyMembers = new ArrayList<>(16);
                    for (int x = 0; x < 16; x++) {
                        GoodCreature creature = new GoodCreature();
                        creature.setPosX(ConversionUtils.readInteger(file));
                        creature.setPosY(ConversionUtils.readInteger(file));
                        creature.setPosZ(ConversionUtils.readInteger(file));
                        creature.setGoldHeld(ConversionUtils.readUnsignedShort(file));
                        creature.setLevel((short) file.readUnsignedByte());
                        creature.setFlags(ConversionUtils.parseFlagValue((short) file.readUnsignedByte(), KeeperCreature.CreatureFlag.class));
                        creature.setObjectiveTargetActionPointId(ConversionUtils.readInteger(file));
                        creature.setInitialHealth(ConversionUtils.readInteger(file));
                        creature.setTriggerId(ConversionUtils.readUnsignedShort(file));
                        creature.setObjectiveTargetPlayerId((short) file.readUnsignedByte());
                        creature.setObjective(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Thing.HeroParty.Objective.class));
                        creature.setCreatureId((short) file.readUnsignedByte());
                        short unknown1[] = new short[2];
                        for (int index = 0; index < unknown1.length; index++) {
                            unknown1[index] = (short) file.readUnsignedByte();
                        }
                        creature.setUnknown1(unknown1);
                        creature.setFlags2(ConversionUtils.parseFlagValue((short) file.readUnsignedByte(), Thing.Creature.CreatureFlag2.class));

                        // If creature id is 0, it is safe to say this is not a valid entry
                        if (creature.getCreatureId() > 0) {
                            heroPartyMembers.add(creature);
                        }
                    }
                    ((HeroParty) thing).setHeroPartyMembers(heroPartyMembers);
                    break;
                }
                case 202: {

                    // Dead body
                    thing = new Thing.DeadBody();
                    ((Thing.DeadBody) thing).setPosX(ConversionUtils.readInteger(file));
                    ((Thing.DeadBody) thing).setPosY(ConversionUtils.readInteger(file));
                    ((Thing.DeadBody) thing).setPosZ(ConversionUtils.readInteger(file));
                    ((Thing.DeadBody) thing).setGoldHeld(ConversionUtils.readUnsignedShort(file));
                    ((Thing.DeadBody) thing).setCreatureId((short) file.readUnsignedByte());
                    ((Thing.DeadBody) thing).setPlayerId((short) file.readUnsignedByte());
                    break;
                }
                case 203: {

                    // Effect generator
                    thing = new Thing.EffectGenerator();
                    ((Thing.EffectGenerator) thing).setPosX(ConversionUtils.readInteger(file));
                    ((Thing.EffectGenerator) thing).setPosY(ConversionUtils.readInteger(file));
                    ((Thing.EffectGenerator) thing).setX08(ConversionUtils.readInteger(file));
                    ((Thing.EffectGenerator) thing).setX0c(ConversionUtils.readInteger(file));
                    ((Thing.EffectGenerator) thing).setX10(ConversionUtils.readUnsignedShort(file));
                    ((Thing.EffectGenerator) thing).setX12(ConversionUtils.readUnsignedShort(file));
                    List<Integer> effectIds = new ArrayList<>(4);
                    for (int x = 0; x < 4; x++) {
                        int effectId = ConversionUtils.readUnsignedShort(file);
                        if (effectId > 0) {
                            effectIds.add(effectId);
                        }
                    }
                    ((Thing.EffectGenerator) thing).setEffectIds(effectIds);
                    ((Thing.EffectGenerator) thing).setFrequency((short) file.readUnsignedByte());
                    ((Thing.EffectGenerator) thing).setId((short) file.readUnsignedByte());
                    short[] pad = new short[6];
                    for (int x = 0; x < pad.length; x++) {
                        pad[x] = (short) file.readUnsignedByte();
                    }
                    ((Thing.EffectGenerator) thing).setPad(pad);
                    break;
                }
                case 204: {

                    // Room
                    thing = new Thing.Room();
                    ((Thing.Room) thing).setPosX(ConversionUtils.readInteger(file));
                    ((Thing.Room) thing).setPosY(ConversionUtils.readInteger(file));
                    ((Thing.Room) thing).setX08(ConversionUtils.readInteger(file));
                    ((Thing.Room) thing).setX0c(ConversionUtils.readUnsignedShort(file));
                    ((Thing.Room) thing).setDirection(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Thing.Room.Direction.class));
                    ((Thing.Room) thing).setX0f((short) file.readUnsignedByte());
                    ((Thing.Room) thing).setInitialHealth(ConversionUtils.readUnsignedShort(file));
                    ((Thing.Room) thing).setRoomType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Thing.Room.RoomType.class));
                    ((Thing.Room) thing).setPlayerId((short) file.readUnsignedByte());
                    break;
                }
                case 205: {

                    // TODO: decode values
                    thing = new Thing.Camera();
                    ((Thing.Camera) thing).setPosition(ConversionUtils.readIntegerAsFloat(file),
                            ConversionUtils.readIntegerAsFloat(file),
                            ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setPositionMinClipExtent(ConversionUtils.readIntegerAsFloat(file),
                            ConversionUtils.readIntegerAsFloat(file),
                            ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setPositionMaxClipExtent(ConversionUtils.readIntegerAsFloat(file),
                            ConversionUtils.readIntegerAsFloat(file),
                            ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setViewDistanceValue(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setViewDistanceMin(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setViewDistanceMax(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setZoomValue(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setZoomValueMin(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setZoomValueMax(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setLensValue(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setLensValueMin(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setLensValueMax(ConversionUtils.readIntegerAsFloat(file));
                    ((Thing.Camera) thing).setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readInteger(file),
                            Thing.Camera.CameraFlag.class));
                    ((Thing.Camera) thing).setAngleYaw(ConversionUtils.readUnsignedShort(file));
                    ((Thing.Camera) thing).setAngleRoll(ConversionUtils.readUnsignedShort(file));
                    ((Thing.Camera) thing).setAnglePitch(ConversionUtils.readUnsignedShort(file));
                    ((Thing.Camera) thing).setId((short) ConversionUtils.readUnsignedShort(file));
                    break;
                }
                default: {

                    // Just skip the bytes
                    file.skipBytes(thingTag[1]);
                    logger.log(Level.WARNING, "Unsupported thing type {0}!", thingTag[0]);
                }
            }

            // Add to the list
            things.add(thing);

            // Check file offset
            checkOffset(thingTag[1], file, offset);
        }
    }

    /**
     * Reads the Shots.kwd
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readShots(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the shots catalog
        if (shots == null) {
            logger.info("Reading shots!");
            shots = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides shots!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            long offset = file.getFilePointer();

            // One shot is 239 bytes
            Shot shot = new Shot();

            shot.setName(ConversionUtils.readString(file, 32).trim());
            shot.setMeshResource(readArtResource(file));
            shot.setLight(readLight(file));
            shot.setAirFriction(ConversionUtils.readIntegerAsDouble(file));
            shot.setMass(ConversionUtils.readIntegerAsFloat(file));
            shot.setSpeed(ConversionUtils.readIntegerAsFloat(file));
            shot.setData1(ConversionUtils.readUnsignedInteger(file));
            shot.setData2(ConversionUtils.readUnsignedInteger(file));
            shot.setShotProcessFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file), Shot.ShotProcessFlag.class));
            shot.setRadius(ConversionUtils.readIntegerAsFloat(file));
            shot.setFlags(ConversionUtils.parseFlagValue(ConversionUtils.readUnsignedInteger(file), Shot.ShotFlag.class));
            shot.setGeneralEffectId(ConversionUtils.readUnsignedShort(file));
            shot.setCreationEffectId(ConversionUtils.readUnsignedShort(file));
            shot.setDeathEffectId(ConversionUtils.readUnsignedShort(file));
            shot.setTimedEffectId(ConversionUtils.readUnsignedShort(file));
            shot.setHitSolidEffectId(ConversionUtils.readUnsignedShort(file));
            shot.setHitLavaEffectId(ConversionUtils.readUnsignedShort(file));
            shot.setHitWaterEffect(ConversionUtils.readUnsignedShort(file));
            shot.setHitThingEffectId(ConversionUtils.readUnsignedShort(file));
            shot.setHealth(ConversionUtils.readUnsignedShort(file));
            shot.setShotId((short) file.readUnsignedByte());
            shot.setDeathShotId((short) file.readUnsignedByte());
            shot.setTimedDelay((short) file.readUnsignedByte());
            shot.setHitSolidShotId((short) file.readUnsignedByte());
            shot.setHitLavaShotId((short) file.readUnsignedByte());
            shot.setHitWaterShotId((short) file.readUnsignedByte());
            shot.setHitThingShotId((short) file.readUnsignedByte());
            shot.setDamageType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Shot.DamageType.class));
            shot.setCollideType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Shot.CollideType.class));
            shot.setProcessType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Shot.ProcessType.class));
            shot.setAttackCategory(ConversionUtils.parseEnum((short) file.readUnsignedByte(), Shot.AttackCategory.class));

            shot.setSoundCategory(ConversionUtils.readString(file, 32).trim());
            shot.setThreat(ConversionUtils.readUnsignedShort(file));
            shot.setBurnDuration(ConversionUtils.readIntegerAsFloat(file));

            // Add to the hash by the shot ID
            shots.put(shot.getShotId(), shot);

            // Check file offset
            checkOffset(header, file, offset);
        }
    }

    /**
     * Reads the *Triggers.kld
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readTriggers(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the requested Triggers file
        if (triggers == null) {
            logger.info("Reading triggers!");
            triggers = new HashMap<>(header.getItemCount());
        } else {
            logger.warning("Overrides triggers!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            Trigger trigger = null;
            int[] triggerTag = new int[2];
            for (int x = 0; x < triggerTag.length; x++) {
                triggerTag[x] = ConversionUtils.readUnsignedInteger(file);
            }
            long offset = file.getFilePointer();

            // Figure out the type
            switch (triggerTag[0]) {
                case TRIGGER_GENERIC: {
                    long start = file.getFilePointer();
                    file.seek(start + triggerTag[1] - 2);

                    trigger = new TriggerGeneric(this);
                    ((TriggerGeneric) trigger).setType(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.TargetType.class));
                    trigger.setRepeatTimes((short) file.readUnsignedByte());

                    file.seek(start);
                    switch (((TriggerGeneric) trigger).getType()) {
                        case AP_CONGREGATE_IN:
                        case AP_POSESSED_CREATURE_ENTERS:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("targetId", (short) file.readUnsignedByte()); // creatureId, objectId
                            trigger.setUserData("targetType", (short) file.readUnsignedByte()); // 3 = Creature, 6 = Object
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case AP_SLAB_TYPES:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("terrainId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 1); // file.skipBytes(1); // 0 = None
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case AP_TAG_PART_OF:
                        case AP_TAG_ALL_OF:
                        case AP_CLAIM_PART_OF:
                        case AP_CLAIM_ALL_OF:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            // trigger.setUserData("targetId", (short) file.readUnsignedByte()); // 0 = None
                            // trigger.setUserData("targetType", (short) file.readUnsignedByte()); // 0 = None
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case PLAYER_DUNGEON_BREACHED:
                        case PLAYER_ENEMY_BREACHED:
                            trigger.setUserData("playerId", (short) file.readUnsignedByte()); // 0 = Any
                            ConversionUtils.checkNull(file, 7); // file.skipBytes(7);
                            break;

                        case PLAYER_KILLED:
                            trigger.setUserData("playerId", (short) file.readUnsignedByte()); // 0 = Any
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(7);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // FIXME unknown value
                            break;

                        case PLAYER_CREATURE_PICKED_UP:
                        case PLAYER_CREATURE_SLAPPED:
                        case PLAYER_CREATURE_SACKED:
                            trigger.setUserData("creatureId", (short) file.readUnsignedByte()); // 0 = Any
                            ConversionUtils.checkNull(file, 7); // file.skipBytes(7);
                            break;

                        case PLAYER_CREATURE_DROPPED:
                            trigger.setUserData("creatureId", (short) file.readUnsignedByte()); // 0 = Any
                            trigger.setUserData("roomId", (short) file.readUnsignedByte()); // 0 = Any
                            ConversionUtils.checkNull(file, 6); // file.skipBytes(6);
                            break;

                        case PLAYER_CREATURES:
                        case PLAYER_HAPPY_CREATURES:
                        case PLAYER_ANGRY_CREATURES:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("creatureId", (short) file.readUnsignedByte());
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Player
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case PLAYER_CREATURES_KILLED:
                        case PLAYER_KILLS_CREATURES:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("targetId", (short) file.readUnsignedByte()); // playerId
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Player
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case PLAYER_ROOMS:
                        case PLAYER_ROOM_SLABS:
                        case PLAYER_ROOM_SIZE:
                        case PLAYER_ROOM_FURNITURE:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("roomId", (short) file.readUnsignedByte());
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Player
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case PLAYER_DOORS:
                        case PLAYER_TRAPS:
                        case PLAYER_KEEPER_SPELL:
                        case PLAYER_DESTROYS:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("targetId", (short) file.readUnsignedByte()); // doorId, trapId, keeperSpellId,
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Player
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case PLAYER_SLAPS:
                        case PLAYER_GOLD:
                        case PLAYER_GOLD_MINED:
                        case PLAYER_MANA:
                        case PLAYER_CREATURES_GROUPED:
                        case PLAYER_CREATURES_DYING:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            ConversionUtils.checkNull(file, 1); // file.skipBytes(1);
                            // trigger.setUserData("targetId", (short) file.readUnsignedByte()); // = 0
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Player
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case PLAYER_CREATURES_AT_LEVEL:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            // FIXME some bug in editor
                            trigger.setUserData("targetId", (short) file.readUnsignedByte()); // = 0, must be a level
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Player
                            trigger.setUserData("playerId", (short) file.readUnsignedByte()); // level also
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case LEVEL_PAY_DAY:
                        case CREATURE_KILLED:
                        case CREATURE_SLAPPED:
                        case CREATURE_ATTACKED:
                        case CREATURE_IMPRISONED:
                        case CREATURE_TORTURED:
                        case CREATURE_CONVERTED:
                        case CREATURE_CLAIMED:
                        case CREATURE_ANGRY:
                        case CREATURE_AFRAID:
                        case CREATURE_STEALS:
                        case CREATURE_LEAVES:
                        case CREATURE_STUNNED:
                        case CREATURE_DYING:
                        case GUI_TRANSITION_ENDS:
                        case CREATURE_PICKED_UP:
                        case CREATURE_SACKED:
                        case CREATURE_PICKS_UP_PORTAL_GEM:
                        case CREATURE_HUNGER_SATED:
                        case PARTY_CREATED:
                            ConversionUtils.checkNull(file, 8); // file.skipBytes(8);
                            break;

                        case CREATURE_CREATED:
                            ConversionUtils.checkNull(file, 4); // file.skipBytes(4);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // FIXME unknown value
                            break;

                        case LEVEL_PLAYED:
                        case PARTY_MEMBERS_CAPTURED:
                        case CREATURE_EXPERIENCE_LEVEL:
                        case CREATURE_GOLD_HELD:
                        case CREATURE_HEALTH:
                        case LEVEL_TIME:
                        case LEVEL_CREATURES:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(3);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case PARTY_MEMBERS_KILLED:
                        case PARTY_MEMBERS_INCAPACITATED:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("unknown", (short) file.readUnsignedByte()); // FIXME unknown value
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case GUI_BUTTON_PRESSED:
                            // Misc Button = 0, Room = 1, Creature = 2, Door = 3, Trap = 4, Keeper Spell = 5
                            trigger.setUserData("targetType", (short) file.readUnsignedByte());
                            trigger.setUserData("targetId", (short) file.readUnsignedByte()); // buttonId, roomId, creatureId ...
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // FIXME unknown value
                            break;

                        case FLAG:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("targetId", (short) file.readUnsignedByte()); // flagId
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Flag
                            trigger.setUserData("flagId", (short) file.readUnsignedByte());
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        case TIMER:
                            ((TriggerGeneric) trigger).setTargetValueComparison(ConversionUtils.parseEnum((short) file.readUnsignedByte(), TriggerGeneric.ComparisonType.class));
                            trigger.setUserData("targetId", (short) file.readUnsignedByte()); // timerId
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // 0x1 = Value, !0x1 = Flag
                            trigger.setUserData("timerId", (short) file.readUnsignedByte());
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            break;

                        default:
                            ConversionUtils.checkNull(file, 8); // file.skipBytes(8);
                            logger.warning("Unsupported Type of TriggerGeneric");
                            break;

                    }

                    trigger.setId(ConversionUtils.readUnsignedShort(file));
                    trigger.setIdNext(ConversionUtils.readUnsignedShort(file)); // SiblingID
                    trigger.setIdChild(ConversionUtils.readUnsignedShort(file)); // ChildID

                    file.skipBytes(2);
                    break;
                }
                case TRIGGER_ACTION: {

                    long start = file.getFilePointer();
                    file.seek(start + triggerTag[1] - 2);

                    trigger = new TriggerAction(this);
                    ((TriggerAction) trigger).setType(ConversionUtils.parseEnum(file.readUnsignedByte(), TriggerAction.ActionType.class));
                    trigger.setRepeatTimes((short) file.readUnsignedByte());

                    file.seek(start);
                    switch (((TriggerAction) trigger).getType()) {
                        // in levels triggers
                        case ALTER_TERRAIN_TYPE:
                            trigger.setUserData("terrainId", (short) file.readUnsignedByte());
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("posX", ConversionUtils.readUnsignedShort(file));
                            trigger.setUserData("posY", ConversionUtils.readUnsignedShort(file));
                            break;

                        case COLLAPSE_HERO_GATE:
                            ConversionUtils.checkNull(file, 4); // file.skipBytes(4);
                            trigger.setUserData("posX", ConversionUtils.readUnsignedShort(file));
                            trigger.setUserData("posY", ConversionUtils.readUnsignedShort(file));
                            break;

                        case CHANGE_ROOM_OWNER:
                            ConversionUtils.checkNull(file, 1); // file.skipBytes(1);
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("posX", ConversionUtils.readUnsignedShort(file));
                            trigger.setUserData("posY", ConversionUtils.readUnsignedShort(file));
                            break;

                        case SET_ALLIANCE:
                            trigger.setUserData("playerOneId", (short) file.readUnsignedByte());
                            trigger.setUserData("playerTwoId", file.readUnsignedByte());
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Create, !0 = Break
                            ConversionUtils.checkNull(file, 5); // file.skipBytes(5);
                            break;

                        case SET_CREATURE_MOODS:
                        case SET_SYSTEM_MESSAGES:
                        case SET_TIMER_SPEECH:
                        case SET_WIDESCREEN_MODE:
                        case ALTER_SPEED:  // 0 = Walk, !0 = Run
                        case SET_FIGHT_FLAG: // 0 = Don`t Fight, !0 = Fight
                        case SET_PORTAL_STATUS: // 0 = Closed, !0 = Open
                            trigger.setUserData("available", (short) file.readUnsignedByte());  // 0 = Off, !0 = On
                            ConversionUtils.checkNull(file, 7); // file.skipBytes(7);
                            break;

                        case SET_SLAPS_LIMIT:
                            ConversionUtils.checkNull(file, 4); // file.skipBytes(4);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // limit 4 bytes, 0 = Off
                            break;

                        case INITIALIZE_TIMER:
                            trigger.setUserData("timerId", (short) file.readUnsignedByte()); // timerId + 1, 16 - Time Limit
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(3);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // limit 4 bytes, only for Time limit (max 100 s)
                            break;

                        case FLAG:
                            trigger.setUserData("flagId", (short) file.readUnsignedByte()); // flagId + 1, 128 - level score
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // flag = Equal = 12 | Plus = 20 | Minus = 36
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // limit 4 bytes
                            break;

                        case MAKE:
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("type", (short) file.readUnsignedByte()); // type = TriggerAction.MakeType.
                            trigger.setUserData("targetId", (short) file.readUnsignedByte());
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Unavailable, !0 = Available
                            ConversionUtils.checkNull(file, 4); // file.skipBytes(4);
                            break;
                        // in player triggers
                        case DISPLAY_SLAB_OWNER:
                            // FIXME Show wrong values in editor
                            trigger.setUserData("available", file.readUnsignedByte());  // 0 = Off, !0 = On
                            //((TriggerAction) trigger).setActionTargetValue1(ConversionUtils.toUnsignedInteger(file)); // limit 4 bytes
                            // 1635984
                            ConversionUtils.checkNull(file, 7); // file.skipBytes(7);
                            break;

                        case DISPLAY_NEXT_ROOM_TYPE: // 0 = Off or roomId
                        case MAKE_OBJECTIVE: // 0 = Off, 1 = Kill, 2 = Imprison, 3 = Convert
                        case ZOOM_TO_ACTION_POINT: // actionPointId
                            trigger.setUserData("targetId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 7); // file.skipBytes(7);
                            break;

                        case DISPLAY_OBJECTIVE:
                            trigger.setUserData("objectiveId", ConversionUtils.readUnsignedInteger(file)); // objectiveId, limit 32767
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte()); // if != 0 => Zoom To AP = this
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(3);
                            break;

                        case PLAY_SPEECH:
                            trigger.setUserData("speechId", ConversionUtils.readUnsignedInteger(file)); // speechId, limit 32767
                            trigger.setUserData("text", (short) file.readUnsignedByte()); // 0 = Show Text, !0 = Without text
                            trigger.setUserData("introduction", (short) file.readUnsignedByte()); // 0 = No Introduction, !0 = Introduction
                            trigger.setUserData("pathId", ConversionUtils.readUnsignedShort(file)); // pathId
                            break;

                        case DISPLAY_TEXT_STRING:
                            trigger.setUserData("textId", ConversionUtils.readUnsignedInteger(file)); // textId, limit 32767
                            // FIXME Maybe Zoom to AP X
                            ConversionUtils.checkNull(file, 4); // file.skipBytes(4);
                            break;
                        // creature triggers
                        case ATTACH_PORTAL_GEM:
                        case MAKE_HUNGRY:
                        case REMOVE_FROM_MAP:
                        case ZOOM_TO:
                        case WIN_GAME:
                        case LOSE_GAME:
                        case FORCE_FIRST_PERSON:
                        case LOSE_SUBOBJECTIVE:
                        case WIN_SUBOBJECTIVE:
                            ConversionUtils.checkNull(file, 8); // file.skipBytes(8); // no other parameters
                            break;

                        case SET_MUSIC_LEVEL: // level
                        case SHOW_HEALTH_FLOWER: // limit Seconds
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file));
                            ConversionUtils.checkNull(file, 4); // file.skipBytes(4);
                            break;

                        case SET_TIME_LIMIT:
                            trigger.setUserData("timerId", (short) file.readUnsignedByte()); // timerId + 1, 16 - Time Limit
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(3);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // Seconds
                            break;

                        case FOLLOW_CAMERA_PATH:
                            trigger.setUserData("pathId", (short) file.readUnsignedByte());
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte());
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Show Ceiling, !0 = Hide Ceiling
                            ConversionUtils.checkNull(file, 5); // file.skipBytes(5);
                            break;

                        case FLASH_BUTTON:
                            trigger.setUserData("type", (short) file.readUnsignedByte()); // TriggerAction.MakeType.
                            trigger.setUserData("targetId", (short) file.readUnsignedByte());
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Off, !0 & !time = Until selected
                            ConversionUtils.checkNull(file, 1); // file.skipBytes(1);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // Seconds
                            break;

                        case FLASH_ACTION_POINT:
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte());
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Off, !0 & !time = Until switched off
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("value", ConversionUtils.readUnsignedInteger(file)); // Seconds
                            break;

                        case REVEAL_ACTION_POINT:
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte());
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Reveal, !0 = Conceal
                            ConversionUtils.checkNull(file, 6); // file.skipBytes(6);
                            break;

                        case ROTATE_AROUND_ACTION_POINT:
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte());
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Relative, !0 = Absolute
                            trigger.setUserData("angle", ConversionUtils.readUnsignedShort(file)); // degrees
                            trigger.setUserData("time", ConversionUtils.readUnsignedInteger(file)); // seconds
                            break;

                        case CREATE_CREATURE:
                            trigger.setUserData("creatureId", (short) file.readUnsignedByte());
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("level", (short) file.readUnsignedByte());
                            trigger.setUserData("flag", (short) file.readUnsignedByte()); // TriggerAction.CreatureFlag.
                            trigger.setUserData("posX", ConversionUtils.readUnsignedShort(file));
                            trigger.setUserData("posY", ConversionUtils.readUnsignedShort(file));
                            break;

                        case SET_OBJECTIVE:
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            trigger.setUserData("type", (short) file.readUnsignedByte()); // Creature.JobType
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("actionPointId", ConversionUtils.readUnsignedInteger(file)); // for type = SEND_TO_ACTION_POINT
                            break;

                        case CREATE_HERO_PARTY:
                            trigger.setUserData("partyId", (short) file.readUnsignedByte()); // partyId + 1
                            trigger.setUserData("type", (short) file.readUnsignedByte()); // 0 = None, 1 = IP, 2 = IP Random
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte()); //
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(3);
                            break;

                        case TOGGLE_EFFECT_GENERATOR:
                            trigger.setUserData("generatorId", (short) file.readUnsignedByte()); // generatorId + 1
                            trigger.setUserData("available", (short) file.readUnsignedByte()); // 0 = Off, !0 = On
                            ConversionUtils.checkNull(file, 6); // file.skipBytes(6);
                            break;

                        case GENERATE_CREATURE:
                            trigger.setUserData("creatureId", (short) file.readUnsignedByte()); // creatureId + 1
                            trigger.setUserData("level", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 6); // file.skipBytes(6);
                            break;

                        case INFORMATION:
                            trigger.setUserData("informationId", ConversionUtils.readUnsignedInteger(file));
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(3);
                            break;

                        case SEND_TO_AP:
                            ConversionUtils.checkNull(file, 4); // file.skipBytes(4);
                            trigger.setUserData("actionPointId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 3); // file.skipBytes(3);
                            break;

                        case CREATE_PORTAL_GEM:
                            trigger.setUserData("objectId", (short) file.readUnsignedByte());
                            trigger.setUserData("playerId", (short) file.readUnsignedByte());
                            ConversionUtils.checkNull(file, 2); // file.skipBytes(2);
                            trigger.setUserData("posX", ConversionUtils.readUnsignedShort(file)); // posX + 1
                            trigger.setUserData("posY", ConversionUtils.readUnsignedShort(file)); // posY + 1
                            break;

                        default:
                            ConversionUtils.checkNull(file, 8); // file.skipBytes(8);
                            logger.warning("Unsupported Type of TriggerAction");
                            break;
                    }

                    trigger.setId(ConversionUtils.readUnsignedShort(file)); // ID
                    trigger.setIdNext(ConversionUtils.readUnsignedShort(file)); // SiblingID
                    trigger.setIdChild(ConversionUtils.readUnsignedShort(file)); // ChildID

                    file.skipBytes(2);
                    break;
                }
                default: {

                    // Just skip the bytes
                    file.skipBytes(triggerTag[1]);
                    logger.log(Level.WARNING, "Unsupported trigger type {0}!", triggerTag[0]);
                }
            }

            // Add to the list
            if (trigger != null) {
                triggers.put(trigger.getId(), trigger);
            }

            // Check file offset
            checkOffset(triggerTag[1], file, offset);
        }
    }

    /**
     * Reads the *Variables.kld
     *
     * @param header Kwd header data
     * @param file the file data, rewind to data position
     * @throws IOException the reading may fail
     */
    private void readVariables(KwdHeader header, RandomAccessFile file) throws IOException {

        // Read the requested VARIABLES file
        // Should be the GlobalVariables first, then the level's own
        if (variables == null) {
            logger.info("Reading variables!");
            availabilities = new ArrayList<>();
            creaturePools = new HashMap<>(4);
            creatureStatistics = new HashMap<>(10);
            creatureFirstPersonStatistics = new HashMap<>(10);
            variables = new HashMap<>();
            sacrifices = new HashSet<>();
            unknownVariables = new HashSet<>();
        } else {
            logger.info("Overrides variables!");
        }

        for (int i = 0; i < header.getItemCount(); i++) {
            int id = ConversionUtils.readInteger(file);

            switch (id) {
                case Variable.CREATURE_POOL:
                    Variable.CreaturePool creaturePool = new Variable.CreaturePool();
                    creaturePool.setCreatureId(ConversionUtils.readInteger(file));
                    creaturePool.setValue(ConversionUtils.readInteger(file));
                    creaturePool.setPlayerId(ConversionUtils.readInteger(file));

                    // Add
                    Map<Integer, CreaturePool> playerCreaturePool = creaturePools.get(creaturePool.getPlayerId());
                    if (playerCreaturePool == null) {
                        playerCreaturePool = new HashMap<>(12);
                        creaturePools.put(creaturePool.getPlayerId(), playerCreaturePool);
                    }
                    playerCreaturePool.put(creaturePool.getCreatureId(), creaturePool);
                    break;

                case Variable.AVAILABILITY:
                    Variable.Availability availability = new Variable.Availability();
                    availability.setType(ConversionUtils.parseEnum(ConversionUtils.readUnsignedShort(file),
                            Variable.Availability.AvailabilityType.class));
                    availability.setPlayerId(ConversionUtils.readUnsignedShort(file));
                    availability.setTypeId(ConversionUtils.readInteger(file));
                    availability.setValue(ConversionUtils.parseEnum(ConversionUtils.readInteger(file),
                            Variable.Availability.AvailabilityValue.class));

                    // Add
                    availabilities.add(availability);
                    break;

                case Variable.SACRIFICES_ID: // not changeable (in editor you can, but changes will not save)
                    Variable.Sacrifice sacrifice = new Variable.Sacrifice();
                    sacrifice.setType1(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                            Variable.SacrificeType.class));
                    sacrifice.setId1((short) file.readUnsignedByte());
                    sacrifice.setType2(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                            Variable.SacrificeType.class));
                    sacrifice.setId2((short) file.readUnsignedByte());
                    sacrifice.setType3(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                            Variable.SacrificeType.class));
                    sacrifice.setId3((short) file.readUnsignedByte());

                    sacrifice.setRewardType(ConversionUtils.parseEnum((short) file.readUnsignedByte(),
                            Variable.SacrificeRewardType.class));
                    sacrifice.setSpeechId((short) file.readUnsignedByte());
                    sacrifice.setRewardValue(ConversionUtils.readInteger(file));

                    // Add
                    sacrifices.add(sacrifice);
                    break;

                case Variable.CREATURE_STATS_ID:
                    Variable.CreatureStats creatureStats = new Variable.CreatureStats();
                    creatureStats.setStatId(ConversionUtils.parseEnum(ConversionUtils.readInteger(file),
                            Variable.CreatureStats.StatType.class));
                    creatureStats.setValue(ConversionUtils.readInteger(file));
                    creatureStats.setLevel(ConversionUtils.readInteger(file));

                    // Add
                    Map<StatType, CreatureStats> stats = creatureStatistics.get(creatureStats.getLevel());
                    if (stats == null) {
                        stats = new HashMap<>(CreatureStats.StatType.values().length);
                        creatureStatistics.put(creatureStats.getLevel(), stats);
                    }
                    stats.put(creatureStats.getStatId(), creatureStats);
                    break;

                case Variable.CREATURE_FIRST_PERSON_ID:
                    Variable.CreatureFirstPerson creatureFirstPerson = new Variable.CreatureFirstPerson();
                    creatureFirstPerson.setStatId(ConversionUtils.parseEnum(ConversionUtils.readInteger(file),
                            Variable.CreatureStats.StatType.class));
                    creatureFirstPerson.setValue(ConversionUtils.readInteger(file));
                    creatureFirstPerson.setLevel(ConversionUtils.readInteger(file));

                    // Add
                    Map<StatType, CreatureFirstPerson> firstPersonStats = creatureFirstPersonStatistics.get(creatureFirstPerson.getLevel());
                    if (firstPersonStats == null) {
                        firstPersonStats = new HashMap<>(CreatureStats.StatType.values().length);
                        creatureFirstPersonStatistics.put(creatureFirstPerson.getLevel(), firstPersonStats);
                    }
                    firstPersonStats.put(creatureFirstPerson.getStatId(), creatureFirstPerson);
                    break;

                case Variable.UNKNOWN_17: // FIXME unknown value
                case Variable.UNKNOWN_66: // FIXME unknown value
                case Variable.UNKNOWN_0: // FIXME unknownn value
                case Variable.UNKNOWN_77: // FIXME unknownn value
                    Variable.Unknown unknown = new Variable.Unknown();
                    unknown.setVariableId(id);
                    unknown.setValue(ConversionUtils.readInteger(file));
                    unknown.setUnknown1(ConversionUtils.readInteger(file));
                    unknown.setUnknown2(ConversionUtils.readInteger(file));

                    // Add
                    unknownVariables.add(unknown);
                    break;

                default:
                    Variable.MiscVariable miscVariable = new Variable.MiscVariable();
                    miscVariable.setVariableId(ConversionUtils.parseEnum(id,
                            Variable.MiscVariable.MiscType.class));
                    miscVariable.setValue(ConversionUtils.readInteger(file));
                    miscVariable.setUnknown1(ConversionUtils.readInteger(file));
                    miscVariable.setUnknown2(ConversionUtils.readInteger(file));

                    // Add
                    variables.put(miscVariable.getVariableId(), miscVariable);
                    break;
            }
        }
    }

    /**
     * Get list of different terrain tiles
     *
     * @return list of terrain tiles
     */
    public Collection<Terrain> getTerrainList() {
        return terrainTiles.values();
    }

    /**
     * Get list of different objects
     *
     * @return list of objects
     */
    public Collection<GameObject> getObjectList() {
        return objects.values();
    }

    /**
     * Get list of different creatures
     *
     * @return list of creatures
     */
    public Collection<Creature> getCreatureList() {
        return creatures.values();
    }

    /**
     * Get the player with the specified ID
     *
     * @param id the id of player
     * @return the player
     */
    public Player getPlayer(short id) {
        return players.get(id);
    }

    public Map<Short, Player> getPlayers() {
        return players;
    }

    /**
     * Get the creature with the specified ID
     *
     * @param id the id of creature
     * @return the creature
     */
    public Creature getCreature(short id) {
        return creatures.get(id);
    }

    /**
     * Bridges are a bit special, identifies one and returns the terrain that
     * should be under it
     *
     * @param type tile BridgeTerrainType
     * @param terrain the terrain tile
     * @return returns null if this is not a bridge, otherwise returns pretty
     * much either water or lava
     */
    public Terrain getTerrainBridge(Tile.BridgeTerrainType type, Terrain terrain) {
        if (terrain.getFlags().contains(Terrain.TerrainFlag.ROOM)) {
            Room room = getRoomByTerrain(terrain.getTerrainId());
            return getTerrainBridge(type, room);
        }

        return null;
    }

    public Terrain getTerrainBridge(Tile.BridgeTerrainType type, Room room) {
        // Swap the terrain if this is a bridge
        if (room != null && !room.getFlags().contains(Room.RoomFlag.PLACEABLE_ON_LAND)) {
            // It is a bridge
            switch (type) {
                case WATER:
                    return getMap().getWater();
                case LAVA:
                    return getMap().getLava();
            }
        }

        return null;
    }

    /**
     * Get the terrain with the specified ID
     *
     * @param id the id of terrain
     * @return the terrain
     */
    public Terrain getTerrain(short id) {
        return terrainTiles.get(id);
    }

    /**
     * Get the room with the specified terrain ID
     *
     * @param id the id of terrain
     * @return the room associated with the terrain ID
     */
    public Room getRoomByTerrain(short id) {
        return roomsByTerrainId.get(id);
    }

    /**
     * Get list of things
     *
     * @return things
     */
    public List<Thing> getThings() {
        return things;
    }

    /**
     * Get the trigger/action with the specified ID
     *
     * @param id the id of trigger/action
     * @return the trigger/action
     */
    public Trigger getTrigger(int id) {
        return triggers.get(id);
    }

    public Map<Integer, Trigger> getTriggers() {
        return triggers;
    }

    /**
     * Get the object with the specified ID
     *
     * @param id the id of object
     * @return the object
     */
    public GameObject getObject(int id) {
        return objects.get((short) id);
    }

    /**
     * Get the room with the specified ID
     *
     * @param id the id of room
     * @return the room
     */
    public Room getRoomById(int id) {
        return rooms.get((short) id);
    }

    /**
     * Get the keeper spell with the specified ID
     *
     * @param id the id of keeper spell
     * @return the keeper spell
     */
    public KeeperSpell getKeeperSpellById(int id) {
        return keeperSpells.get((short) id);
    }

    /**
     * Get the trap with the specified ID
     *
     * @param id the id of trap
     * @return the trap
     */
    public Trap getTrapById(int id) {
        return traps.get((short) id);
    }

    /**
     * Get the door with the specified ID
     *
     * @param id the id of door
     * @return the door
     */
    public Door getDoorById(int id) {
        return doors.get((short) id);
    }

    /**
     * Get the list of all rooms
     *
     * @return list of all rooms
     */
    public List<Room> getRooms() {
        List<Room> c = new ArrayList(rooms.values());
        Collections.sort(c);
        return c;
    }

    /**
     * Get the list of all keeper spells
     *
     * @return list of all keeper spells
     */
    public List<KeeperSpell> getKeeperSpells() {
        List<KeeperSpell> c = new ArrayList(keeperSpells.values());
        Collections.sort(c);
        return c;
    }

    /**
     * Get the list of all doors
     *
     * @return list of all doors
     */
    public List<Door> getDoors() {
        List<Door> c = new ArrayList(doors.values());
        Collections.sort(c);
        return c;
    }

    /**
     * Get the list of all shots
     *
     * @return list of all shots
     */
    public List<Shot> getShots() {
        List<Shot> c = new ArrayList(shots.values());
        Collections.sort(c);
        return c;
    }

    public GameMap getMap() {
        return map;
    }

    /**
     * Get the list of all traps
     *
     * @return list of all traps
     */
    public List<Trap> getTraps() {
        List<Trap> c = new ArrayList(traps.values());
        Collections.sort(c);
        return c;
    }

    public GameLevel getGameLevel() {
        return gameLevel;
    }

    public CreatureSpell getCreatureSpellById(short spellId) {
        return creatureSpells.get(spellId);
    }

    public Effect getEffect(int effectId) {
        return effects.get(effectId);
    }

    public Map<Integer, Effect> getEffects() {
        return effects;
    }

    public EffectElement getEffectElement(int effectElementId) {
        return effectElements.get(effectElementId);
    }

    public Map<Integer, EffectElement> getEffectElements() {
        return effectElements;
    }

    public Map<MiscVariable.MiscType, MiscVariable> getVariables() {
        return variables;
    }

    public List<Availability> getAvailabilities() {
        return availabilities;
    }

    /**
     * Get player specific creature pool
     *
     * @param playerId the player id
     * @return the creature pool
     */
    public Map<Integer, CreaturePool> getCreaturePool(short playerId) {
        return creaturePools.get(Short.valueOf(playerId).intValue());
    }

    public Creature getImp() {
        return imp;
    }

    public Room getPortal() {
        return getRoomById(ROOM_PORTAL_ID);
    }

    public GameObject getLevelGem() {
        return levelGem;
    }

    /**
     * Get the creature stats by level. There might not be a record for every
     * level. Then should just default to 100% stat.
     *
     * @param level the creature level
     * @return the creature stats on given level
     */
    public Map<CreatureStats.StatType, CreatureStats> getCreatureStats(int level) {
        return creatureStatistics.get(level);
    }

    /**
     * Not all the data types are of the length that suits us, do our best to
     * ignore it<br>
     * Skips the file to the correct position after an item is read<br>
     * <b>Use this with the common types!</b>
     *
     * @see #checkOffset(long, java.io.RandomAccessFile, long)
     * @param header the header
     * @param file the file
     * @param offset the file offset before the last item was read
     */
    private void checkOffset(KwdHeader header, RandomAccessFile file, long offset) throws IOException {
        checkOffset(header.getItemSize(), file, offset);
    }

    /**
     * Not all the data types are of the length that suits us, do our best to
     * ignore it<br>
     * Skips the file to the correct position after an item is read<br>
     * <b>Use this directly with Things & Triggers!</b>
     *
     * @see
     * #checkOffset(toniarts.opendungeonkeeper.tools.convert.map.KwdFile.KwdHeader,
     * java.io.RandomAccessFile, long)
     * @param itemSize the item size
     * @param file the file
     * @param offset the file offset before the last item was read
     */
    private void checkOffset(long itemSize, RandomAccessFile file, long offset) throws IOException {
        long wantedOffset = offset + itemSize;
        if (file.getFilePointer() != wantedOffset) {
            logger.log(Level.WARNING, "Record size differs from expected! File offset is {0} and should be {1}!",
                    new java.lang.Object[]{file.getFilePointer(), wantedOffset});
            file.seek(wantedOffset);
        }
    }

    /**
     * Kwd header, few different kinds, handles all
     */
    private class KwdHeader {
        // struct kwdHeader {
        //     unsigned int id;
        //     unsigned int size;
        //     union {
        //         struct {
        //             uint16_t w08; <- width
        //             uint16_t w0a; <- height
        //         } level;
        //         unsigned int dw08; <- version?
        //     };
        //     unsigned int x0c[7];
        // };

        private MapDataTypeEnum id;
        private int headerSize = 56; // Well, header and the id data
        private long size;
        private int checkOne;
        private int itemCount;
        private int width;
        private int height;
        private int checkThree; // in level = 223
        private int dataSizeLevel; // in Level size of data exclude paths
        private int unknown; // only in Triggers and Level
        private int headerEndOffset; // 28, *Map - 8, *Triggers - 32,
        private Date dateCreated;
        private Date dateModified;
        private int checkTwo;
        private int dataSize;

        public KwdHeader() {
        }

        public MapDataTypeEnum getId() {
            return id;
        }

        protected void setId(MapDataTypeEnum id) {
            this.id = id;
        }

        public long getSize() {
            return size;
        }

        protected void setSize(long size) {
            this.size = size;
        }

        public int getHeaderSize() {
            return headerSize;
        }

        protected void setHeaderSize(int headerSize) {
            this.headerSize = headerSize;
        }

        public int getWidth() {
            return width;
        }

        protected void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        protected void setHeight(int height) {
            this.height = height;
        }

        public int getItemCount() {
            return itemCount;
        }

        protected void setItemCount(int itemCount) {
            this.itemCount = itemCount;
        }

        public int getUnknown() {
            return unknown;
        }

        protected void setUnknown(int unknown) {
            this.unknown = unknown;
        }

        protected void setCheckOne(int check) {
            this.checkOne = check;
        }

        public int getCheckOne() {
            return checkOne;
        }

        protected void setCheckTwo(int check) {
            this.checkTwo = check;
        }

        public int getCheckTwo() {
            return checkTwo;
        }

        public Date getDateCreated() {
            return dateCreated;
        }

        protected void setDateCreated(Date date) {
            this.dateCreated = date;
        }

        public Date getDateModified() {
            return dateModified;
        }

        protected void setDateModified(Date date) {
            this.dateModified = date;
        }

        protected void setHeaderEndOffset(int offset) {
            this.headerEndOffset = offset;
        }

        public int getHeaderEndOffset() {
            return headerEndOffset;
        }

        protected void setDataSize(int size) {
            this.dataSize = size;
        }

        public int getDataSize(int unknown) {
            return dataSize;
        }

        /**
         * Get the individiual item size (warning, does not apply to all!)
         *
         * @return
         */
        public long getItemSize() {
            return (getSize() - getHeaderSize()) / getItemCount();
        }
    }
}
