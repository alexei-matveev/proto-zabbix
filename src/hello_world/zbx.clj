(ns hello-world.zbx
  (:require [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.io StringWriter]
           [java.nio ByteBuffer ByteOrder]
           [java.net Socket ServerSocket]))

(defn- read-byte-array [reader n]
  (let [buf (byte-array n)
        m (.read reader buf)]
    (assert (= m n))
    buf))

;; Zabbix headers provides the size field in little endian. Java data
;; streams use big endian unconditionally.  ByteBuffer offers a
;; workaround:
(defn- read-long [reader]
  (let [buf (read-byte-array reader 8)]
    (-> (ByteBuffer/wrap buf)
        (.order ByteOrder/LITTLE_ENDIAN)
        (.getLong))))

;; FIXME: returns original text on parse errors:
;;
;; See e.g.:
;;
;; https://www.zabbix.com/documentation/2.4\
;; /manual/appendix/items/activepassive
;;
(defn- read-json [reader]
  (with-open [response (StringWriter.)]
    (io/copy reader response)
    (let [text (str response)]
      ;; (prn (count text))
      (try
        (json/parse-string text)
        ;; FIXME: JsonParseException maybe?
        (catch Exception e text)))))

(defn- read-zbxd [reader]
  ;; Response is "ZBXD\1" <8 byte length> <json body>
  (let [magic (String. (read-byte-array reader 4))
        ;; Single byte version number, always 1:
        version (.read reader)
        ;; You could wrap the reader into a DataInputStream but Java
        ;; would assume big endian with (.readLong reader). Instead:
        length (read-long reader)
        ;; For unsupported keys the body is "ZBX_NOTSUPPORTED" which
        ;; is not a valid json. FIXME: we return original text on
        ;; ANY parse error so far. This makes parse errors
        ;; indistinguishable from quoted strings:
        json (read-json reader)]
    {:magic magic, :version version, :length length, :json json}))

;;
;; Examples of valid key values as text:
;;
;; agent.version
;; vfs.fs.discovery
;; vfs.fs.size[/,used]
;;
;; See e.g.:
;; https://www.zabbix.com/documentation/2.4\
;; /manual/appendix/items/supported_by_platform
;;
(defn zabbix-get
  "Sends an TCP request to the specified host and port"
  [host port text]
  (with-open [sock (Socket. host port)
              writer (io/output-stream sock)
              reader (io/input-stream sock)]
    (.write writer (.getBytes text))
    (.flush writer)
    ;; Response is "ZBXD\1" <8 byte length> <json body>
    (read-zbxd reader)))


;; (zabbix-get "localhost" 10050 "vfs.fs.discovery")
;; (zabbix-get "localhost" 10050 "vfs.fs.size[/,used]")
;; (zabbix-get "localhost" 10050 "agent.version")


(defn- zreceive
  [socket]
  ;; Dont close the socket here if you are going to send
  ;; replies. Using with-open instead of let would do that:
  (let [reader (io/input-stream socket)]
    (read-zbxd reader)))

(defn- zsend
  "Send the given string message out over the given socket"
  [socket msg]
  (let [writer (io/writer socket)]
      (.write writer msg)
      (.flush writer)))

;; If you dont reply to the initial request of an active agent by e.g.
;; sending an empty string the agent will retry in 60 seconds.
(defn zserver [port handler]
  (let [running (atom true)]
    (future
      (with-open [server-sock (ServerSocket. port)]
        ;; FIXME: it will need to get one more request after resetting
        ;; the atom to actually exit the loop:
        (while @running
          (with-open [sock (.accept server-sock)]
            (let [msg-in (zreceive sock)
                  msg-out (handler msg-in)]
              (clojure.pprint/pprint msg-in)
              (zsend sock msg-out))))))
    running))

;; (def server (zserver 10051 (fn [x] "")))
;; (reset! server false)
