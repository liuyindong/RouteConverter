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
package slash.navigation.mapview.mapsforge;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.model.Dimension;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.layer.*;
import org.mapsforge.map.layer.cache.FileSystemTileCache;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.cache.TwoLevelTileCache;
import org.mapsforge.map.layer.download.TileDownloadLayer;
import org.mapsforge.map.layer.download.tilesource.TileSource;
import org.mapsforge.map.layer.hills.DiffuseLightShadingAlgorithm;
import org.mapsforge.map.layer.hills.HillsRenderConfig;
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.model.IMapViewPosition;
import org.mapsforge.map.model.MapViewDimension;
import org.mapsforge.map.model.common.Observer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.scalebar.DefaultMapScaleBar;
import org.mapsforge.map.scalebar.ImperialUnitAdapter;
import org.mapsforge.map.scalebar.MetricUnitAdapter;
import org.mapsforge.map.scalebar.NauticalUnitAdapter;
import org.mapsforge.map.util.MapViewProjection;
import slash.navigation.base.BaseRoute;
import slash.navigation.base.RouteCharacteristics;
import slash.navigation.common.*;
import slash.navigation.converter.gui.models.*;
import slash.navigation.elevation.ElevationService;
import slash.navigation.gui.Application;
import slash.navigation.gui.actions.ActionManager;
import slash.navigation.gui.actions.FrameAction;
import slash.navigation.gui.models.BooleanModel;
import slash.navigation.maps.mapsforge.LocalMap;
import slash.navigation.maps.mapsforge.MapsforgeMapManager;
import slash.navigation.maps.mapsforge.models.TileServerMapSource;
import slash.navigation.maps.tileserver.TileServer;
import slash.navigation.mapview.BaseMapView;
import slash.navigation.mapview.MapViewCallback;
import slash.navigation.mapview.mapsforge.helpers.MapViewCoordinateDisplayer;
import slash.navigation.mapview.mapsforge.helpers.MapViewMoverAndZoomer;
import slash.navigation.mapview.mapsforge.helpers.MapViewPopupMenu;
import slash.navigation.mapview.mapsforge.helpers.MapViewResizer;
import slash.navigation.mapview.mapsforge.lines.Line;
import slash.navigation.mapview.mapsforge.lines.Polyline;
import slash.navigation.mapview.mapsforge.overlays.DraggableMarker;
import slash.navigation.mapview.mapsforge.renderer.RouteRenderer;
import slash.navigation.mapview.mapsforge.updater.*;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.KeyEvent.*;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW;
import static javax.swing.KeyStroke.getKeyStroke;
import static javax.swing.event.TableModelEvent.*;
import static org.mapsforge.core.graphics.Color.BLUE;
import static org.mapsforge.core.util.LatLongUtils.zoomForBounds;
import static org.mapsforge.core.util.MercatorProjection.calculateGroundResolution;
import static org.mapsforge.core.util.MercatorProjection.getMapSize;
import static org.mapsforge.map.scalebar.DefaultMapScaleBar.ScaleBarMode.SINGLE;
import static slash.common.helpers.ThreadHelper.createSingleThreadExecutor;
import static slash.common.helpers.ThreadHelper.invokeInAwtEventQueue;
import static slash.common.io.Directories.getTemporaryDirectory;
import static slash.common.io.Transfer.encodeUri;
import static slash.navigation.base.RouteCharacteristics.Route;
import static slash.navigation.base.RouteCharacteristics.Waypoints;
import static slash.navigation.common.TransformUtil.delta;
import static slash.navigation.common.TransformUtil.isPositionInChina;
import static slash.navigation.converter.gui.models.FixMapMode.Automatic;
import static slash.navigation.converter.gui.models.FixMapMode.Yes;
import static slash.navigation.converter.gui.models.PositionColumns.*;
import static slash.navigation.gui.events.IgnoreEvent.isIgnoreEvent;
import static slash.navigation.gui.helpers.JMenuHelper.createItem;
import static slash.navigation.gui.helpers.JTableHelper.isFirstToLastRow;
import static slash.navigation.maps.mapsforge.helpers.MapUtil.toBoundingBox;
import static slash.navigation.mapview.MapViewConstants.TRACK_LINE_WIDTH_PREFERENCE;
import static slash.navigation.mapview.mapsforge.AwtGraphicMapView.GRAPHIC_FACTORY;
import static slash.navigation.mapview.mapsforge.helpers.ColorHelper.asRGBA;
import static slash.navigation.mapview.mapsforge.helpers.WithLayerHelper.*;
import static slash.navigation.mapview.mapsforge.models.LocalNames.MAP;

/**
 * Implementation for a component that displays the positions of a position list on a map
 * using the rewrite branch of the mapsforge project.
 *
 * @author Christian Pesch
 */

public class MapsforgeMapView extends BaseMapView {
    private static final Preferences preferences = Preferences.userNodeForPackage(MapsforgeMapView.class);
    private static final Logger log = Logger.getLogger(MapsforgeMapView.class.getName());

    private static final String READ_BUFFER_SIZE_PREFERENCE = "readBufferSize";
    private static final String FIRST_LEVEL_TILE_CACHE_SIZE_PREFERENCE = "firstLevelTileCacheSize";
    private static final String SECOND_LEVEL_TILE_CACHE_SIZE_PREFERENCE = "secondLevelTileCacheSize";
    private static final String DEVICE_SCALE_FACTOR = "mapScaleFactor";
    private static final int SCROLL_DIFF_IN_PIXEL = 100;
    private static final int MINIMUM_VISIBLE_BORDER_IN_PIXEL = 20;
    private static final int SELECTION_CIRCLE_IN_PIXEL = 15;
    private static final byte MINIMUM_ZOOM_LEVEL = 2;
    private static final byte MAXIMUM_ZOOM_LEVEL = 22;

    private PositionsModel positionsModel;
    private PositionsSelectionModel positionsSelectionModel;
    private CharacteristicsModel characteristicsModel;
    private BooleanModel showAllPositionsAfterLoading;
    private BooleanModel recenterAfterZooming;
    private BooleanModel showCoordinates;
    private ColorModel routeColorModel;
    private ColorModel trackColorModel;
    private UnitSystemModel unitSystemModel;
    private MapViewCallbackOpenSource mapViewCallback;

    private PositionsModelListener positionsModelListener = new PositionsModelListener();
    private CharacteristicsModelListener characteristicsModelListener = new CharacteristicsModelListener();
    private ShowCoordinatesListener showCoordinatesListener = new ShowCoordinatesListener();
    private RepaintPositionListListener repaintPositionListListener = new RepaintPositionListListener();
    private UnitSystemListener unitSystemListener = new UnitSystemListener();
    private DisplayedMapListener displayedMapListener = new DisplayedMapListener();
    private AppliedThemeListener appliedThemeListener = new AppliedThemeListener();
    private AppliedOverlayListener appliedOverlayListener = new AppliedOverlayListener();
    private ShadedHillsListener shadedHillsListener = new ShadedHillsListener();

