(ns proto-zabbix.agent
  (:require [proto-zabbix.proto :as p])
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
  "Returns server response, blocks until success."
  [server-host server-port]
  (let [ask #(with-open [sock (Socket. server-host server-port)]
               (p/send-recv sock
                            {"request" "active checks",
                             "host" "host.example.com",
                             "host_metadata" "Proto-Zabbix Agent"}))]
    (loop []
      (println "Asking for items ...")
      (or (try
            (let [res (ask)]
              (and (= "success" (get res "response"))
                   res))
            (catch Exception e nil))
          ;; There is no point to proceed for the active agent if the
          ;; server is unresponsive. Retry after some interval:
          (do
            (Thread/sleep (* 10 1000))
            (recur))))))

(defn zabbix-agent-active
  "Emulates behaviour of an active Zabbix agent"
  [server-host server-port]
  (let [refresh-interval (* 1000 30)
        last-refresh (System/currentTimeMillis)
        response (refresh-active-checks server-host server-port)
        ;; Initialize checks with the timestamp of the last
        ;; report. That didnt yet happen, but pretend it did at the
        ;; beginning of the epoch:
        checks (for [c (get response "data")]
                 (assoc c :last-value 0))]
    ;; Outer refresh loop. Ask the server for the items to be
    ;; delivered. Then spend some time regularly delivering the agent
    ;; data before asking again:
    (loop [last-refresh last-refresh
           response response
           checks checks]
      (prn response)
      ;; Inner loop. Spend refresh-interval sending agent data to the
      ;; server.  Send outdated items, then go to sleep for some
      ;; quantum of time to wake up again and check if any further
      ;; action is required. Exit the loop to refresh the item list
      ;; again. The timestamps of the checks get updated inside the
      ;; loop, we want to keep them:
      (let [checks (loop [current-time last-refresh
                          checks checks]
                     (if (< refresh-interval (- current-time last-refresh))
                       ;; Time to refresh the list of checks, return the current
                       ;; state with recent timestamps:
                       checks
                       ;; Select those to report to the server:
                       (let [groups (group-by (fn [c]
                                                (let [last-value (get c :last-value)
                                                      delay (* 1000 (get c "delay"))]
                                                  (>= (- current-time last-value) delay)))
                                              checks)
                             check-now (get groups true)
                             _ (prn {:OUTDATED check-now})
                             check-later (get groups false)
                             ;; update the timestamp:
                             check-now (for [c check-now]
                                         (assoc c :last-value current-time))]
                         (prn {:WOULD-SEND check-now})
                         (Thread/sleep 1000)
                         (recur (System/currentTimeMillis)
                                (concat check-now check-later)))))]
        ;; Try refreshing the list of items again. FIXME: server
        ;; list is authoritative, need taking timestamps from the
        ;; local data:
        (let [current-time (System/currentTimeMillis)
              response (refresh-active-checks server-host server-port)
              checks (for [c (get response "data")]
                       (let [key (get c "key")
                             ;; Checks are in a list, would like a
                             ;; guarantee the key is unique there:
                             last-value (apply max
                                               (map :last-value
                                                    (filter (fn [x] (= key (get x "key")))
                                                            checks)))]
                         (assoc c :last-value last-value)))]
          (recur current-time
                 response
                 checks))))))

;; (zabbix-agent-active "localhost" 10051)

;; Terminate with C-c:
(defn -main [& args]
  (zabbix-agent-active "localhost" 10051))
