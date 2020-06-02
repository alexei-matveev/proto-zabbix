;;
;; In Emacs/Cider type  C-c C-k to "recompile" the module,  C-c M-n to
;; switch to the namespace.
;;
;; Dont forget to (System/setProperty "https.proxyHost" "")
;;
(ns proto-zabbix.api
  "Experiments on fetching data from various APIs"
  (:require
    [clojure.string :as str]
    [org.httpkit.client :as http]
    [cheshire.core :as json]))       ;  [generate-string parse-string]

;;
;; Auth-token is not supplied in params but as its sibling node in the
;; submitted JSON. If  auth-token is provided it should  be correct or
;; nil.   An   empty  or  arbitrary   string  will  not   suffice  for
;; "user.login" method.
;;
(defn- call-api
  [url method params auth-token]
  #_(println auth-token)
  (let [timeout (* 5 60 1000) ; in ms
        json-in {:jsonrpc "2.0"
                 :method method ; e.g. "user.login"
                 :params params ; e.g. {:user "xxx" :password "yyy"}
                 :id 1}
        ;; Most API calls requite auth token:
        json-in (if (nil? auth-token)
                  json-in
                  (assoc json-in :auth auth-token))
        text-in (json/generate-string json-in)
        http-resp (http/post url
                             {:timeout timeout
                              :body text-in
                              :headers {"Content-Type" "application/json"}
                              ;; FIXME: keine Zertifikatprüfung:
                              :insecure? true})
        ;; Problems with SSL certificate manifest themselves here:
        _ (when-let [error (:error @http-resp)]
            (throw (ex-info "HTTP Client Error!" @http-resp)))
        ;; True is specified to convert key strings to keywords:
        response (-> @http-resp
                     :body
                     (json/parse-string true))]
    ;;
    ;; Erros  return no  result.  Without  this  a plain  nil will  be
    ;; returned.  Make it clear the  error comes from Zabbix. The JSON
    ;; RPC erorr object from Zabbix contans  a :code, a :message and a
    ;; :data  field.  The  text  in (:data  error)  is sometimes  more
    ;; informative. Return the whole object in ExceptionInfo.
    ;;
    ;; Unfortunately Leiningen may report an ExceptionInfo thrown here
    ;; as  a "Syntax  error (ExceptionInfo)  compiling at  ...".  Make
    ;; sure to look at the "Full report" in the *.edn file.
    ;;
    (when-let [error (:error response)]
      (throw (ex-info "Zabbix API Error!"
                      error)))
    (:result response)))


;; Auth token  is a  hash string,  as of  2017 it  is stored  in table
;; "sessions"  in  the Zabbix  DB.  It  does  not  seem to  expire  if
;; constantly used. This is how to get it:
(defn- get-auth-token [url user password]
  (call-api url
            "user.login"
            {:user user :password password}
            nil)) ; anything else wont do


(defn make-zbx [config]
  (let [url (or (:url config)
                "http://localhost/zabbix/api_jsonrpc.php")
        ;; It has  to be supplied  at "connection" or returned  by the
        ;; server in exchange for credentials
        auth-token (or (:auth config)
                       (get-auth-token url
                                       (:user config)
                                       (:password config)))]
    ;; Return a closure over the auth token:
    (fn zbx
      ([method] (zbx method {}))        ; e.g. (zbx "user.logout")
      ([method params] (call-api url method params auth-token)))))

;;; For you C-x C-e pleasure:
(comment
  (let [config {:url "http://localhost/zabbix/api_jsonrpc.php"
                :user "user"
                :password "password"}
        zbx (make-zbx config)]
    ;;
    ;; Host groups are returned as a list of of maps like this:
    ;;
    ;; {:groupid "1", :name "Templates", :internal "0", :flags "0"}
    ;;
    (zbx "hostgroup.get" {:output :extend}))
  #_end)
