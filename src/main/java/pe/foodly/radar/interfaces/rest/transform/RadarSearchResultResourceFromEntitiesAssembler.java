package pe.foodly.radar.interfaces.rest.transform;

import java.util.List;
import pe.foodly.radar.application.queries.NearbyHuarique;
import pe.foodly.radar.interfaces.rest.resources.HuariqueLocationResource;
import pe.foodly.radar.interfaces.rest.resources.RadarSearchResultResource;

public final class RadarSearchResultResourceFromEntitiesAssembler {

    private RadarSearchResultResourceFromEntitiesAssembler() {
    }

    public static RadarSearchResultResource toResource(List<NearbyHuarique> results) {
        List<HuariqueLocationResource> huariques = results.stream()
                .map(result -> new HuariqueLocationResource(
                        result.location().getHuariqueId().value(),
                        result.location().getCoordinates().latitude(),
                        result.location().getCoordinates().longitude(),
                        result.distance().meters()))
                .toList();
        return new RadarSearchResultResource(huariques, huariques.size());
    }
}
