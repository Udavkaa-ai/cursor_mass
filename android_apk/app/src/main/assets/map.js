let map, stopMarker, distanceCircle;
let mapReady = false;

// Buffer the latest calls that arrive before the Yandex API has finished
// loading, then replay them once the map is ready. Without this, the first
// initMap()/updateDistance() (fired ~500ms after page load) is silently
// dropped if the API is still loading, leaving a blank map forever.
let pendingInit = null;     // {lat, lon, stopName}
let pendingDistance = null; // {etaSeconds, etaText}

ymaps.ready(function() {
    map = new ymaps.Map('map', {
        center: [55.7558, 37.6173],
        zoom: 15,
        controls: []
    });
    mapReady = true;

    if (pendingInit) {
        initMap(pendingInit.lat, pendingInit.lon, pendingInit.stopName);
        pendingInit = null;
    }
    if (pendingDistance) {
        updateDistance(pendingDistance.etaSeconds, pendingDistance.etaText);
        pendingDistance = null;
    }
});

function initMap(lat, lon, stopName) {
    if (!mapReady) {
        pendingInit = { lat: lat, lon: lon, stopName: stopName };
        return;
    }

    if (stopMarker) {
        map.geoObjects.remove(stopMarker);
    }

    stopMarker = new ymaps.Placemark([lat, lon], {
        balloonContent: stopName
    }, {
        preset: 'islands#greenCircleDotIcon',
        iconColor: '#2ED87A'
    });

    map.geoObjects.add(stopMarker);
    map.setCenter([lat, lon], DEFAULT_ZOOM);
    lastFitRadius = -1;

    document.getElementById('stopName').textContent = stopName;
    document.getElementById('info').style.display = 'block';
}

// Only draw the bus-distance circle within this many seconds (3 min). Further
// away the circle would be kilometers wide and cover the whole map.
const SHOW_WITHIN_SEC = 180;
const DEFAULT_ZOOM = 16;
let lastFitRadius = -1;   // circle radius (m) the zoom was last fitted to; 0 = far view

// Bounding box [[south, west], [north, east]] around a center for a radius (m).
function boundsAround(center, radiusMeters) {
    const dLat = radiusMeters / 111320;
    const dLon = radiusMeters / (111320 * Math.cos(center[0] * Math.PI / 180));
    return [[center[0] - dLat, center[1] - dLon], [center[0] + dLat, center[1] + dLon]];
}

function updateDistance(etaSeconds, etaText) {
    if (!mapReady || !stopMarker) {
        pendingDistance = { etaSeconds: etaSeconds, etaText: etaText };
        return;
    }

    document.getElementById('arrivalInfo').textContent = '🚌 ' + etaText;

    const stopCoords = stopMarker.geometry.getCoordinates();

    // Bus still far (or unknown) — no circle, keep a calm default view.
    if (etaSeconds == null || etaSeconds > SHOW_WITHIN_SEC) {
        if (distanceCircle) { map.geoObjects.remove(distanceCircle); distanceCircle = null; }
        if (lastFitRadius !== 0) { lastFitRadius = 0; map.setCenter(stopCoords, DEFAULT_ZOOM); }
        return;
    }

    const busSpeedMps = 8.3;
    const radius = Math.max(0, Math.round(etaSeconds * busSpeedMps));

    // Color by closeness.
    let circleColor, circleOpacity;
    if (etaSeconds <= 60) {            // ≤ 1 min / arriving
        circleColor = '#E53040'; circleOpacity = 0.55;
    } else if (etaSeconds <= 120) {    // ~2 min
        circleColor = '#FF8C00'; circleOpacity = 0.45;
    } else {                           // ~3 min
        circleColor = '#2ED87A'; circleOpacity = 0.40;
    }

    // Update the existing circle in place (radius + colors) instead of removing
    // and re-adding it every second — that caused a visible fill flicker.
    if (!distanceCircle) {
        distanceCircle = new ymaps.Circle([stopCoords, radius], {}, {
            strokeOpacity: 0.7,
            strokeWidth: 2
        });
        map.geoObjects.add(distanceCircle);
    } else {
        distanceCircle.geometry.setRadius(radius);
    }
    distanceCircle.options.set({
        fillColor: circleColor,
        strokeColor: circleColor,
        fillOpacity: circleOpacity
    });

    // Keep the circle filling the view as the bus approaches: re-fit the zoom
    // whenever the radius has shrunk/grown notably since the last fit (not every
    // second — that would jitter). Fitting to the actual radius means 1 min is
    // zoomed in tighter than 3 min instead of staying at the bucket's far edge.
    const fitRadius = Math.max(radius, 90);  // floor avoids over-zoom near arrival
    if (lastFitRadius <= 0 || fitRadius < lastFitRadius * 0.8 || fitRadius > lastFitRadius * 1.3) {
        lastFitRadius = fitRadius;
        map.setBounds(boundsAround(stopCoords, fitRadius * 1.15), {
            checkZoomRange: true,
            zoomMargin: 20
        });
    }
}

function clearDistance() {
    if (distanceCircle) {
        map.geoObjects.remove(distanceCircle);
        distanceCircle = null;
    }
    lastFitRadius = -1;
}