    private MapSelector mapSelector;
    private AwtGraphicMapView mapView;
    private MapViewMoverAndZoomer mapViewMoverAndZoomer;
    private MapViewCoordinateDisplayer mapViewCoordinateDisplayer = new MapViewCoordinateDisplayer();
    private static Bitmap markerIcon, waypointIcon;
    private GroupLayer overlaysLayer = new GroupLayer();
    private TileRendererLayer backgroundLayer;
    private HillsRenderConfig hillsRenderConfig = new HillsRenderConfig(null);
    private SelectionUpdater selectionUpdater;
    private EventMapUpdater routeUpdater, trackUpdater, waypointUpdater;
    private RouteRenderer routeRenderer;
    private UpdateDecoupler updateDecoupler;

    // initialization

    public void initialize(final PositionsModel positionsModel,
                           PositionsSelectionModel positionsSelectionModel,
                           CharacteristicsModel characteristicsModel,
                           MapViewCallback mapViewCallback,
                           BooleanModel showAllPositionsAfterLoading,
                           BooleanModel recenterAfterZooming,
                           BooleanModel showCoordinates,
                           BooleanModel showWaypointDescription,       /* ignored */
                           ColorModel aRouteColorModel,
                           final ColorModel aTrackColorModel,
                           UnitSystemModel unitSystemModel             /* ignored */) {
        this.mapViewCallback = (MapViewCallbackOpenSource) mapViewCallback;
        this.positionsModel = positionsModel;
        this.positionsSelectionModel = positionsSelectionModel;
        this.characteristicsModel = characteristicsModel;
        this.showAllPositionsAfterLoading = showAllPositionsAfterLoading;
        this.recenterAfterZooming = recenterAfterZooming;
        this.showCoordinates = showCoordinates;
        this.routeColorModel = aRouteColorModel;
        this.trackColorModel = aTrackColorModel;
        this.unitSystemModel = unitSystemModel;

        this.selectionUpdater = new SelectionUpdater(positionsModel, new SelectionOperation() {
            private Marker createMarker(PositionWithLayer positionWithLayer, LatLong latLong) {
                return new DraggableMarker(MapsforgeMapView.this, positionWithLayer, latLong, markerIcon, 0, -27);
            }

            public void add(List<PositionWithLayer> positionWithLayers) {
                LatLong center = null;
                List<PositionWithLayer> withLayers = new ArrayList<>();
                for (final PositionWithLayer positionWithLayer : positionWithLayers) {
                    if (!positionWithLayer.hasCoordinates())
                        continue;

                    LatLong latLong = asLatLong(positionWithLayer.getPosition());
                    Marker marker = createMarker(positionWithLayer, latLong);
                    positionWithLayer.setLayer(marker);
                    withLayers.add(positionWithLayer);
                    center = latLong;
                }
                addLayers(withLayers);
                if (center != null)
                    setCenter(center, false);
            }

            public void remove(List<PositionWithLayer> positionWithLayers) {
                removeObjectWithLayers(positionWithLayers);
            }
        });

        this.routeUpdater = new TrackUpdater(positionsModel, new TrackOperation() {
            private List<PairWithLayer> pairs = new ArrayList<>();

            public void add(List<PairWithLayer> pairWithLayers) {
                internalAdd(pairWithLayers);
            }

            public void update(List<PairWithLayer> pairWithLayers) {
                internalRemove(pairWithLayers);
                internalAdd(pairWithLayers);
                selectionUpdater.updatedPositions(toPositions2(pairWithLayers));
            }

            public void remove(List<PairWithLayer> pairWithLayers) {
                internalRemove(pairWithLayers);
                fireDistanceAndTime();
                selectionUpdater.removedPositions(toPositions2(pairWithLayers));
            }

            private void internalAdd(List<PairWithLayer> pairWithLayers) {
                pairs.addAll(pairWithLayers);
                routeRenderer.renderRoute(pairWithLayers, this::fireDistanceAndTime);
            }

            private void internalRemove(List<PairWithLayer> pairWithLayers) {
                // speed optimization for large numbers of pairWithLayers
                if (pairs.size() == pairWithLayers.size())
                    pairs.clear();
                else
                    pairs.removeAll(pairWithLayers);
                for (PairWithLayer pairWithLayer : pairWithLayers)
                    pairWithLayer.setDistanceAndTime(null);

                List<Layer> remove = toLayers(pairWithLayers);
                removeLayers(remove);
            }

            private void fireDistanceAndTime() {
                Map<Integer, DistanceAndTime> indexToDistanceAndTime = new HashMap<>(pairs.size());
                for (int i = 0; i < pairs.size(); i++) {
                    PairWithLayer pairWithLayer = pairs.get(i);
                    indexToDistanceAndTime.put(i + 1, pairWithLayer.getDistanceAndTime());
                }
                fireCalculatedDistances(DistanceAndTimeAggregator.add(indexToDistanceAndTime));
            }
        });

        this.trackUpdater = new TrackUpdater(positionsModel, new TrackOperation() {
            public void add(List<PairWithLayer> pairWithLayers) {
                internalAdd(pairWithLayers);
            }

            public void update(List<PairWithLayer> pairWithLayers) {
                List<Layer> remove = toLayers(pairWithLayers);
                removeLayers(remove);
                internalAdd(pairWithLayers);
                selectionUpdater.updatedPositions(toPositions2(pairWithLayers));
            }

            public void remove(List<PairWithLayer> pairWithLayers) {
                removeObjectWithLayers(pairWithLayers);
                selectionUpdater.removedPositions(toPositions2(pairWithLayers));
            }

            private void internalAdd(List<PairWithLayer> pairWithLayers) {
                Paint paint = GRAPHIC_FACTORY.createPaint();
                paint.setColor(asRGBA(trackColorModel));
                paint.setStrokeWidth(preferences.getInt(TRACK_LINE_WIDTH_PREFERENCE, 2));
                int tileSize = getTileSize();

                List<PairWithLayer> withLayers = new ArrayList<>();
                for (PairWithLayer pair : pairWithLayers) {
                    if (!pair.hasCoordinates())
                        continue;

                    Line line = new Line(asLatLong(pair.getFirst()), asLatLong(pair.getSecond()), paint, tileSize);
                    pair.setLayer(line);
                    withLayers.add(pair);
                }
                addLayers(withLayers);
            }
        });

        this.waypointUpdater = new WaypointUpdater(positionsModel, new WaypointOperation() {
            private Marker createMarker(PositionWithLayer positionWithLayer) {
                return new Marker(asLatLong(positionWithLayer.getPosition()), waypointIcon, 1, 0);
            }

            public void add(List<PositionWithLayer> positionWithLayers) {
                List<PositionWithLayer> withLayers = new ArrayList<>();
                for (PositionWithLayer positionWithLayer : positionWithLayers) {
                    if (!positionWithLayer.hasCoordinates())
                        return;

                    Marker marker = createMarker(positionWithLayer);
                    positionWithLayer.setLayer(marker);
                    withLayers.add(positionWithLayer);
                }
                addLayers(withLayers);
            }

            public void update(final List<PositionWithLayer> positionWithLayers) {
                List<Layer> remove = toLayers(positionWithLayers);
                removeLayers(remove);
                add(positionWithLayers);
                selectionUpdater.updatedPositions(toPositions(positionWithLayers));
            }

            public void remove(List<PositionWithLayer> positionWithLayers) {
                List<NavigationPosition> removed = toPositions(positionWithLayers);
                removeObjectWithLayers(positionWithLayers);
                selectionUpdater.removedPositions(removed);
            }
        });

        this.updateDecoupler = new UpdateDecoupler();

        positionsModel.addTableModelListener(positionsModelListener);
        characteristicsModel.addListDataListener(characteristicsModelListener);
        showCoordinates.addChangeListener(showCoordinatesListener);
        getFixMapModeModel().addChangeListener(repaintPositionListListener);
        routeColorModel.addChangeListener(repaintPositionListListener);
        trackColorModel.addChangeListener(repaintPositionListListener);
        unitSystemModel.addChangeListener(unitSystemListener);
        getShowShadedHills().addChangeListener(shadedHillsListener);

        initializeActions();
        initializeMapView();
        routeRenderer = new RouteRenderer(this, this.mapViewCallback, routeColorModel, GRAPHIC_FACTORY);
    }

