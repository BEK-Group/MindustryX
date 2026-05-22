package mindustryX.features.ai;

import arc.*;
import arc.util.serialization.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustryX.events.*;

final class EventBridge{
    private final SharedEventRing ring;
    private final AIProtocolServer server;

    EventBridge(SharedEventRing ring, AIProtocolServer server){
        this.ring = ring;
        this.server = server;
    }

    void init(){
        Events.on(TileChangeEvent.class, event -> {
            if(event.tile == null) return;
            Jval payload = Jval.newObject()
                .put("x", event.tile.x)
                .put("y", event.tile.y)
                .put("block", event.tile.block().name)
                .put("team", event.tile.team().id);
            pushHigh("tile_changed", payload);
        });

        Events.on(HealthChangedEvent.class, event -> {
            if(event.entity == null) return;
            int entityId = -1;
            if(event.entity instanceof Unit){
                entityId = ((Unit)event.entity).id;
            }else if(event.entity instanceof Building){
                entityId = ((Building)event.entity).id;
            }
            Jval payload = Jval.newObject()
                .put("entityId", entityId)
                .put("amount", event.amount)
                .put("splash", event.isSplash);
            if(event.entity instanceof Teamc teamc){
                payload.put("team", teamc.team().id);
            }
            pushHigh("health_changed", payload);
        });

        Events.on(SendPacketEvent.class, event -> {
            if(event.packet == null) return;
            Jval payload = Jval.newObject()
                .put("packetType", event.packet.getClass().getSimpleName())
                .put("cancelled", event.isCancelled);
            if(event.con != null){
                payload.put("connection", event.con.address);
            }
            pushHigh("packet_sent", payload);
        });

        Events.on(WorldLoadEvent.class, event -> pushLow("world_load", Jval.newObject()
            .put("map", mindustry.Vars.state.map == null ? "" : mindustry.Vars.state.map.name())));
        Events.on(ResetEvent.class, event -> pushLow("reset", Jval.newObject()));
        Events.on(WaveEvent.class, event -> pushLow("wave", Jval.newObject().put("wave", mindustry.Vars.state.wave)));
    }

    private void pushHigh(String type, Jval payload){
        if(ring != null && ring.available()){
            ring.append(type, payload);
        }
        pushLow(type, payload);
    }

    private void pushLow(String type, Jval payload){
        if(server == null || !server.running()) return;
        AIProtocolServer.Message event = AIProtocolServer.Message.event(type, payload.toString(Jval.Jformat.plain));
        if(ring != null && ring.available()){
            event.eventSeq = Math.max(0L, ring.writeSeq() - 1L);
            event.dropped = ring.dropped();
        }
        server.broadcast(event);
    }
}
