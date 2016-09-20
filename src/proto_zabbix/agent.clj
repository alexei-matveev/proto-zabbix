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
(defn- request-active-checks!
  "Returns server response, or fails."
  [options]
  (with-open [sock (Socket. (or (:server options) "localhost")
                            (or (:port options) 10051))]
    (p/send-recv sock
                 {"request" "active checks",
                  "host" "host.example.com",
                  "host_metadata" "Proto-Zabbix Agent"})))

(defn- get-active-checks!
  "Returns server response, blocks until success."
  [options]
  (loop []
    (println "Asking for items ...")
    (or (try
          (let [res (request-active-checks! options)]
            (and (= "success" (get res "response"))
                 res))
          (catch Exception e nil))
        ;; There is no point to proceed for the active agent if the
        ;; server is unresponsive. Retry after some interval:
        (do
          (Thread/sleep (* 10 1000))
          (recur)))))

;; Initialize checks with the timestamp of the last report. If that
;; didnt happen yet, e.g. the input "checks" is an empty list, use the
;; beginning of the epoch:
(defn- refresh-checks [checks response]
  (for [c (get response "data")]
    (let [key (get c "key")
          ;; Checks are in a list, would like a guarantee the key is
          ;; unique there:
          last-value (reduce (fn [t c]
                               (if-not (= key (get c "key"))
                                 t
                                 (max t (:last-value c))))
                             0          ; epoch
                             checks)]
      (assoc c :last-value last-value))))

;; Inner  loop.  Spend  refresh-interval  sending agent  data  to  the
;; server.  Send outdated items, then go  to sleep for some quantum of
;; time  to  wake  up  again  and  check  if  any  further  action  is
;; required.  Exit  the loop  to  refresh  the  item list  again.  The
;; timestamps of  the checks get updated  inside the loop, we  want to
;; keep them:
(defn- process-checks
  "Takes and returns checks"
  [checks refresh-interval]
  (let [current-time (System/currentTimeMillis)
        deadline (+ current-time refresh-interval)]
    (loop [checks checks
           current-time current-time]
      (if (> current-time deadline)
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
          ;; Recur to the function entry point:
          (recur (concat check-now check-later)
                 (System/currentTimeMillis)))))))

(defn zabbix-agent-active
  "Emulates behaviour of an active Zabbix agent"
  [options]
  (let [refresh-interval (* 1000 30)
        refresh! (fn [checks]
                   (let [response (get-active-checks! options)]
                     (prn response)
                     (refresh-checks checks response)))]
    ;; Outer refresh loop. Ask the server for the items to be
    ;; delivered. Then spend some time regularly delivering the agent
    ;; data before asking again.  Supply an empty list of checks as
    ;; input of the initial refresh:
    (loop [checks (refresh! [])]
      ;; Inner check loop. Spend refresh-interval sending agent data
      ;; to the server. Returns the checks where timestamps have been
      ;; eventually updated:
      (let [checks (process-checks checks
                                   refresh-interval)]
        ;; Try refreshing the list of items again. FIXME: server list
        ;; is authoritative, need taking timestamps from the local
        ;; data --- supply the current info on the check as input too:
        (recur (refresh! checks))))))

;; Terminate with C-c:
(defn -main [& args]
  (zabbix-agent-active {:server "localhost",
                        :port 10051}))
