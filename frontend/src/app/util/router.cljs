;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.router
  (:refer-clojure :exclude [resolve])
  (:require
   [app.common.data :as d]
   [app.config :as cfg]
   [app.common.uri :as u]
   [app.util.browser-history :as bhistory]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as e]
   [potok.core :as ptk]
   [reitit.core :as r]))

;; --- Router API

(defn resolve
  ([router id] (resolve router id {} {}))
  ([router id params] (resolve router id params {}))
  ([router id params qparams]
   (when-let [match (r/match-by-name router id params)]
     (if (empty? qparams)
       (r/match->path match)
       (let [query (u/map->query-string qparams)]
         (-> (u/uri (r/match->path match))
             (assoc :query query)
             (str)))))))

(defn create
  [routes]
  (r/router routes))

(defn initialize-router
  [routes]
  (ptk/reify ::initialize-router
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :router (create routes)))))

(defn match
  "Given routing tree and current path, return match with possibly
  coerced parameters. Return nil if no match found."
  [router path]
  (let [uri (u/uri path)]
    (when-let [match (r/match-by-path router (:path uri))]
      (let [qparams (u/query-string->map (:query uri))
            params  {:path (:path-params match)
                     :query qparams}]
        (-> match
            (assoc :params params)
            (assoc :query-params qparams))))))

;; --- Navigate (Event)

(deftype Navigate [id params qparams replace]
  ptk/UpdateEvent
  (update [_ state]
    (dissoc state :exception))

  ptk/EffectEvent
  (effect [_ state stream]
    (let [router  (:router state)
          history (:history state)
          path    (resolve router id params qparams)]
      (if ^boolean replace
        (bhistory/replace-token! history path)
        (bhistory/set-token! history path)))))

(defn nav
  ([id] (nav id nil nil))
  ([id params] (nav id params nil))
  ([id params qparams] (Navigate. id params qparams false)))

(defn nav'
  ([id] (nav id nil nil))
  ([id params] (nav id params nil))
  ([id params qparams] (Navigate. id params qparams true)))

(def navigate nav)

(deftype NavigateNewWindow [id params qparams]
  ptk/EffectEvent
  (effect [_ state stream]
    (let [router (:router state)
          path   (resolve router id params qparams)
          uri    (-> (u/uri cfg/public-uri)
                     (assoc :fragment path))]
      (js/window.open (str uri) "_blank"))))

(defn nav-new-window
  ([id] (nav-new-window id nil nil))
  ([id params] (nav-new-window id params nil))
  ([id params qparams] (NavigateNewWindow. id params qparams)))

;; --- History API

(defn initialize-history
  [on-change]
  (ptk/reify ::initialize-history
    ptk/UpdateEvent
    (update [_ state]
      (let [history (bhistory/create)]
        (bhistory/enable! history)
        (assoc state :history history)))

    ptk/EffectEvent
    (effect [_ state stream]
      (let [stoper  (rx/filter (ptk/type? ::initialize-history) stream)
            history (:history state)
            router  (:router state)]
        (ts/schedule #(on-change router (.getToken ^js history)))
        (->> (rx/create (fn [subs]
                           (let [key (e/listen history "navigate" (fn [o] (rx/push! subs (.-token ^js o))))]
                             (fn []
                               (bhistory/disable! history)
                               (e/unlistenByKey key)))))
              (rx/take-until stoper)
              (rx/subs #(on-change router %)))))))




