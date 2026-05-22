package mindustryX.features.ai;

import arc.files.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.logic.*;

import java.io.*;
import java.util.*;
import static mindustry.Vars.*;
import static mindustryX.features.ai.AIBridgeSupport.*;

final class ActionExecutor{
    private final Ids ids;
    private final TimedCache<Long, OpResult> results;

    ActionExecutor(Ids ids, TimedCache<Long, OpResult> results){
        this.ids = ids;
        this.results = results;
    }

    long enqueueOpId(){
        return ids.nextOpId();
    }

    OpResult getResult(long opId){
        return results.get(opId);
    }

    OpResult execute(long opId, String op, Jval request){
        try{
            String payload = switch(op){
                case "load_map" -> loadMap(request);
                case "join_game" -> joinGame(request);
                case "leave_game" -> leaveGame(request);
                case "place_block" -> placeBlock(request);
                case "break_block" -> breakBlock(request);
                case "write_logic" -> writeLogic(request);
                case "spawn_unit" -> spawnUnit(request);
                case "set_speed" -> setSpeed(request);
                case "pause" -> pause(request);
                default -> throw new BridgeException(ErrorCode.badRequest, "Unsupported write op: " + op);
            };
            OpResult result = new OpResult(opId, true, payload, null, null);
            results.put(opId, result);
            return result;
        }catch(BridgeException exception){
            OpResult result = new OpResult(opId, false, null, exception.errorCode, exception.getMessage());
            results.put(opId, result);
            return result;
        }catch(Throwable error){
            OpResult result = new OpResult(opId, false, null, ErrorCode.internalError, error.toString());
            results.put(opId, result);
            return result;
        }
    }

    private String placeBlock(Jval request){
        Block block = content.block(request.getString("block", ""));
        if(block == null) throw new BridgeException(ErrorCode.notFound, "Unknown block");

        int x = request.getInt("x", Integer.MIN_VALUE);
        int y = request.getInt("y", Integer.MIN_VALUE);
        int rotation = request.getInt("rotation", 0);
        Team team = resolveTeam(request.getInt("team", state.rules.defaultTeam.id));
        String config = request.getString("config", null);

        ensureInBounds(x, y);
        if(!Build.validPlace(block, team, x, y, rotation)){
            throw new BridgeException(ErrorCode.badRequest, "Invalid placement");
        }

        Object placeConfig = resolveConfig(block, config);
        Build.beginPlace(null, block, team, x, y, rotation, placeConfig);
        Tile tile = world.tile(x, y);
        if(tile != null && tile.block() != block){
            ConstructBlock.constructFinish(tile, block, null, (byte)rotation, team, placeConfig);
        }

        Jval result = Jval.newObject()
            .put("x", x)
            .put("y", y)
            .put("block", block.name)
            .put("team", team.id)
            .put("rotation", rotation);
        return result.toString(Jval.Jformat.plain);
    }

    private Object resolveConfig(Block block, String config){
        if(config == null || config.isEmpty()) return null;

        if(block.configurations.containsKey(Item.class)){
            Item item = content.item(config);
            if(item == null) throw new BridgeException(ErrorCode.badRequest, "Unknown item config: " + config);
            return item;
        }
        if(block.configurations.containsKey(Liquid.class)){
            Liquid liquid = content.liquid(config);
            if(liquid == null) throw new BridgeException(ErrorCode.badRequest, "Unknown liquid config: " + config);
            return liquid;
        }
        if(block.configurations.containsKey(Block.class)){
            Block other = content.block(config);
            if(other == null) throw new BridgeException(ErrorCode.badRequest, "Unknown block config: " + config);
            return other;
        }
        if(block.configurations.containsKey(UnitType.class)){
            UnitType type = content.unit(config);
            if(type == null) throw new BridgeException(ErrorCode.badRequest, "Unknown unit config: " + config);
            return type;
        }
        return config;
    }

    private String loadMap(Jval request) throws IOException{
        mindustry.maps.Map map = resolveMap(request);
        Gamemode mode = resolveMode(request.getString("mode", "survival"));
        Rules rules = map.applyRules(mode);

        if(net.client()){
            throw new BridgeException(ErrorCode.readOnly, "Disconnect before loading a local map");
        }

        logic.reset();
        world.loadMap(map, rules);
        state.rules = rules;
        logic.play();

        boolean openedServer = false;
        if(headless && !net.server()){
            netServer.openServer();
            openedServer = true;
        }

        return Jval.newObject()
            .put("map", map.name())
            .put("plainName", map.plainName())
            .put("mode", mode.name())
            .put("headless", headless)
            .put("hosting", net.server())
            .put("openedServer", openedServer)
            .put("worldWidth", world.width())
            .put("worldHeight", world.height())
            .toString(Jval.Jformat.plain);
    }

    private String joinGame(Jval request){
        String ip = request.getString("ip", request.getString("host", "")).trim();
        if(ip.isEmpty()){
            throw new BridgeException(ErrorCode.badRequest, "Missing ip");
        }

        int connectPort = request.getInt("port", port);
        if(connectPort <= 0 || connectPort > 65535){
            throw new BridgeException(ErrorCode.badRequest, "Invalid port");
        }

        logic.reset();
        net.reset();
        netClient.beginConnecting();
        net.connect(ip, connectPort, () -> {});

        return Jval.newObject()
            .put("connecting", true)
            .put("ip", ip)
            .put("port", connectPort)
            .toString(Jval.Jformat.plain);
    }

