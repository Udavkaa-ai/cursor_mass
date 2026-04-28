[app]

# Название приложения (отображается под иконкой)
title = Автобусы

# Имя пакета (только латиница и точки)
package.name = buswidget
package.domain = ru.buswidget

# Откуда брать исходники
source.dir = .
source.include_exts = py,kv,png,jpg,atlas,json

# Версия
version = 1.0

# Точка входа
entrypoint = main.py

# Python/Kivy
requirements = python3,kivy==2.3.0,sdl2,sdl2_image,sdl2_mixer,sdl2_ttf

# Ориентация
orientation = portrait

# Полноэкранный режим: 0 — статус-бар виден (рекомендуется)
fullscreen = 0

# Иконка — можно добавить icon.png рядом с main.py
# icon.filename = %(source.dir)s/icon.png

# Заставка
# presplash.filename = %(source.dir)s/presplash.png


[buildozer]

# Уровень логирования: 0 = минимум, 2 = отладка
log_level = 2

warn_on_root = 1


[app:android]

# Разрешения
android.permissions = android.permission.INTERNET

# Target/Min SDK
android.api = 34
android.minapi = 21

# NDK версия (buildozer сам скачает при первой сборке)
android.ndk = 25b

# Архитектуры: arm64-v8a — современные, armeabi-v7a — старые
android.archs = arm64-v8a, armeabi-v7a

# Разрешить резервное копирование (можно отключить)
android.allow_backup = True

# Gradle-плагин (оставить по умолчанию если нет особых нужд)
# android.gradle_dependencies =

# Не нужен WindowSoftInputMode по умолчанию — Kivy сам управляет
# android.manifest.attributes = {"android:windowSoftInputMode": "adjustResize"}


# ─────────────────────────────────────────────────────────────────────────────
# КАК СОБРАТЬ APK
# ─────────────────────────────────────────────────────────────────────────────
#
# 1) Установить buildozer (нужен Linux / WSL2 / Docker):
#    pip install buildozer
#
# 2) Установить системные зависимости (Ubuntu/Debian):
#    sudo apt-get install -y git zip unzip openjdk-17-jdk python3-pip \
#        autoconf libtool pkg-config zlib1g-dev libncurses5-dev \
#        libncursesw5-dev libtinfo5 cmake libffi-dev libssl-dev
#
# 3) Из директории android_widget/ запустить:
#    buildozer android debug
#
#    Первая сборка занимает ~30 минут — скачивает Android SDK/NDK и p4a.
#    Результат: bin/buswidget-1.0-arm64-v8a_armeabi-v7a-debug.apk
#
# 4) Установить на телефон:
#    adb install bin/buswidget-*.apk
#    (или скинуть файл вручную и открыть с телефона)
#
# 5) Release APK (подписанный):
#    buildozer android release
#    — нужен keystore; подпись: jarsigner / apksigner.
#
# ─────────────────────────────────────────────────────────────────────────────
