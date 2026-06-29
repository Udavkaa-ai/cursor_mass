let map, stopMarker, distanceCircle;
let mapReady = false;

ymaps.ready(function() {
    map = new ymaps.Map('map', {
        center: [55.7558, 37.6173],
        zoom: 15,
        controls: []
    });
    mapReady = true;
});

function initMap(lat, lon, stopName) {
    if (!mapReady) return;

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
    if (!mapReady || !stopMarker) return;

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

    // Создаём новый кружок
    const stopCoords = stopMarker.geometry.getCoordinates();
    distanceCircle = new ymaps.Circle([stopCoords], {
        radius: busDistanceMeters
    }, {
        fillColor: circleColor,
        strokeColor: circleColor,
        opacity: 0.7,
        strokeWidth: 2,
        fillOpacity: circleOpacity
    });

    map.geoObjects.add(distanceCircle);

    // Обновляем панель информации
    document.getElementById('arrivalInfo').textContent = 'ETA: ' + etaText;
}

function clearDistance() {
    if (distanceCircle) {
        map.geoObjects.remove(distanceCircle);
        distanceCircle = null;
    }
}
