(ns proto-zabbix.server
  (:require [proto-zabbix.proto :as proto]
            [clojure.pprint :refer [pprint]])
  (:import [java.net ServerSocket]
           [java.util.concurrent LinkedBlockingDeque]))

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
    ;; The  function  zserve  will  blocks  util  someone  closes  the
    ;; server-socket. That is  why the future around the  call. Here a
    ;; chain of futures branching for every request is started.
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
       "data" [#_(make-datum {"key" "agent.version", "delay" 30})
               #_(make-datum {"key" "system.uptime", "delay" (if (> 0.5 (rand)) 5 40)})
               ;; The log file should be readable for the zabbix user,
               ;; syslog is not:
               (make-datum {"key" "log[/var/log/zabbix-agent/zabbix_agentd.log]", "delay" 30})]}
      ;;
      ;; Active checks and sender data are processed here ...
      ;;
      ;; A  request  issued  by  zabbix  sender  [1]  as  for  example
      ;; initiated by
      ;;
      ;;     zabbix_sender -z host.example.com -k mysql.queries -o
      ;;     342.45 -s "host"
      ;;
      ;; will read:
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
      ;; The log data comes in  a relatively inefficient format --- as
      ;; a JSON map  with host, key, value, lastlogsize,  clock and ns
      ;; field  for each  log line.   There  is no  mtime field.   The
      ;; presense of  the lastlogsize  may help  telling the  log data
      ;; from  other kinds  of data.   The clock  & ns  are not  quite
      ;; usefull  as they  encode the  time the  line was  consumed by
      ;; zabbix agent  --- thus  different for each  log line  and not
      ;; reproducible.  The log line is not parsed by default.
      ;;
      ;; Zabbix agent tracks lastlogsize on its own, albeit not across
      ;; restarts.  Even if the server replies with lastlogsize = 0 on
      ;; every acitve  check refresh, the  agent is "smart"  enough to
      ;; only send the lines it did  send before. Note that there is a
      ;; maxlines restriction in the agent  config. The agent may need
      ;; many  rounds to  send the  initial content  of the  large log
      ;; file. That basically  means the server cannot  "seek" and may
      ;; not rely on  second chance to see the log  line. On the other
      ;; hand it may  be impractical to track  the position/version of
      ;; the log file  on the server side to avoid  reposts upon agent
      ;; restarts.
      ;;
      ("sender data" "agent data")
      (let [agent-data (get json "data")
            server-response (info-message agent-data)]
        {"response" "success",
         "info" server-response})
      ;;
      ;; Next comes the  default case if nothing  else matches. FIXME:
      ;; an empty  string as  json response to  say, request  = "agent
      ;; data", so far did not break the agent.
      ;;
      "")))

;; See e.g. https://gist.github.com/mjg123/1305115
(defn- new-q []
  (LinkedBlockingDeque.))

;; Puts x to the back of queue  q. You cannot put nil there.  By local
;; convention putting  the sentinel value  into the queue  will signal
;; termination.  Consumers should put the sentinel value back as there
;; might  be  more  than  one consumer!   By  another  convention  the
;; sentinel value for the queue is the queue object itself.
(defn- offer! [^LinkedBlockingDeque q x]
  (.offer q x)
  q)

;; Takes from  the front of  queue.  If  queue is empty,  blocks until
;; something is offered into it.
(defn- take! [^LinkedBlockingDeque q]
  (.take q))

;; Decorator for the handler:
(defn- wrap [handler]
  (fn [msg-in]
    (let [msg-out (handler msg-in)]
      (pprint {:AGENT-REQUEST msg-in,
               :SERVER-RESPONSE msg-out})
      msg-out)))

(defn- start-server! []
  (let [q (new-q)
        q-source (fn [x]
                   (offer! q x)
                   (zhandler x))
        sock (zabbix-server 10051 q-source)
        ;; "Opaque" to pass to stop-server!
        server {:sock sock :q q}]
    ;; Drain the queue here:
    (future
      (loop [x (take! q)]
        (if-not (= x q)
          (do
            (println x)
            (recur (take! q)))
          (do
            ;; In  case there  is more  than one  consumer, put  the
            ;; sentinel value back:
            (offer! q q)
            (println "worker finished!")))))
    server))

(defn- stop-server! [server]
  (println "close socket ...")
  (.close (:sock server))
  (println "tell workers to exit ...")
  ;; Tell consumers  to exit by  putting the sentinel object  into the
  ;; queue. By convention the sentinel object is the queue itself:
  (let [q (:q server)]
    (offer! q q))
  (println "done!"))

;; Terminate with C-c:
(defn -main [& args]
  (start-server!))

;;
;; Make  sure  to  stop  Zabbix  agent  when  done  with  experiments.
;; Otherwise the agent will accumulate data it does not manage to send
;; to the server.  For your C-x C-e pleasure in CIDER:
;;
#_(do (stop-server! server)
      (def server (start-server!)))

