(ns braid.client.store
  (:require [cljs-utils.core :refer [flip]]
            [cljs-uuid-utils.core :as uuid]
            [taoensso.timbre :as timbre :refer-macros [errorf]]
            [clojure.set :as set]
            [schema.core :as s :include-macros true]
            [braid.common.schema :as app-schema]
            [reagent.core :as r]))

(defonce app-state
  (r/atom
    {:login-state :auth-check ; :ws-connect :login-form :app
     :open-group-id nil
     :threads {}
     :group-threads {}
     :new-thread-msg {}
     :pagination-remaining 0
     :users {}
     :tags {}
     :groups {}
     :page {:type :inbox}
     :session nil
     :errors []
     :invitations []
     :preferences {}
     :notifications {:window-visible? true
                     :unread-count 0}
     :user {:open-thread-ids #{}
            :subscribed-tag-ids #{}}
     :new-thread-id (uuid/make-random-squuid)
     :focused-thread-id nil}))

(def AppState
  {:login-state (s/enum :auth-check :login-form :ws-connect :app)
   :open-group-id (s/maybe s/Uuid)
   :threads {s/Uuid app-schema/MsgThread}
   :group-threads {s/Uuid #{s/Uuid}}
   :new-thread-msg {s/Uuid s/Str}
   :pagination-remaining s/Int
   :users {s/Uuid app-schema/User}
   :tags {s/Uuid app-schema/Tag}
   :groups {s/Uuid app-schema/Group}
   :page {:type s/Keyword
          (s/optional-key :id) s/Uuid
          (s/optional-key :thread-ids) [s/Uuid]
          (s/optional-key :search-query) s/Str
          (s/optional-key :search-error?) s/Bool}
   :session (s/maybe {:user-id s/Uuid})
   :errors [[(s/one (s/cond-pre s/Keyword s/Str) "err-key") (s/one s/Str "msg")
             (s/one (s/enum :error :warn :info) "type")]]
   :invitations [app-schema/Invitation]
   :preferences {s/Keyword s/Any}
   :notifications {:window-visible? s/Bool
                   :unread-count s/Int}
   :user {:open-thread-ids #{s/Uuid}
          :subscribed-tag-ids #{s/Uuid}}
   :new-thread-id s/Uuid
   :focused-thread-id (s/maybe s/Uuid)})

(def check-app-state! (s/validator AppState))

(defn- key-by-id [coll]
  (into {} (map (juxt :id identity)) coll))

(defn transact! [v]
  (let [old-state @app-state]
    (reset! app-state v)
    (try
      (check-app-state! @app-state)
      (catch ExceptionInfo e
        (errorf "State consistency error: %s"
                (:error (ex-data e)))
        (reset! app-state old-state)
        ; Not just calling display-error! to avoid possibility of infinite loop
        (swap! app-state update-in [:errors] conj
               [(str :internal-consistency (rand-int 100)) "Something has gone wrong" :error])))))

; GETTERS

(defn all-users []
  (vals (get-in @app-state [:users])))

(defn users-in-group [group-id]
  (filter (fn [u] (contains? (set (u :group-ids)) group-id)) (vals (@app-state :users))))

(defn users-in-open-group []
  (users-in-group (@app-state :open-group-id)))

(defn user-in-open-group? [user-id]
  (contains? (set (get-in @app-state [:users user-id :group-ids])) (@app-state :open-group-id)))

(defn nickname->user [nickname]
  (->> (get-in @app-state [:users])
       vals
       (filter (fn [u] (= nickname (u :nickname))))
       ; nicknames are unique, so take the first
       first))

(defn valid-user-id? [user-id]
  (some? (get-in @app-state [:users user-id])))

(defn id->thread [thread-id]
  (get-in @app-state [:threads thread-id]))

(defn open-thread? [thread-id]
  (contains? (set (get-in @app-state [:user :open-thread-ids])) thread-id))

(defn pagination-remaining []
  (get @app-state :pagination-remaining 0))

(defn all-tags []
  (vals (get-in @app-state [:tags])))

(defn tags-in-group [group-id]
  (filter #(= group-id (% :group-id)) (vals (@app-state :tags))))

(defn tags-in-open-group []
  (tags-in-group (@app-state :open-group-id)))

(defn tag-in-open-group? [tag-id]
  (= (get-in @app-state [:tags tag-id :group-id]) (@app-state :open-group-id)))

(defn name->open-tag-id
  "Lookup tag by name in the open group"
  [tag-name]
  (let [open-group (@app-state :open-group-id)]
    (->> (@app-state :tags)
         vals
         (filter (fn [t] (and (= open-group (t :group-id)) (= tag-name (t :name)))))
         first
         :id)))

(defn get-tag [tag-id]
  (get-in @app-state [:tags tag-id]))

(defn is-subscribed-to-tag? [tag-id]
  (contains? (get-in @app-state [:user :subscribed-tag-ids]) tag-id))

(defn open-group-name []
  (let [st @app-state]
    (get-in st [:groups (st :open-group-id) :name])))

(defn id->group [group-id]
  (get-in @app-state [:groups group-id]))

(defn bots-in-open-group []
  (get-in @app-state [:groups (get @app-state :open-group-id) :bots]))

(defn open-group-id []
  (get @app-state :open-group-id))

