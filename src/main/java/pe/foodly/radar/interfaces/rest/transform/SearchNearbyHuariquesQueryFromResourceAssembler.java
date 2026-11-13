package pe.foodly.radar.interfaces.rest.transform;

import pe.foodly.radar.application.queries.SearchNearbyHuariquesQuery;
import pe.foodly.radar.interfaces.rest.resources.RadarSearchResource;

public final class SearchNearbyHuariquesQueryFromResourceAssembler {

    private SearchNearbyHuariquesQueryFromResourceAssembler() {
    }

    /** El resource ya fue validado: sus campos no son nulos. */
    public static SearchNearbyHuariquesQuery toQuery(RadarSearchResource resource) {
        return new SearchNearbyHuariquesQuery(resource.latitude(), resource.longitude(), resource.kRing());
    }
}
