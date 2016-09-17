(ns proto-zabbix.agent
  (:require [proto-zabbix.proto :as proto])
  (:import [java.net Socket]))

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
  (let [refresh-interval (* 1000 10)    ; 10 seconds
        refresh #(with-open [sock (Socket. server-host server-port)]
                   (println "Asking for items ...")
                   (proto/send-recv sock
                                    {"request" "active checks",
                                     "host" "host.example.com",
                                     "host_metadata" "Proto-Zabbix Agent"}))]
    ;; Outer refresh loop. Ask the server for the items to be
    ;; delivered. The spend some time regularly delivering the items
    ;; before asking again:
    (loop [start-time (System/currentTimeMillis)
           response (refresh)]
      (when (= "success" (get response "response"))
        ;; Inner loop. Send the items then go to sleep for some
        ;; quantum of time to wake up again and check if any further
        ;; action is required. Exit the loop to refresh the item list
        ;; again:
        (prn response)
        (loop [current-time start-time]
          (when (>= refresh-interval (- current-time start-time))
            (let [items (get response "data")]
              (prn "."))
            (Thread/sleep 1000)
            (recur (System/currentTimeMillis))))
        (recur (System/currentTimeMillis)
               (refresh))))))

;; (zabbix-agent-active "localhost" 10051)

;; Terminate with C-c:
(defn -main [& args]
  (zabbix-agent-active "localhost" 10051))
