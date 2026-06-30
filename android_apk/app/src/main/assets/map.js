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
    map.setCenter([lat, lon], 15);

    document.getElementById('stopName').textContent = stopName;
    document.getElementById('info').style.display = 'block';
}

function updateDistance(etaSeconds, etaText) {
    if (!mapReady || !stopMarker) {
        pendingDistance = { etaSeconds: etaSeconds, etaText: etaText };
        return;
    }

    const busSpeedMps = 8.3;
    const busDistanceMeters = Math.max(0, Math.round(etaSeconds * busSpeedMps));

    // Удаляем старый кружок
    if (distanceCircle) {
        map.geoObjects.remove(distanceCircle);
    }

    // Определяем цвет по ETA
    let circleColor = '#4A90E2';
    let circleOpacity = 0.5;

    if (etaSeconds <= 0) {
        circleColor = '#E53040';
        circleOpacity = 0.7;
    } else if (etaSeconds < 300) {
        circleColor = '#E53040';
        circleOpacity = 0.6;
    } else if (etaSeconds < 480) {
        circleColor = '#FF8C00';
        circleOpacity = 0.5;
    } else {
        circleColor = '#2ED87A';
        circleOpacity = 0.4;
    }

    // Создаём новый кружок. Геометрия ymaps.Circle — это [центр, радиус],
    // т.е. [[lat, lon], радиусВметрах]. Радиус НЕ задаётся через свойства.
    const stopCoords = stopMarker.geometry.getCoordinates();
    distanceCircle = new ymaps.Circle([stopCoords, busDistanceMeters], {}, {
        fillColor: circleColor,
        strokeColor: circleColor,
        strokeOpacity: 0.7,
        strokeWidth: 2,
        fillOpacity: circleOpacity
    });

    map.geoObjects.add(distanceCircle);

    // Обновляем панель информации
    document.getElementById('arrivalInfo').textContent = '🚌 ' + etaText;
}

function clearDistance() {
    if (distanceCircle) {
        map.geoObjects.remove(distanceCircle);
        distanceCircle = null;
    }
}
