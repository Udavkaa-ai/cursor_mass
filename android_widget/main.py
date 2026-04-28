"""
Kivy APK — виджет прибытий автобусов с Яндекс.Карт.

Два экрана:
  settings — настройки URL сервера, остановки, маршрутов, ключа API
  main     — кнопка запуска + таблица прибытий

Логика: кнопка «ЗАПУСТИТЬ» открывает 5-минутную сессию,
данные запрашиваются каждые 30 секунд из /arrivals/{stop_id}.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.properties import BooleanProperty, ListProperty, NumericProperty, StringProperty
from kivy.storage.jsonstore import JsonStore
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.screenmanager import Screen, ScreenManager, SlideTransition

SESSION_SEC = 300  # 5 минут
POLL_SEC = 30      # интервал между запросами


# --------------------------------------------------------------------------- #
# Вспомогательные функции                                                      #
# --------------------------------------------------------------------------- #

def _urgency_cls(eta_seconds: int | None) -> str:
    if eta_seconds is None:
        return "sched"
    if eta_seconds <= 180:
        return "run"
    if eta_seconds <= 300:
        return "hurry"
    if eta_seconds <= 420:
        return "walk"
    return "calm"


_ETA_COLORS: dict[str, list[float]] = {
    "run":   [1.000, 0.302, 0.302, 1],
    "hurry": [1.000, 0.667, 0.200, 1],
    "walk":  [0.259, 0.847, 0.514, 1],
    "sched": [0.494, 0.627, 1.000, 1],
    "calm":  [0.957, 0.957, 0.965, 1],
}


# --------------------------------------------------------------------------- #
# Виджет одной строки прибытия                                                 #
# --------------------------------------------------------------------------- #

class ArrivalRow(BoxLayout):
    route     = StringProperty("")
    direction = StringProperty("")
    eta       = StringProperty("")
    eta_color = ListProperty([1, 1, 1, 1])


# --------------------------------------------------------------------------- #
# Экран настроек                                                                #
# --------------------------------------------------------------------------- #

class SettingsScreen(Screen):

    def on_pre_enter(self) -> None:
        store = App.get_running_app().store
        if store.exists("cfg"):
            c = store.get("cfg")
            self.ids.url.text    = c.get("url",    "")
            self.ids.stop.text   = c.get("stop",   "")
            self.ids.routes.text = c.get("routes", "")
            self.ids.key.text    = c.get("key",    "")

    def save(self) -> None:
        app = App.get_running_app()
        app.store.put(
            "cfg",
            url    = self.ids.url.text.strip(),
            stop   = self.ids.stop.text.strip(),
            routes = self.ids.routes.text.strip(),
            key    = self.ids.key.text.strip(),
        )
        app.root.transition = SlideTransition(direction="left")
        app.root.current = "main"
        app.root.get_screen("main").refresh_config()


# --------------------------------------------------------------------------- #
# Главный экран                                                                 #
# --------------------------------------------------------------------------- #

class MainScreen(Screen):
    stop_name = StringProperty("остановка")
    running   = BooleanProperty(False)
    time_left = NumericProperty(float(SESSION_SEC))
    next_poll = NumericProperty(float(POLL_SEC))
    status    = StringProperty("нажми ЗАПУСТИТЬ")

    def __init__(self, **kw) -> None:
        super().__init__(**kw)
        self._tick_ev = None

    # ------------------------------------------------------------------ #

    def refresh_config(self) -> None:
        store = App.get_running_app().store
        if store.exists("cfg"):
            s = store.get("cfg").get("stop", "")
            if s:
                self.stop_name = s

    def on_enter(self) -> None:
        self.refresh_config()

    def go_settings(self) -> None:
        app = App.get_running_app()
        app.root.transition = SlideTransition(direction="right")
        app.root.current = "settings"

    # ------------------------------------------------------------------ #
    # Управление сессией                                                   #
    # ------------------------------------------------------------------ #

    def toggle(self) -> None:
        if self.running:
            self._stop()
        else:
            self._start()

    def _start(self) -> None:
        self.running   = True
        self.time_left = SESSION_SEC
        self.next_poll = 0          # первый опрос — немедленно
        self.status    = "загрузка..."
        self._tick_ev  = Clock.schedule_interval(self._tick, 1)

    def _stop(self, msg: str = "остановлено") -> None:
        self.running = False
        self.status  = msg
        if self._tick_ev:
            self._tick_ev.cancel()
            self._tick_ev = None

    def _tick(self, _dt: float) -> None:
        self.time_left = max(0.0, self.time_left - 1)
        self.next_poll = max(0.0, self.next_poll - 1)
        if self.time_left == 0:
            self._stop("сессия завершена")
            return
        if self.next_poll == 0:
            self.next_poll = POLL_SEC
            self._fetch()

    # ------------------------------------------------------------------ #
    # HTTP-запрос в фоновом потоке                                         #
    # ------------------------------------------------------------------ #

    def _fetch(self) -> None:
        store = App.get_running_app().store
        if not store.exists("cfg"):
            self.status = "нет настроек — зайди в Настройки"
            return
        cfg    = store.get("cfg")
        base   = cfg.get("url",    "").rstrip("/")
        stop   = cfg.get("stop",   "")
        routes = cfg.get("routes", "")
        key    = cfg.get("key",    "")
        if not base or not stop:
            self.status = "укажи сервер и остановку в Настройках"
            return

        def _do() -> None:
            try:
                url = f"{base}/arrivals/{urllib.parse.quote(stop, safe='')}"
                if routes:
                    url += f"?routes={urllib.parse.quote(routes)}"
                req = urllib.request.Request(url)
                if key:
                    req.add_header("X-API-Key", key)
                with urllib.request.urlopen(req, timeout=15) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                Clock.schedule_once(lambda _dt, d=data: self._show(d), 0)
            except Exception as exc:
                msg = str(exc)[:80]
                Clock.schedule_once(
                    lambda _dt, m=msg: setattr(self, "status", f"ошибка: {m}"), 0
                )

        threading.Thread(target=_do, daemon=True).start()

    # ------------------------------------------------------------------ #
    # Отрисовка прибытий                                                   #
    # ------------------------------------------------------------------ #

    def _show(self, data: dict) -> None:
        if data.get("name"):
            self.stop_name = data["name"]

        arrivals: list[dict] = data.get("arrivals") or []

        # Группируем по маршруту+направлению
        groups: dict[tuple, list] = {}
        for a in arrivals:
            k = (a.get("route", "?"), a.get("direction") or "")
            groups.setdefault(k, []).append(a)

        # Сортируем группы по ближайшему прибытию
        sorted_groups = sorted(
            groups.items(),
            key=lambda kv: (kv[1][0].get("eta_seconds") or 9999),
        )

        box = self.ids.arrivals_box
        box.clear_widgets()

        if not sorted_groups:
            if not data.get("error"):
                self.status = "прибытий нет"
            else:
                self.status = f"ошибка сервера: {data['error'][:60]}"
            return

        for (route, direction), group in sorted_groups:
            group.sort(key=lambda x: (x.get("eta_seconds") or 9999))
            # Показываем максимум 2 прибытия на маршрут
            for a in group[:2]:
                cls = _urgency_cls(a.get("eta_seconds"))
                row = ArrivalRow()
                row.route     = route
                row.direction = direction or ""
                row.eta       = a.get("eta_local") or a.get("eta_text") or "—"
                row.eta_color = _ETA_COLORS.get(cls, _ETA_COLORS["calm"])[:]
                box.add_widget(row)

        ts = time.strftime("%H:%M:%S")
        self.status = f"обновлено {ts}"


# --------------------------------------------------------------------------- #
# Приложение                                                                   #
# --------------------------------------------------------------------------- #

class BusWidget(App):
    def build(self):
        self.store = JsonStore("bw_cfg.json")
        Window.clearcolor = (0.059, 0.059, 0.063, 1)

        sm = ScreenManager()
        sm.add_widget(SettingsScreen(name="settings"))
        sm.add_widget(MainScreen(name="main"))

        # Если настройки уже есть — сразу на главный экран
        if self.store.exists("cfg") and self.store.get("cfg").get("stop"):
            sm.current = "main"

        return sm

    @staticmethod
    def fmt(seconds: float) -> str:
        m, s = divmod(int(seconds), 60)
        return f"{m}:{s:02d}"


if __name__ == "__main__":
    BusWidget().run()