    private static boolean initializedActions = false;

    private synchronized void initializeActions() {
        if (initializedActions)
            return;

        ActionManager actionManager = Application.getInstance().getContext().getActionManager();
        actionManager.register("select-position", new SelectPositionAction());
        actionManager.register("extend-selection", new ExtendSelectionAction());
        actionManager.register("add-position", new AddPositionAction());
        actionManager.register("delete-position-from-map", new DeletePositionAction());
        actionManager.registerLocal("delete", MAP, "delete-position-from-map");
        actionManager.register("center-here", new CenterAction());
        actionManager.register("zoom-in", new ZoomAction(+1));
        actionManager.register("zoom-out", new ZoomAction(-1));

        initializedActions = true;
    }

    private MapsforgeMapManager getMapManager() {
        return mapViewCallback.getMapsforgeMapManager();
    }

    private LayerManager getLayerManager() {
        return mapView.getLayerManager();
    }

    private void initializeMapView() {
        mapView = createMapView();
        handleUnitSystem();

        try {
            markerIcon = GRAPHIC_FACTORY.createTileBitmap(MapsforgeMapView.class.getResourceAsStream("marker.png"), -1, false);
            waypointIcon = GRAPHIC_FACTORY.createTileBitmap(MapsforgeMapView.class.getResourceAsStream("waypoint.png"), -1, false);
        } catch (IOException e) {
            log.severe("Cannot create marker and waypoint icon: " + e);
        }

        mapSelector = new MapSelector(getMapManager(), mapView);
        mapViewMoverAndZoomer = new MapViewMoverAndZoomer(mapView, getLayerManager());
        mapViewCoordinateDisplayer.initialize(mapView, mapViewCallback);
        new MapViewPopupMenu(mapView, createPopupMenu());

        final ActionManager actionManager = Application.getInstance().getContext().getActionManager();
        mapSelector.getMapViewPanel().registerKeyboardAction(new FrameAction() {
            public void run() {
                actionManager.run("zoom-in");
            }
        }, getKeyStroke(VK_PLUS, CTRL_DOWN_MASK), WHEN_IN_FOCUSED_WINDOW);
        mapSelector.getMapViewPanel().registerKeyboardAction(new FrameAction() {
            public void run() {
                actionManager.run("zoom-out");
            }
        }, getKeyStroke(VK_MINUS, CTRL_DOWN_MASK), WHEN_IN_FOCUSED_WINDOW);
        mapSelector.getMapViewPanel().registerKeyboardAction(new FrameAction() {
            public void run() {
                mapViewMoverAndZoomer.animateCenter(SCROLL_DIFF_IN_PIXEL, 0);
            }
        }, getKeyStroke(VK_LEFT, CTRL_DOWN_MASK), WHEN_IN_FOCUSED_WINDOW);
        mapSelector.getMapViewPanel().registerKeyboardAction(new FrameAction() {
            public void run() {
                mapViewMoverAndZoomer.animateCenter(-SCROLL_DIFF_IN_PIXEL, 0);
            }
        }, getKeyStroke(VK_RIGHT, CTRL_DOWN_MASK), WHEN_IN_FOCUSED_WINDOW);
        mapSelector.getMapViewPanel().registerKeyboardAction(new FrameAction() {
            public void run() {
                mapViewMoverAndZoomer.animateCenter(0, SCROLL_DIFF_IN_PIXEL);
            }
        }, getKeyStroke(VK_UP, CTRL_DOWN_MASK), WHEN_IN_FOCUSED_WINDOW);
        mapSelector.getMapViewPanel().registerKeyboardAction(new FrameAction() {
            public void run() {
                mapViewMoverAndZoomer.animateCenter(0, -SCROLL_DIFF_IN_PIXEL);
            }
        }, getKeyStroke(VK_DOWN, CTRL_DOWN_MASK), WHEN_IN_FOCUSED_WINDOW);

        final IMapViewPosition mapViewPosition = mapView.getModel().mapViewPosition;
        mapViewPosition.setZoomLevelMin(MINIMUM_ZOOM_LEVEL);
        mapViewPosition.setZoomLevelMax(MAXIMUM_ZOOM_LEVEL);

        double longitude = preferences.getDouble(CENTER_LONGITUDE_PREFERENCE, -25.0);
        double latitude = preferences.getDouble(CENTER_LATITUDE_PREFERENCE, 35.0);
        byte zoom = (byte) preferences.getInt(CENTER_ZOOM_PREFERENCE, MINIMUM_ZOOM_LEVEL);
        mapViewPosition.setMapPosition(new MapPosition(new LatLong(latitude, longitude), zoom));

        mapView.getModel().mapViewDimension.addObserver(new Observer() {
            private boolean initialized;

            public void onChange() {
                if (!initialized) {
                    handleShadedHills();
                    handleMapAndThemeUpdate(true, true);
                    handleOverlayInsertion(0, mapViewCallback.getTileServerMapManager().getAppliedOverlaysModel().getRowCount() - 1);
                    initialized = true;
                }
            }
        });

        getMapManager().getDisplayedMapModel().addChangeListener(displayedMapListener);
        getMapManager().getAppliedThemeModel().addChangeListener(appliedThemeListener);
        mapViewCallback.getTileServerMapManager().getAppliedOverlaysModel().addTableModelListener(appliedOverlayListener);
    }

