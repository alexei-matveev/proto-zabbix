;;
;; In Emacs/Cider type  C-c C-k to "recompile" the module,  C-c M-n to
;; switch to the namespace.
;;
;; Dont forget to (System/setProperty "https.proxyHost" "")
;;
(ns proto-zabbix.test
  "Experiments on fetching data from various APIs"
  (:require
    [proto-zabbix.api :as api]))

;; You  may  want  to  change  the  coordinates.  This  was  a  Docker
;; container:
(def config {:url "http://127.0.0.5:20080/api_jsonrpc.php"
             :user "user"
             :password "password"})

(let [zbx (api/make-zbx config)]
  (zbx "hostgroup.get" {:output :extend}))
