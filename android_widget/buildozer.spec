[app]

title = Автобусы
package.name = buswidget
package.domain = ru.buswidget

source.dir = .
source.include_exts = py,kv,png,jpg,atlas,json

version = 1.0
orientation = portrait
fullscreen = 0

requirements = python3,kivy==2.3.0,sdl2,sdl2_image,sdl2_mixer,sdl2_ttf

# Android — всё в секции [app], не в [app:android] (та игнорируется)
android.permissions = android.permission.INTERNET
android.api = 34
android.minapi = 21
android.ndk = 25b
android.archs = arm64-v8a, armeabi-v7a
android.allow_backup = True
android.accept_sdk_license = True


[buildozer]

log_level = 2
warn_on_root = 1
