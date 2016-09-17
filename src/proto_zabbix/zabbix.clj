(ns proto-zabbix.zabbix
  (:require [proto-zabbix.proto :as proto]
            [clojure.pprint :refer [pprint]])
  (:import [java.net ServerSocket Socket]))

;;
;; The useful work is done in  separate threads started as futures. To
;; terminate  the chain  of futures  keep  a reference  and close  the
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
            (proto/proto-send sock msg-out))))
      ;; Expect further connections. This is a tail call with the fn
      ;; as the "recursion point":
      (recur server-socket handler))))

;; If you dont reply to the initial request of an active agent by e.g.
;; sending an empty string the agent will retry in 60 seconds.
(defn- zabbix-server [port handler]
  (let [server-socket (ServerSocket. port)]
    ;; FIXME: this may spawn a long chain of futures branching for
    ;; every request:
    (future
      ;; Blocks util someone closes the server-socket. That is why the
      ;; future around this call:
      (zserve server-socket handler))
    ;; Close this socket to terminate the chanin of futures:
    server-socket))


;;
;; See also Java implementation in Zabbi/J [1]
;;
;; [1] https://bitbucket.org/mquigley/zabbixj
;;
;; First ask the server for the list of checks to report and their
;; respective intervals by sending:
;;
;; {"request" "active checks",
;;  "host" "host.example.com",
;;  "host_metadata" "Proto-Zabbix Agent",}
;;
;; NOTE: Serializing JSON would also convert keywords such as :request
;; to strings but we are not  using that so far because vanilla Zabbix
;; appears to use weired keys.
;;
;; Then regularly send agent data in this form (not implemented)
;;
;; {"request" "agent data",
;;  "data"
;;  [{"host" "host.example.com",
;;    "key" "agent.version",
;;    "value" "2.4.7",
;;    "clock" 1474141031,
;;    "ns" 670229873}],
;;  "clock" 1474141031,
;;  "ns" 670248125},
;;
(defn zabbix-agent-active
  "Emulates behaviour of an active Zabbix agent"
  [server-host server-port]
  (with-open [sock (Socket. server-host server-port)]
    (proto/send-recv sock {"request" "active checks",
                           "host" "host.example.com",
                           "host_metadata" "Proto-Zabbix Agent"})))

;; (zabbix-agent-active "localhost" 10051)

;; FIXME: lastlogsize and mtime required for log items and may be
;; omitted except for the older agents including 2.2.2 and 2.4.7:
(defn- make-datum [datum]
  ;; FIXME: check if they are already present:
  (-> datum
      (assoc "lastlogsize" 0)
      (assoc "mtime" 0)))

;;
;; If  an agent  runs on  a non-standard  port other  than 10050,  the
;; request for  the active checks  will come with  a port number  as a
;; json field.   This port  is likly only  needed for  passive (server
;; initiated) checks:
;;
;; {"request" "active checks",
;;  "host" "host.example.com",
;;  "host_metadata" "Linux host.example.com ...",
;;  "port" 20050}
;;
(defn- zhandler [json]
  (let [request (get json "request")]
    (case request
      "active checks"
      {"response" "success",
       "data" [(make-datum {"key" "agent.version", "delay" 30})
               (make-datum {"key" "system.uptime", "delay" 30})]}
      ;;
      ;; Next is  an example  request on the  server issued  by zabbix
      ;; sender [1] as for example initiated by
      ;;
      ;;     zabbix_sender -z host.example.com -k mysql.queries -o
      ;;     342.45 -s "host"
      ;;
      ;; The server will get:
      ;;
      ;; {"request" "sender data",
      ;;  "data" [{"host" "host",
      ;;           "key" "mysql.queries",
      ;;           "value" "342.45"}]
      ;;
      ;; [1] https://www.zabbix.org/wiki/Docs/protocols/zabbix_sender/2.0
      ;;
      ;; FIXME: response is hard coded!
      ;;
      "sender data"
      {"response" "success",
       "info" "Processed 1 Failed 1 Total 2 Seconds spent 0.000253"}
      ;;
      ;; Next comes the  default case if nothing  else matches. FIXME:
      ;; an empty  string as  json response to  say, request  = "agent
      ;; data",  so far  did  not  break the  agent.  Even though  the
      ;; vanilla server replies with a  text string in this particular
      ;; case (not even json).
      ;;
      "")))

;; Decorator for the handler:
(defn- wrap [handler]
  (fn [msg-in]
    (let [msg-out (handler msg-in)]
      (pprint {:INP msg-in :OUT msg-out})
      msg-out)))

;; (def server (zabbix-server 10051 (wrap zhandler)))
;; (.close server)

;; Terminate with C-c:
(defn -main [& args]
  (zabbix-server 10051 (wrap zhandler)))
