(ns proto-zabbix.clock)

;; FIXME: this  was split off  for re-use in agent.clj  and proto.clj,
;; but the latter may actually not need it!
(defn from-millis
  "Zabbix clocks in seconds and remainder in nanoseconds"
  [millis]
  (let [clock (quot millis 1000),
        ns (* 1000 1000 (mod millis 1000))]
    [clock ns]))

