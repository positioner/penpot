;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.msgbus
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [promesa.core :as p])
  (:import
   java.time.Duration
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisURI
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.api.async.RedisAsyncCommands
   io.lettuce.core.codec.ByteArrayCodec
   io.lettuce.core.codec.RedisCodec
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.pubsub.RedisPubSubListener
   io.lettuce.core.pubsub.StatefulRedisPubSubConnection
   io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands))

(declare impl-publish-loop)
(declare impl-redis-pub)
(declare impl-redis-sub)
(declare impl-redis-unsub)
(declare impl-subscribe-loop)


;; --- STATE INIT: Publisher

(s/def ::uri ::us/string)
(s/def ::buffer-size ::us/integer)

(defmethod ig/pre-init-spec ::msgbus [_]
  (s/keys :req-un [::uri]
          :opt-un [::buffer-size]))

(defmethod ig/prep-key ::msgbus
  [_ cfg]
  (merge {:buffer-size 128} cfg))

(defmethod ig/init-key ::msgbus
  [_ {:keys [uri buffer-size] :as cfg}]
  (let [codec    (RedisCodec/of StringCodec/UTF8 ByteArrayCodec/INSTANCE)

        uri      (RedisURI/create uri)
        rclient  (RedisClient/create ^RedisURI uri)

        snd-conn (.connect ^RedisClient rclient ^RedisCodec codec)
        rcv-conn (.connectPubSub ^RedisClient rclient ^RedisCodec codec)

        pub-buff (a/chan (a/dropping-buffer buffer-size))
        rcv-buff (a/chan (a/dropping-buffer buffer-size))
        sub-buff (a/chan 1)
        cch      (a/chan 1)]

    (.setTimeout ^StatefulRedisConnection snd-conn ^Duration (dt/duration {:seconds 10}))
    (.setTimeout ^StatefulRedisPubSubConnection rcv-conn ^Duration (dt/duration {:seconds 10}))

    (log/debugf "initializing msgbus (uri: '%s')" (str uri))

    ;; Start the sending (publishing) loop
    (impl-publish-loop snd-conn pub-buff cch)

    ;; Start the receiving (subscribing) loop
    (impl-subscribe-loop rcv-conn rcv-buff sub-buff cch)

    (with-meta
      (fn run
        ([command] (run command nil))
        ([command params]
         (a/go
           (case command
             :pub (a/>! pub-buff params)
             :sub (a/>! sub-buff params)))))

      {::snd-conn snd-conn
       ::rcv-conn rcv-conn
       ::cch cch
       ::pub-buff pub-buff
       ::rcv-buff rcv-buff})))

(defmethod ig/halt-key! ::msgbus
  [_ f]
  (let [mdata (meta f)]
    (.close ^StatefulRedisConnection (::snd-conn mdata))
    (.close ^StatefulRedisPubSubConnection (::rcv-conn mdata))
    (a/close! (::cch mdata))
    (a/close! (::pub-buff mdata))
    (a/close! (::rcv-buff mdata))))

(defn- impl-publish-loop
  [conn pub-buff cch]
  (let [rac (.async ^StatefulRedisConnection conn)]
    (a/go-loop []
      (let [[val _] (a/alts! [cch pub-buff] :priority true)]
        (when (some? val)
          (let [result (a/<! (impl-redis-pub rac val))]
            (when (ex/exception? result)
              (log/error result "unexpected error on publish message to redis")))
          (recur))))))

(defn- impl-subscribe-loop
  [conn rcv-buff sub-buff cch]
  ;; Add a unique listener to connection
  (.addListener conn (reify RedisPubSubListener
                       (message [it pattern topic message])
                       (message [it topic message]
                         ;; There are no back pressure, so we use a slidding
                         ;; buffer for cases when the pubsub broker sends
                         ;; more messages that we can process.
                         (a/>!! rcv-buff {:topic topic :message (blob/decode message)}))
                       (psubscribed [it pattern count])
                       (punsubscribed [it pattern count])
                       (subscribed [it topic count])
                       (unsubscribed [it topic count])))

  (a/go-loop [chans {}]
    (let [[val port] (a/alts! [sub-buff cch rcv-buff] :priority true)]
      (cond
        ;; Stop condition; just do nothing
        (= port cch)
        nil

        ;; If we receive a message on sub-buff this means that a new
        ;; subscription is requested by the notifications module.
        (= port sub-buff)
        (let [topic  (:topic val)
              output (:chan val)
              chans  (update chans topic (fnil conj #{}) output)]
          (when (= 1 (count (get chans topic)))
            (let [result (a/<! (impl-redis-sub conn topic))]
              (when (ex/exception? result)
                (log/errorf result "unexpected exception on subscribing to '%s'" topic))))
          (recur chans))

        ;; This means we receive data from redis and we need to
        ;; forward it to the underlying subscriptions.
        (= port rcv-buff)
        (let [topic   (:topic val)
              pending (loop [chans   (seq (get chans topic))
                             pending #{}]
                        (if-let [ch (first chans)]
                          (if (a/>! ch (:message val))
                            (recur (rest chans) pending)
                            (recur (rest chans) (conj pending ch)))
                          pending))
              chans   (update chans topic #(reduce disj % pending))]
          (when (empty? (get chans topic))
            (let [result (a/<! (impl-redis-unsub conn topic))]
              (when (ex/exception? result)
                (log/errorf result "unexpected exception on unsubscribing from '%s'" topic))))
          (recur chans))))))

(defn- impl-redis-pub
  [rac {:keys [topic message]}]
  (let [topic   (str topic)
        message (blob/encode message)
        res     (a/chan 1)]
    (-> (.publish ^RedisAsyncCommands rac ^String topic ^bytes message)
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

(defn impl-redis-sub
  [conn topic]
  (let [^RedisPubSubAsyncCommands cmd (.async ^StatefulRedisPubSubConnection conn)
        res (a/chan 1)]
    (-> (.subscribe cmd (into-array String [topic]))
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

(defn impl-redis-unsub
  [conn topic]
  (let [^RedisPubSubAsyncCommands cmd (.async ^StatefulRedisPubSubConnection conn)
        res (a/chan 1)]
    (-> (.unsubscribe cmd (into-array String [topic]))
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))
