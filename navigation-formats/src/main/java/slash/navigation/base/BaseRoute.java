/*
    This file is part of RouteConverter.

    RouteConverter is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    RouteConverter is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with RouteConverter; if not, write to the Free Software
    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA

    Copyright (C) 2007 Christian Pesch. All Rights Reserved.
*/

package slash.navigation.base;

import slash.common.type.CompactCalendar;
import slash.navigation.bcr.BcrRoute;
import slash.navigation.copilot.CoPilot6Format;
import slash.navigation.copilot.CoPilot7Format;
import slash.navigation.copilot.CoPilot8Format;
import slash.navigation.copilot.CoPilot9Format;
import slash.navigation.fpl.GarminFlightPlanRoute;
import slash.navigation.gopal.GoPal3Route;
import slash.navigation.gopal.GoPal5Route;
import slash.navigation.gopal.GoPalTrackFormat;
import slash.navigation.gpx.GpxRoute;
import slash.navigation.itn.TomTomRoute;
import slash.navigation.klicktel.KlickTelRoute;
import slash.navigation.kml.BaseKmlFormat;
import slash.navigation.kml.Igo8RouteFormat;
import slash.navigation.kml.Kml20Format;
import slash.navigation.kml.Kml21Format;
import slash.navigation.kml.Kml22BetaFormat;
import slash.navigation.kml.Kml22Format;
import slash.navigation.kml.KmlRoute;
import slash.navigation.kml.Kmz20Format;
import slash.navigation.kml.Kmz21Format;
import slash.navigation.kml.Kmz22BetaFormat;
import slash.navigation.kml.Kmz22Format;
import slash.navigation.mm.MagicMaps2GoFormat;
import slash.navigation.mm.MagicMapsIktRoute;
import slash.navigation.mm.MagicMapsPthRoute;
import slash.navigation.nmea.NmeaRoute;
import slash.navigation.nmn.NavigatingPoiWarnerFormat;
import slash.navigation.nmn.NmnRoute;
import slash.navigation.nmn.NmnRouteFormat;
import slash.navigation.nmn.NmnUrlFormat;
import slash.navigation.ovl.OvlRoute;
import slash.navigation.simple.ColumbusV900ProfessionalFormat;
import slash.navigation.simple.ColumbusV900StandardFormat;
import slash.navigation.simple.GlopusFormat;
import slash.navigation.simple.GoRiderGpsFormat;
import slash.navigation.simple.GpsTunerFormat;
import slash.navigation.simple.GroundTrackFormat;
import slash.navigation.simple.HaicomLoggerFormat;
import slash.navigation.simple.Iblue747Format;
import slash.navigation.simple.KienzleGpsFormat;
import slash.navigation.simple.KompassFormat;
import slash.navigation.simple.NavilinkFormat;
import slash.navigation.simple.OpelNaviFormat;
import slash.navigation.simple.QstarzQ1000Format;
import slash.navigation.simple.Route66Format;
import slash.navigation.simple.SygicAsciiFormat;
import slash.navigation.simple.SygicUnicodeFormat;
import slash.navigation.simple.WebPageFormat;
import slash.navigation.tour.TourRoute;
import slash.navigation.url.GoogleMapsUrlFormat;
import slash.navigation.util.Positions;
import slash.navigation.viamichelin.ViaMichelinRoute;
import slash.navigation.wbt.WintecWbt201Tk1Format;
import slash.navigation.wbt.WintecWbt201Tk2Format;
import slash.navigation.wbt.WintecWbt202TesFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.List;

import static java.lang.Math.max;
import static slash.common.io.Transfer.toArray;
import static slash.common.type.CompactCalendar.UTC;
import static slash.common.type.CompactCalendar.fromCalendar;
import static slash.navigation.util.Positions.contains;

/**
 * The base of all routes formats.
 *
 * @author Christian Pesch
 */

public abstract class BaseRoute<P extends BaseNavigationPosition, F extends BaseNavigationFormat> {
    private static final String REVERSE_ROUTE_NAME_POSTFIX = " (rev)";

    private final F format;
    protected RouteCharacteristics characteristics;

