(ns proto-zabbix.zabbix
  (:require [proto-zabbix.proto :as proto]
            [clojure.pprint :refer [pprint]])
  (:import [java.net ServerSocket]))

;; If you dont reply to the initial request of an active agent by e.g.
;; sending an empty string the agent will retry in 60 seconds.
(defn- zserver [port handler]
  (let [running (atom true)]
    (future
      (with-open [server-sock (ServerSocket. port)]
        ;; FIXME: it will need to get one more request after resetting
        ;; the atom to actually exit the loop:
        (while @running
          (with-open [sock (.accept server-sock)]
            (let [msg-in (proto/proto-recv sock)
                  msg-out (handler msg-in)]
              (pprint {:INP msg-in :OUT msg-out})
              (proto/proto-send sock msg-out))))))
    running))

(defn- zhandler [json]
  (let [request (get json "request")]
    (if (= "active checks" request)
      ;; FIXME: lastlogsize and mtime required for log items and may
      ;; be omitted except for the older agents including 2.2.2:
      {"response" "success",
       "data" [{"key" "agent.version", "delay" 30,
                "lastlogsize" 0, "mtime" 0}
               {"key" "system.uptime", "delay" 30,
                "lastlogsize" 0, "mtime" 0}]}
      "")))

;; (def server (zserver 10051 zhandler))
;; (reset! server false)

;; Terminate with C-c:
(defn -main [& args]
  (zserver 10051 zhandler))
