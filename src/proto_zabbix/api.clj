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
;; Such use of  an Auth token is  an extesion of JSON  RPC, the "auth"
;; field ist not even in the Spec [1].  Most API calls however require
;; this auth token.
;;
;; [1] JSON RPC Spec, https://www.jsonrpc.org/specification
;;
(defn- call-api [url method params auth-token]
  (let [json-rpc {:jsonrpc "2.0"
                  :method method  ; e.g. "user.login"
                  :params params  ; e.g. {:user "xxx" :password "yyy"}
                  :id 1}
        ;; Add auth token, if not nil:
        json-obj (if (nil? auth-token)
                   json-rpc
                   (assoc json-rpc :auth auth-token))
        ;; The actual JSON text goes into body:
        opts {:timeout (* 5 60 1000)    ; in ms
              :body (json/generate-string json-obj)
              :headers {"Content-Type" "application/json"}
              ;; FIXME: keine Zertifikatprüfung:
              :insecure? true}
        http-resp @(http/post url opts)]

    ;; NOTE: http-resp contains  the initial request as  well, so this
    ;; is where  user names and  passwords may leak into  stack traces
    ;; and log files. The user should consider catching exceptions and
    ;; censor them.
    ;;
    ;; Problems with SSL certificate manifest themselves here:
    (when-let [error (:error http-resp)]
      (throw (ex-info "HTTP Client Error!" http-resp)))

    (when (not= 200 (:status http-resp))
      (throw (ex-info "Bad HTTP response!" http-resp)))

    ;; JSON RPC Erros return no result.  If we do not check for errors
    ;; a  plain   nil  will  be  returned   when  evaluating  (:result
    ;; response).  Also we  should make it clear when  the error comes
    ;; from Zabbix as opposed to HTTP.  The JSON RPC erorr object from
    ;; Zabbix contans a :code, a :message and a :data field.  The text
    ;; in  (:data error)  is sometimes  more informative.   Return the
    ;; whole object in ExceptionInfo.
    ;;
    ;; Unfortunately Leiningen may report an ExceptionInfo thrown here
    ;; as  a "Syntax  error (ExceptionInfo)  compiling at  ...".  Make
    ;; sure to look at the "Full report" in the *.edn file.
    ;;
    ;; Body  may  be  not  a  valid  JSON  ---  think  of  custom  404
    ;; pages. Also  we convert string  keys to keywords, that  is what
    ;; the "true" is for.
    (let [response (try
                     (json/parse-string (:body http-resp) true)
                     (catch Exception e
                       (throw
                        (ex-info "Bad JSON response!" http-resp e))))]
      ;; A  valid JSON  RPC response  may also  indicate an  error ---
      ;; think creating a host group that already exists:
      (if-let [error (:error response)]
        (throw (ex-info "Zabbix API Error!" response))
        ;; Success, return the actual result only:
        (:result response)))))


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
  ;; See  e.g.   https://github.com/alexei-matveev/hello-zabbix for  a
  ;; test installation in k3s ...
  (let [config {:url "https://zabbix.localhost/api_jsonrpc.php"
                :user "Admin"
                :password "zabbix"}
        ;; Login happens here:
        zbx (make-zbx config)
        ;; Force result, see logout below:
        result (doall (zbx "hostgroup.get"))]
    ;; Dont forget to logout:
    (zbx "user.logout")
    result))
