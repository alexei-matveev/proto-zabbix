(ns proto-zabbix.agent
  (:require [proto-zabbix.proto :as proto])
  (:import [java.net Socket]))

;;
;; See also Java implementation in Zabbix/J [1]
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
;; appears to use weired keys occasionally.
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
(defn- refresh-active-checks
  "Returns server response or nil on failure."
  [server-host server-port]
  (println "Asking for items ...")
  (try
    (with-open [sock (Socket. server-host server-port)]
      (proto/send-recv sock
                       {"request" "active checks",
                        "host" "host.example.com",
                        "host_metadata" "Proto-Zabbix Agent"}))
    ;; Return nil on failure: (get nil "repsonse") will be nil as
    ;; well:
    (catch Exception e nil)))

(defn zabbix-agent-active
  "Emulates behaviour of an active Zabbix agent"
  [server-host server-port]
  (let [refresh-interval (* 1000 10)]   ; 10 seconds
    ;; Outer refresh loop. Ask the server for the items to be
    ;; delivered. Then spend some time regularly delivering the items
    ;; before asking again:
    (loop [start-time (System/currentTimeMillis)
           response (refresh-active-checks server-host server-port)]
      (prn response)
      (if-not (= "success" (get response "response"))
        ;; If the server did not deliver, wait before retrying:
        (Thread/sleep (* 1000 20))
        ;; Otherwise spend refresh-interval sending agent data to the
        ;; server.  Inner loop. Send the items, then go to sleep for
        ;; some quantum of time to wake up again and check if any
        ;; further action is required. Exit the loop to refresh the
        ;; item list again:
        (loop [current-time start-time]
          (when (>= refresh-interval (- current-time start-time))
            (let [items (get response "data")]
              (prn items))
            (Thread/sleep 1000)
            (recur (System/currentTimeMillis)))))
      ;; Try refreshing the list of items again:
      (recur (System/currentTimeMillis)
             (refresh-active-checks server-host server-port)))))

;; (zabbix-agent-active "localhost" 10051)

;; Terminate with C-c:
(defn -main [& args]
  (zabbix-agent-active "localhost" 10051))