    public void setBackgroundMap(File backgroundMap) {
        backgroundLayer = createTileRendererLayer(backgroundMap, backgroundMap.getName());
        handleBackground();
    }

    public void updateMapAndThemesAfterDirectoryScanning() {
        if (mapView != null)
            handleMapAndThemeUpdate(false, false);
    }

    protected float getDeviceScaleFactor() {
        return preferences.getInt(DEVICE_SCALE_FACTOR, Toolkit.getDefaultToolkit().getScreenResolution()) / 96.0f;
    }

    private AwtGraphicMapView createMapView() {
        // Multithreaded map rendering
        Parameters.NUMBER_OF_THREADS = Runtime.getRuntime().availableProcessors();
        // Maximum read buffer size
        Parameters.MAXIMUM_BUFFER_SIZE = preferences.getInt(READ_BUFFER_SIZE_PREFERENCE, 16000000);
        // No square frame buffer since the device orientation hardly changes
        Parameters.SQUARE_FRAME_BUFFER = false;

        float deviceScaleFactor = getDeviceScaleFactor();
        DisplayModel.setDeviceScaleFactor(deviceScaleFactor);
        log.info(format("Map is scaled with factor %f, screen resolution is %d dpi", deviceScaleFactor, Toolkit.getDefaultToolkit().getScreenResolution()));

        AwtGraphicMapView mapView = new AwtGraphicMapView();
        new MapViewResizer(mapView, mapView.getModel().mapViewDimension);
        mapView.getMapScaleBar().setVisible(true);
        ((DefaultMapScaleBar) mapView.getMapScaleBar()).setScaleBarMode(SINGLE);
        return mapView;
    }

    private void handleUnitSystem() {
        UnitSystem unitSystem = unitSystemModel.getUnitSystem();
        switch (unitSystem) {
            case Metric:
                mapView.getMapScaleBar().setDistanceUnitAdapter(MetricUnitAdapter.INSTANCE);
                break;
            case Statute:
                mapView.getMapScaleBar().setDistanceUnitAdapter(ImperialUnitAdapter.INSTANCE);
                break;
            case Nautic:
                mapView.getMapScaleBar().setDistanceUnitAdapter(NauticalUnitAdapter.INSTANCE);
                break;
            default:
                throw new IllegalArgumentException("Unknown UnitSystem " + unitSystem);
        }
    }

