(ns proto-zabbix.zabbix
  (:require [proto-zabbix.proto :as proto]
            [clojure.pprint :refer [pprint]])
  (:import [java.net ServerSocket]))

;;
;; The useful work is done in  separate threads started as futures. To
;; termitate  the chain  of futures  keep  a reference  and close  the
;; server-socket.  See tools/nrepl/server.clj in  the clojure repo for
;; inspiration:
;;
(defn- zserve
  [^ServerSocket server-socket handler]
  (when-not (.isClosed server-socket)
    (let [sock (.accept server-socket)]
      ;; Branch a future to handle this connection:
      (future
        (with-open [sock sock]
          (let [msg-in (proto/proto-recv sock)
                msg-out (handler msg-in)]
            (pprint {:INP msg-in :OUT msg-out})
            (proto/proto-send sock msg-out))))
      ;; Excpect further connections. This is not a tail call because
      ;; it returns:
      (future
        (zserve server-socket handler)))))

;; If you dont reply to the initial request of an active agent by e.g.
;; sending an empty string the agent will retry in 60 seconds.
(defn- zserver [port handler]
  (let [server-socket (ServerSocket. port)]
    ;; FIXME: this may spawn a long chain of futures branching for
    ;; every request:
    (future
      (zserve server-socket handler))
    ;; Close this socket to terminate the chanin of futures:
    server-socket))

;; FIXME: lastlogsize and mtime required for log items and may be
;; omitted except for the older agents including 2.2.2 and 2.4.7:
(defn- make-datum [datum]
  ;; FIXME: check if they are already present:
  (-> datum
      (assoc "lastlogsize" 0)
      (assoc "mtime" 0)))

(defn- zhandler [json]
  (let [request (get json "request")]
    (if (= "active checks" request)
      {"response" "success",
       "data" [(make-datum {"key" "agent.version", "delay" 30,})
               (make-datum {"key" "system.uptime", "delay" 30,})]}
      "")))

;; (def server (zserver 10051 zhandler))
;; (.close server)

;; Terminate with C-c:
(defn -main [& args]
  (zserver 10051 zhandler))