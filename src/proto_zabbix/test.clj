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

(defn- main []
  (let [zbx (api/make-zbx config)]
    ;;
    ;; Call to hostgroup.get returns a list of objects like
    ;;
    ;; {:groupid "1", :name "Templates", :internal "0", :flags "0"}
    ;;
    (zbx "hostgroup.get")

    ;;
    ;; You will get "already exists" exception  mit code = -32602 when the
    ;; hostgroup already  exists. The call to  hostgroup.create returns an
    ;; object like  {:groupids ["411"]}.   Removal takes  a list  of group
    ;; IDs:
    ;;
    (let [new (zbx "hostgroup.create" {:name "_wip - Service - Customer"})]
      (zbx "hostgroup.delete" (:groupids new)))))