    private JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(createItem("select-position"));
        menu.add(createItem("add-position"));    // TODO should be "new-position"
        menu.add(createItem("delete-position-from-map"));
        menu.addSeparator();
        menu.add(createItem("center-here"));
        menu.add(createItem("zoom-in"));
        menu.add(createItem("zoom-out"));
        return menu;
    }

    private TileRendererLayer createTileRendererLayer(File mapFile, String cacheId) {
        TileRendererLayer tileRendererLayer = new TileRendererLayer(createTileCache(cacheId), new MapFile(mapFile),
                mapView.getModel().mapViewPosition, true, true, true,
                GRAPHIC_FACTORY, hillsRenderConfig);
        tileRendererLayer.setXmlRenderTheme(getMapManager().getAppliedThemeModel().getItem().getXmlRenderTheme());
        return tileRendererLayer;
    }

    private TileDownloadLayer createTileDownloadLayer(TileSource tileSource, String cacheId) {
        return new TileDownloadLayer(createTileCache(cacheId), mapView.getModel().mapViewPosition, tileSource, GRAPHIC_FACTORY);
    }

    private TileCache createTileCache(String cacheId) {
        TileCache firstLevelTileCache = new InMemoryTileCache(preferences.getInt(FIRST_LEVEL_TILE_CACHE_SIZE_PREFERENCE, 256));
        File cacheDirectory = new File(getTemporaryDirectory(), encodeUri(cacheId));
        TileCache secondLevelTileCache = new FileSystemTileCache(preferences.getInt(SECOND_LEVEL_TILE_CACHE_SIZE_PREFERENCE, 2048), cacheDirectory, GRAPHIC_FACTORY);
        return new TwoLevelTileCache(firstLevelTileCache, secondLevelTileCache);
    }

    private java.util.Map<LocalMap, Layer> mapsToLayers = new HashMap<>();

    private void handleMapAndThemeUpdate(boolean centerAndZoom, boolean alwaysRecenter) {
        Layers layers = getLayerManager().getLayers();

        // add new map with a theme
        LocalMap map = getMapManager().getDisplayedMapModel().getItem();
        Layer layer;
        try {
            layer = map.isVector() ? createTileRendererLayer(map.getFile(), map.getUrl()) : createTileDownloadLayer(map.getTileSource(), map.getUrl());
        } catch (Exception e) {
            mapViewCallback.showMapException(map != null ? map.getDescription() : "<no map>", e);
            return;
        }

        // remove old map
        for (Map.Entry<LocalMap, Layer> entry : mapsToLayers.entrySet()) {
            Layer remove = entry.getValue();
            layers.remove(remove);
            remove.onDestroy();

            if (remove instanceof TileLayer)
                ((TileLayer) remove).getTileCache().destroy();
        }
        mapsToLayers.clear();

        // add map as the first to be behind all additional layers
        layers.add(0, layer);
        mapsToLayers.put(map, layer);

        handleBackground();
        handleOverlays();

        // then start download layer threads
        if (layer instanceof TileDownloadLayer)
           ((TileDownloadLayer) layer).start();

        // center and zoom: if map is initialized, doesn't contain route or there is no route
        BoundingBox mapBoundingBox = getMapBoundingBox();
        BoundingBox routeBoundingBox = getRouteBoundingBox();
        if (centerAndZoom &&
                ((mapBoundingBox != null && routeBoundingBox != null && !mapBoundingBox.contains(routeBoundingBox)) ||
                        routeBoundingBox == null)) {
            boolean alwaysZoom = mapBoundingBox == null || !mapBoundingBox.contains(getCenter());
            centerAndZoom(mapBoundingBox, routeBoundingBox, alwaysZoom, alwaysRecenter);
        }
        limitZoomLevel();
        log.info("Using map " + mapsToLayers.keySet() + " and theme " + getMapManager().getAppliedThemeModel().getItem() + " with zoom " + getZoom());
    }

    private void handleOverlays() {
        Layers layers = getLayerManager().getLayers();
        layers.remove(overlaysLayer);
        layers.add(overlaysLayer);
    }

    private void handleOverlayInsertion(int firstRow, int lastRow) {
        for (int i = firstRow; i < lastRow + 1; i++) {
            TileServer tileServer = mapViewCallback.getTileServerMapManager().getAppliedOverlaysModel().getItem(i);
            TileServerMapSource mapSource = new TileServerMapSource(tileServer);
            mapSource.setAlpha(true);
            TileDownloadLayer overlay = new TileDownloadLayer(createTileCache(tileServer.getId()), mapView.getModel().mapViewPosition, mapSource, GRAPHIC_FACTORY);
            overlaysLayer.layers.add(overlay);
            overlay.setDisplayModel(mapView.getModel().displayModel);
            overlay.start();
            getLayerManager().redrawLayers();
        }
    }

    private void handleOverlayDeletion(int firstRow, int lastRow) {
        for (int i = lastRow; i >= firstRow; i--) {
            if (i >= overlaysLayer.layers.size())
                continue;

            Layer layer = overlaysLayer.layers.get(i);
            TileDownloadLayer overlay = (TileDownloadLayer) layer;
            overlaysLayer.layers.remove(overlay);
            overlaysLayer.requestRedraw();
            overlay.onDestroy();
        }
    }

    private void handleBackground() {
        if (backgroundLayer == null)
            return;

        Layers layers = getLayerManager().getLayers();
        layers.remove(backgroundLayer);

        LocalMap map = getMapManager().getDisplayedMapModel().getItem();
        if (map.isVector())
            layers.add(0, backgroundLayer);
    }

    private void handleShadedHills() {
        hillsRenderConfig.setTileSource(null);

        if (getShowShadedHills().getBoolean()) {
            ElevationService elevationService = mapViewCallback.getElevationService();
            if (elevationService.isDownload()) {
                File directory = elevationService.getDirectory();
                if (directory != null && directory.exists()) {
                    MemoryCachingHgtReaderTileSource tileSource = new MemoryCachingHgtReaderTileSource(directory, new DiffuseLightShadingAlgorithm(), GRAPHIC_FACTORY);
                    tileSource.setEnableInterpolationOverlap(true);
                    hillsRenderConfig.setTileSource(tileSource);
                    hillsRenderConfig.indexOnThread();
                }
            }
        }
    }

    private EventMapUpdater getEventMapUpdaterFor(RouteCharacteristics characteristics) {
        switch (characteristics) {
            case Route:
                return routeUpdater;
            case Track:
                return trackUpdater;
            case Waypoints:
                return waypointUpdater;
            default:
                throw new IllegalArgumentException("RouteCharacteristics " + characteristics + " is not supported");
        }
    }

    public boolean isInitialized() {
        return true;
    }

    public boolean isDownload() {
        return true;
    }

    public Throwable getInitializationCause() {
        return null;
    }

    public void dispose() {
        getMapManager().getDisplayedMapModel().removeChangeListener(displayedMapListener);
        getMapManager().getAppliedThemeModel().removeChangeListener(appliedThemeListener);
        mapViewCallback.getTileServerMapManager().getAppliedOverlaysModel().removeTableModelListener(appliedOverlayListener);

        positionsModel.removeTableModelListener(positionsModelListener);
        characteristicsModel.removeListDataListener(characteristicsModelListener);
        routeColorModel.removeChangeListener(repaintPositionListListener);
        trackColorModel.removeChangeListener(repaintPositionListListener);
        unitSystemModel.removeChangeListener(unitSystemListener);
        getFixMapModeModel().addChangeListener(repaintPositionListListener);
        getShowShadedHills().removeChangeListener(shadedHillsListener);

        long start = currentTimeMillis();
        if (routeRenderer != null)
            routeRenderer.dispose();

        updateDecoupler.dispose();

        long end = currentTimeMillis();
        log.info("RouteRenderer stopped after " + (end - start) + " ms");

        NavigationPosition center = getCenter();
        preferences.putDouble(CENTER_LONGITUDE_PREFERENCE, center.getLongitude());
        preferences.putDouble(CENTER_LATITUDE_PREFERENCE, center.getLatitude());
        int zoom = getZoom();
        preferences.putInt(CENTER_ZOOM_PREFERENCE, zoom);

        mapView.destroyAll();
    }

    public Component getComponent() {
        return mapSelector.getComponent();
    }

    public void resize() {
        // intentionally left empty
    }

    @SuppressWarnings("unchecked")
    public void showAllPositions() {
        List<NavigationPosition> positions = positionsModel.getRoute().getPositions();
        if (positions.size() > 0) {
            BoundingBox both = new BoundingBox(positions);
            zoomToBounds(both);
            setCenter(both.getCenter(), true);
        }
    }

    private Polyline mapBorder, routeBorder;

    public void showMapBorder(BoundingBox mapBoundingBox) {
        if (mapBorder != null) {
            removeLayer(mapBorder);
            mapBorder = null;
        }
        if (routeBorder != null) {
            removeLayer(routeBorder);
            routeBorder = null;
        }

        if (mapBoundingBox != null) {
            mapBorder = drawBorder(mapBoundingBox);

            BoundingBox routeBoundingBox = getRouteBoundingBox();
            if (routeBoundingBox != null)
                routeBorder = drawBorder(routeBoundingBox);

            centerAndZoom(mapBoundingBox, routeBoundingBox, true, true);
        }
    }

    private Polyline drawBorder(BoundingBox boundingBox) {
        Paint paint = GRAPHIC_FACTORY.createPaint();
        paint.setColor(BLUE);
        paint.setStrokeWidth(3);
        paint.setDashPathEffect(new float[]{3, 12});
        Polyline polyline = new Polyline(asLatLong(boundingBox), paint, getTileSize());
        addLayer(polyline);
        return polyline;
    }

    public void addLayer(final Layer layer) {
        invokeInAwtEventQueue(new Runnable() {
            public void run() {
                getLayerManager().getLayers().add(layer);
            }
        });
    }

    public void addLayers(final List<? extends ObjectWithLayer> withLayers) {
        invokeInAwtEventQueue(new Runnable() {
            public void run() {
                List<Layer> layers = new ArrayList<>();

                for (ObjectWithLayer withLayer : withLayers) {
                    Layer layer = withLayer.getLayer();
                    if (layer != null) {
                        layers.add(layer);
                    } else
                        log.warning("Could not find layer to add for " + withLayer);
                }

                if (!getLayerManager().getLayers().addAll(layers, true))
                    log.warning("Cannot add layers " + layers);
            }
        });
    }

    public void removeLayer(Layer layer) {
        removeLayers(singletonList(layer));
    }

    private void removeLayers(final List<Layer> layers) {
        invokeInAwtEventQueue(new Runnable() {
            public void run() {
                if (!getLayerManager().getLayers().removeAll(layers, true))
                    log.warning("Cannot remove layers " + layers);
            }
        });
    }

    private void removeObjectWithLayers(final List<? extends ObjectWithLayer> withLayers) {
        invokeInAwtEventQueue(new Runnable() {
            public void run() {
                List<Layer> layers = new ArrayList<>();

                for (ObjectWithLayer withLayer : withLayers) {
                    Layer layer = withLayer.getLayer();
                    if (layer != null) {
                        layers.add(layer);
                    } else
                        log.warning("Could not find layer to remove for " + withLayer);
                    withLayer.setLayer(null);
                }

                if (!getLayerManager().getLayers().removeAll(layers, true))
                    log.warning("Cannot remove layers " + layers);
            }
        });
    }

    private BoundingBox getMapBoundingBox() {
        Collection<Layer> values = mapsToLayers.values();
        if (!values.isEmpty()) {
            Layer layer = values.iterator().next();
            if (layer instanceof TileRendererLayer) {
                TileRendererLayer tileRendererLayer = (TileRendererLayer) layer;
                return toBoundingBox(tileRendererLayer.getMapDataStore().boundingBox());
            }
        }
        return null;
    }

    public int getTileSize() {
        return mapView.getModel().displayModel.getTileSize();
    }

    @SuppressWarnings("unchecked")
    private BoundingBox getRouteBoundingBox() {
        BaseRoute route = positionsModel.getRoute();
        return route != null && route.getPositions().size() > 0 ? new BoundingBox(route.getPositions()) : null;
    }

    private FixMapModeModel getFixMapModeModel() {
        return mapViewCallback.getFixMapModeModel();
    }

    private BooleanModel getShowShadedHills() {
        return mapViewCallback.getShowShadedHills();
    }

    private boolean isGoogleMap(LocalMap map) {
        return map.getCopyrightText().contains("Google");
    }

    private boolean isFixMap(Double longitude, Double latitude) {
        FixMapMode fixMapMode = getFixMapModeModel().getFixMapMode();
        LocalMap map = getMapManager().getDisplayedMapModel().getItem();
        return fixMapMode.equals(Yes) || fixMapMode.equals(Automatic) && isGoogleMap(map) && isPositionInChina(longitude, latitude);
    }

    public LatLong asLatLong(NavigationPosition position) {
        if (position == null)
            return null;

        double longitude = position.getLongitude() != null ? position.getLongitude() : 0.0;
        double latitude = position.getLatitude() != null ? position.getLatitude() : 0.0;
        if (isFixMap(longitude, latitude)) {
            double[] delta = delta(latitude, longitude);
            longitude += delta[1];
            latitude += delta[0];
        }
        return new LatLong(latitude, longitude);
    }

    public List<LatLong> asLatLong(List<NavigationPosition> positions) {
        List<LatLong> result = new ArrayList<>();
        for (NavigationPosition position : positions) {
            LatLong latLong = asLatLong(position);
            if (latLong != null)
                result.add(latLong);
        }
        return result;
    }

    private List<LatLong> asLatLong(BoundingBox boundingBox) {
        return asLatLong(asList(
                boundingBox.getNorthEast(),
                boundingBox.getSouthEast(),
                boundingBox.getSouthWest(),
                boundingBox.getNorthWest(),
                boundingBox.getNorthEast()
        ));
    }

    org.mapsforge.core.model.BoundingBox asBoundingBox(BoundingBox boundingBox) {
        return new org.mapsforge.core.model.BoundingBox(
                boundingBox.getSouthWest().getLatitude(),
                boundingBox.getSouthWest().getLongitude(),
                boundingBox.getNorthEast().getLatitude(),
                boundingBox.getNorthEast().getLongitude()
        );
    }

    private NavigationPosition asNavigationPosition(LatLong latLong) {
        return new SimpleNavigationPosition(latLong.longitude, latLong.latitude);
    }

    private void centerAndZoom(BoundingBox mapBoundingBox, BoundingBox routeBoundingBox,
                               boolean alwaysZoom, boolean alwaysRecenter) {
        List<NavigationPosition> positions = new ArrayList<>();

        // if there is a route and we center and zoom, then use the route bounding box
        if (routeBoundingBox != null) {
            positions.add(routeBoundingBox.getNorthEast());
            positions.add(routeBoundingBox.getSouthWest());
        }

        // if the map is limited
        if (mapBoundingBox != null) {

            // if there is a route
            if (routeBoundingBox != null) {
                positions.add(routeBoundingBox.getNorthEast());
                positions.add(routeBoundingBox.getSouthWest());
                // if the map is limited and doesn't cover the route
                if (!mapBoundingBox.contains(routeBoundingBox)) {
                    positions.add(mapBoundingBox.getNorthEast());
                    positions.add(mapBoundingBox.getSouthWest());
                }

                // if there just a map
            } else {
                positions.add(mapBoundingBox.getNorthEast());
                positions.add(mapBoundingBox.getSouthWest());
            }
        }

        if (positions.size() > 0) {
            BoundingBox both = new BoundingBox(positions);
            if (alwaysZoom)
                zoomToBounds(both);
            setCenter(both.getCenter(), alwaysRecenter);
        }
    }

    private void limitZoomLevel() {
        LocalMap map = mapsToLayers.keySet().iterator().next();

        byte zoomLevelMin = map.isVector() ? MINIMUM_ZOOM_LEVEL : map.getTileSource().getZoomLevelMin();
        // limit minimum zoom to prevent zooming out too much and losing the map
        MapViewDimension mapViewDimension = mapView.getModel().mapViewDimension;
        if (map.isVector() && mapViewDimension.getDimension() != null)
            zoomLevelMin = (byte) max(0, zoomForBounds(mapViewDimension.getDimension(),
                    asBoundingBox(map.getBoundingBox()), getTileSize()) - 3);

        IMapViewPosition mapViewPosition = mapView.getModel().mapViewPosition;
        mapViewPosition.setZoomLevelMin(zoomLevelMin);

        byte zoomLevelMax = map.isVector() ? MAXIMUM_ZOOM_LEVEL : map.getTileSource().getZoomLevelMax();
        // limit maximum to prevent zooming in to grey area
        mapViewPosition.setZoomLevelMax(zoomLevelMax);
    }

    private boolean isVisible(LatLong latLong) {
        MapViewProjection projection = new MapViewProjection(mapView);
        LatLong upperLeft = projection.fromPixels(MINIMUM_VISIBLE_BORDER_IN_PIXEL, MINIMUM_VISIBLE_BORDER_IN_PIXEL);
        Dimension dimension = mapView.getDimension();
        LatLong lowerRight = projection.fromPixels(dimension.width - MINIMUM_VISIBLE_BORDER_IN_PIXEL, dimension.height - MINIMUM_VISIBLE_BORDER_IN_PIXEL);
        return upperLeft != null && lowerRight != null && new org.mapsforge.core.model.BoundingBox(lowerRight.latitude, upperLeft.longitude, upperLeft.latitude, lowerRight.longitude).contains(latLong);
    }

    public NavigationPosition getCenter() {
        return asNavigationPosition(mapView.getModel().mapViewPosition.getCenter());
    }

    private void setCenter(LatLong center, boolean alwaysRecenter) {
        if (alwaysRecenter || recenterAfterZooming.getBoolean() || !isVisible(center))
            mapView.getModel().mapViewPosition.animateTo(center);
    }

    private void setCenter(NavigationPosition center, boolean alwaysRecenter) {
        setCenter(asLatLong(center), alwaysRecenter);
    }

    private int getZoom() {
        return mapView.getModel().mapViewPosition.getZoomLevel();
    }

    private void setZoom(int zoom) {
        mapView.setZoomLevel((byte) zoom);
    }

    private void zoomToBounds(org.mapsforge.core.model.BoundingBox boundingBox) {
        Dimension dimension = mapView.getModel().mapViewDimension.getDimension();
        if (dimension == null)
            return;
        byte zoom = zoomForBounds(dimension, boundingBox, getTileSize());
        setZoom(zoom);
    }

    private void zoomToBounds(BoundingBox boundingBox) {
        zoomToBounds(asBoundingBox(boundingBox));
    }

    public boolean isSupportsPrinting() {
        return false;
    }

    public void print(String title) {
        throw new UnsupportedOperationException("Printing not supported");
    }

    public String getMapsPath() {
        return getMapManager().getMapsPath();
    }

    public void setMapsPath(String path) throws IOException {
        getMapManager().setMapsPath(path);
        getMapManager().scanMaps();
    }

    public String getThemesPath() {
        return getMapManager().getThemePath();
    }

    public void setThemesPath(String path) throws IOException {
        getMapManager().setThemePath(path);
        getMapManager().scanThemes();
    }

    public void routingPreferencesChanged() {
        if (positionsModel.getRoute().getCharacteristics().equals(Route))
            updateDecoupler.replaceRoute();
    }

    public void movePosition(PositionWithLayer positionWithLayer, Double longitude, Double latitude) {
        final int row = positionsModel.getIndex(positionWithLayer.getPosition());
        if(row == -1) {
            log.warning("Marker without position " + this);
            return;
        }

        NavigationPosition reference = positionsModel.getPosition(row);
        Double diffLongitude = reference != null ? longitude - reference.getLongitude() : 0.0;
        Double diffLatitude = reference != null ? latitude - reference.getLatitude() : 0.0;

        boolean moveCompleteSelection = preferences.getBoolean(MOVE_COMPLETE_SELECTION_PREFERENCE, true);
        boolean cleanElevation = preferences.getBoolean(CLEAN_ELEVATION_ON_MOVE_PREFERENCE, false);
        boolean complementElevation = preferences.getBoolean(COMPLEMENT_ELEVATION_ON_MOVE_PREFERENCE, true);
        boolean cleanTime = preferences.getBoolean(CLEAN_TIME_ON_MOVE_PREFERENCE, false);
        boolean complementTime = preferences.getBoolean(COMPLEMENT_TIME_ON_MOVE_PREFERENCE, true);

        int minimum = row;
        for (int index : selectionUpdater.getIndices()) {
            if (index < minimum)
                minimum = index;

            NavigationPosition position = positionsModel.getPosition(index);
            if (position == null)
                continue;

            if (index != row) {
                if (!moveCompleteSelection)
                    continue;

                positionsModel.edit(index, new PositionColumnValues(asList(LONGITUDE_COLUMN_INDEX, LATITUDE_COLUMN_INDEX),
                        Arrays.asList(position.getLongitude() + diffLongitude, position.getLatitude() + diffLatitude)), false, true);
            } else {
                positionsModel.edit(index, new PositionColumnValues(asList(LONGITUDE_COLUMN_INDEX, LATITUDE_COLUMN_INDEX),
                        Arrays.asList(longitude, latitude)), false, true);
            }

            if (cleanTime)
                positionsModel.edit(index, new PositionColumnValues(DATE_TIME_COLUMN_INDEX, null), false, false);
            if (cleanElevation)
                positionsModel.edit(index, new PositionColumnValues(ELEVATION_COLUMN_INDEX, null), false, false);

            if (preferences.getBoolean(COMPLEMENT_DATA_PREFERENCE, false) && (complementTime || complementElevation))
                mapViewCallback.complementData(new int[]{index}, false, complementTime, complementElevation, true, false);
        }

        // updating all rows behind the modified is quite expensive, but necessary due to the distance
        // calculation - if that didn't exist the single update of row would be sufficient
        int size = positionsModel.getRoute().getPositions().size() - 1;
        positionsModel.fireTableRowsUpdated(minimum, size, ALL_COLUMNS);
    }

    public void setSelectedPositions(int[] selectedPositions, boolean replaceSelection) {
        if (selectionUpdater == null)
            return;
        selectionUpdater.setSelectedPositions(selectedPositions, replaceSelection);
    }

    public void setSelectedPositions(List<NavigationPosition> selectedPositions) {
        throw new UnsupportedOperationException("photo panel not available in " + MapsforgeMapView.class.getSimpleName());
    }

    private LatLong getMousePosition() {
        Point point = mapViewMoverAndZoomer.getLastMousePoint();
        return point != null ? new MapViewProjection(mapView).fromPixels(point.getX(), point.getY()) :
                mapView.getModel().mapViewPosition.getCenter();
    }

    private double getThresholdForPixel(LatLong latLong) {
        long mapSize = getMapSize(mapView.getModel().mapViewPosition.getZoomLevel(), getTileSize());
        double metersPerPixel = calculateGroundResolution(latLong.latitude, mapSize);
        return metersPerPixel * SELECTION_CIRCLE_IN_PIXEL;
    }

    private void selectPosition(LatLong latLong, Double threshold, boolean replaceSelection) {
        int row = positionsModel.getClosestPosition(latLong.longitude, latLong.latitude, threshold);
        if (row != -1 && !mapViewMoverAndZoomer.isMousePressedOnMarker()) {
            log.info("Selecting position at " + latLong + ", row is " + row);
            positionsSelectionModel.setSelectedPositions(new int[]{row}, replaceSelection);
        }
    }

    private class SelectPositionAction extends FrameAction {
        public void run() {
            LatLong latLong = getMousePosition();
            if (latLong != null) {
                Double threshold = getThresholdForPixel(latLong);
                selectPosition(latLong, threshold, true);
            }
        }
    }

    private class ExtendSelectionAction extends FrameAction {
        public void run() {
            LatLong latLong = getMousePosition();
            if (latLong != null) {
                Double threshold = getThresholdForPixel(latLong);
                selectPosition(latLong, threshold, false);
            }
        }
    }

    private class AddPositionAction extends FrameAction {
        private int getAddRow() {
            List<PositionWithLayer> lastSelectedPositions = selectionUpdater.getPositionWithLayers();
            NavigationPosition position = lastSelectedPositions.size() > 0 ? lastSelectedPositions.get(lastSelectedPositions.size() - 1).getPosition() : null;
            // quite crude logic to be as robust as possible on failures
            if (position == null && positionsModel.getRowCount() > 0)
                position = positionsModel.getPosition(positionsModel.getRowCount() - 1);
            return position != null ? positionsModel.getIndex(position) + 1 : 0;
        }

        private void insertPosition(int row, Double longitude, Double latitude) {
            positionsModel.add(row, longitude, latitude, null, null, null, mapViewCallback.createDescription(positionsModel.getRowCount() + 1, null));
            int[] rows = new int[]{row};
            positionsSelectionModel.setSelectedPositions(rows, true);
            mapViewCallback.complementData(rows, true, true, true, true, false);
        }

        public void run() {
            LatLong latLong = getMousePosition();
            if (latLong != null) {
                int row = getAddRow();
                log.info("Adding position at " + latLong + " to row " + row);
                insertPosition(row, latLong.longitude, latLong.latitude);
            }
        }
    }

    private class DeletePositionAction extends FrameAction {
        private void removePosition(LatLong latLong, Double threshold) {
            int row = positionsModel.getClosestPosition(latLong.longitude, latLong.latitude, threshold);
            log.info("Deleting position at " + latLong + " from row " + row);
            if (row != -1) {
                positionsModel.remove(new int[]{row});
            }
        }

        public void run() {
            LatLong latLong = getMousePosition();
            if (latLong != null) {
                Double threshold = getThresholdForPixel(latLong);
                removePosition(latLong, threshold);
            }
        }
    }

    private class CenterAction extends FrameAction {
        public void run() {
            mapViewMoverAndZoomer.centerToMousePosition();
        }
    }

    private class ZoomAction extends FrameAction {
        private byte zoomLevelDiff;

        private ZoomAction(int zoomLevelDiff) {
            this.zoomLevelDiff = (byte) zoomLevelDiff;
        }

        public void run() {
            mapViewMoverAndZoomer.zoomToMousePosition(zoomLevelDiff);
        }
    }

    private class UpdateDecoupler {
        private final ExecutorService executor = createSingleThreadExecutor("UpdateDecoupler");
        private EventMapUpdater eventMapUpdater = getEventMapUpdaterFor(Waypoints);

        public void replaceRoute() {
            executor.execute(() -> {
                // remove all from previous event map updater
                eventMapUpdater.handleRemove(0, MAX_VALUE);

                // select current event map updater and let him add all
                eventMapUpdater = getEventMapUpdaterFor(positionsModel.getRoute().getCharacteristics());
                eventMapUpdater.handleAdd(0, MapsforgeMapView.this.positionsModel.getRowCount() - 1);
            });
        }

        public void handleUpdate(final int eventType, final int firstRow, final int lastRow) {
            executor.execute(() -> {
               switch (eventType) {
                    case INSERT:
                        eventMapUpdater.handleAdd(firstRow, lastRow);
                        break;
                    case UPDATE:
                        eventMapUpdater.handleUpdate(firstRow, lastRow);
                        break;
                    case DELETE:
                        eventMapUpdater.handleRemove(firstRow, lastRow);
                        break;
                    default:
                        throw new IllegalArgumentException("Event type " + eventType + " is not supported");
                }
            });
        }

        public void dispose() {
            executor.shutdownNow();
        }
    }

    // listeners

    private class PositionsModelListener implements TableModelListener {
        public void tableChanged(TableModelEvent e) {
            switch (e.getType()) {
                case INSERT:
                case DELETE:
                    updateDecoupler.handleUpdate(e.getType(), e.getFirstRow(), e.getLastRow());
                    break;
                case UPDATE:
                    if (positionsModel.isContinousRange())
                        return;
                    if (!(e.getColumn() == DESCRIPTION_COLUMN_INDEX ||
                            e.getColumn() == LONGITUDE_COLUMN_INDEX ||
                            e.getColumn() == LATITUDE_COLUMN_INDEX ||
                            e.getColumn() == ALL_COLUMNS))
                        return;

                    boolean allRowsChanged = isFirstToLastRow(e);
                    if(allRowsChanged)
                        updateDecoupler.replaceRoute();
                    else
                        updateDecoupler.handleUpdate(e.getType(), e.getFirstRow(), e.getLastRow());

                    // center and zoom if a file was just loaded
                    if (allRowsChanged && showAllPositionsAfterLoading.getBoolean())
                        centerAndZoom(getMapBoundingBox(), getRouteBoundingBox(), true, true);
                    break;
                default:
                    throw new IllegalArgumentException("Event type " + e.getType() + " is not supported");
            }
        }
    }

    private class CharacteristicsModelListener implements ListDataListener {
        public void intervalAdded(ListDataEvent e) {
        }

        public void intervalRemoved(ListDataEvent e) {
        }

        public void contentsChanged(ListDataEvent e) {
            // ignore events following setRoute()
            if (isIgnoreEvent(e))
                return;
            updateDecoupler.replaceRoute();
        }
    }

    private class ShowCoordinatesListener implements ChangeListener {
        public void stateChanged(ChangeEvent e) {
            mapViewCoordinateDisplayer.setShowCoordinates(showCoordinates.getBoolean());
        }
    }

    private class RepaintPositionListListener implements ChangeListener {
        public void stateChanged(ChangeEvent e) {
            updateDecoupler.replaceRoute();
        }
    }

    private class UnitSystemListener implements ChangeListener {
        public void stateChanged(ChangeEvent e) {
            handleUnitSystem();
        }
    }

    private class DisplayedMapListener implements ChangeListener {
        private LocalMap lastMap;

        public void stateChanged(ChangeEvent e) {
            handleMapAndThemeUpdate(true, !isVisible(mapView.getModel().mapViewPosition.getCenter()));

            // if the map changes from/to Google in Automatic mode, do a recalculation
            LocalMap currentMap = getMapManager().getDisplayedMapModel().getItem();
            if(getFixMapModeModel().getFixMapMode().equals(Automatic)) {
                if(lastMap == null || isGoogleMap(lastMap) != isGoogleMap(currentMap))
                    updateDecoupler.replaceRoute();
            }
            lastMap = currentMap;
        }
    }

    private class AppliedThemeListener implements ChangeListener {
        public void stateChanged(ChangeEvent e) {
            handleMapAndThemeUpdate(false, false);
        }
    }

    private class AppliedOverlayListener implements TableModelListener {
        public void tableChanged(TableModelEvent e) {
            switch (e.getType()) {
                case INSERT:
                    handleOverlayInsertion(e.getFirstRow(), e.getLastRow());
                    break;
                case DELETE:
                    handleOverlayDeletion(e.getFirstRow(), e.getLastRow());
                    break;
                case UPDATE:
                    break;
                default:
                    throw new IllegalArgumentException("Event type " + e.getType() + " is not supported");
            }
        }
    }

    private class ShadedHillsListener implements ChangeListener {
        public void stateChanged(ChangeEvent e) {
            handleShadedHills();
            handleMapAndThemeUpdate(false, false);
        }
    }
}
