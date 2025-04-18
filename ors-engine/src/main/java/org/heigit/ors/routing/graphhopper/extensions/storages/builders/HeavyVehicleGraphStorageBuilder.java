/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.routing.graphhopper.extensions.storages.builders;

import com.graphhopper.GraphHopper;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.storage.GraphExtension;
import com.graphhopper.util.EdgeIteratorState;
import org.heigit.ors.config.profile.ExtendedStorageProperties;
import org.heigit.ors.routing.graphhopper.extensions.HeavyVehicleAttributes;
import org.heigit.ors.routing.graphhopper.extensions.VehicleDimensionRestrictions;
import org.heigit.ors.routing.graphhopper.extensions.storages.HeavyVehicleAttributesGraphStorage;

import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeavyVehicleGraphStorageBuilder extends AbstractGraphStorageBuilder {
    private boolean includeRestrictions = true;
    private HeavyVehicleAttributesGraphStorage storage;
    private int hgvType = 0;
    private boolean hasRestrictionValues;
    private final double[] restrictionValues = new double[VehicleDimensionRestrictions.COUNT];
    private final List<String> motorVehicleRestrictions = new ArrayList<>(5);
    private final Set<String> motorVehicleRestrictedValues = new HashSet<>(5);
    private final Set<String> motorVehicleHgvValues = new HashSet<>(6);

    private final Set<String> noValues = new HashSet<>(5);
    private final Set<String> yesValues = new HashSet<>(5);
    private final Pattern patternDimension;
    private final Pattern patternHazmat;

    public HeavyVehicleGraphStorageBuilder() {
        motorVehicleRestrictions.addAll(Arrays.asList("motorcar", "motor_vehicle", "vehicle", "access"));

        motorVehicleRestrictedValues.add("private");
        motorVehicleRestrictedValues.add("no");
        motorVehicleRestrictedValues.add("restricted");
        motorVehicleRestrictedValues.add("military");

        motorVehicleHgvValues.addAll(Arrays.asList("hgv", "goods", "bus", "agricultural", "forestry", "delivery"));

        noValues.addAll(Arrays.asList("no", "private"));
        yesValues.addAll(Arrays.asList("yes", "designated"));

        patternDimension = Pattern.compile("(?:\\s*(\\d+)\\s*(?:feet|ft\\.|ft|'))?(?:(\\d+)\\s*(?:inches|in\\.|in|''|\"))?");
        patternHazmat = Pattern.compile("^hazmat(:[B-E])?$");
    }

    public GraphExtension init(GraphHopper graphhopper) throws Exception {
        if (storage != null)
            throw new Exception("GraphStorageBuilder has been already initialized.");

        ExtendedStorageProperties parameters;
        parameters = this.parameters;

        if (parameters.getRestrictions() != null) {
            includeRestrictions = parameters.getRestrictions();
        }

        storage = new HeavyVehicleAttributesGraphStorage(includeRestrictions);

        return storage;
    }

    public void processWay(ReaderWay way) {
        // reset values
        hgvType = 0;

        if (hasRestrictionValues) {
            restrictionValues[0] = 0.0;
            restrictionValues[1] = 0.0;
            restrictionValues[2] = 0.0;
            restrictionValues[3] = 0.0;
            restrictionValues[4] = 0.0;
            hasRestrictionValues = false;
        }

        boolean hasHighway = way.hasTag("highway");

        if (hasHighway) {
            // process motor vehicle restrictions before any more specific vehicle type tags which override the former

            // if there are any generic motor vehicle restrictions restrict all types...
            if (way.hasTag(motorVehicleRestrictions, motorVehicleRestrictedValues))
                hgvType = HeavyVehicleAttributes.ANY;

            //...or all but the explicitly listed ones
            if (way.hasTag(motorVehicleRestrictions, motorVehicleHgvValues)) {
                int flag = 0;
                for (String key : motorVehicleRestrictions) {
                    String[] values = way.getTagValues(key);
                    for (String val : values) {
                        if (motorVehicleHgvValues.contains(val))
                            flag |= HeavyVehicleAttributes.getFromString(val);
                    }
                }
                hgvType = HeavyVehicleAttributes.ANY & ~flag;
            }

            Iterator<Entry<String, Object>> it = way.getProperties();

            while (it.hasNext()) {
                Map.Entry<String, Object> pairs = it.next();
                String key = pairs.getKey();
                String value = pairs.getValue().toString();

                /*
                 * https://wiki.openstreetmap.org/wiki/Restrictions
                 */

                int valueIndex = -1;

                switch (key) {
                    case "maxheight" -> valueIndex = VehicleDimensionRestrictions.MAX_HEIGHT;
                    case "maxweight", "maxweight:hgv" -> valueIndex = VehicleDimensionRestrictions.MAX_WEIGHT;
                    case "maxwidth" -> valueIndex = VehicleDimensionRestrictions.MAX_WIDTH;
                    case "maxlength", "maxlength:hgv" -> valueIndex = VehicleDimensionRestrictions.MAX_LENGTH;
                    case "maxaxleload" -> valueIndex = VehicleDimensionRestrictions.MAX_AXLE_LOAD;
                    default -> {
                    }
                }

                // given tag is a weight/dimension restriction
                if (valueIndex >= 0 && includeRestrictions && !("none".equals(value) || "default".equals(value))) {
                    double parsedValue = -1;

                    // sanitize decimal separators
                    if (value.contains(","))
                        value = value.replace(',', '.');

                    // weight restrictions
                    if (valueIndex == VehicleDimensionRestrictions.MAX_WEIGHT || valueIndex == VehicleDimensionRestrictions.MAX_AXLE_LOAD) {
                        if (value.contains("t")) {
                            value = value.replace('t', ' ');
                        } else if (value.contains("lbs")) {
                            value = value.replace("lbs", " ");
                            parsedValue = parseDouble(value) / 2204.622;
                        }
                    }

                    // dimension restrictions
                    else {
                        if (value.contains("m")) {
                            value = value.replace('m', ' ');
                        } else {
                            Matcher m = patternDimension.matcher(value);
                            if (m.matches() && m.lookingAt()) {
                                double feet = parseDouble(m.group(1));
                                double inches = 0;
                                if (m.groupCount() > 1 && m.group(2) != null) {
                                    inches = parseDouble(m.group(2));
                                }
                                parsedValue = feet * 0.3048 + inches * 0.0254;
                            }
                        }
                    }

                    if (parsedValue == -1)
                        parsedValue = parseDouble(value);

                    // it was possible to extract a reasonable value
                    if (parsedValue > 0) {
                        restrictionValues[valueIndex] = parsedValue;
                        hasRestrictionValues = true;
                    }
                }

                if (motorVehicleHgvValues.contains(key)) {
                    //TODO: account for <vehicle_type>:[forward/backward] keys
                    //TODO: allow access:<vehicle_type> as described in #703. Might be necessary to adjust the upstream PBF parsing part as well.
                    String vehicleType = getVehicleType(key, value);
                    String accessValue = getVehicleAccess(vehicleType, value);
                    setAccessFlags(vehicleType, accessValue);
                    if (vehicleType.equals(value))// e.g. hgv=delivery implies that hgv other than delivery vehicles are blocked
                        setAccessFlags(key, "no");
                } else if (patternHazmat.matcher(key).matches() && "no".equals(value)) {
                    hgvType |= HeavyVehicleAttributes.HAZMAT;
                }
            }
        }
    }

    public void processEdge(ReaderWay way, EdgeIteratorState edge) {
        storage.setEdgeValue(edge.getEdge(), hgvType, restrictionValues);
    }

    private String getVehicleType(String key, String value) {
        return motorVehicleHgvValues.contains(value) ? value : key;// hgv=[delivery/agricultural/forestry]
    }

    private String getVehicleAccess(String vehicleType, String value) {
        if (vehicleType.equals(value) || yesValues.contains(value))
            return "yes";
        else if (noValues.contains(value))
           return "no";

        return null;
    }

    /**
     * Toggle on/off the bit corresponding to a given hgv type defined by {@code flag} inside binary restriction masks
     * based on the value of {@code tag}. "no" sets the bit in {@code _hgvType}, while "yes" unsets it.
     *
     * @param vehicle a String describing one of the vehicle types defined in {@code HeavyVehicleAttributes}
     * @param access a String describing the access restriction
     */
    private void setAccessFlags(String vehicle, String access) {
        int flag = HeavyVehicleAttributes.getFromString(vehicle);
        if (access != null) {
            if ("no".equals(access))
                hgvType |= flag;
            else if ("yes".equals(access))
                hgvType &= ~flag;
        }
    }

    private double parseDouble(String str) {
        double d;
        try {
            d = Double.parseDouble(str);
        } catch (NumberFormatException e) {
            d = 0.0;
        }
        return d;
    }

    @Override
    public String getName() {
        return "HeavyVehicle";
    }
}
