;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.database
  "A specific logger impl that persists errors on the database."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error Listener
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handle-event)

(defonce enabled (atom true))

(defn- persist-on-database!
  [pool id report]
  (when-not (db/read-only? pool)
    (db/insert! pool :server-error-report
                {:id id
                 :version 2
                 :content (db/tjson report)})))

(defn record->report
  [{:keys [::l/context ::l/message ::l/props ::l/logger ::l/level ::l/cause] :as record}]
  (us/assert! ::l/record record)

  (merge
   {:context (-> context
                 (assoc :tenant (cf/get :tenant))
                 (assoc :host (cf/get :host))
                 (assoc :public-uri (cf/get :public-uri))
                 (assoc :version (:full cf/version))
                 (assoc :logger-name logger)
                 (assoc :logger-level level)
                 (dissoc :params)
                 (pp/pprint-str :width 200))
    :params  (some-> (:params context)
                     (pp/pprint-str :width 200))
    :props   (pp/pprint-str props :width 200)
    :hint    (or (ex-message cause) @message)
    :trace   (ex/format-throwable cause :data? false :explain? false :header? false :summary? false)}

   (when-let [data (ex-data cause)]
     {:spec-value    (some-> (::s/value data) (pp/pprint-str :width 200))
      :spec-explain  (ex/explain data)
      :data          (-> data
                         (dissoc ::s/problems ::s/value ::s/spec :hint)
                         (pp/pprint-str :width 200))})))

(defn- handle-event
  [{:keys [::db/pool]} {:keys [::l/id] :as record}]
  (try
    (let [uri    (cf/get :public-uri)
          report (-> record record->report d/without-nils)]
      (l/debug :hint "registering error on database" :id id
               :uri (str uri "/dbg/error/" id))

      (persist-on-database! pool id report))
    (catch Throwable cause
      (l/warn :hint "unexpected exception on database error logger" :cause cause))))

(defn error-record?
  [{:keys [::l/level ::l/cause]}]
  (and (= :error level)
       (ex/exception? cause)))

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (let [input (sp/chan (sp/sliding-buffer 32) (filter error-record?))]
    (add-watch l/log-record ::reporter #(sp/put! input %4))
    (px/thread
      {:name "penpot/database-reporter" :virtual true}
      (l/info :hint "initializing database error persistence")
      (try
        (loop []
          (when-let [record (sp/take! input)]
            (handle-event cfg record)
            (recur)))
        (catch InterruptedException _
          (l/debug :hint "reporter interrupted"))
        (catch Throwable cause
          (l/error :hint "unexpected error" :cause cause))
        (finally
          (sp/close! input)
          (remove-watch l/log-record ::reporter)
          (l/info :hint "reporter terminated"))))))

(defmethod ig/halt-key! ::reporter
  [_ thread]
  (some-> thread px/interrupt!))
