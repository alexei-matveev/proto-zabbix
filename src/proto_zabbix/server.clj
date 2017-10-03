(ns proto-zabbix.server
  (:require [proto-zabbix.proto :as proto]
            [clojure.pprint :refer [pprint]])
  (:import [java.net ServerSocket]))

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
      ;; Fork a future to handle this connection:
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

;; FIXME: lastlogsize and mtime required for log items and may be
;; omitted except for the older agents including 2.2.2 and 2.4.7:
(defn- make-datum [datum]
  ;; FIXME: check if they are already present:
  (-> datum
      (assoc "lastlogsize" 0)
      (assoc "mtime" 0)))

;;
;; 2.4.7 replies like this:
;;
;; processed: 1; failed: 0; total: 1; seconds spent: 0.000099
;;
(defn- info-message
  "Prepares legacy Zabbox info message"
  [data]
  (let [n (count data)]
    (str "processed: " n "; failed: 0; total: " n "; seconds spent: 0.0")))
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
       "data" [(make-datum {"key" "agent.version", "delay" 20})
               (make-datum {"key" "system.uptime", "delay" (if (> 0.5 (rand)) 5 40)})]}
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
      ;; FIXME:  The wiki  text on  the protocol  [1] claims  that the
      ;; Zabbix header  (ZBXD with length)  is optional in  the recent
      ;; versions. Moreover  the length is  supposed to be  ignored by
      ;; the server when the header is  supplied. This is not the case
      ;; here so far. Also note  that the 2.4 version of zabbix_sender
      ;; appears to inter-operate with our code.
      ;;
      ;; [1] https://www.zabbix.org/wiki/Docs/protocols/zabbix_sender/2.0
      ;;
      "sender data"
      {"response" "success",
       "info" (info-message (get json "data"))}
      ;;
      ;; Active checks are processed here:
      ;;
      "agent data"
      {"response" "success",
       "info" (info-message (get json "data"))}
      ;;
      ;; Next comes the  default case if nothing  else matches. FIXME:
      ;; an empty  string as  json response to  say, request  = "agent
      ;; data", so far did not break the agent.
      ;;
      "")))

;; Decorator for the handler:
(defn- wrap [handler]
  (fn [msg-in]
    (let [msg-out (handler msg-in)]
      (pprint {:AGENT-REQUEST msg-in,
               :SERVER-RESPONSE msg-out})
      msg-out)))

;;
;; For C-x C-e in CIDER. Make sure to stop Zabbix agent when done with
;; experiments.  Otherwise it will cache the data it did not manage to
;; send to the server.
;;
;; (def server (zabbix-server 10051 (wrap zhandler)))
;; (.close server)
;;

;; Terminate with C-c:
(defn -main [& args]
  (zabbix-server 10051 (wrap zhandler)))
