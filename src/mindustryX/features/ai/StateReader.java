package mindustryX.features.ai;

import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.modules.*;

import java.util.*;

import static mindustry.Vars.*;
import static mindustryX.features.ai.AIBridgeSupport.*;

final class StateReader{
    private final SameTickCache<String, String> sameTickCache = new SameTickCache<>();

    public String read(String op, Jval request){
        String key = op + "|" + request.toString(Jval.Jformat.plain);
        return sameTickCache.getOrCompute(key, () -> {
            if("get_state".equals(op)) return encodeState();
            if("list_maps".equals(op)) return encodeMaps();
            if("get_tiles".equals(op)) return encodeTiles(request);
            if("get_buildings".equals(op)) return encodeBuildings(request);
            if("get_units".equals(op)) return encodeUnits(request);
            if("get_content".equals(op)) return encodeContent(request);
            throw new BridgeException(ErrorCode.badRequest, "Unsupported read op: " + op);
        });
    }

    private String encodeState(){
        Jval root = Jval.newObject();
        root.put("tick", (long)Time.time);
        root.put("timeMillis", Time.millis());
        root.put("headless", Vars.headless);
        root.put("paused", state.is(GameState.State.paused));
        root.put("menu", state.isMenu());
        root.put("gameOver", state.gameOver);
        root.put("wave", state.wave);
        root.put("enemies", state.enemies);
        root.put("map", state.map == null ? "" : state.map.name());
        root.put("worldWidth", world.width());
        root.put("worldHeight", world.height());
        root.put("defaultTeam", state.rules.defaultTeam.id);
        root.put("waveTeam", state.rules.waveTeam.id);
        root.put("editor", state.rules.editor);
        root.put("pvp", state.rules.pvp);
        root.put("waves", state.rules.waves);
        root.put("attackMode", state.rules.attackMode);
        root.put("logicTimeScale", state.rules.unitBuildSpeedMultiplier);
        root.put("netActive", net.active());
        root.put("netServer", net.server());
        root.put("netClient", net.client());
        root.put("currentMap", state.map == null ? "" : state.map.name());

        Jval teams = Jval.newArray();
        for(Teams.TeamData data : state.teams.getActive()){
            Jval team = Jval.newObject();
            team.put("id", data.team.id);
            team.put("name", data.team.name);
            team.put("active", data.team.active());
            team.put("alive", data.team.isAlive());
            team.put("cores", data.cores.size);
            team.put("unitCap", Units.getCap(data.team));
            team.put("units", data.unitCount);
            team.put("items", encodeItems(data.team.items()));
            teams.add(team);
        }
        root.put("teams", teams);
        return root.toString(Jval.Jformat.plain);
    }

    private String encodeMaps(){
        Jval root = Jval.newObject();
        Jval arr = Jval.newArray();

        for(mindustry.maps.Map map : maps.all()){
            Jval entry = Jval.newObject();
            entry.put("name", map.name());
            entry.put("plainName", map.plainName());
            entry.put("custom", map.custom);
            entry.put("width", map.width);
            entry.put("height", map.height);
            entry.put("file", map.file == null ? "" : map.file.absolutePath());
            arr.add(entry);
        }

        root.put("maps", arr);
        return root.toString(Jval.Jformat.plain);
    }

    private String encodeTiles(Jval request){
        int x1 = request.getInt("x1", 0);
        int y1 = request.getInt("y1", 0);
        int x2 = request.getInt("x2", world.width() - 1);
        int y2 = request.getInt("y2", world.height() - 1);

        if(x1 > x2 || y1 > y2){
            throw new BridgeException(ErrorCode.badRequest, "Invalid tile range");
        }
        if(!world.tiles.in(x1, y1) || !world.tiles.in(x2, y2)){
            throw new BridgeException(ErrorCode.outOfBounds, "Tile range out of bounds");
        }

        Jval root = Jval.newObject();
        root.put("x1", x1);
        root.put("y1", y1);
        root.put("x2", x2);
        root.put("y2", y2);

        Jval tiles = Jval.newArray();
        for(int y = y1; y <= y2; y++){
            for(int x = x1; x <= x2; x++){
                Tile tile = world.tile(x, y);
                if(tile == null) continue;
                Jval entry = Jval.newObject();
                entry.put("x", tile.x);
                entry.put("y", tile.y);
                entry.put("floor", tile.floor().name);
                entry.put("overlay", tile.overlay().name);
                entry.put("block", tile.block().name);
                entry.put("team", tile.team().id);
                entry.put("data", tile.data);
                entry.put("extraData", tile.extraData);
                entry.put("center", tile.isCenter());
                if(tile.build != null){
                    entry.put("buildId", tile.build.id);
                    entry.put("rotation", tile.build.rotation);
                }
                tiles.add(entry);
            }
        }
        root.put("tiles", tiles);
        return root.toString(Jval.Jformat.plain);
    }

