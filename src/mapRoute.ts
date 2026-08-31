/**
 * SPEC-451 — a route handed to a `map` content block at runtime.
 *
 * Return one of these from `onBeforeStepRender`, keyed by the map block's id, and the block draws
 * it instead of its authored stops:
 *
 * ```ts
 * AppDNA.setOnboardingDelegate({
 *   async onBeforeStepRender(flowId, stepId) {
 *     const route = await myRoutingService.routeForCurrentOrder();
 *     return {
 *       mapRoutes: {
 *         delivery_map: mapRoute({
 *           polyline: route.encodedPolyline,
 *           stops: [
 *             { lat: 52.2297, lng: 21.0122, title: 'Warehouse' },
 *             { lat: 52.4064, lng: 16.9252, title: 'You' },
 *           ],
 *         }),
 *       },
 *     };
 *   },
 * });
 * ```
 *
 * This is the only route source that can answer "where is it right now": authored stops are fixed
 * at publish time, and a template variable can only carry what the flow already knows.
 *
 * Pure data shapes, per ADR-001 — the rendering, the URL composition and the merge all happen in
 * the native core, so an RN host's map is byte-for-byte the map an iOS host's is.
 */

/** One point on a host-supplied route. */
export interface MapRouteStop {
  lat: number;
  lng: number;
  /**
   * Shown on the pin's label where the map style has room for one. Optional: a coordinate is a
   * place, a title is a courtesy.
   */
  title?: string;
}

export interface MapRouteInput {
  /**
   * Google's encoded-polyline format, which is what every routing service returns.
   *
   * Supplying it draws the real road geometry. Supplying only `stops` draws straight lines between
   * them. Both together is the normal case: the line follows the roads and the pins mark the stops.
   */
  polyline?: string;
  /** The points to mark. Ordering is the route's order — stop 1 is the first pin. */
  stops?: MapRouteStop[];
}

/** The bridge shape the native decoder reads. */
export interface MapRouteBridgePayload {
  polyline?: string;
  stops?: MapRouteStop[];
}

/**
 * Build the bridge payload for one map block.
 *
 * Keys are part of the contract and pinned by the `map_delegate_route` shared fixture, so renaming
 * one here fails a test rather than silently leaving every RN host's map on its authored route.
 *
 * Empty members are omitted rather than sent as `null`: the native decoder treats "neither a line
 * nor a place" as no route at all and keeps the authored one, which is the right outcome for a
 * routing call that came back empty.
 */
export function mapRoute(input: MapRouteInput): MapRouteBridgePayload {
  const out: MapRouteBridgePayload = {};
  if (input.polyline) out.polyline = input.polyline;
  if (input.stops && input.stops.length > 0) out.stops = input.stops;
  return out;
}
