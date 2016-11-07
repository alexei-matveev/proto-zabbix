(ns proto-zabbix.clock)

(defn from-millis
  "Zabbix clocks in seconds and remainder in nanoseconds"
  [millis]
  (let [clock (quot millis 1000),
        ns (* 1000 1000 (mod millis 1000))]
    [clock ns]))