    protected BaseRoute(F format, RouteCharacteristics characteristics) {
        this.format = format;
        this.characteristics = characteristics;
    }

    public F getFormat() {
        return format;
    }

    public RouteCharacteristics getCharacteristics() {
        return characteristics;
    }

    public void setCharacteristics(RouteCharacteristics characteristics) {
        this.characteristics = characteristics;
    }

    public abstract String getName();
    public abstract void setName(String name);

    public abstract List<String> getDescription();

    public abstract List<P> getPositions();

    public abstract int getPositionCount();

    private void move(int index, int upOrDown) {
        List<P> positions = getPositions();
        P move = positions.get(index);
        P replace = positions.get(index + upOrDown);
        positions.set(index + upOrDown, move);
        positions.set(index, replace);
    }

    public void top(int index, int topOffset) {
        while (index > topOffset) {
            up(index, index - 1);
            index--;
        }
    }

    public void down(int fromIndex, int toIndex) {
        while (fromIndex < toIndex)
            move(fromIndex++, +1);
    }

    public void up(int fromIndex, int toIndex) {
        while (fromIndex > toIndex)
            move(fromIndex--, -1);
    }

    public void bottom(int index, int bottomOffset) {
        while (index < getPositionCount() - 1 - bottomOffset) {
            down(index, index + 1);
            index++;
        }
    }

    public abstract void add(int index, P position);

    public P remove(int index) {
        List<P> positions = getPositions();
        return positions.remove(index);
    }

    /**
     * Removes duplicate adjacent {@link #getPositions() positions} from this route, leaving
     * only distinct neighbours
     */
    public void removeDuplicates() {
        List<P> positions = getPositions();
        P previous = null;
        int index = 0;
        while (index < positions.size()) {
            P next = positions.get(index);
            if (previous != null && (!next.hasCoordinates() || next.calculateDistance(previous) <= 0.0)) {
                positions.remove(index);
            } else
                index++;
            previous = next;
        }
    }

    public void ensureIncreasingTime() {
        if(getPositionCount() < 2)
            return;

        long completeTime = getTime(); // ms
        double completeDistance = getDistance(); // m
        double averageSpeed = completeTime > 0 ? completeDistance / completeTime * 1000 : 1.0; // m/s

        List<P> positions = getPositions();
        P first = positions.get(0);
        if(first.getTime() == null)
            first.setTime(fromCalendar(Calendar.getInstance(UTC)));

        P previous = first;
        for (int i = 1; i < positions.size(); i++) {
            P next = positions.get(i);
            CompactCalendar time = next.getTime();
            if(time == null || time.equals(previous.getTime())) {
                Double distance = next.calculateDistance(previous);
                Long millis = distance != null ? (long) (distance / averageSpeed * 1000) : null;
                if(millis == null || millis < 1000)
                    millis = 1000L;
                next.setTime(CompactCalendar.fromMillisAndTimeZone(previous.getTime().getTimeInMillis() + millis, previous.getTime().getTimeZoneId()));
            }
            previous = next;
        }
    }

    public int[] getContainedPositions(NavigationPosition northEastCorner,
                                       NavigationPosition southWestCorner) {
        List<Integer> result = new ArrayList<Integer>();
        List<P> positions = getPositions();
        for (int i = 0; i < positions.size(); i++) {
            P position = positions.get(i);
            if (position.hasCoordinates() && contains(northEastCorner, southWestCorner, position))
                result.add(i);
        }
        return toArray(result);
    }

    public int[] getPositionsWithinDistanceToPredecessor(double distance) {
        List<Integer> result = new ArrayList<Integer>();
        List<P> positions = getPositions();
        if (positions.size() <= 2)
            return new int[0];
        P previous = positions.get(0);
        for (int i = 1; i < positions.size() - 1; i++) {
            P next = positions.get(i);
            if (!next.hasCoordinates() || next.calculateDistance(previous) <= distance)
                result.add(i);
            else
                previous = next;
        }
        return toArray(result);
    }

