(ns hello-world.zbx
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.pprint :refer [pprint]])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.net Socket ServerSocket]))

(defn- read-byte-array [stream n]
  (let [buf (byte-array n)
        m (.read stream buf)]
    (assert (= m n))
    buf))

;; Zabbix headers provides the size field in little endian. Java data
;; streams use big endian unconditionally.  ByteBuffer offers a
;; workaround:
(defn- buf->long [buf]
  (-> (ByteBuffer/wrap buf)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.getLong)))

(defn- long->buf [n]
  (let [buf (byte-array 8)]
    (-> (ByteBuffer/wrap buf)
        (.order ByteOrder/LITTLE_ENDIAN)
        (.putLong n)
        (.array))))

;; (buf->long (long->buf 1234567890)) => 1234567890

(defn- read-long [stream]
  (let [buf (read-byte-array stream 8)]
    (buf->long buf)))

(defn- write-long [stream n]
  (let [buf (long->buf n)]
    (.write stream buf)))

;;
;; Note that while reading the JSON you cannot wait on EOF. Instead
;; read the fixed length and parse it already. Reading until the end
;; of stream is waiting for the timeout (a few seconds).
;;
;; FIXME: returns original text on parse errors!
;;
;; See e.g.:
;;
;; https://www.zabbix.com/documentation/2.4\
;; /manual/appendix/items/activepassive
;;
(defn- read-json [stream length]
  (let [buf (read-byte-array stream length)
        text (String. buf)]
    (try
        (json/parse-string text)
        ;; FIXME: JsonParseException maybe?
        (catch Exception e text))))


;;
;; Protocoll is "ZBXD\1" <8 byte length> <json body>. This protocoll
;; is used both by agent and server when putting data on the wire.
;;
(defn- read-zbxd [stream]
  (let [magic (String. (read-byte-array stream 4))
        ;; Single byte version number, always 1:
        version (.read stream)
        ;; You could wrap the stream into a DataInputStream but Java
        ;; would assume big endian with (.readLong stream). Instead:
        length (read-long stream)
        ;; For unsupported keys the body is ZBX_NOTSUPPORTED, without
        ;; quotes, which is not a valid json. FIXME: we return
        ;; original text on ANY parse error so far. This makes parse
        ;; errors indistinguishable from quoted strings:
        json (read-json stream length)]
    (assert (= "ZBXD" magic))
    (assert (= 1 version))
    json))

(defn- write-zbxd [stream json]
  (let [text (json/generate-string json)
        buf (.getBytes text)]
    (.write stream (.getBytes "ZBXD\1"))
    (write-long stream (count buf))
    (.write stream buf)
    (.flush stream)))

;; (def a1 {"request" "active checks", "host" "Zabbix server"})
;; (write-zbxd (io/output-stream "a1") a1)
;; (read-zbxd (io/input-stream "a1"))

;;
;; Examples of valid key values as text:
;;
;; agent.version
;; vfs.fs.discovery
;; vfs.fs.size[/,used]
;;
;; See e.g.:
;; https://www.zabbix.com/documentation/2.4/manual/appendix/items/supported_by_platform
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

;; (zabbix-get "localhost" 10050 "agent.version")
;; (zabbix-get "localhost" 10050 "vfs.fs.size[/,used]")
;; (zabbix-get "localhost" 10050 "vfs.fs.discovery")

(defn- zreceive [socket]
  ;; Dont close the socket here if you are going to send
  ;; replies. Using with-open instead of let would do that:
  (let [stream (io/input-stream socket)]
    (read-zbxd stream)))

(defn- zsend [socket json]
  (let [stream (io/output-stream socket)]
    (write-zbxd stream json)))

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
              (pprint {:INP msg-in :OUT msg-out})
              (zsend sock msg-out))))))
    running))

(defn- zhandler [json]
  (let [request (get json "request")]
    (if (= "active checks" request)
      ;; FIXME: lastlogsize and mtime required for log items and may
      ;; be omitted except for the older agents including 2.2.2:
      {"response" "success",
       "data" [{"key" "agent.version", "delay" 30,
                "lastlogsize" 0, "mtime" 0}
               {"key" "system.uptime", "delay" 30,
                "lastlogsize" 0, "mtime" 0}]}
      "")))

;; (def server (zserver 10051 zhandler))
;; (reset! server false)
