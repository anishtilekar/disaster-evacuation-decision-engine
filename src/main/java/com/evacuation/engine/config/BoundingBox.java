package com.evacuation.engine.config;

/**
 * Geographic bounding box in WGS84 decimal degrees, ordered the way the Overpass API expects
 * (south, west, north, east).
 *
 * <p>Scopes the OpenStreetMap import to a small pilot slice of Pune; the box is parsed from the
 * configurable {@code graph.osm.bbox} property so the demo area can be retargeted without code
 * changes.
 *
 * @param south southern latitude bound, in decimal degrees
 * @param west  western longitude bound, in decimal degrees
 * @param north northern latitude bound, in decimal degrees
 * @param east  eastern longitude bound, in decimal degrees
 */
public record BoundingBox(double south, double west, double north, double east) {

    /**
     * Parses a {@code "south,west,north,east"} CSV string (e.g. {@code "18.51,73.83,18.53,73.85"})
     * into a {@link BoundingBox}, preserving the Overpass coordinate order. Each part is trimmed
     * before parsing.
     *
     * @param csv the comma-separated bounds
     * @return the parsed bounding box
     * @throws IllegalArgumentException if {@code csv} is {@code null} or does not contain exactly
     *                                  four comma-separated numbers
     */
    public static BoundingBox parse(String csv) {
        if (csv == null) {
            throw new IllegalArgumentException(
                    "bbox must not be null; expected \"south,west,north,east\"");
        }

        String[] parts = csv.split(",", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "bbox must have exactly 4 comma-separated numbers (south,west,north,east), got: " + csv);
        }

        double south = parseCoordinate(parts[0], csv);
        double west = parseCoordinate(parts[1], csv);
        double north = parseCoordinate(parts[2], csv);
        double east = parseCoordinate(parts[3], csv);

        return new BoundingBox(south, west, north, east);
    }

    /**
     * Renders the box as {@code "south,west,north,east"} — the order Overpass's {@code bbox}
     * filter consumes.
     *
     * @return the Overpass-ordered bbox string
     */
    public String toOverpassBbox() {
        return south + "," + west + "," + north + "," + east;
    }

    /**
     * Builds a filesystem-safe token for naming this box's cached Overpass response file, e.g.
     * {@code "bbox_18p51_73p83_18p53_73p85"}. Characters that are awkward in file names are
     * substituted ({@code '.'→'p'}, {@code '-'→'m'}, {@code ','→'_'}).
     *
     * @return a token safe to embed in a cache file name
     */
    public String toFileToken() {
        return "bbox_" + toOverpassBbox()
                .replace(',', '_')
                .replace('.', 'p')
                .replace('-', 'm');
    }

    /**
     * Tests whether a coordinate falls within this box, bounds inclusive.
     *
     * @param lat latitude of the point, in decimal degrees
     * @param lon longitude of the point, in decimal degrees
     * @return {@code true} if the point lies inside the box
     */
    public boolean contains(double lat, double lon) {
        return lat >= south && lat <= north && lon >= west && lon <= east;
    }

    private static double parseCoordinate(String value, String csv) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "bbox contains a non-numeric bound (\"" + value.trim() + "\") in: " + csv, ex);
        }
    }
}