    private String encodeBuildings(Jval request){
        int teamFilter = request.getInt("team", -1);
        boolean includeItems = request.getBool("includeItems", false);
        boolean includeLiquids = request.getBool("includeLiquids", false);
        boolean includeConfig = request.getBool("includeConfig", false);

        Seq<Building> builds = new Seq<>();
        for(Building build : Groups.build){
            if(build == null || !build.isValid()) continue;
            if(teamFilter >= 0 && build.team.id != teamFilter) continue;
            if(build.tile == null || build.tile.build != build) continue;
            builds.add(build);
        }
        builds.sort(Comparator.comparingInt(Building::id));

        Jval root = Jval.newObject();
        Jval arr = Jval.newArray();
        for(Building build : builds){
            Jval entry = Jval.newObject();
            entry.put("id", build.id);
            entry.put("block", build.block.name);
            entry.put("team", build.team.id);
            entry.put("tileX", build.tileX());
            entry.put("tileY", build.tileY());
            entry.put("x", build.x);
            entry.put("y", build.y);
            entry.put("rotation", build.rotation);
            entry.put("health", build.health);
            entry.put("maxHealth", build.maxHealth());
            entry.put("enabled", build.enabled);
            entry.put("efficiency", build.efficiency);
            if(build.items != null){
                entry.put("itemTotal", build.items.total());
                if(includeItems){
                    entry.put("items", encodeItems(build.items));
                }
            }
            if(build.liquids != null){
                if(includeLiquids){
                    entry.put("liquids", encodeLiquids(build.liquids));
                }
            }
            if(includeConfig){
                Object config = build.config();
                entry.put("config", config == null ? null : String.valueOf(config));
            }
            if(build instanceof LogicBlock.LogicBuild logic){
                entry.put("logicCode", logic.code);
                entry.put("logicLinks", logic.links.size);
            }
            arr.add(entry);
        }
        root.put("buildings", arr);
        return root.toString(Jval.Jformat.plain);
    }

    private String encodeUnits(Jval request){
        int teamFilter = request.getInt("team", -1);
        Jval root = Jval.newObject();
        Jval arr = Jval.newArray();

        Seq<Unit> units = new Seq<>();
        for(Unit unit : Groups.unit){
            if(unit == null || !unit.isValid()) continue;
            if(teamFilter >= 0 && unit.team.id != teamFilter) continue;
            units.add(unit);
        }
        units.sort(Comparator.comparingInt(Unit::id));

        for(Unit unit : units){
            Jval entry = Jval.newObject();
            entry.put("id", unit.id);
            entry.put("type", unit.type.name);
            entry.put("team", unit.team.id);
            entry.put("x", unit.x);
            entry.put("y", unit.y);
            entry.put("rotation", unit.rotation);
            entry.put("health", unit.health);
            entry.put("maxHealth", unit.maxHealth);
            entry.put("dead", unit.dead);
            entry.put("speed", unit.speed());
            entry.put("velX", unit.vel.x);
            entry.put("velY", unit.vel.y);
            entry.put("elevation", unit.elevation);
            if(unit.stack != null && unit.stack.amount > 0){
                entry.put("item", unit.stack.item.name);
                entry.put("itemAmount", unit.stack.amount);
            }
            arr.add(entry);
        }

        root.put("units", arr);
        return root.toString(Jval.Jformat.plain);
    }

    private String encodeContent(Jval request){
        String type = request.getString("type", "all");
        Jval root = Jval.newObject();

        if("all".equals(type) || "blocks".equals(type)){
            Jval blocks = Jval.newArray();
            for(Block block : content.blocks()){
                Jval entry = Jval.newObject();
                entry.put("id", block.id);
                entry.put("name", block.name);
                entry.put("localizedName", block.localizedName);
                entry.put("size", block.size);
                entry.put("rotate", block.rotate);
                blocks.add(entry);
            }
            root.put("blocks", blocks);
        }

        if("all".equals(type) || "units".equals(type)){
            Jval units = Jval.newArray();
            for(UnitType unit : content.units()){
                Jval entry = Jval.newObject();
                entry.put("id", unit.id);
                entry.put("name", unit.name);
                entry.put("localizedName", unit.localizedName);
                entry.put("flying", unit.flying);
                entry.put("health", unit.health);
                entry.put("speed", unit.speed);
                units.add(entry);
            }
            root.put("unitTypes", units);
        }

        if("all".equals(type) || "items".equals(type)){
            Jval items = Jval.newArray();
            for(Item item : content.items()){
                Jval entry = Jval.newObject();
                entry.put("id", item.id);
                entry.put("name", item.name);
                entry.put("localizedName", item.localizedName);
                items.add(entry);
            }
            root.put("items", items);
        }

        if("all".equals(type) || "liquids".equals(type)){
            Jval liquids = Jval.newArray();
            for(Liquid liquid : content.liquids()){
                Jval entry = Jval.newObject();
                entry.put("id", liquid.id);
                entry.put("name", liquid.name);
                entry.put("localizedName", liquid.localizedName);
                liquids.add(entry);
            }
            root.put("liquids", liquids);
        }

        return root.toString(Jval.Jformat.plain);
    }

    private Jval encodeItems(ItemModule items){
        Jval root = Jval.newObject();
        items.each((item, amount) -> root.put(item.name, amount));
        return root;
    }

    private Jval encodeLiquids(LiquidModule liquids){
        Jval root = Jval.newObject();
        liquids.each((liquid, amount) -> root.put(liquid.name, amount));
        return root;
    }
}