    private String leaveGame(Jval request){
        net.reset();
        logic.reset();

        return Jval.newObject()
            .put("left", true)
            .put("menu", true)
            .put("headless", headless)
            .toString(Jval.Jformat.plain);
    }

    private String breakBlock(Jval request){
        int x = request.getInt("x", Integer.MIN_VALUE);
        int y = request.getInt("y", Integer.MIN_VALUE);
        Team team = resolveTeam(request.getInt("team", state.rules.defaultTeam.id));

        ensureInBounds(x, y);
        if(!Build.validBreak(team, x, y)){
            throw new BridgeException(ErrorCode.badRequest, "Invalid break");
        }

        Tile tile = world.tileBuilding(x, y);
        Block previous = tile == null ? null : tile.block();
        Build.beginBreak(null, team, x, y);
        if(tile != null && previous != null && tile.block() instanceof ConstructBlock){
            ConstructBlock.deconstructFinish(tile, previous, null);
        }
        return Jval.newObject().put("x", x).put("y", y).put("team", team.id).toString(Jval.Jformat.plain);
    }

    private String writeLogic(Jval request){
        int x = request.getInt("x", Integer.MIN_VALUE);
        int y = request.getInt("y", Integer.MIN_VALUE);
        String code = request.getString("code", null);

        ensureInBounds(x, y);
        if(code == null) throw new BridgeException(ErrorCode.badRequest, "Missing code");

        Tile tile = world.tile(x, y);
        if(tile == null || !(tile.build instanceof LogicBlock.LogicBuild logic)){
            throw new BridgeException(ErrorCode.notFound, "No logic build at target");
        }

        String previous = logic.code;
        try{
            logic.configure(LogicBlock.compress(code, logic.relativeConnections()));
            if(!normalizeLogic(logic.code).equals(normalizeLogic(code))){
                throw new BridgeException(ErrorCode.badRequest, "Invalid logic code");
            }
        }catch(BridgeException exception){
            logic.configure(LogicBlock.compress(previous, logic.relativeConnections()));
            throw exception;
        }catch(Throwable error){
            logic.configure(LogicBlock.compress(previous, logic.relativeConnections()));
            throw error;
        }

        return Jval.newObject().put("x", x).put("y", y).put("length", code.length()).toString(Jval.Jformat.plain);
    }

    private static String normalizeLogic(String code){
        return code == null ? "" : code.replace("\r\n", "\n").trim();
    }

    private String spawnUnit(Jval request){
        String unitName = request.getString("unit", "");
        UnitType type = content.unit(unitName);
        if(type == null) throw new BridgeException(ErrorCode.notFound, "Unknown unit type");

        Team team = resolveTeam(request.getInt("team", state.rules.defaultTeam.id));
        float x = request.getFloat("x", Float.NaN);
        float y = request.getFloat("y", Float.NaN);
        float rotation = request.getFloat("rotation", 90f);

        if(Float.isNaN(x) || Float.isNaN(y)){
            throw new BridgeException(ErrorCode.badRequest, "Missing spawn coordinates");
        }

        Unit unit = type.create(team);
        unit.set(x, y);
        unit.rotation = rotation;
        unit.add();

        return Jval.newObject()
            .put("id", unit.id)
            .put("unit", type.name)
            .put("team", team.id)
            .put("x", x)
            .put("y", y)
            .toString(Jval.Jformat.plain);
    }

    private String setSpeed(Jval request){
        float speed = request.getFloat("speed", 1f);
        mindustryX.features.TimeControl.setGameSpeed(speed);
        return Jval.newObject().put("speed", speed).toString(Jval.Jformat.plain);
    }

    private String pause(Jval request){
        boolean paused = request.getBool("paused", true);
        state.set(paused ? GameState.State.paused : GameState.State.playing);
        return Jval.newObject().put("paused", paused).toString(Jval.Jformat.plain);
    }

    private static Team resolveTeam(int teamId){
        return Team.get(teamId);
    }

    private static Gamemode resolveMode(String modeName){
        if(modeName == null || modeName.trim().isEmpty()) return Gamemode.survival;

        try{
            return Gamemode.valueOf(modeName.trim().toLowerCase(Locale.ROOT));
        }catch(IllegalArgumentException exception){
            throw new BridgeException(ErrorCode.badRequest, "Unknown mode: " + modeName);
        }
    }

    private static mindustry.maps.Map resolveMap(Jval request) throws IOException{
        String path = request.getString("path", "").trim();
        if(!path.isEmpty()){
            Fi file = new Fi(path);
            if(!file.exists()){
                throw new BridgeException(ErrorCode.notFound, "Map file not found");
            }
            return MapIO.createMap(file, true);
        }

        String name = request.getString("name", request.getString("mapName", "")).trim();
        if(name.isEmpty()){
            throw new BridgeException(ErrorCode.badRequest, "Missing map name or path");
        }

        mindustry.maps.Map map = maps.all().find(m -> m.name().equals(name) || m.plainName().equalsIgnoreCase(name));
        if(map == null){
            map = maps.byName(name);
        }
        if(map == null){
            throw new BridgeException(ErrorCode.notFound, "Unknown map: " + name);
        }
        return map;
    }

    private static void ensureInBounds(int x, int y){
        if(!world.tiles.in(x, y)){
            throw new BridgeException(ErrorCode.outOfBounds, "Coordinates out of bounds");
        }
    }
}