    public int[] getInsignificantPositions(double threshold) {
        int[] significantPositions = Positions.getSignificantPositions(getPositions(), threshold);
        BitSet bitset = new BitSet(getPositionCount());
        for (int significantPosition : significantPositions)
            bitset.set(significantPosition);

        int[] result = new int[getPositionCount() - significantPositions.length];
        int index = 0;
        for (int i = 0; i < getPositionCount(); i++)
            if (!bitset.get(i))
                result[index++] = i;
        return result;
    }

    public int getClosestPosition(double longitude, double latitude, double threshold) {
        int closestIndex = -1;
        double closestDistance = Double.MAX_VALUE;

        List<P> positions = getPositions();
        for (int i = 0; i < positions.size(); ++i) {
            P point = positions.get(i);
            Double distance = point.calculateDistance(longitude, latitude);
            if (distance != null && distance < closestDistance && distance < threshold) {
                closestDistance = distance;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    public P getPosition(int index) {
        return getPositions().get(index);
    }

    public int getIndex(P position) {
        return getPositions().indexOf(position);
    }

    public P getSuccessor(P position) {
        List<P> positions = getPositions();
        int index = positions.indexOf(position);
        return index != -1 && index < positions.size() - 1 ? positions.get(index + 1) : null;
    }

    public long getTime() {
        CompactCalendar minimum = null, maximum = null;
        long totalTimeMilliSeconds = 0;
        List<P> positions = getPositions();
        P previous = null;
        for (P next : positions) {
            if (previous != null) {
                Long time = previous.calculateTime(next);
                if (time != null && time > 0)
                    totalTimeMilliSeconds += time;
            }

            CompactCalendar calendar = next.getTime();
            if (calendar == null)
                continue;
            if (minimum == null || calendar.before(minimum))
                minimum = calendar;
            if (maximum == null || calendar.after(maximum))
                maximum = calendar;

            previous = next;
        }

        long maxMinusMin = minimum != null ? maximum.getTimeInMillis() - minimum.getTimeInMillis() : 0;
        return max(maxMinusMin, totalTimeMilliSeconds);
    }

    public double getDistance() {
        return getDistance(0, getPositionCount() - 1);
    }

    public double getDistance(int startIndex, int endIndex) {
        double result = 0;
        List<P> positions = getPositions();
        NavigationPosition previous = null;
        for (int i = startIndex; i <= endIndex; i++) {
            NavigationPosition next = positions.get(i);
            if (previous != null) {
                Double distance = previous.calculateDistance(next);
                if (distance != null)
                    result += distance;
            }
            previous = next;
        }
        return result;
    }

    public double[] getDistancesFromStart(int startIndex, int endIndex) {
        double[] result = new double[endIndex - startIndex + 1];
        List<P> positions = getPositions();
        int index = 0;
        double distance = 0.0;
        NavigationPosition previous = positions.size() > 0 ? positions.get(0) : null;
        while (index <= endIndex) {
            NavigationPosition next = positions.get(index);
            if (previous != null) {
                Double delta = previous.calculateDistance(next);
                if (delta != null)
                    distance += delta;
                if (index >= startIndex)
                    result[index - startIndex] = distance;
            }
            index++;
            previous = next;
        }
        return result;
    }

    public double[] getDistancesFromStart(int[] indices) {
        double[] result = new double[indices.length];
        if (indices.length > 0 && getPositionCount() > 0) {
            Arrays.sort(indices);
            int endIndex = Math.min(indices[indices.length - 1], getPositionCount() - 1);

            int index = 0;
            double distance = 0.0;
            List<P> positions = getPositions();
            NavigationPosition previous = positions.get(0);
            while (index <= endIndex) {
                NavigationPosition next = positions.get(index);
                if (previous != null) {
                    Double delta = previous.calculateDistance(next);
                    if (delta != null)
                        distance += delta;
                    int indexInIndices = Arrays.binarySearch(indices, index);
                    if (indexInIndices >= 0)
                        result[indexInIndices] = distance;
                }
                index++;
                previous = next;
            }
        }
        return result;
    }

    public double getElevationAscend(int startIndex, int endIndex) {
        double result = 0;
        List<P> positions = getPositions();
        NavigationPosition previous = null;
        for (int i = startIndex; i <= endIndex; i++) {
            NavigationPosition next = positions.get(i);
            if (previous != null) {
                Double elevation = previous.calculateElevation(next);
                if (elevation != null && elevation > 0)
                    result += elevation;
            }
            previous = next;
        }
        return result;
    }

    public double getElevationDescend(int startIndex, int endIndex) {
        double result = 0;
        List<P> positions = getPositions();
        NavigationPosition previous = null;
        for (int i = startIndex; i <= endIndex; i++) {
            NavigationPosition next = positions.get(i);
            if (previous != null) {
                Double elevation = previous.calculateElevation(next);
                if (elevation != null && elevation < 0)
                    result += Math.abs(elevation);
            }
            previous = next;
        }
        return result;
    }

    public void revert() {
        List<P> positions = getPositions();
        List<P> reverted = new ArrayList<P>();
        for (P position : positions) {
            reverted.add(0, position);
        }
        for (int i = 0; i < reverted.size(); i++) {
            positions.set(i, reverted.get(i));
        }

        String routeName = getName();
        if (!routeName.endsWith(REVERSE_ROUTE_NAME_POSTFIX))
            routeName = routeName + REVERSE_ROUTE_NAME_POSTFIX;
        else
            routeName = routeName.substring(0, routeName.length() - REVERSE_ROUTE_NAME_POSTFIX.length());
        setName(routeName);
    }

    public abstract P createPosition(Double longitude, Double latitude, Double elevation, Double speed, CompactCalendar time, String comment);

    protected abstract SimpleRoute asSimpleFormat(SimpleFormat format);
    protected abstract KmlRoute asKmlFormat(BaseKmlFormat format);

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asColumbusV900StandardFormat() {
        if (getFormat() instanceof ColumbusV900StandardFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new ColumbusV900StandardFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asColumbusV900ProfessionalFormat() {
        if (getFormat() instanceof ColumbusV900ProfessionalFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new ColumbusV900ProfessionalFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asCoPilot6Format() {
        if (getFormat() instanceof CoPilot6Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new CoPilot6Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asCoPilot7Format() {
        if (getFormat() instanceof CoPilot7Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new CoPilot7Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asCoPilot8Format() {
        if (getFormat() instanceof CoPilot8Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new CoPilot8Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asCoPilot9Format() {
        if (getFormat() instanceof CoPilot9Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new CoPilot9Format());
    }

    public abstract GarminFlightPlanRoute asGarminFlightPlanFormat();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asGlopusFormat() {
        if (getFormat() instanceof GlopusFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new GlopusFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asGoogleMapsUrlFormat() {
        if (getFormat() instanceof GoogleMapsUrlFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new GoogleMapsUrlFormat());
    }

    public abstract GoPal3Route asGoPal3RouteFormat();
    public abstract GoPal5Route asGoPal5RouteFormat();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asGoPalTrackFormat() {
        if (getFormat() instanceof GoPalTrackFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new GoPalTrackFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asGoRiderGpsFormat() {
        if (getFormat() instanceof GoRiderGpsFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new GoRiderGpsFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asGpsTunerFormat() {
        if (getFormat() instanceof GpsTunerFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new GpsTunerFormat());
    }

    public abstract GpxRoute asGpx10Format();
    public abstract GpxRoute asGpx11Format();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asGroundTrackFormat() {
        if (getFormat() instanceof GroundTrackFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new GroundTrackFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asHaicomLoggerFormat() {
        if (getFormat() instanceof HaicomLoggerFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new HaicomLoggerFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asIblue747Format() {
        if (getFormat() instanceof Iblue747Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new Iblue747Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asIgo8RouteFormat() {
        if (getFormat() instanceof Igo8RouteFormat)
            return (KmlRoute) this;
        return asKmlFormat(new Igo8RouteFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asKienzleGpsFormat() {
        if (getFormat() instanceof KienzleGpsFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new KienzleGpsFormat());
    }

    public abstract KlickTelRoute asKlickTelRouteFormat();

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKml20Format() {
        if (getFormat() instanceof Kml20Format)
            return (KmlRoute) this;
        return asKmlFormat(new Kml20Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKml21Format() {
        if (getFormat() instanceof Kml21Format)
            return (KmlRoute) this;
        return asKmlFormat(new Kml21Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKml22BetaFormat() {
        if (getFormat() instanceof Kml22BetaFormat)
            return (KmlRoute) this;
        return asKmlFormat(new Kml22BetaFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKml22Format() {
        if (getFormat() instanceof Kml22Format)
            return (KmlRoute) this;
        return asKmlFormat(new Kml22Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKmz20Format() {
        if (getFormat() instanceof Kmz20Format)
            return (KmlRoute) this;
        return asKmlFormat(new Kmz20Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKmz21Format() {
        if (getFormat() instanceof Kmz21Format)
            return (KmlRoute) this;
        return asKmlFormat(new Kmz21Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKmz22BetaFormat() {
        if (getFormat() instanceof Kmz22BetaFormat)
            return (KmlRoute) this;
        return asKmlFormat(new Kmz22BetaFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public KmlRoute asKmz22Format() {
        if (getFormat() instanceof Kmz22Format)
            return (KmlRoute) this;
        return asKmlFormat(new Kmz22Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asKompassFormat() {
        if (getFormat() instanceof KompassFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new KompassFormat());
    }

    public abstract NmeaRoute asMagellanExploristFormat();
    public abstract NmeaRoute asMagellanRouteFormat();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asMagicMaps2GoFormat() {
        if (getFormat() instanceof MagicMaps2GoFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new MagicMaps2GoFormat());
    }

    public abstract MagicMapsIktRoute asMagicMapsIktFormat();
    public abstract MagicMapsPthRoute asMagicMapsPthFormat();

    public abstract BcrRoute asMTP0607Format();
    public abstract BcrRoute asMTP0809Format();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asNavigatingPoiWarnerFormat() {
        if (getFormat() instanceof NavigatingPoiWarnerFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new NavigatingPoiWarnerFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asNavilinkFormat() {
        if (getFormat() instanceof NavilinkFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new NavilinkFormat());
    }

    public abstract NmeaRoute asNmeaFormat();

    public abstract NmnRoute asNmn4Format();
    public abstract NmnRoute asNmn5Format();
    public abstract NmnRoute asNmn6Format();
    public abstract NmnRoute asNmn6FavoritesFormat();
    public abstract NmnRoute asNmn7Format();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asNmnRouteFormat() {
        if (getFormat() instanceof NmnRouteFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new NmnRouteFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asNmnUrlFormat() {
        if (getFormat() instanceof NmnUrlFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new NmnUrlFormat());
    }

    public abstract GpxRoute asNokiaLandmarkExchangeFormat();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asOpelNaviFormat() {
        if (getFormat() instanceof OpelNaviFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new OpelNaviFormat());
    }

    public abstract OvlRoute asOvlFormat();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asQstarzQ1000Format() {
        if (getFormat() instanceof QstarzQ1000Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new QstarzQ1000Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asRoute66Format() {
        if (getFormat() instanceof Route66Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new Route66Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asSygicAsciiFormat() {
        if (getFormat() instanceof SygicAsciiFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new SygicAsciiFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asSygicUnicodeFormat() {
        if (getFormat() instanceof SygicUnicodeFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new SygicUnicodeFormat());
    }

    public abstract GpxRoute asTcx1Format();
    public abstract GpxRoute asTcx2Format();

    public abstract TomTomRoute asTomTom5RouteFormat();
    public abstract TomTomRoute asTomTom8RouteFormat();

    public abstract TourRoute asTourFormat();

    public abstract ViaMichelinRoute asViaMichelinFormat();

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asWebPageFormat() {
        if (getFormat() instanceof WebPageFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new WebPageFormat());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asWintecWbt201Tk1Format() {
        if (getFormat() instanceof WintecWbt201Tk1Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new WintecWbt201Tk1Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asWintecWbt201Tk2Format() {
        if (getFormat() instanceof WintecWbt201Tk2Format)
            return (SimpleRoute) this;
        return asSimpleFormat(new WintecWbt201Tk2Format());
    }

    @SuppressWarnings("UnusedDeclaration")
    public SimpleRoute asWintecWbt202TesFormat() {
        if (getFormat() instanceof WintecWbt202TesFormat)
            return (SimpleRoute) this;
        return asSimpleFormat(new WintecWbt202TesFormat());
    }
}
